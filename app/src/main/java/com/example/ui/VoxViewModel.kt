package com.example.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
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
    private val audioEngine = AudioEngine()

    // --- State Variables ---
    val appMode = MutableStateFlow(AppMode.IDLE)
    val isConnected = MutableStateFlow(false)
    val localIpAddress = MutableStateFlow("Unknown")
    
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

    init {
        detectLocalIp()

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
        appMode.value = AppMode.CLIENT
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
