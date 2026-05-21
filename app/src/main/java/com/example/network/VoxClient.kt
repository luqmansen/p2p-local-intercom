package com.example.network

import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer

class VoxClient(
    serverUri: URI,
    private val nickname: String,
    private val onConnected: (Int) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onPeerListChanged: (List<VoxServer.PeerInfo>) -> Unit,
    private val onAudioReceived: (Int, String, ByteArray) -> Unit,
    private val onStatusMessage: (String) -> Unit
) : WebSocketClient(serverUri) {

    companion object {
        private const val TAG = "VoxClient"
    }

    private var myAssignedId: Int = -1

    override fun onOpen(handshakedata: ServerHandshake) {
        Log.i(TAG, "Connected to server: $uri")
        notifyStatus("Connected to server")
    }

    override fun onMessage(message: String) {
        try {
            val json = JSONObject(message)
            when (json.optString("type")) {
                "welcome" -> {
                    myAssignedId = json.optInt("id", -1)
                    Log.i(TAG, "Successfully registered on server with ID $myAssignedId")
                    onConnected(myAssignedId)
                    
                    // Respond with our selected nickname to the server
                    sendNickname()
                }
                "peer_list" -> {
                    val peersArray = json.optJSONArray("peers") ?: return
                    val list = mutableListOf<VoxServer.PeerInfo>()
                    for (i in 0 until peersArray.length()) {
                        val p = peersArray.getJSONObject(i)
                        val id = p.getInt("id")
                        val nick = p.getString("nickname")
                        // If ID matches ours, it's "self"
                        list.add(VoxServer.PeerInfo(id, nick, isSelf = (id == myAssignedId)))
                    }
                    onPeerListChanged(list)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message string: $message", e)
        }
    }

    override fun onMessage(bytes: ByteBuffer) {
        try {
            if (bytes.remaining() >= 8) {
                val senderId = bytes.int
                val nickLength = bytes.int
                if (bytes.remaining() >= nickLength) {
                    val nickBytes = ByteArray(nickLength)
                    bytes.get(nickBytes)
                    val senderNickname = String(nickBytes, Charsets.UTF_8)

                    val audioLength = bytes.remaining()
                    val audioBytes = ByteArray(audioLength)
                    bytes.get(audioBytes)

                    // Skip self-loopback playback since we hear ourselves in real life
                    if (senderId != myAssignedId) {
                        onAudioReceived(senderId, senderNickname, audioBytes)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing audio packet on client", e)
        }
    }

    override fun onClose(code: Int, reason: String, remote: Boolean) {
        Log.i(TAG, "Connection closed. Code: $code, Reason: $reason, Remote: $remote")
        notifyStatus("Disconnected from server: $reason")
        onDisconnected()
    }

    override fun onError(ex: Exception) {
        Log.e(TAG, "Connection error", ex)
        notifyStatus("Connection error: ${ex.message}")
    }

    fun sendVoice(audioBytes: ByteArray) {
        if (isOpen) {
            try {
                send(audioBytes)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send sound packet", e)
            }
        }
    }

    private fun sendNickname() {
        if (isOpen) {
            try {
                val nickJson = JSONObject().apply {
                    put("type", "set_nickname")
                    put("nickname", nickname)
                }
                send(nickJson.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed sending nickname registration", e)
            }
        }
    }

    private fun notifyStatus(msg: String) {
        onStatusMessage(msg)
    }
}
