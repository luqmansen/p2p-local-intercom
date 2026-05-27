package com.example.audio

import android.annotation.SuppressLint
import android.media.ToneGenerator
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

class AudioEngine(private val context: android.content.Context) {
    private val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager

    companion object {
        const val TAG = "AudioEngine"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    // Settings (volatile for thread safety)
    @Volatile var isVoxEnabled: Boolean = true
    @Volatile var voxThreshold: Float = 0.05f       // 5% peak amplitude
    @Volatile var voxHangoverMs: Long = 800L        // Keep transmitting for 800ms after quiet
    @Volatile var isNoiseSuppressorEnabled: Boolean = true
    @Volatile var isEchoCancelerEnabled: Boolean = true
    @Volatile var isAgcEnabled: Boolean = true
    @Volatile var micBoostFactor: Float = 2.5f       // Microphone/recording boost factor (1.0x - 5.0x)
    @Volatile var playbackBoostFactor: Float = 2.5f  // Playback/speaker boost factor (1.0x - 5.0x)
    @Volatile var isHpfEnabled: Boolean = true
    @Volatile var hpfCutoff: Float = 120f             // Default HPF cutoff (Hz)
    @Volatile var isLimiterEnabled: Boolean = true
    @Volatile var limiterThreshold: Float = 0.8f      // Default Soft Limiter threshold (80% full scale)
    @Volatile var isAcousticCuesEnabled: Boolean = true

    private var toneGen: ToneGenerator? = null
    @Volatile var tonePlayingUntilMillis: Long = 0L

    // State for HPF to maintain continuity across audio buffers
    private var lastHpfInput: Float = 0f
    private var lastHpfOutput: Float = 0f

    // Read-only coefficient for HPF
    private val hpfAlpha: Float
        get() = (1f / (1f + (2f * Math.PI.toFloat() * hpfCutoff / SAMPLE_RATE)))

    // State flows for UI observing
    private val _realtimeAmplitude = MutableStateFlow(0f)
    val realtimeAmplitude: StateFlow<Float> = _realtimeAmplitude

    private val _isTransmitting = MutableStateFlow(false)
    val isTransmitting: StateFlow<Boolean> = _isTransmitting

    private val _isSpeakerphoneOn = MutableStateFlow(true)
    val isSpeakerphoneOn: StateFlow<Boolean> = _isSpeakerphoneOn

    // Audio Capture and Render
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    // Audio FX
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var agc: AutomaticGainControl? = null

    // Coroutine Jobs for capture & playback
    private var recordJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Last time amplitude was above threshold
    private var lastVoiceDetectedTime: Long = 0L

    @SuppressLint("MissingPermission")
    fun startRecording(onAudioChunk: (ByteArray) -> Unit) {
        if (recordJob != null) return

        startCommunicationMode()

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        // Ensure a buffer of about ~40ms to keep latency low but reading robust
        val desiredBufferSize = max(bufferSize, 2048)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_IN,
                ENCODING,
                desiredBufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord could not be initialized")
                return
            }

            // Bind hardware audio effects to the active session ID
            val sessionId = audioRecord!!.audioSessionId
            setupAudioEffects(sessionId)

            audioRecord?.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord", e)
            releaseRecorder()
            return
        }

