package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import com.example.audio.VoxService
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioEngine
import com.example.network.VoxClient
import com.example.network.VoxServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

enum class AppMode {
    IDLE, SERVER, CLIENT
}

class VoxViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "VoxViewModel"
        const val DEFAULT_PORT = 50005
    }

    private val context = application.applicationContext
    private val audioEngine = AudioEngine(application)

    // --- State Variables ---
    val appMode = MutableStateFlow(AppMode.IDLE)
    val isConnected = MutableStateFlow(false)
    val localIpAddress = MutableStateFlow("Unknown")
    val discoveredServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    
    // On-demand scanning & broadcasting states
    val isScanning = MutableStateFlow(false)
    val scanTimeRemaining = MutableStateFlow(0)
    val isBroadcasting = MutableStateFlow(false)
    val broadcastTimeRemaining = MutableStateFlow(0)
    
    // User Configuration Input
    val nickname = MutableStateFlow("User_" + (100..999).random())
    val targetServerIp = MutableStateFlow("192.168.43.1") // Standard default Android hotspot IP
    val serverLaunchPort = MutableStateFlow(DEFAULT_PORT)

    // Log messages
    private val _statusLog = MutableStateFlow<List<String>>(emptyList())
    val statusLog: StateFlow<List<String>> = _statusLog

    // VOX Config
    val voxEnabled = MutableStateFlow(audioEngine.isVoxEnabled)
    val voxThreshold = MutableStateFlow(audioEngine.voxThreshold)
    val voxHangoverMs = MutableStateFlow(audioEngine.voxHangoverMs)

    // Booster & Routing Configs
    val micBoost = MutableStateFlow(audioEngine.micBoostFactor)
    val playbackBoost = MutableStateFlow(audioEngine.playbackBoostFactor)
    val isSpeakerphoneOn: StateFlow<Boolean> = audioEngine.isSpeakerphoneOn

    // Noise Cancellation Configs
    val noiseSuppressorEnabled = MutableStateFlow(audioEngine.isNoiseSuppressorEnabled)
    val echoCancelerEnabled = MutableStateFlow(audioEngine.isEchoCancelerEnabled)
    val agcEnabled = MutableStateFlow(audioEngine.isAgcEnabled)

    // Real-time Mic Amplitude
    val realtimeAmplitude: StateFlow<Float> = audioEngine.realtimeAmplitude
    val isTransmitting: StateFlow<Boolean> = audioEngine.isTransmitting

    // Peer Lists and Speaker Activity States
    private val rawPeerList = MutableStateFlow<List<VoxServer.PeerInfo>>(emptyList())
    private val peerActiveSpeakerTimestamps = ConcurrentHashMap<Int, Long>()
    private val voiceLevelUpdaterJob: Job

    // Speaker activity combined states
    val activeSpeakerIdName = MutableStateFlow<Pair<Int, String>?>(null)

    // Expose final decorated Peer list with speaking flags
    val peersState: StateFlow<List<UIPeerInfo>> = combine(rawPeerList, activeSpeakerIdName) { list, activeSpeaker ->
        val activeId = activeSpeaker?.first ?: -1
        list.map { peer ->
            UIPeerInfo(
                id = peer.id,
                nickname = peer.nickname,
                isSelf = peer.isSelf,
                isSpeaking = peer.id == activeId
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Network instances
    private var voxServer: VoxServer? = null
    private var voxClient: VoxClient? = null

    private var broadcastJob: Job? = null
    private var discoveryJob: Job? = null
    private var discoveryTimerJob: Job? = null

    fun toggleBroadcast() {
        if (appMode.value != AppMode.SERVER) return
        if (isBroadcasting.value) {
            stopBroadcasting()
        } else {
            startBroadcasting(localIpAddress.value, serverLaunchPort.value, nickname.value + " (Host)")
        }
    }

    fun stopBroadcasting() {
        broadcastJob?.cancel()
        broadcastJob = null
        isBroadcasting.value = false
        broadcastTimeRemaining.value = 0
    }

    fun startBroadcasting(ip: String, port: Int, hostname: String) {
        stopBroadcasting()
        isBroadcasting.value = true
        broadcastTimeRemaining.value = 60
        addLog("[Broadcast] Advertising beacon started on port $port for 60s...")

        broadcastJob = viewModelScope.launch(Dispatchers.IO) {
            var socket: java.net.DatagramSocket? = null
            try {
                socket = java.net.DatagramSocket()
                socket.broadcast = true
                val address = java.net.InetAddress.getByName("255.255.255.255")
                val message = "VOX_SERVER_BEACON:$ip:$port:$hostname"
                val data = message.toByteArray()
                val packet = java.net.DatagramPacket(data, data.size, address, 50006)
                for (sec in 60 downTo 1) {
                    if (appMode.value != AppMode.SERVER || !isBroadcasting.value) {
                        break
                    }
                    broadcastTimeRemaining.value = sec
                    if (sec % 2 == 0 || sec == 60) {
                        try {
                            socket.send(packet)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed sending UDP beacon", e)
                        }
                    }
                    delay(1000)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting broadcast socket", e)
            } finally {
                socket?.close()
                isBroadcasting.value = false
                broadcastTimeRemaining.value = 0
                addLog("[Broadcast] Live advertising expired.")
            }
        }
    }

    fun toggleDiscoveryScan() {
        if (appMode.value != AppMode.IDLE) return
        if (isScanning.value) {
            stopDiscoveryScan()
        } else {
            startDiscoveryScan()
        }
    }

    fun startDiscoveryScan() {
        if (appMode.value != AppMode.IDLE) return
        stopDiscoveryScan() // safety clean
        isScanning.value = true
        scanTimeRemaining.value = 30
        
        addLog("[Discovery] Scanning started for 30s...")
        startDiscoveryListener()

        discoveryTimerJob = viewModelScope.launch(Dispatchers.Default) {
            for (sec in 30 downTo 1) {
                if (appMode.value != AppMode.IDLE || !isScanning.value) break
                scanTimeRemaining.value = sec
                delay(1000)
            }
            stopDiscoveryScan()
            addLog("[Discovery] Scanning stopped.")
        }
    }

    fun stopDiscoveryScan() {
        discoveryTimerJob?.cancel()
        discoveryTimerJob = null
        discoveryJob?.cancel()
        discoveryJob = null
        isScanning.value = false
        scanTimeRemaining.value = 0
    }

    fun startDiscoveryListener() {
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch(Dispatchers.IO) {
            var socket: java.net.DatagramSocket? = null
            try {
                socket = java.net.DatagramSocket(50006)
                socket.reuseAddress = true
                val buffer = ByteArray(1024)
                val packet = java.net.DatagramPacket(buffer, buffer.size)
                while (appMode.value == AppMode.IDLE && isScanning.value) {
                    try {
                        socket.receive(packet)
                        val msg = String(packet.data, 0, packet.length).trim()
                        if (msg.startsWith("VOX_SERVER_BEACON:")) {
                            val parts = msg.split(":")
                            if (parts.size >= 4) {
                                val srvIp = parts[1]
                                val srvPort = parts[2].toIntOrNull() ?: DEFAULT_PORT
                                val srvName = parts.subList(3, parts.size).joinToString(":")
                                
                                if (srvIp != localIpAddress.value) {
                                    val now = System.currentTimeMillis()
                                    val newSrv = DiscoveredServer(srvIp, srvPort, srvName, now)
                                    val current = discoveredServers.value.toMutableList()
                                    val idx = current.indexOfFirst { it.ip == srvIp && it.port == srvPort }
                                    if (idx != -1) {
                                        current[idx] = newSrv
                                    } else {
                                        current.add(0, newSrv)
                                        addLog("[Discovery] Found $srvName online at $srvIp:$srvPort")
                                    }
                                    discoveredServers.value = current
                                    
                                    viewModelScope.launch(Dispatchers.Main) {
                                        stopDiscoveryScan()
                                    }
                                }
                            }
                        }
                    } catch (e: java.net.SocketException) {
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Discovery read error", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not launch discovery receiver on port 50006", e)
            } finally {
                socket?.close()
            }
        }
    }

    init {
        detectLocalIp()

        // Pruning job for dead discovery beacons
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                val now = System.currentTimeMillis()
                val current = discoveredServers.value
                val active = current.filter { now - it.lastSeenTime < 6000 }
                if (active.size != current.size) {
                    discoveredServers.value = active
                }
                delay(1500)
            }
        }

        // Create background coroutine to monitor active speakers and reset speaker state after silence timeout
        voiceLevelUpdaterJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                val now = System.currentTimeMillis()
                var currentSpeakerPair: Pair<Int, String>? = null

                // Go through timestamps and find the most recent speaking client (timeout at 700ms)
                var newestTime = 0L
                for ((id, stamp) in peerActiveSpeakerTimestamps) {
                    if (now - stamp < 700L) {
                        if (stamp > newestTime) {
                            newestTime = stamp
                            // Look up nickname in rawPeerList
                            val name = rawPeerList.value.firstOrNull { it.id == id }?.nickname ?: "Peer $id"
                            currentSpeakerPair = Pair(id, name)
                        }
                    }
                }

                activeSpeakerIdName.value = currentSpeakerPair
                delay(100)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceLevelUpdaterJob.cancel()
        broadcastJob?.cancel()
        discoveryJob?.cancel()
        stopAll()
    }

    /**
     * Finds out the current IPv4 address of this phone.
     */
    fun detectLocalIp() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                for (netInterface in interfaces) {
                    if (!netInterface.isUp || netInterface.isLoopback) continue
                    
                    val addresses = Collections.list(netInterface.inetAddresses)
                    for (address in addresses) {
                        if (!address.isLoopbackAddress) {
                            val hostAddress = address.hostAddress
                            if (hostAddress != null && !hostAddress.contains(":")) {
                                localIpAddress.value = hostAddress
                                // Auto set target IP if we detect we are on hotspot or wifi
                                if (hostAddress.startsWith("192.168.")) {
                                    // Often hotspot is .1 on that subnet, so we keep helper default
                                }
                                return@launch
                            }
                        }
                    }
                }
                localIpAddress.value = "Disconnected"
            } catch (e: Exception) {
                Log.e(TAG, "Error seeking IP address", e)
                localIpAddress.value = "Unknown"
            }
        }
    }

    fun startServerMode() {
        if (appMode.value != AppMode.IDLE) return
        appMode.value = AppMode.SERVER
        discoveryJob?.cancel()
        discoveredServers.value = emptyList()
        addLog("Initializing Server Mode...")
        detectLocalIp()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val port = serverLaunchPort.value
                val server = VoxServer(
                    port = port,
                    serverNickname = nickname.value + " (Host)",
                    onPeerListChanged = { newList ->
                        rawPeerList.value = newList
                    },
                    onAudioReceived = { id, nick, data ->
                        // Server receives audio package from clients, process speaker state and play
                        peerActiveSpeakerTimestamps[id] = System.currentTimeMillis()
                        audioEngine.playAudio(data)
                    },
                    onStatusMessage = { msg ->
                        addLog("[Server] $msg")
                    }
                )

                server.start()
                voxServer = server
                isConnected.value = true
                addLog("Server running.")

                startBroadcasting(localIpAddress.value, port, nickname.value + " (Host)")

                // Launch Foreground Service to ensure screen off / background continuation
                val serviceIntent = Intent(context, VoxService::class.java).apply {
                    action = VoxService.ACTION_START
                    putExtra(VoxService.EXTRA_STATUS, "Hosting on port $port")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                // Start audio operations for host
                audioEngine.startPlayback()
                audioEngine.startRecording { bytes ->
                    voxServer?.broadcastServerAudio(bytes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server", e)
                addLog("Launch failed: ${e.message}")
                stopAll()
            }
        }
    }

    fun startClientMode() {
        if (appMode.value != AppMode.IDLE) return
        stopDiscoveryScan() // Disable any pending scans immediately
        appMode.value = AppMode.CLIENT
        discoveredServers.value = emptyList()
        addLog("Connecting to server...")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val serverIp = targetServerIp.value
                val port = serverLaunchPort.value
                val uri = URI("ws://$serverIp:$port")

                val client = VoxClient(
                    serverUri = uri,
                    nickname = nickname.value,
                    onConnected = { assignedId ->
                        isConnected.value = true
                        addLog("Connected. ID: $assignedId")

                        // Launch Foreground Service to ensure screen off / background continuation
                        val serviceIntent = Intent(context, VoxService::class.java).apply {
                            action = VoxService.ACTION_START
                            putExtra(VoxService.EXTRA_STATUS, "Connected to: $serverIp")
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                        
                        // Start recording and playback
                        audioEngine.startPlayback()
                        audioEngine.startRecording { bytes ->
                            voxClient?.sendVoice(bytes)
                        }
                    },
                    onDisconnected = {
                        addLog("Disconnected from walkie-talkie list")
                        stopAll()
                    },
                    onPeerListChanged = { newList ->
                        rawPeerList.value = newList
                    },
                    onAudioReceived = { id, nick, data ->
                        // Record other client's speaking activity
                        peerActiveSpeakerTimestamps[id] = System.currentTimeMillis()
                        audioEngine.playAudio(data)
                    },
                    onStatusMessage = { msg ->
                        addLog("[Client] $msg")
                    }
                )

                voxClient = client
                client.connect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed initiating client connection", e)
                addLog("Connection failed: ${e.message}")
                stopAll()
            }
        }
    }

    fun stopAll() {
        viewModelScope.launch(Dispatchers.IO) {
            addLog("Shutting down audio engine and network sockets...")

            // Stop Foreground Service
            try {
                val serviceIntent = Intent(context, VoxService::class.java).apply {
                    action = VoxService.ACTION_STOP
                }
                context.startService(serviceIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping VoxService", e)
            }
            
            // Stop scanning and broadcasting timers
            stopDiscoveryScan()
            stopBroadcasting()

            // Stop sound
            audioEngine.stopRecording()
            audioEngine.stopPlayback()

            // Close server if present
            try {
                voxServer?.stopServer()
            } catch (e: Exception) {
                Log.e(TAG, "Error stop server", e)
            }
            voxServer = null

            // Close client if present
            try {
                voxClient?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error close client", e)
            }
            voxClient = null

            isConnected.value = false
            appMode.value = AppMode.IDLE
            rawPeerList.value = emptyList()
            peerActiveSpeakerTimestamps.clear()
            addLog("Status: Offline/Idle")
        }
    }

    // --- Dynamic Parameters Updates ---

    fun toggleVox(enabled: Boolean) {
        voxEnabled.value = enabled
        audioEngine.isVoxEnabled = enabled
        addLog("Voice Activation (VOX): " + if (enabled) "Enabled" else "Continuous streaming")
    }

    fun setVoxThreshold(value: Float) {
        voxThreshold.value = value
        audioEngine.voxThreshold = value
    }

    fun setVoxHangover(value: Long) {
        voxHangoverMs.value = value
        audioEngine.voxHangoverMs = value
    }

    fun setMicBoost(value: Float) {
        micBoost.value = value
        audioEngine.micBoostFactor = value
    }

    fun setPlaybackBoost(value: Float) {
        playbackBoost.value = value
        audioEngine.playbackBoostFactor = value
    }

    fun toggleSpeakerphone() {
        val current = isSpeakerphoneOn.value
        audioEngine.setSpeakerphoneOn(!current)
        addLog("Audio output switched to " + if (!current) "Loudspeaker" else "Earpiece/Receiver")
    }

    fun toggleNoiseSuppressor(enabled: Boolean) {
        noiseSuppressorEnabled.value = enabled
        audioEngine.isNoiseSuppressorEnabled = enabled
        audioEngine.updateHardwareEffects(enabled, echoCancelerEnabled.value, agcEnabled.value)
        addLog("Noise Suppressor: " + if (enabled) "ON" else "OFF")
    }

    fun toggleEchoCanceler(enabled: Boolean) {
        echoCancelerEnabled.value = enabled
        audioEngine.isEchoCancelerEnabled = enabled
        audioEngine.updateHardwareEffects(noiseSuppressorEnabled.value, enabled, agcEnabled.value)
        addLog("Echo Canceler: " + if (enabled) "ON" else "OFF")
    }

    fun toggleAgc(enabled: Boolean) {
        agcEnabled.value = enabled
        audioEngine.isAgcEnabled = enabled
        audioEngine.updateHardwareEffects(noiseSuppressorEnabled.value, echoCancelerEnabled.value, enabled)
        addLog("Automatic Gain Control: " + if (enabled) "ON" else "OFF")
    }

    fun clearLogs() {
        _statusLog.value = emptyList()
    }

    private fun addLog(message: String) {
        val current = _statusLog.value.toMutableList()
        current.add(0, message) // Insert at top
        if (current.size > 50) current.removeAt(current.size - 1)
        _statusLog.value = current
    }
}

data class UIPeerInfo(
    val id: Int,
    val nickname: String,
    val isSelf: Boolean,
    val isSpeaking: Boolean
)

data class DiscoveredServer(
    val ip: String,
    val port: Int,
    val hostname: String,
    val lastSeenTime: Long
)
