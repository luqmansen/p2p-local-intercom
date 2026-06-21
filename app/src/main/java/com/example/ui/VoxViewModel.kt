package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import com.example.audio.VoxService
import com.example.update.UpdateInfo
import com.example.update.UpdateManager
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

enum class AppThemeChoice {
    SYSTEM, LIGHT, DARK
}

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpdateAvailable(val info: UpdateInfo) : UpdateState()
    data class Downloading(val progress: Int) : UpdateState()
    data class Error(val message: String) : UpdateState()
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
    val themeChoice = MutableStateFlow(AppThemeChoice.SYSTEM)
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

    // Software DSP Configs (HPF and Soft Limiter)
    val hpfEnabled = MutableStateFlow(audioEngine.isHpfEnabled)
    val hpfCutoff = MutableStateFlow(audioEngine.hpfCutoff)
    val limiterEnabled = MutableStateFlow(audioEngine.isLimiterEnabled)
    val limiterThreshold = MutableStateFlow(audioEngine.limiterThreshold)
    val acousticCuesEnabled = MutableStateFlow(audioEngine.isAcousticCuesEnabled)

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
    private var clientConnectionJob: Job? = null

    // OTA update
    private val updateManager = UpdateManager(context)
    val updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)

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
        // Load configurations from SharedPreferences
        val prefs = context.getSharedPreferences("vox_prefs", Context.MODE_PRIVATE)

        // 0. Theme Choice
        val storedTheme = prefs.getString("themeChoice", AppThemeChoice.SYSTEM.name) ?: AppThemeChoice.SYSTEM.name
        try {
            themeChoice.value = AppThemeChoice.valueOf(storedTheme)
        } catch (e: Exception) {
            themeChoice.value = AppThemeChoice.SYSTEM
        }

        // 1. Nickname
        val storedNick = prefs.getString("nickname", null)
        if (storedNick != null) {
            nickname.value = storedNick
        }

        // 2. targetServerIp
        val storedTargetIp = prefs.getString("targetServerIp", null)
        if (storedTargetIp != null) {
            targetServerIp.value = storedTargetIp
        }

        // 3. serverLaunchPort
        val storedPort = prefs.getInt("serverLaunchPort", -1)
        if (storedPort != -1) {
            serverLaunchPort.value = storedPort
        }

        // 4. VOX enabled
        val storedVoxEnabled = prefs.getBoolean("voxEnabled", audioEngine.isVoxEnabled)
        voxEnabled.value = storedVoxEnabled
        audioEngine.isVoxEnabled = storedVoxEnabled

        // 5. VOX threshold
        val storedVoxThreshold = prefs.getFloat("voxThreshold", audioEngine.voxThreshold).coerceIn(0.005f, 0.30f)
        voxThreshold.value = storedVoxThreshold
        audioEngine.voxThreshold = storedVoxThreshold

        // 6. VOX hangover
        val storedVoxHangover = prefs.getLong("voxHangoverMs", audioEngine.voxHangoverMs).coerceIn(200L, 2000L)
        voxHangoverMs.value = storedVoxHangover
        audioEngine.voxHangoverMs = storedVoxHangover

        // 7. micBoost
        val storedMicBoost = prefs.getFloat("micBoost", audioEngine.micBoostFactor).coerceIn(1.0f, 5.0f)
        micBoost.value = storedMicBoost
        audioEngine.micBoostFactor = storedMicBoost

        // 8. playbackBoost
        val storedPlaybackBoost = prefs.getFloat("playbackBoost", audioEngine.playbackBoostFactor).coerceIn(1.0f, 5.0f)
        playbackBoost.value = storedPlaybackBoost
        audioEngine.playbackBoostFactor = storedPlaybackBoost

        // 9. noiseSuppressorEnabled
        val storedNSEnabled = prefs.getBoolean("noiseSuppressorEnabled", audioEngine.isNoiseSuppressorEnabled)
        noiseSuppressorEnabled.value = storedNSEnabled
        audioEngine.isNoiseSuppressorEnabled = storedNSEnabled

        // 10. echoCancelerEnabled
        val storedECEnabled = prefs.getBoolean("echoCancelerEnabled", audioEngine.isEchoCancelerEnabled)
        echoCancelerEnabled.value = storedECEnabled
        audioEngine.isEchoCancelerEnabled = storedECEnabled

        // 11. agcEnabled
        val storedAGCEnabled = prefs.getBoolean("agcEnabled", audioEngine.isAgcEnabled)
        agcEnabled.value = storedAGCEnabled
        audioEngine.isAgcEnabled = storedAGCEnabled

        // Update hardware effects based on loaded values
        audioEngine.updateHardwareEffects(storedNSEnabled, storedECEnabled, storedAGCEnabled)

        // 12. hpfEnabled
        val storedHpfEnabled = prefs.getBoolean("hpfEnabled", audioEngine.isHpfEnabled)
        hpfEnabled.value = storedHpfEnabled
        audioEngine.isHpfEnabled = storedHpfEnabled

        // 13. hpfCutoff
        val storedHpfCutoff = prefs.getFloat("hpfCutoff", audioEngine.hpfCutoff).coerceIn(60f, 300f)
        hpfCutoff.value = storedHpfCutoff
        audioEngine.hpfCutoff = storedHpfCutoff

        // 14. limiterEnabled
        val storedLimiterEnabled = prefs.getBoolean("limiterEnabled", audioEngine.isLimiterEnabled)
        limiterEnabled.value = storedLimiterEnabled
        audioEngine.isLimiterEnabled = storedLimiterEnabled

        // 15. limiterThreshold
        val storedLimiterThreshold =
            prefs.getFloat("limiterThreshold", audioEngine.limiterThreshold).coerceIn(0.5f, 0.99f)
        limiterThreshold.value = storedLimiterThreshold
        audioEngine.limiterThreshold = storedLimiterThreshold

        // 16. isSpeakerphoneOn
        val storedSpeakerphone = prefs.getBoolean("isSpeakerphoneOn", true) // Default loudspeaker is true
        audioEngine.setSpeakerphoneOn(storedSpeakerphone)

        // 17. acousticCuesEnabled
        val storedAcousticCues = prefs.getBoolean("acousticCuesEnabled", audioEngine.isAcousticCuesEnabled)
        acousticCuesEnabled.value = storedAcousticCues
        audioEngine.isAcousticCuesEnabled = storedAcousticCues

        // Launch watchers to save any config changes
        viewModelScope.launch {
            themeChoice.collect { prefs.edit().putString("themeChoice", it.name).apply() }
        }
        viewModelScope.launch {
            nickname.collect { prefs.edit().putString("nickname", it).apply() }
        }
        viewModelScope.launch {
            targetServerIp.collect { prefs.edit().putString("targetServerIp", it).apply() }
        }
        viewModelScope.launch {
            serverLaunchPort.collect { prefs.edit().putInt("serverLaunchPort", it).apply() }
        }
        viewModelScope.launch {
            voxEnabled.collect { prefs.edit().putBoolean("voxEnabled", it).apply() }
        }
        viewModelScope.launch {
            voxThreshold.collect { prefs.edit().putFloat("voxThreshold", it).apply() }
        }
        viewModelScope.launch {
            voxHangoverMs.collect { prefs.edit().putLong("voxHangoverMs", it).apply() }
        }
        viewModelScope.launch {
            micBoost.collect { prefs.edit().putFloat("micBoost", it).apply() }
        }
        viewModelScope.launch {
            playbackBoost.collect { prefs.edit().putFloat("playbackBoost", it).apply() }
        }
        viewModelScope.launch {
            noiseSuppressorEnabled.collect { prefs.edit().putBoolean("noiseSuppressorEnabled", it).apply() }
        }
        viewModelScope.launch {
            echoCancelerEnabled.collect { prefs.edit().putBoolean("echoCancelerEnabled", it).apply() }
        }
        viewModelScope.launch {
            agcEnabled.collect { prefs.edit().putBoolean("agcEnabled", it).apply() }
        }
        viewModelScope.launch {
            hpfEnabled.collect { prefs.edit().putBoolean("hpfEnabled", it).apply() }
        }
        viewModelScope.launch {
            hpfCutoff.collect { prefs.edit().putFloat("hpfCutoff", it).apply() }
        }
        viewModelScope.launch {
            limiterEnabled.collect { prefs.edit().putBoolean("limiterEnabled", it).apply() }
        }
        viewModelScope.launch {
            limiterThreshold.collect { prefs.edit().putFloat("limiterThreshold", it).apply() }
        }
        viewModelScope.launch {
            isSpeakerphoneOn.collect { prefs.edit().putBoolean("isSpeakerphoneOn", it).apply() }
        }
        viewModelScope.launch {
            acousticCuesEnabled.collect { prefs.edit().putBoolean("acousticCuesEnabled", it).apply() }
        }

        detectLocalIp()

        // Check for updates in the background a few seconds after launch
        viewModelScope.launch(Dispatchers.IO) {
            delay(4_000)
            checkForUpdate()
        }

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
        addLog("Client mode activated.")

        clientConnectionJob?.cancel()
        clientConnectionJob = viewModelScope.launch(Dispatchers.IO) {
            while (appMode.value == AppMode.CLIENT) {
                if (!isConnected.value) {
                    val serverIp = targetServerIp.value
                    val port = serverLaunchPort.value
                    addLog("Connecting to server at $serverIp:$port...")

                    try {
                        val uri = URI("ws://$serverIp:$port")

                        // Clean up old audio engine state before trying again
                        audioEngine.stopRecording()
                        audioEngine.stopPlayback()

                        try {
                            voxClient?.close()
                        } catch (e: Exception) {
                        }
                        voxClient = null

                        var isClientConnectionActive = true

                        val client = VoxClient(
                            serverUri = uri,
                            nickname = nickname.value,
                            onConnected = { assignedId ->
                                isConnected.value = true
                                addLog("Connected to server. ID: $assignedId")

                                // Launch Foreground Service to ensure screen off / background continuation
                                val serviceIntent = Intent(context, VoxService::class.java).apply {
                                    action = VoxService.ACTION_START
                                    putExtra(VoxService.EXTRA_STATUS, "Connected to: $serverIp")
                                }
                                try {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(serviceIntent)
                                    } else {
                                        context.startService(serviceIntent)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to start service", e)
                                }

                                // Start recording and playback
                                audioEngine.startPlayback()
                                audioEngine.startRecording { bytes ->
                                    voxClient?.sendVoice(bytes)
                                }
                            },
                            onDisconnected = {
                                if (isClientConnectionActive) {
                                    isClientConnectionActive = false
                                    isConnected.value = false
                                    addLog("Connection lost. Retrying in background...")
                                    audioEngine.stopRecording()
                                    audioEngine.stopPlayback()
                                }
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
                        val connected = client.connectBlocking()
                        if (connected) {
                            while (appMode.value == AppMode.CLIENT && isConnected.value && client.isOpen) {
                                delay(1000)
                            }
                        } else {
                            isConnected.value = false
                            addLog("Connection attempt failed. Retrying in 3 seconds...")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed initiating client connection", e)
                        isConnected.value = false
                        addLog("Connection failed: ${e.message}. Retrying in 3 seconds...")
                    }
                }
                delay(3000)
            }
        }
    }

    fun stopAll() {
        viewModelScope.launch(Dispatchers.IO) {
            addLog("Shutting down audio engine and network sockets...")

            // Cancel any client reconnection job
            clientConnectionJob?.cancel()
            clientConnectionJob = null

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

    fun setThemeChoice(choice: AppThemeChoice) {
        themeChoice.value = choice
        addLog("Application Theme changed to: ${choice.name}")
    }

    fun toggleAcousticCues(enabled: Boolean) {
        acousticCuesEnabled.value = enabled
        audioEngine.isAcousticCuesEnabled = enabled
        addLog("Acoustic Cues (Beep Feeds): " + if (enabled) "Enabled" else "Muted")
    }

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

    fun toggleHpf(enabled: Boolean) {
        hpfEnabled.value = enabled
        audioEngine.isHpfEnabled = enabled
        addLog("DSP High-Pass Filter: " + if (enabled) "ACTIVE" else "BYPASSED")
    }

    fun setHpfCutoff(value: Float) {
        hpfCutoff.value = value
        audioEngine.hpfCutoff = value
    }

    fun toggleLimiter(enabled: Boolean) {
        limiterEnabled.value = enabled
        audioEngine.isLimiterEnabled = enabled
        addLog("DSP Soft Limiter: " + if (enabled) "ACTIVE" else "BYPASSED")
    }

    fun setLimiterThreshold(value: Float) {
        limiterThreshold.value = value
        audioEngine.limiterThreshold = value
    }

    fun clearLogs() {
        _statusLog.value = emptyList()
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            updateState.value = UpdateState.Checking
            try {
                val info = updateManager.checkForUpdate()
                updateState.value = if (info != null) UpdateState.UpdateAvailable(info) else UpdateState.Idle
            } catch (e: Exception) {
                Log.w(TAG, "Update check error: ${e.message}")
                updateState.value = UpdateState.Idle
            }
        }
    }

    fun downloadAndInstallUpdate(info: UpdateInfo) {
        viewModelScope.launch {
            try {
                val apkFile = updateManager.downloadApk(info) { progress ->
                    updateState.value = UpdateState.Downloading(progress)
                }
                // Hand off to the system installer; dismiss dialog
                updateState.value = UpdateState.Idle
                updateManager.installApk(apkFile)
            } catch (e: Exception) {
                Log.e(TAG, "Update download failed", e)
                updateState.value = UpdateState.Error(e.message ?: "Download failed")
            }
        }
    }

    fun dismissUpdate() {
        updateState.value = UpdateState.Idle
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
