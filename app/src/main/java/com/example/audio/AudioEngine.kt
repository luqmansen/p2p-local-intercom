package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

class AudioEngine {
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

    // State flows for UI observing
    private val _realtimeAmplitude = MutableStateFlow(0f)
    val realtimeAmplitude: StateFlow<Float> = _realtimeAmplitude

    private val _isTransmitting = MutableStateFlow(false)
    val isTransmitting: StateFlow<Boolean> = _isTransmitting

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

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        // Ensure a buffer of about ~40ms to keep latency low but reading robust
        val desiredBufferSize = max(bufferSize, 2048)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
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

            while (audioRecord != null && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val readResult = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: -1
                if (readResult > 0) {
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

                    _isTransmitting.value = voiceActive

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
    }

    // --- Audio Playback ---
    fun startPlayback() {
        if (audioTrack != null) return

        val minPlayBufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        val desiredBufferSize = max(minPlayBufSize, 4096)

        try {
            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
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
                track.write(data, 0, data.size)
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
}
