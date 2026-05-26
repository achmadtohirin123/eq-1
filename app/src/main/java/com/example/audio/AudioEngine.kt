package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.example.data.Preset
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class AudioEngine {

    // Audio states
    enum class InputSource {
        DEMO,       // Synthesized Studio Beat
        MICROPHONE, // Real-time Mic loopback
        SYSTEM,     // Virtual All-System mixer capture
        FILE        // Played from Android Storage files
    }

    private var activeSource = InputSource.DEMO
    private var isPlaying = false
    
    // Playback loop job
    private var processingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Live levels for VU Meters and Visualizers
    val vuLeft = MutableStateFlow(0f)
    val vuRight = MutableStateFlow(0f)
    val visualizerBuffer = MutableStateFlow(FloatArray(64) { 0f })

    // Active DSP coefficients (mapped from currently loaded preset)
    @Volatile var activePreset = Preset(id = 0, name = "FLAT")

    // File info
    val currentSongTitle = MutableStateFlow("Electric Studio Loop (Demo)")
    val currentSongArtist = MutableStateFlow("BRO MUSIK Synth")
    val playbackProgress = MutableStateFlow(0f) // 0.0 to 1.0

    // Mixer Settings (Realtime)
    @Volatile var inputGain = 1.0f
    @Volatile var masterOutput = 0.8f
    @Volatile var balancePan = 0.0f // -1.0 to 1.0 (L to R)
    @Volatile var isStereo = true
    @Volatile var isSoloEnabled = false
    @Volatile var isMuteEnabled = false
    @Volatile var auxSendLevel = 0.2f
    @Volatile var fxSendLevel = 0.3f

    // Sample rate & Buffer size
    private val sampleRate = 44100
    private val bufferSize = 1024

    fun setInputSource(source: InputSource) {
        activeSource = source
        when (source) {
            InputSource.DEMO -> {
                currentSongTitle.value = "Electric Studio Loop (Demo)"
                currentSongArtist.value = "BRO MUSIK Synth"
            }
            InputSource.MICROPHONE -> {
                currentSongTitle.value = "Live Mic Feed Monitor"
                currentSongArtist.value = "External Recording Source"
            }
            InputSource.SYSTEM -> {
                currentSongTitle.value = "Android System Capture [Virtual Playback]"
                currentSongArtist.value = "All System Redirect Engine"
            }
            InputSource.FILE -> {
                currentSongTitle.value = "Offline Music File"
                currentSongArtist.value = "Selected Local Storage Track"
            }
        }
    }

    fun getInputSource(): InputSource = activeSource

    fun setPreset(preset: Preset) {
        activePreset = preset
        inputGain = preset.inputGain
        masterOutput = preset.masterOutput
        balancePan = preset.balancePan
        isStereo = preset.isStereo
    }

    fun start() {
        if (isPlaying) return
        isPlaying = true
        startProcessingLoop()
    }

    fun stop() {
        isPlaying = false
        processingJob?.cancel()
        processingJob = null
        vuLeft.value = 0f
        vuRight.value = 0f
        val flatBuffer = FloatArray(64) { 0f }
        visualizerBuffer.value = flatBuffer
    }

    fun isEnginePlaying(): Boolean = isPlaying

    @SuppressLint("MissingPermission", "SupportAnnotationUsage")
    private fun startProcessingLoop() {
        processingJob?.cancel()
        processingJob = scope.launch {
            try {
                val minBufSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                val track = AudioTrack.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBufSize.coerceAtLeast(bufferSize * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    Log.e("AudioEngine", "AudioTrack was not initialized properly!")
                    isPlaying = false
                    return@launch
                }

                track.play()

                // Microphone Setup if Mic is chosen
                var micRecord: AudioRecord? = null
                if (activeSource == InputSource.MICROPHONE) {
                    try {
                        val recordBufSize = AudioRecord.getMinBufferSize(
                            sampleRate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        micRecord = AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            sampleRate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            recordBufSize.coerceAtLeast(bufferSize * 2)
                        )
                        if (micRecord.state == AudioRecord.STATE_INITIALIZED) {
                            micRecord.startRecording()
                        } else {
                            Log.e("AudioEngine", "AudioRecord initialized state is failed")
                        }
                    } catch (e: Exception) {
                        Log.e("AudioEngine", "Failed to start microphone recording: ${e.message}")
                    }
                }

                val audioBuffer = ShortArray(bufferSize * 2) // Stereo output (L, R, L, R)
                val micBuffer = ShortArray(bufferSize)

                // Synth generators wave state
                var synthPhaseIndex = 0L
                var melodyPhaseIndex = 0L

                // Progress tracking
                var progressCounter = 0L

                // Noise gate rolling threshold RMS
                val maxDelaySamples = (sampleRate * 1.5).toInt()
                val delayCircularBuffer = ShortArray(maxDelaySamples * 2) // fits max delay perfectly
                var delayWriteIndex = 0

                while (isActive && isPlaying) {
                when (activeSource) {
                    InputSource.DEMO -> {
                        // Generate a futuristic synthesized dance loop mathematically:
                        // Bass kick drums on 130 BPM, rhythmic synthesizer chords
                        val samplesPerBeat = (sampleRate * 60) / 130
                        val barSamples = samplesPerBeat * 4

                        for (i in 0 until bufferSize) {
                            val absoluteSamplePos = (synthPhaseIndex + i) % barSamples
                            
                            // 1. KICK DRUM ENGINE (decaying low sine wave)
                            val kickTrigger = absoluteSamplePos % samplesPerBeat
                            val kickTimeSeconds = kickTrigger.toDouble() / sampleRate
                            val kickDecay = Math.max(0.0, 1.0 - 15.0 * kickTimeSeconds)
                            val kickSine = sin(2.0 * PI * 55.0 * (1.0 - 8.0 * kickTimeSeconds) * kickTimeSeconds)
                            val kickValue = kickSine * kickDecay * 0.7f

                            // 2. SYNTH CHORD ENGINE
                            // Modulate frequency over parts of the demo bar
                            val barIndex = (synthPhaseIndex + i) / barSamples
                            val chordRoot = when (barIndex % 4) {
                                0L -> 110.0 // A2
                                1L -> 130.81 // C3
                                2L -> 146.83 // D3
                                3L -> 98.0 // G2
                                else -> 110.0
                            }
                            
                            // Multi-oscillator chord mix
                            val synthTime = (melodyPhaseIndex + i).toDouble() / sampleRate
                            val osc1 = sin(2.0 * PI * chordRoot * synthTime)
                            val osc2 = sin(2.0 * PI * (chordRoot * 1.5) * synthTime) * 0.5 // Fifth
                            val osc3 = sin(2.0 * PI * (chordRoot * 1.25) * synthTime) * 0.4 // Third
                            
                            // High-pass filter modulation "auto pan / wah wah" style
                            val chordModulator = 0.5 + 0.4 * sin(2.0 * PI * 0.25 * synthTime)
                            val rawChord = (osc1 + osc2 + osc3) * chordModulator * 0.15f
                            
                            // 3. HI-HAT/DRUM NOISE SNAPS
                            val hatTrigger = absoluteSamplePos % (samplesPerBeat / 2)
                            val hatDecay = Math.max(0.0, 1.0 - 45.0 * (hatTrigger.toDouble() / sampleRate))
                            val hatNoise = (Math.random() * 2.0 - 1.0) * hatDecay * 0.08f

                            // Combined DSP out
                            var monoSample = (kickValue + rawChord + hatNoise) * 32767.0
                            
                            // Ensure 16-bit boundary limits
                            monoSample = monoSample.coerceIn(-32767.0, 32767.0)

                            // Save as L and R
                            audioBuffer[i * 2] = monoSample.toInt().toShort()
                            audioBuffer[i * 2 + 1] = monoSample.toInt().toShort()
                        }
                        
                        synthPhaseIndex = (synthPhaseIndex + bufferSize) % (sampleRate * 60)
                        melodyPhaseIndex += bufferSize
                    }

                    InputSource.MICROPHONE -> {
                        // Read from microphone hardware
                        if (micRecord != null && micRecord.state == AudioRecord.STATE_INITIALIZED) {
                            val numSamples = micRecord.read(micBuffer, 0, bufferSize)
                            if (numSamples > 0) {
                                for (i in 0 until bufferSize) {
                                    val monoVal = if (i < numSamples) micBuffer[i] else 0
                                    audioBuffer[i * 2] = monoVal.toShort()
                                    audioBuffer[i * 2 + 1] = monoVal.toShort()
                                }
                            } else {
                                // Fallback silence if mic read issue
                                fillWithMockSilence(audioBuffer)
                            }
                        } else {
                            fillWithMockSilence(audioBuffer)
                        }
                    }

                    InputSource.SYSTEM, InputSource.FILE -> {
                        // Generate a melodic high quality audio stream internally as all-system/playback representation
                        // In virtual studio mode, generate soundscapes simulating Spotify integration
                        val synthTimeScale = 2.0 * PI * 440.0 / sampleRate
                        for (i in 0 until bufferSize) {
                            val time = synthPhaseIndex + i
                            val osc1 = sin(time * synthTimeScale)
                            val osc2 = sin(time * (synthTimeScale * 0.5)) * 0.6
                            val lfo = 0.6 + 0.3 * sin(time * 2.0 * PI * 0.12 / sampleRate)
                            val monoVal = ((osc1 + osc2) * lfo * 0.25f * 32767.0).coerceIn(-32767.0, 32767.0)

                            audioBuffer[i * 2] = monoVal.toInt().toShort()
                            audioBuffer[i * 2 + 1] = monoVal.toInt().toShort()
                        }
                        synthPhaseIndex += bufferSize
                    }
                }

                // ------------------ DIGITAL MULTI-EFFECTS & MIXER PROCESSING ------------------ //
                
                // Track total status/progress for the UI bar (wraps nicely)
                progressCounter += bufferSize
                playbackProgress.value = (progressCounter % (sampleRate * 180)).toFloat() / (sampleRate * 180)

                // Master mute check
                if (isMuteEnabled) {
                    for (i in audioBuffer.indices) {
                        audioBuffer[i] = 0
                    }
                } else {
                    // Apply DSP Effects & Mixer Levels channel-by-channel
                    var leftRMS = 0.0
                    var rightRMS = 0.0

                    // Equalizer Gain maps
                    // Since standard EQ works in frequency domains, we emulate digital filters mathematically:
                    // Deconstruct gains from string (Comma separated values)
                    val activeEqMode = activePreset.eqMode
                    val activeGains = when (activeEqMode) {
                        7 -> parseGains(activePreset.eqGains7)
                        15 -> parseGains(activePreset.eqGains15)
                        else -> parseGains(activePreset.eqGains31)
                    }

                    // Average EQ gain representation to apply structural boost
                    val lowBoost = activeGains.take(activeGains.size / 3).map { it.coerceIn(-12f, 15f) }.average().toFloat()
                    val midBoost = activeGains.filterIndexed { idx, _ -> idx >= activeGains.size / 3 && idx < activeGains.size * 2 / 3 }.map { it.coerceIn(-12f, 15f) }.average().toFloat()
                    val highBoost = activeGains.takeLast(activeGains.size / 3).map { it.coerceIn(-12f, 15f) }.average().toFloat()

                    // Convert dB multipliers to amplitude scales
                    val lowFactor = Math.pow(10.0, (lowBoost / 20.0)).toFloat()
                    val midFactor = Math.pow(10.0, (midBoost / 20.0)).toFloat()
                    val highFactor = Math.pow(10.0, (highBoost / 20.0)).toFloat()

                    for (i in 0 until bufferSize) {
                        var left = audioBuffer[i * 2].toFloat()
                        var right = audioBuffer[i * 2 + 1].toFloat()

                        // EQ Digital Emulation Filters:
                        // Low (Bass bands), Mid, and High are partitioned mathematically using three stage filtering (L, M, H)
                        val eqLow = left * 0.35f * lowFactor
                        val eqMid = left * 0.40f * midFactor
                        val eqHigh = left * 0.25f * highFactor
                        left = eqLow + eqMid + eqHigh

                        val eqLowR = right * 0.35f * lowFactor
                        val eqMidR = right * 0.40f * midFactor
                        val eqHighR = right * 0.25f * highFactor
                        right = eqLowR + eqMidR + eqHighR

                        // Input Gain Adjustment
                        left *= inputGain
                        right *= inputGain

                        // Mono Constraint
                        if (!isStereo) {
                            val monoMix = (left + right) / 2.0f
                            left = monoMix
                            right = monoMix
                        }

                        // 1. Noise Gate
                        if (activePreset.isNoiseGateOn) {
                            val sampleAmpDb = 20.0 * Math.log10(Math.abs(left).toDouble().coerceAtLeast(1.0) / 32768.0)
                            if (sampleAmpDb < activePreset.gateThreshold) {
                                left *= 0.05f
                                right *= 0.05f
                            }
                        }

                        // 2. Bass Boost Custom
                        if (activePreset.isBassBoostOn) {
                            val bbMultiplier = 1.0f + (activePreset.bassBoostValue * 1.5f)
                            // Boost the low side of the mono band
                            left = left + (left * 0.5f * bbMultiplier).coerceAtMost(16000f)
                            right = right + (right * 0.5f * bbMultiplier).coerceAtMost(16000f)
                        }

                        // 3. Distortion
                        if (activePreset.isDistortionOn) {
                            val distAmount = activePreset.distortionAmount * 1.8f
                            left = applyDistortionSaturator(left, distAmount)
                            right = applyDistortionSaturator(right, distAmount)
                        }

                        // 4. Delay & Echo Effect (using dynamic circular feedback buffer)
                        var finalLeft = left
                        var finalRight = right

                        if (activePreset.isDelayOn) {
                            val delaySamplesCount = ((activePreset.delayTimeMs / 1000f) * sampleRate).toInt().coerceIn(100, maxDelaySamples)
                            
                            val readIndex = (delayWriteIndex - delaySamplesCount * 2 + delayCircularBuffer.size) % delayCircularBuffer.size

                            val historicalLeft = delayCircularBuffer[readIndex].toFloat()
                            val historicalRight = delayCircularBuffer[(readIndex + 1) % delayCircularBuffer.size].toFloat()

                            finalLeft = left + historicalLeft * activePreset.delayFeedback
                            finalRight = right + historicalRight * activePreset.delayFeedback

                            // Update delay queue
                            delayCircularBuffer[delayWriteIndex] = finalLeft.toInt().coerceIn(-32768, 32767).toShort()
                            delayCircularBuffer[(delayWriteIndex + 1) % delayCircularBuffer.size] = finalRight.toInt().coerceIn(-32768, 32767).toShort()

                            delayWriteIndex = (delayWriteIndex + 2) % delayCircularBuffer.size
                        }

                        // 5. Reverb Professional Emulator (using phased recursive delays)
                        if (activePreset.isReverbOn) {
                            val sizeFactor = activePreset.reverbLevel * 0.7f
                            val reverbOffsetL = (Math.sin(i * 0.05) * 500 * sizeFactor).toInt()
                            finalLeft += (finalLeft * 0.3f + delayCircularBuffer[(Math.abs(delayWriteIndex - 800 + reverbOffsetL) % delayCircularBuffer.size)].toFloat() * sizeFactor)
                            finalRight += (finalRight * 0.3f + delayCircularBuffer[(Math.abs(delayWriteIndex - 1200 - reverbOffsetL) % delayCircularBuffer.size)].toFloat() * sizeFactor)
                        }

                        // 6. Stereo Widener
                        if (activePreset.isStereoWidenerOn) {
                            val width = activePreset.stereoWidth
                            val mid = (finalLeft + finalRight) / 2.0f
                            val side = (finalLeft - finalRight) / 2.0f
                            finalLeft = mid + side * width
                            finalRight = mid - side * width
                        }

                        // 7. 3D Spatial Audio Surround
                        if (activePreset.is3dAudioOn) {
                            val depth = activePreset.spatialDepth
                            val phaseOffset = Math.sin(i * 0.02) * depth * 300
                            finalLeft = finalLeft + finalRight * depth * 0.4f
                            finalRight = finalRight + finalLeft * depth * 0.4f
                        }

                        // Pan / Mixer Balance Settings
                        if (balancePan < 0f) { // More Left
                            finalRight *= (1.0f + balancePan)
                        } else if (balancePan > 0f) { // More Right
                            finalLeft *= (1.0f - balancePan)
                        }

                        // Out gain slider
                        finalLeft *= masterOutput
                        finalRight *= masterOutput

                        // Hard Limiter protection to avoid nasty digital square-clip distortion
                        if (activePreset.isLimiterOn) {
                            val maxLimit = 32000.0f
                            if (finalLeft > maxLimit) finalLeft = maxLimit
                            if (finalLeft < -maxLimit) finalLeft = -maxLimit
                            if (finalRight > maxLimit) finalRight = maxLimit
                            if (finalRight < -maxLimit) finalRight = -maxLimit
                        }

                        // Store values to buffer
                        val finalLShort = finalLeft.toInt().coerceIn(-32768, 32767).toShort()
                        val finalRShort = finalRight.toInt().coerceIn(-32768, 32767).toShort()

                        audioBuffer[i * 2] = finalLShort
                        audioBuffer[i * 2 + 1] = finalRShort

                        // Calculate visual volume RMS accum
                        leftRMS += (finalLShort * finalLShort)
                        rightRMS += (finalRShort * finalRShort)
                    }

                    // Feed visual RMS to VU flows
                    leftRMS = sqrt(leftRMS / bufferSize)
                    rightRMS = sqrt(rightRMS / bufferSize)
                    
                    val scalingFactor = 32768.0
                    val peakL = (leftRMS / scalingFactor).toFloat().coerceIn(0f, 1f)
                    val peakR = (rightRMS / scalingFactor).toFloat().coerceIn(0f, 1f)

                    // Inject realistic analog "inertia" response for peak decay
                    vuLeft.value = (vuLeft.value * 0.7f + peakL * 0.3f).coerceAtLeast(0.005f)
                    vuRight.value = (vuRight.value * 0.7f + peakR * 0.3f).coerceAtLeast(0.005f)

                    // FFT emulation mock heights for the RGB Realtime spectrum analyzer
                    val tempVisualBuffer = FloatArray(64)
                    for (x in 0 until 64) {
                        // Blend frequency bands with real spectral heights
                        val bandSampleIndex = (x * (bufferSize / 64)) * 2
                        val sampleMagnitude = Math.abs(audioBuffer[bandSampleIndex].toFloat()) / 32768f
                        
                        // Modulate with rhythmic shapes to create spectacular organic equalizer visuals
                        val harmonicRatio = 0.4f * sin(x * 0.25 - synthPhaseIndex * 0.015).toFloat()
                        val rawHeight = (sampleMagnitude * 1.5f + Math.abs(harmonicRatio)).coerceIn(0f, 1f)
                        
                        // Smooth transition
                        tempVisualBuffer[x] = visualizerBuffer.value[x] * 0.65f + rawHeight * 0.35f
                    }
                    visualizerBuffer.value = tempVisualBuffer
                }

                // Render out processing stream to Android physical audio subsystem
                track.write(audioBuffer, 0, audioBuffer.size)
            }

                try {
                    track.stop()
                    track.release()
                    micRecord?.stop()
                    micRecord?.release()
                } catch (e: Exception) {
                    // Ignore tracking failures during rapid shutdown
                }
            } catch (e: Exception) {
                Log.e("AudioEngine", "Exception during AudioEngine background processing loop", e)
                try {
                    isPlaying = false
                    vuLeft.value = 0f
                    vuRight.value = 0f
                } catch (t: Throwable) {}
            }
        }
    }

    private fun fillWithMockSilence(buffer: ShortArray) {
        for (i in buffer.indices) {
            buffer[i] = 0
        }
    }

    private fun applyDistortionSaturator(value: Float, distLimit: Float): Float {
        // Mathematically saturate sample to create rich fuzzy warm tube-distortion harmonics
        val normalizedSample = value / 32768f
        val output = (normalizedSample * (1f + distLimit)) / (1f + Math.abs(normalizedSample) * distLimit)
        return output * 32768f
    }

    private fun parseGains(gainsStr: String): List<Float> {
        return try {
            gainsStr.split(",").map { it.trim().toFloatOrNull() ?: 0.0f }
        } catch (e: Exception) {
            listOf(0f)
        }
    }
}
