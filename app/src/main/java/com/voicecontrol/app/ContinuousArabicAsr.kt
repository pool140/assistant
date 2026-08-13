package com.voicecontrol.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Keeps one AudioRecord open for the entire assistant session.
 * Speech is segmented locally with a lightweight energy VAD and each segment
 * is decoded by the Arabic Moonshine-v2 model through sherpa-onnx.
 *
 * The microphone is NOT reopened between commands. Only the ASR decoding of
 * completed speech segments is separate from the capture stream.
 */
class ContinuousArabicAsr(
    private val context: Context,
    private val onText: (String) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val PRE_ROLL_MS = 280
        private const val MIN_SPEECH_MS = 220
        private const val TRAILING_SILENCE_MS = 720
        private const val MAX_SPEECH_MS = 8_000
        private const val MIN_SAMPLES = SAMPLE_RATE * MIN_SPEECH_MS / 1000
        private const val TRAILING_SILENCE_SAMPLES = SAMPLE_RATE * TRAILING_SILENCE_MS / 1000
        private const val MAX_SAMPLES = SAMPLE_RATE * MAX_SPEECH_MS / 1000
        private const val PRE_ROLL_SAMPLES = SAMPLE_RATE * PRE_ROLL_MS / 1000
    }

    private val running = AtomicBoolean(false)
    private val decoderExecutor = Executors.newSingleThreadExecutor()
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var recognizer: OfflineRecognizer? = null
    @Volatile private var ignoreUntilElapsed = 0L

    fun start(): Boolean {
        if (running.get()) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onStatus("الميكروفون غير مسموح")
            return false
        }

        try {
            recognizer = createRecognizer()
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            if (minBuffer <= 0) throw IllegalStateException("AudioRecord buffer unavailable")
            val bufferBytes = maxOf(minBuffer, SAMPLE_RATE / 2)
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufferBytes,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                throw IllegalStateException("AudioRecord initialization failed")
            }

            audioRecord = record
            running.set(true)
            record.startRecording()
            onStatus("المساعد يستمع باستمرار")

            captureThread = Thread({ captureLoop(bufferBytes / 2) }, "voice-capture").also {
                it.isDaemon = true
                it.start()
            }
            return true
        } catch (t: Throwable) {
            stop()
            onStatus("فشل تشغيل الميكروفون")
            return false
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
        captureThread?.interrupt()
        captureThread = null
        recognizer?.release()
        recognizer = null
    }

    fun close() {
        stop()
        decoderExecutor.shutdownNow()
    }

    /** Temporarily ignore decoded commands while TTS speaks, without closing the mic. */
    fun muteFor(ms: Long) {
        ignoreUntilElapsed = maxOf(ignoreUntilElapsed, SystemClock.elapsedRealtime() + ms)
    }

    private fun captureLoop(readSamples: Int) {
        val readBuffer = ShortArray(readSamples)
        val preRoll = ArrayDeque<Short>(PRE_ROLL_SAMPLES)
        val speech = ArrayList<Short>(MAX_SAMPLES)
        var inSpeech = false
        var silenceSamples = 0
        var speechSamples = 0
        var noiseFloor = 0.008f

        while (running.get() && !Thread.currentThread().isInterrupted) {
            val read = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: break
            if (read <= 0) continue

            var sumSq = 0.0
            for (i in 0 until read) {
                val v = readBuffer[i] / 32768.0
                sumSq += v * v
            }
            val rms = sqrt(sumSq / read).toFloat()
            val threshold = maxOf(0.018f, noiseFloor * 2.8f)

            if (!inSpeech) {
                if (rms < threshold) {
                    noiseFloor = noiseFloor * 0.95f + rms * 0.05f
                    for (i in 0 until read) {
                        if (preRoll.size >= PRE_ROLL_SAMPLES) preRoll.removeFirst()
                        preRoll.addLast(readBuffer[i])
                    }
                } else {
                    inSpeech = true
                    speech.clear()
                    speech.addAll(preRoll)
                    preRoll.clear()
                    for (i in 0 until read) speech.add(readBuffer[i])
                    speechSamples = speech.size
                    silenceSamples = 0
                }
            } else {
                for (i in 0 until read) speech.add(readBuffer[i])
                speechSamples += read
                if (rms < threshold) silenceSamples += read else silenceSamples = 0

                if (silenceSamples >= TRAILING_SILENCE_SAMPLES || speechSamples >= MAX_SAMPLES) {
                    if (speechSamples >= MIN_SAMPLES && SystemClock.elapsedRealtime() >= ignoreUntilElapsed) {
                        val segment = FloatArray(speech.size) { i -> speech[i] / 32768.0f }
                        decoderExecutor.execute { decodeSegment(segment) }
                    }
                    inSpeech = false
                    silenceSamples = 0
                    speechSamples = 0
                    speech.clear()
                }
            }
        }
    }

    private fun createRecognizer(): OfflineRecognizer {
        val modelDir = "moonshine-ar"
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = 80,
            ),
            modelConfig = OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    encoder = "$modelDir/encoder_model.ort",
                    mergedDecoder = "$modelDir/decoder_model_merged.ort",
                ),
                tokens = "$modelDir/tokens.txt",
                numThreads = 2,
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        )
        return OfflineRecognizer(context.assets, config)
    }

    private fun decodeSegment(samples: FloatArray) {
        if (!running.get()) return
        try {
            val r = recognizer ?: return
            val stream = r.createStream()
            stream.use {
                it.acceptWaveform(samples, SAMPLE_RATE)
                r.decode(it)
                val text = r.getResult(it).text.trim()
                if (text.isNotBlank() && SystemClock.elapsedRealtime() >= ignoreUntilElapsed) {
                    onText(text)
                }
            }
        } catch (_: Throwable) {
            // Keep the capture stream alive even if one utterance fails to decode.
        }
    }
}