        recordJob = scope.launch {
            val shortBuffer = ShortArray(1024) // 1024 shorts is 64ms at 16000Hz
            val byteBuffer = ByteArray(2048)
            lastVoiceDetectedTime = 0L
            lastHpfInput = 0f
            lastHpfOutput = 0f

            while (audioRecord != null && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val readResult = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: -1
                if (readResult > 0) {
                    val nowTime = System.currentTimeMillis()
                    if (nowTime < tonePlayingUntilMillis) {
                        for (i in 0 until readResult) {
                            shortBuffer[i] = 0
                        }
                    }
                    // 1. High Pass Filter (HPF) to remove low-frequency rumble & engine hums
                    if (isHpfEnabled) {
                        val alpha = hpfAlpha
                        for (i in 0 until readResult) {
                            val x = shortBuffer[i].toFloat()
                            val y = alpha * (lastHpfOutput + x - lastHpfInput)
                            lastHpfInput = x
                            lastHpfOutput = y
                            shortBuffer[i] = y.coerceIn(-32768f, 32767f).toInt().toShort()
                        }
                    }

                    // 2. Apply real-time digital software mic boost
                    val currentMicBoost = micBoostFactor
                    if (currentMicBoost != 1.0f) {
                        for (i in 0 until readResult) {
                            val originalVal = shortBuffer[i].toFloat()
                            val boostedVal = originalVal * currentMicBoost
                            shortBuffer[i] = boostedVal.coerceIn(-32768f, 32767f).toInt().toShort()
                        }
                    }

                    // 3. Apply Soft Limiter to prevent harsh digital clipping from the boost
                    if (isLimiterEnabled) {
                        val limitT = limiterThreshold
                        for (i in 0 until readResult) {
                            val sampleNorm = shortBuffer[i].toFloat() / 32768f
                            val absSample = abs(sampleNorm)
                            if (absSample > limitT) {
                                val excess = absSample - limitT
                                val compressedExcess = (1f - limitT) * (excess / ((1f - limitT) + excess))
                                val sign = if (sampleNorm < 0f) -1f else 1f
                                val limitedNorm = sign * (limitT + compressedExcess)
                                shortBuffer[i] = (limitedNorm * 32768f).coerceIn(-32768f, 32767f).toInt().toShort()
                            }
                        }
                    }

                    var maxAbs = 0
                    for (i in 0 until readResult) {
                        val sampleValue = shortBuffer[i].toInt()
                        if (abs(sampleValue) > maxAbs) {
                            maxAbs = abs(sampleValue)
                        }
                    }

                    // Calculate peak amplitude normalized 0f to 1f
                    val currentPeak = maxAbs / 32768f
                    _realtimeAmplitude.value = currentPeak

                    val now = System.currentTimeMillis()

                    val voiceActive = if (isVoxEnabled) {
                        if (currentPeak >= voxThreshold) {
                            lastVoiceDetectedTime = now
                            true
                        } else {
                            (now - lastVoiceDetectedTime) <= voxHangoverMs
                        }
                    } else {
                        // If VOX is off, we are in continuous streaming / push-to-hear mode
                        true
                    }

                    val previousTransmitting = _isTransmitting.value
                    _isTransmitting.value = voiceActive

                    if (voiceActive && !previousTransmitting) {
                        playTxStartTone()
                    } else if (!voiceActive && previousTransmitting) {
                        playTxEndTone()
                    }

                    if (voiceActive) {
                        // Convert short buffer to byte array (endianness is little-endian on Android ARM)
                        for (i in 0 until readResult) {
                            val shortVal = shortBuffer[i]
                            byteBuffer[i * 2] = (shortVal.toInt() and 0xFF).toByte()
                            byteBuffer[i * 2 + 1] = ((shortVal.toInt() shr 8) and 0xFF).toByte()
                        }
                        val finalBytesLength = readResult * 2
                        val chunkToSend = byteBuffer.copyOfRange(0, finalBytesLength)
                        onAudioChunk(chunkToSend)
                    }
                } else {
                    Thread.sleep(10)
                }
            }
        }
    }

    private fun setupAudioEffects(sessionId: Int) {
        try {
            if (NoiseSuppressor.isAvailable() && isNoiseSuppressorEnabled) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)
                noiseSuppressor?.enabled = true
                Log.d(TAG, "NoiseSuppressor enabled")
            }
            if (AcousticEchoCanceler.isAvailable() && isEchoCancelerEnabled) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)
                echoCanceler?.enabled = true
                Log.d(TAG, "AcousticEchoCanceler enabled")
            }
            if (AutomaticGainControl.isAvailable() && isAgcEnabled) {
                agc = AutomaticGainControl.create(sessionId)
                agc?.enabled = true
                Log.d(TAG, "AutomaticGainControl enabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply hardware audio effects", e)
        }
    }

    private fun releaseAudioEffects() {
        try {
            noiseSuppressor?.release()
            noiseSuppressor = null
            echoCanceler?.release()
            echoCanceler = null
            agc?.release()
            agc = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio effects", e)
        }
    }

    fun stopRecording() {
        recordJob?.cancel()
        recordJob = null
        releaseRecorder()
        _realtimeAmplitude.value = 0f
        _isTransmitting.value = false
    }

    private fun releaseRecorder() {
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            // ignore
        }
        audioRecord?.release()
        audioRecord = null
        releaseAudioEffects()
        releaseToneGenerator()
        checkAndResetAudioMode()
    }

    // --- Audio Playback ---
    fun startPlayback() {
        if (audioTrack != null) return

        startCommunicationMode()

        val minPlayBufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        val desiredBufferSize = max(minPlayBufSize, 4096)

        try {
            audioTrack = AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                SAMPLE_RATE,
                CHANNEL_OUT,
                ENCODING,
                desiredBufferSize,
                AudioTrack.MODE_STREAM
            )

            if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack?.play()
                Log.d(TAG, "Playback started successfully")
            } else {
                Log.e(TAG, "AudioTrack state not initialized")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioTrack", e)
        }
    }

    fun playAudio(data: ByteArray) {
        val track = audioTrack
        if (track != null && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
            try {
                val currentPlayBoost = playbackBoostFactor
                val isLimiter = isLimiterEnabled
                val limitT = limiterThreshold

                if (currentPlayBoost != 1.0f || isLimiter) {
                    val len = data.size
                    val result = ByteArray(len)
                    for (i in 0 until len step 2) {
                        if (i + 1 < len) {
                            // Extract 16-bit linear PCM sample
                            val sample = ((data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)).toShort()
                            
                            // 1. Gain boost
                            var fSample = sample.toFloat() * currentPlayBoost
                            
                            // 2. Soft limiter
                            if (isLimiter) {
                                val sampleNorm = fSample / 32768f
                                val absVolume = abs(sampleNorm)
                                if (absVolume > limitT) {
                                    val excess = absVolume - limitT
                                    val compressedExcess = (1f - limitT) * (excess / ((1f - limitT) + excess))
                                    val sign = if (sampleNorm < 0f) -1f else 1f
                                    val limitedNorm = sign * (limitT + compressedExcess)
                                    fSample = limitedNorm * 32768f
                                }
                            }
                            
                            val finalSample = fSample.coerceIn(-32768f, 32767f).toInt()
                            result[i] = (finalSample and 0xFF).toByte()
                            result[i + 1] = ((finalSample shr 8) and 0xFF).toByte()
                        }
                    }
                    track.write(result, 0, result.size)
                } else {
                    track.write(data, 0, data.size)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error writing to AudioTrack", e)
            }
        }
    }

    fun stopPlayback() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            // ignore
        }
        audioTrack = null
        Log.d(TAG, "Playback stopped")
        checkAndResetAudioMode()
    }

    private var routingReceiver: BroadcastReceiver? = null
    private var communicationDeviceListener: Any? = null

    private fun registerRoutingReceiver() {
        if (routingReceiver != null) return
        routingReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                Log.d(TAG, "Audio routing broadcast received: $action")
                // Whenever there is a change, re-apply the current routing to pick the best connected device
                setSpeakerphoneOn(_isSpeakerphoneOn.value)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction("android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
        }
        try {
            context.registerReceiver(routingReceiver, filter)
            Log.d(TAG, "Audio routing broadcast receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register audio routing receiver", e)
        }
    }

    private fun unregisterRoutingReceiver() {
        routingReceiver?.let {
            try {
                context.unregisterReceiver(it)
                Log.d(TAG, "Audio routing broadcast receiver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister audio routing receiver", e)
            }
        }
        routingReceiver = null
    }

    @SuppressLint("NewApi")
    private fun registerCommunicationDeviceListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (communicationDeviceListener != null) return
            try {
                val listener = AudioManager.OnCommunicationDeviceChangedListener { device ->
                    Log.d(TAG, "Communication device changed via listener: ${device?.let { getDeviceTypeName(it.type) } ?: "NONE"}")
                }
                communicationDeviceListener = listener
                audioManager.addOnCommunicationDeviceChangedListener(context.mainExecutor, listener)
                Log.d(TAG, "Registered OnCommunicationDeviceChangedListener")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register OnCommunicationDeviceChangedListener", e)
            }
        }
    }

    @SuppressLint("NewApi")
    private fun unregisterCommunicationDeviceListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val listener = communicationDeviceListener as? AudioManager.OnCommunicationDeviceChangedListener
            if (listener != null) {
                try {
                    audioManager.removeOnCommunicationDeviceChangedListener(listener)
                    Log.d(TAG, "Unregistered OnCommunicationDeviceChangedListener")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to unregister OnCommunicationDeviceChangedListener", e)
                }
            }
            communicationDeviceListener = null
        }
    }

    private fun selectBestCommunicationDevice(devices: List<AudioDeviceInfo>): AudioDeviceInfo? {
        // Preferred order of non-speaker communication devices:
        // 1. BLE Headset (Bluetooth Low Energy Audio)
        val ble = devices.find { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
        if (ble != null) return ble

        // 2. Bluetooth SCO
        val bluetoothSco = devices.find { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        if (bluetoothSco != null) return bluetoothSco

        // 3. USB Headset
        val usbHeadset = devices.find { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        if (usbHeadset != null) return usbHeadset

        // 4. Wired Headset
        val wiredHeadset = devices.find { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
        if (wiredHeadset != null) return wiredHeadset

        // 5. Wired Headphones (no mic)
        val wiredHeadphones = devices.find { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }
        if (wiredHeadphones != null) return wiredHeadphones

        // 6. Built-in Earpiece
        val earpiece = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        if (earpiece != null) return earpiece

        return null
    }

    private fun getDeviceTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
            else -> "UNKNOWN ($type)"
        }
    }

    fun setSpeakerphoneOn(on: Boolean) {
        _isSpeakerphoneOn.value = on
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audioManager.availableCommunicationDevices
                if (on) {
                    val speakerDevice = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speakerDevice != null) {
                        audioManager.clearCommunicationDevice()
                        val success = audioManager.setCommunicationDevice(speakerDevice)
                        Log.d(TAG, "Successfully set communication device to Speaker (Success: $success)")
                    } else {
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = true
                    }
                } else {
                    val targetDevice = selectBestCommunicationDevice(devices)
                    if (targetDevice != null) {
                        audioManager.clearCommunicationDevice()
                        val success = audioManager.setCommunicationDevice(targetDevice)
                        Log.d(TAG, "Successfully set communication device to: ${getDeviceTypeName(targetDevice.type)} (Success: $success)")
                    } else {
                        audioManager.clearCommunicationDevice()
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = false
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                if (on) {
                    if (audioManager.isBluetoothScoOn) {
                        audioManager.isBluetoothScoOn = false
                        audioManager.stopBluetoothSco()
                    }
                    audioManager.isSpeakerphoneOn = true
                } else {
                    if (audioManager.isBluetoothScoAvailableOffCall) {
                        audioManager.isSpeakerphoneOn = false
                        audioManager.isBluetoothScoOn = true
                        audioManager.startBluetoothSco()
                        Log.d(TAG, "Legacy routing: Started Bluetooth SCO")
                    } else {
                        audioManager.isBluetoothScoOn = false
                        audioManager.stopBluetoothSco()
                        audioManager.isSpeakerphoneOn = false
                        Log.d(TAG, "Legacy routing: Normal/Earpiece mode")
                    }
                }
            }
            Log.d(TAG, "Speakerphone set to $on")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle speakerphone/route audio", e)
        }
    }

    private fun startCommunicationMode() {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            registerRoutingReceiver()
            registerCommunicationDeviceListener()
            setSpeakerphoneOn(_isSpeakerphoneOn.value)
            Log.d(TAG, "Communication mode started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start communication mode", e)
        }
    }

    private fun stopCommunicationMode() {
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
            unregisterRoutingReceiver()
            unregisterCommunicationDeviceListener()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
                @Suppress("DEPRECATION")
                if (audioManager.isBluetoothScoOn) {
                    audioManager.isBluetoothScoOn = false
                    audioManager.stopBluetoothSco()
                }
            }
            Log.d(TAG, "Communication mode stopped: normal")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop communication mode", e)
        }
    }

    private fun checkAndResetAudioMode() {
        if (audioRecord == null && audioTrack == null) {
            stopCommunicationMode()
        }
    }

    fun updateVoxSettings(enabled: Boolean, threshold: Float, hangoverMs: Long) {
        this.isVoxEnabled = enabled
        this.voxThreshold = threshold
        this.voxHangoverMs = hangoverMs
    }

    fun updateHardwareEffects(ns: Boolean, ec: Boolean, agcVal: Boolean) {
        this.isNoiseSuppressorEnabled = ns
        this.isEchoCancelerEnabled = ec
        this.isAgcEnabled = agcVal

        // If recording is active, we can recreate/toggle these effects dynamically
        audioRecord?.audioSessionId?.let { sessionId ->
            releaseAudioEffects()
            setupAudioEffects(sessionId)
        }
    }

    private fun initToneGenerator() {
        if (toneGen == null) {
            try {
                // Play over STREAM_MUSIC to work properly with all output routes
                toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize ToneGenerator", e)
            }
        }
    }

    fun playTxStartTone() {
        if (!isAcousticCuesEnabled) return
        try {
            tonePlayingUntilMillis = System.currentTimeMillis() + 300L
            initToneGenerator()
            toneGen?.startTone(ToneGenerator.TONE_PROP_PROMPT, 100)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing TX start tone", e)
        }
    }

    fun playTxEndTone() {
        if (!isAcousticCuesEnabled) return
        try {
            tonePlayingUntilMillis = System.currentTimeMillis() + 320L
            initToneGenerator()
            toneGen?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing TX end tone", e)
        }
    }

    private fun releaseToneGenerator() {
        try {
            toneGen?.release()
            toneGen = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing ToneGenerator", e)
        }
    }
}
