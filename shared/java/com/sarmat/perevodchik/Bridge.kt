package com.sarmat.perevodchik

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Связь часов и телефона через Bluetooth (Wear Data Layer), без интернета.
 * Часы открывают канал AUDIO_PATH и шлют: строку-заголовок JSON + "\n" + звук PCM16 16 кГц.
 * Телефон отвечает сообщением RESULT_PATH с JSON {id, text, translation, error}.
 */
object Bridge {
    const val CAPABILITY_PHONE = "perevodchik_phone"
    const val AUDIO_PATH = "/perevodchik/audio"
    const val RESULT_PATH = "/perevodchik/result"

    fun floatsToPcm16(samples: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            val v = (s.coerceIn(-1f, 1f) * 32767f).toInt()
            buf.putShort(v.toShort())
        }
        return buf.array()
    }

    fun pcm16ToFloats(bytes: ByteArray, offset: Int = 0): FloatArray {
        val n = (bytes.size - offset) / 2
        val buf = ByteBuffer.wrap(bytes, offset, n * 2).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(n) { buf.short / 32768f }
    }
}

/** Офлайн-перевод текста (ML Kit). Кэширует переводчики для пар языков. */
object TextTranslator {
    private val cache = HashMap<String, Translator>()

    @Synchronized
    private fun client(src: String, tgt: String): Translator =
        cache.getOrPut("$src>$tgt") {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(tgt)
                    .build()
            )
        }

    suspend fun translate(text: String, src: String, tgt: String): String {
        if (src == tgt) return text
        val t = client(src, tgt)
        t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return t.translate(text).await()
    }
}
