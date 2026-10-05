package com.sarmat.perevodchik

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Офлайн-голос (Supertonic-3): 31 язык, 10 голосов. */
class OfflineVoice(private val context: Context, private val threads: Int = 2) {

    companion object {
        val languages = setOf(
            "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr",
            "hi", "hr", "hu", "id", "it", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk",
            "sl", "sv", "tr", "uk", "vi"
        )
        const val VOICES = 10
    }

    private var tts: OfflineTts? = null
    private var track: AudioTrack? = null

    fun supports(code: String) = code in languages

    /** Загрузка модели в память (несколько секунд). Вызывать не в главном потоке. */
    @Synchronized
    fun load(): OfflineTts {
        tts?.let { return it }
        val p = VoicePack.TTS
        fun f(n: String) = p.file(context, n).absolutePath
        val t = OfflineTts(
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    supertonic = OfflineTtsSupertonicModelConfig(
                        durationPredictor = f("tts-duration_predictor.int8.onnx"),
                        textEncoder = f("tts-text_encoder.int8.onnx"),
                        vectorEstimator = f("tts-vector_estimator.int8.onnx"),
                        vocoder = f("tts-vocoder.int8.onnx"),
                        ttsJson = f("tts-tts.json"),
                        unicodeIndexer = f("tts-unicode_indexer.bin"),
                        voiceStyle = f("tts-voice.bin")
                    ),
                    numThreads = threads
                )
            )
        )
        tts = t
        return t
    }

    /** Синтезирует и проигрывает фразу. Вызывать не в главном потоке. */
    fun speak(text: String, lang: String, voice: Int, speed: Float, steps: Int = 5) {
        val engine = load()
        val audio = synchronized(this) {
            engine.generateWithConfig(
                text,
                GenerationConfig(
                    speed = speed,
                    sid = voice.coerceIn(0, VOICES - 1),
                    numSteps = steps,
                    extra = mapOf("lang" to lang)
                )
            )
        }
        val samples = audio.samples
        if (samples.isEmpty()) throw IllegalStateException("Пустой звук")
        // Нормализуем громкость — динамик часов маленький
        var peak = 0f
        for (s in samples) peak = max(peak, abs(s))
        if (peak > 0.01f) {
            val k = 0.97f / peak
            for (i in samples.indices) samples[i] *= k
        }
        play(samples, audio.sampleRate)
    }

    private fun play(samples: FloatArray, sampleRate: Int) {
        stop()
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(samples.size * 4)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        t.play()
        track = t
    }

    fun stop() {
        track?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        track = null
    }

    fun release() {
        stop()
        tts?.release()
        tts = null
    }
}

/** Офлайн-распознавание речи (Whisper base), ~99 языков. */
class OfflineEars(
    private val context: Context,
    val pack: VoicePack = VoicePack.ASR,
    private val threads: Int = 4
) {

    companion object {
        val languages = setOf(
            "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr", "pl", "ca", "nl", "ar",
            "sv", "it", "id", "hi", "fi", "vi", "he", "uk", "el", "ms", "cs", "ro", "da", "hu",
            "ta", "no", "th", "ur", "hr", "bg", "lt", "cy", "sk", "te", "fa", "lv", "bn", "sl",
            "kn", "et", "mk", "is", "sq", "sw", "gl", "mr", "af", "ka", "be", "gu", "ht", "mt",
            "tl"
        )
        const val RATE = 16000
    }

    private var recognizer: OfflineRecognizer? = null
    private var currentLang = ""

    fun supports(code: String) = code in languages

    private fun config(lang: String): OfflineRecognizerConfig {
        val p = pack
        val x = pack.asrPrefix
        return OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = p.file(context, "asr-$x-encoder.int8.onnx").absolutePath,
                    decoder = p.file(context, "asr-$x-decoder.int8.onnx").absolutePath,
                    language = lang,
                    task = "transcribe",
                    tailPaddings = 1000
                ),
                tokens = p.file(context, "asr-$x-tokens.txt").absolutePath,
                modelType = "whisper",
                numThreads = threads
            ),
            decodingMethod = "greedy_search"
        )
    }

    @Synchronized
    fun load(lang: String): OfflineRecognizer {
        val r = recognizer
        if (r == null) {
            val created = OfflineRecognizer(config = config(lang))
            recognizer = created
            currentLang = lang
            return created
        }
        if (currentLang != lang) {
            r.setConfig(config(lang))
            currentLang = lang
        }
        return r
    }

    /** Распознаёт записанный звук (16 кГц). Вызывать не в главном потоке. */
    @Synchronized
    fun recognize(samples: FloatArray, lang: String): String {
        val r = load(lang)
        val stream = r.createStream()
        try {
            stream.acceptWaveform(samples, RATE)
            r.decode(stream)
            return cleanup(r.getResult(stream).text)
        } finally {
            stream.release()
        }
    }

    /** Убираем типичные «галлюцинации» Whisper на тишине. */
    private fun cleanup(text: String): String {
        val t = text.trim()
        val junk = listOf(
            "Продолжение следует", "Субтитры", "Thank you for watching", "Thanks for watching",
            "Редактор субтитров", "Kiitos katsomisesta"
        )
        if (junk.any { t.startsWith(it, ignoreCase = true) }) return ""
        return removeRepeats(t)
    }

    /** Whisper иногда повторяет фразу дважды — оставляем один раз. */
    private fun removeRepeats(t: String): String {
        val parts = Regex("(?<=[.!?…])\\s+").split(t).map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        for (p in parts) {
            val key = p.lowercase().trimEnd('.', '!', '?', '…', ',')
            val dup = out.any { prev ->
                val k = prev.lowercase().trimEnd('.', '!', '?', '…', ',')
                k == key || (key.length >= 8 && k.startsWith(key))
            }
            if (!dup) out.add(p)
        }
        return out.joinToString(" ")
    }

    fun release() {
        recognizer?.release()
        recognizer = null
    }
}

/**
 * Запись с микрофона 16 кГц. Останавливается сама после паузы в речи,
 * по кнопке «Готово» или через 25 секунд.
 */
class VoiceRecorder {

    @Volatile
    var stopRequested = false

    @SuppressLint("MissingPermission")
    suspend fun record(onLevel: (Float) -> Unit): FloatArray {
        stopRequested = false
        val rate = OfflineEars.RATE
        val minBuf = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuf, rate)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Микрофон недоступен")
        }
        var out = FloatArray(rate * 10)
        var size = 0
        val chunk = ShortArray(rate / 10) // 100 мс
        var noise = 0.01f
        var chunks = 0
        var heardSpeech = false
        var silentChunks = 0
        rec.startRecording()
        try {
            while (coroutineContext.isActive && !stopRequested) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) {
                    val v = chunk[i] / 32768f
                    if (size == out.size) out = out.copyOf(out.size * 2)
                    out[size++] = v
                    sum += v * v
                }
                val rms = sqrt(sum / n).toFloat()
                chunks++
                if (chunks <= 3) noise = max(noise, rms) // первые 300 мс — фон
                val threshold = max(0.015f, noise * 2.5f)
                onLevel((rms / (threshold * 4)).coerceIn(0f, 1f))
                if (rms > threshold) {
                    heardSpeech = true
                    silentChunks = 0
                } else if (heardSpeech) {
                    silentChunks++
                }
                if (heardSpeech && silentChunks >= 15) break       // 1,5 с тишины после речи
                if (!heardSpeech && chunks >= 80) break             // 8 с без речи
                if (chunks >= 250) break                             // максимум 25 с
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        if (!heardSpeech && !stopRequested) return FloatArray(0)
        return out.copyOf(size)
    }
}
