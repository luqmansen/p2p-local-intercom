package com.example.network

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class VoxServer(
    port: Int,
    private val serverNickname: String,
    private val onPeerListChanged: (List<PeerInfo>) -> Unit,
    private val onAudioReceived: (Int, String, ByteArray) -> Unit,
    private val onStatusMessage: (String) -> Unit
) : WebSocketServer(InetSocketAddress(port)) {

    companion object {
        private const val TAG = "VoxServer"
    }

    data class ClientSession(val id: Int, var nickname: String)
    data class PeerInfo(val id: Int, val nickname: String, val isSelf: Boolean = false)

    private val idGenerator = AtomicInteger(1001) // Server is 1000
    private val clientSessions = ConcurrentHashMap<WebSocket, ClientSession>()

    init {
        isReuseAddr = true
    }

    override fun onStart() {
        Log.i(TAG, "VoxServer started on port $port")
        notifyStatus("Server started on port $port")
        updatePeers()
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val assignedId = idGenerator.getAndIncrement()
        val session = ClientSession(assignedId, "Peer $assignedId")
        clientSessions[conn] = session

        Log.i(TAG, "New connection: ID=$assignedId, Address=${conn.remoteSocketAddress}")
        notifyStatus("${session.nickname} joined the network")

        // Send a welcome message to register the client's own ID
        try {
            val welcomeJson = JSONObject().apply {
                put("type", "welcome")
                put("id", assignedId)
            }
            conn.send(welcomeJson.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send welcome to ID $assignedId", e)
        }

        updatePeers()
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        val session = clientSessions.remove(conn)
        if (session != null) {
            Log.i(TAG, "Closed connection: ID=${session.id}, Nickname=${session.nickname}")
            notifyStatus("${session.nickname} left the network")
            updatePeers()
        }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        try {
            val json = JSONObject(message)
            val session = clientSessions[conn] ?: return

            when (json.optString("type")) {
                "set_nickname" -> {
                    val oldNick = session.nickname
                    val newNick = json.optString("nickname", "Peer ${session.id}")
                    session.nickname = newNick
                    Log.i(TAG, "ID=${session.id} changed nickname to $newNick")
                    notifyStatus("$oldNick changed nickname to $newNick")
                    updatePeers()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message string: $message", e)
        }
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
        val session = clientSessions[conn] ?: return
        val length = message.remaining()
        if (length == 0) return

        val audioBytes = ByteArray(length)
        message.get(audioBytes)

        // Local playback of the audio received from this client
        onAudioReceived(session.id, session.nickname, audioBytes)

        // Broadcast to all OTHER connected clients
        broadcastVoice(session.id, session.nickname, audioBytes, excludeConn = conn)
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "Error in connection: ${conn?.remoteSocketAddress}", ex)
        if (conn == null) {
            notifyStatus("Server error: ${ex.message}")
        }
    }

    // --- Actions ---

    /**
     * Broadcasts Server's local microphone audio to all connected clients.
     */
    fun broadcastServerAudio(audioBytes: ByteArray) {
        // ID 1000 represents the Server itself
        broadcastVoice(1000, serverNickname, audioBytes)
    }

    /**
     * Bundles and broadcasts voice to active clients.
     * Packet structure:
     * [Sender ID: 4 bytes] + [Nickname length: 4 bytes] + [Nickname String: N bytes] + [PCM bytes...]
     */
    private fun broadcastVoice(
        senderId: Int,
        nickname: String,
        audioBytes: ByteArray,
        excludeConn: WebSocket? = null
    ) {
        val nicknameBytes = nickname.toByteArray(Charsets.UTF_8)
        val packetSize = 4 + 4 + nicknameBytes.size + audioBytes.size
        
        // Use a ByteBuffer for efficient byte allocation
        val buffer = ByteBuffer.allocate(packetSize)
        buffer.putInt(senderId)
        buffer.putInt(nicknameBytes.size)
        buffer.put(nicknameBytes)
        buffer.put(audioBytes)
        buffer.flip()

        val data = buffer.array()

        for (clientConn in clientSessions.keys) {
            if (clientConn != excludeConn && clientConn.isOpen) {
                try {
                    clientConn.send(data)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed sending broadcast voice to ID ${clientSessions[clientConn]?.id}", e)
                }
            }
        }
    }

    private fun updatePeers() {
        val list = mutableListOf<PeerInfo>()
        // Add Self (Server) first
        list.add(PeerInfo(1000, serverNickname, isSelf = true))

        // Add other active client sessions
        for (session in clientSessions.values) {
            list.add(PeerInfo(session.id, session.nickname, isSelf = false))
        }

        onPeerListChanged(list)

        // Broadcast current peer list to all clients so they can see active peers too
        broadcastPeerList(list)
    }

    private fun broadcastPeerList(list: List<PeerInfo>) {
        try {
            val jsonArray = JSONArray()
            for (peer in list) {
                val p = JSONObject().apply {
                    put("id", peer.id)
                    put("nickname", peer.nickname)
                }
                jsonArray.put(p)
            }

            val peersJson = JSONObject().apply {
                put("type", "peer_list")
                put("peers", jsonArray)
            }

            val response = peersJson.toString()
            for (conn in clientSessions.keys) {
                if (conn.isOpen) {
                    conn.send(response)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast peer list", e)
        }
    }

    private fun notifyStatus(msg: String) {
        onStatusMessage(msg)
    }

    fun stopServer() {
        try {
            stop(1000)
            Log.i(TAG, "Server shutdown complete")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }
}
