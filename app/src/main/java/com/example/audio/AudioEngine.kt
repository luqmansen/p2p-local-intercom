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
    @Volatile
    var isVoxEnabled: Boolean = true

    @Volatile
    var voxThreshold: Float = 0.05f       // 5% peak amplitude

    @Volatile
    var voxHangoverMs: Long = 800L        // Keep transmitting for 800ms after quiet

    @Volatile
    var isNoiseSuppressorEnabled: Boolean = true

    @Volatile
    var isEchoCancelerEnabled: Boolean = true

    @Volatile
    var isAgcEnabled: Boolean = true

    @Volatile
    var micBoostFactor: Float = 2.5f       // Microphone/recording boost factor (1.0x - 5.0x)

    @Volatile
    var playbackBoostFactor: Float = 2.5f  // Playback/speaker boost factor (1.0x - 5.0x)

    @Volatile
    var isHpfEnabled: Boolean = true

    @Volatile
    var hpfCutoff: Float = 150f             // Default HPF cutoff (Hz) - higher helps reject wind

    @Volatile
    var isLimiterEnabled: Boolean = true

    @Volatile
    var limiterThreshold: Float = 0.8f      // Default Soft Limiter threshold (80% full scale)

    @Volatile
    var isAcousticCuesEnabled: Boolean = true

    private var toneGen: ToneGenerator? = null

    @Volatile
    var tonePlayingUntilMillis: Long = 0L

    // --- Software high-pass filter (2nd-order Butterworth biquad) ---
    // A single-pole HPF (6 dB/oct) barely touches wind noise, whose energy is
    // broadband and extends well above the cutoff. A 2nd-order section (12 dB/oct)
    // rejects low-frequency wind rumble far more aggressively while leaving the
    // speech band (~300-3400 Hz) essentially untouched.
    // Direct Form I state (per recording session, carried across buffers):
    private var hpfX1 = 0f
    private var hpfX2 = 0f
    private var hpfY1 = 0f
    private var hpfY2 = 0f

    // Cached biquad coefficients (normalized by a0) + the cutoff they were computed for.
    private var hpfB0 = 1f
    private var hpfB1 = 0f
    private var hpfB2 = 0f
    private var hpfA1 = 0f
    private var hpfA2 = 0f
    private var hpfCoeffCutoff = -1f

    // RBJ cookbook high-pass coefficients. Recomputed only when the cutoff changes
    // (trig runs at most once per buffer, i.e. ~16x/sec - negligible cost).
    private fun updateHpfCoefficients(cutoff: Float) {
        if (cutoff == hpfCoeffCutoff) return
        val w0 = 2.0 * Math.PI * cutoff / SAMPLE_RATE
        val cosw0 = Math.cos(w0)
        val sinw0 = Math.sin(w0)
        val q = 0.7071068 // Butterworth response (maximally flat passband)
        val alpha = sinw0 / (2.0 * q)
        val a0 = 1.0 + alpha
        hpfB0 = (((1.0 + cosw0) / 2.0) / a0).toFloat()
        hpfB1 = ((-(1.0 + cosw0)) / a0).toFloat()
        hpfB2 = (((1.0 + cosw0) / 2.0) / a0).toFloat()
        hpfA1 = ((-2.0 * cosw0) / a0).toFloat()
        hpfA2 = ((1.0 - alpha) / a0).toFloat()
        hpfCoeffCutoff = cutoff
    }

    private fun resetHpfState() {
        hpfX1 = 0f; hpfX2 = 0f; hpfY1 = 0f; hpfY2 = 0f
    }

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

    // --- Playback jitter buffer ---
    // Received audio is queued here and drained by a dedicated playback thread.
    // This decouples the network read thread from the (blocking) AudioTrack write,
    // and lets us drop the oldest audio when we fall behind so that end-to-end
    // latency stays bounded instead of growing without limit (the cause of the
    // "audio delay keeps increasing the longer you talk" bug).
    private val playbackQueue = ArrayDeque<ByteArray>()
    private val playbackLock = Object()
    private var queuedBytes = 0

    // Maximum audio allowed to sit in the jitter buffer before we start dropping the
    // oldest chunks. 2 bytes per frame (16-bit mono). ~200ms of headroom.
    private val maxBufferedBytes = SAMPLE_RATE * 2 * 200 / 1000
    private var playbackThread: Thread? = null

    @Volatile
    private var playbackRunning = false

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
            resetHpfState()

            while (audioRecord != null && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val readResult = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: -1
                if (readResult > 0) {
                    val nowTime = System.currentTimeMillis()
                    if (nowTime < tonePlayingUntilMillis) {
                        for (i in 0 until readResult) {
                            shortBuffer[i] = 0
                        }
                        // Clear filter memory so the muted gap doesn't leave a
                        // decaying tail / click when audio resumes.
                        resetHpfState()
                    }
                    // 1. High Pass Filter (HPF) to remove low-frequency wind rumble & engine hums.
                    //    2nd-order Butterworth (12 dB/oct) - far more effective against wind than a
                    //    single-pole filter, while preserving the speech band.
                    if (isHpfEnabled) {
                        updateHpfCoefficients(hpfCutoff)
                        val b0 = hpfB0
                        val b1 = hpfB1
                        val b2 = hpfB2
                        val a1 = hpfA1
                        val a2 = hpfA2
                        for (i in 0 until readResult) {
                            val x = shortBuffer[i].toFloat()
                            val y = b0 * x + b1 * hpfX1 + b2 * hpfX2 - a1 * hpfY1 - a2 * hpfY2
                            hpfX2 = hpfX1
                            hpfX1 = x
                            hpfY2 = hpfY1
                            hpfY1 = y
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
                startPlaybackThread()
                Log.d(TAG, "Playback started successfully")
            } else {
                Log.e(TAG, "AudioTrack state not initialized")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioTrack", e)
        }
    }

    // Called from the network read thread. Must never block: it only enqueues the
    // chunk and bounds the backlog so latency cannot grow without limit.
    fun playAudio(data: ByteArray) {
        if (!playbackRunning) return
        synchronized(playbackLock) {
            playbackQueue.addLast(data)
            queuedBytes += data.size
            // If we have fallen behind (slow consumer, clock drift, scheduling/GC
            // hiccups), drop the OLDEST audio so that what the user hears stays
            // close to real time instead of accumulating an ever-growing delay.
            var dropped = 0
            while (queuedBytes > maxBufferedBytes && playbackQueue.size > 1) {
                val removed = playbackQueue.removeFirst()
                queuedBytes -= removed.size
                dropped += removed.size
            }
            if (dropped > 0) {
                Log.w(
                    TAG,
                    "Playback backlog exceeded ${maxBufferedBytes / 32}ms; dropped ${dropped / 32}ms of stale audio."
                )
            }
            playbackLock.notifyAll()
        }
    }

    private fun startPlaybackThread() {
        playbackRunning = true
        val thread = Thread({ playbackLoop() }, "AudioPlayback").apply {
            priority = Thread.MAX_PRIORITY
        }
        playbackThread = thread
        thread.start()
    }

    // Dedicated consumer thread. The blocking AudioTrack.write() here provides the
    // playback pacing; while it is busy, the producer keeps the jitter buffer
    // bounded by dropping old chunks (see playAudio).
    private fun playbackLoop() {
        while (playbackRunning) {
            val chunk: ByteArray? = synchronized(playbackLock) {
                while (playbackRunning && playbackQueue.isEmpty()) {
                    try {
                        playbackLock.wait()
                    } catch (e: InterruptedException) {
                        return@synchronized null
                    }
                }
                if (!playbackRunning || playbackQueue.isEmpty()) {
                    null
                } else {
                    val c = playbackQueue.removeFirst()
                    queuedBytes -= c.size
                    c
                }
            }

            if (chunk != null) {
                writeChunk(chunk)
            }
        }
    }

    private fun writeChunk(data: ByteArray) {
        val track = audioTrack ?: return
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) return
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

    fun stopPlayback() {
        playbackRunning = false
        val thread = playbackThread
        playbackThread = null
        synchronized(playbackLock) {
            playbackQueue.clear()
            queuedBytes = 0
            playbackLock.notifyAll()
        }
        thread?.let {
            it.interrupt()
            try {
                it.join(500)
            } catch (e: InterruptedException) {
                // ignore
            }
        }
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
                    Log.d(
                        TAG,
                        "Communication device changed via listener: ${device?.let { getDeviceTypeName(it.type) } ?: "NONE"}")
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
                        Log.d(
                            TAG,
                            "Successfully set communication device to: ${getDeviceTypeName(targetDevice.type)} (Success: $success)"
                        )
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
