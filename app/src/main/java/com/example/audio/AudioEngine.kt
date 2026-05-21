package com.example.audio

import android.annotation.SuppressLint
import android.media.ToneGenerator
import android.media.AudioFormat
import android.media.AudioManager
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

    fun setSpeakerphoneOn(on: Boolean) {
        _isSpeakerphoneOn.value = on
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audioManager.availableCommunicationDevices
                val speakerDevice = devices.find { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                val earpieceDevice = devices.find { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                if (on) {
                    if (speakerDevice != null) {
                        audioManager.setCommunicationDevice(speakerDevice)
                        Log.d(TAG, "Successfully set communication device to SPEAKER")
                    } else {
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = true
                    }
                } else {
                    if (earpieceDevice != null) {
                        audioManager.setCommunicationDevice(earpieceDevice)
                        Log.d(TAG, "Successfully set communication device to EARPIECE")
                    } else {
                        audioManager.clearCommunicationDevice()
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = false
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = on
            }
            Log.d(TAG, "Speakerphone set to $on")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle speakerphone", e)
        }
    }

    private fun startCommunicationMode() {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            setSpeakerphoneOn(_isSpeakerphoneOn.value)
            Log.d(TAG, "Communication mode started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start communication mode", e)
        }
    }

    private fun stopCommunicationMode() {
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
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
            initToneGenerator()
            toneGen?.startTone(ToneGenerator.TONE_PROP_PROMPT, 100)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing TX start tone", e)
        }
    }

    fun playTxEndTone() {
        if (!isAcousticCuesEnabled) return
        try {
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
