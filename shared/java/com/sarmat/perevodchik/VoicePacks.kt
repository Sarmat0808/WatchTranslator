package com.sarmat.perevodchik

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/** Офлайн-пакет: набор файлов, которые скачиваются из релиза "models" на GitHub. */
enum class VoicePack(
    val title: String,
    val dirName: String,
    val files: List<String>,
    val approxMb: Int,
    /** Для пакетов распознавания: base / small / turbo */
    val asrPrefix: String = ""
) {
    TTS(
        "Офлайн голос",
        "tts",
        listOf(
            "tts-duration_predictor.int8.onnx",
            "tts-text_encoder.int8.onnx",
            "tts-vector_estimator.int8.onnx",
            "tts-vocoder.int8.onnx",
            "tts-tts.json",
            "tts-unicode_indexer.bin",
            "tts-voice.bin"
        ),
        145
    ),
    ASR(
        "Офлайн микрофон",
        "asr",
        listOf(
            "asr-base-encoder.int8.onnx",
            "asr-base-decoder.int8.onnx",
            "asr-base-tokens.txt"
        ),
        160,
        "base"
    ),
    ASR_SMALL(
        "Точный микрофон",
        "asr-small",
        listOf(
            "asr-small-encoder.int8.onnx",
            "asr-small-decoder.int8.onnx",
            "asr-small-tokens.txt"
        ),
        358,
        "small"
    ),
    ASR_TURBO(
        "Максимальный микрофон",
        "asr-turbo",
        listOf(
            "asr-turbo-encoder.int8.onnx",
            "asr-turbo-decoder.int8.onnx",
            "asr-turbo-tokens.txt"
        ),
        989,
        "turbo"
    ),
    /**
     * NVIDIA Parakeet v3: 25 европейских языков (финский, русский, болгарский…).
     * Работает в разы быстрее Whisper: обрабатывает только реальную длину фразы,
     * а не всегда окно в 30 секунд. Только для телефона (для часов слишком большой).
     */
    ASR_FAST(
        "Быстрый микрофон",
        "asr-fast",
        listOf(
            "asr-pk-encoder.int8.onnx",
            "asr-pk-decoder.int8.onnx",
            "asr-pk-joiner.int8.onnx",
            "asr-pk-tokens.txt"
        ),
        670,
        "pk"
    );

    /** Parakeet (transducer) — другая модель, чем Whisper. */
    val isParakeet get() = asrPrefix == "pk"

    val isAsr get() = asrPrefix.isNotEmpty()

    fun dir(context: Context) = File(context.filesDir, dirName)
    fun file(context: Context, name: String) = File(dir(context), name)
    fun isInstalled(context: Context) = File(dir(context), "ready").exists()

    fun delete(context: Context) {
        dir(context).deleteRecursively()
    }

    companion object {
        const val BASE_URL =
            "https://github.com/Sarmat0808/WatchTranslator/releases/download/models/"
    }
}

object VoicePackDownloader {

    /**
     * Скачивает пакет. onProgress получает (скачано байт, примерно всего байт).
     * На часах Wi‑Fi часто выключен, пока есть Bluetooth с телефоном, поэтому
     * сначала просим систему включить Wi‑Fi для быстрой загрузки.
     */
    suspend fun download(
        context: Context,
        pack: VoicePack,
        onProgress: (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        var callback: ConnectivityManager.NetworkCallback? = null
        val wifi: Network? = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) cont.resume(network)
                    }
                }
                callback = cb
                try {
                    cm.requestNetwork(
                        NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .build(),
                        cb
                    )
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

        try {
            val dir = pack.dir(context)
            File(dir, "ready").delete()
            dir.mkdirs()
            val total = pack.approxMb * 1_048_576L
            var done = 0L
            for (name in pack.files) {
                val target = File(dir, name)
                val part = File(dir, "$name.part")
                val url = URL(VoicePack.BASE_URL + name)
                val conn = (wifi?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                conn.instanceFollowRedirects = true
                val finalConn = followRedirects(conn, wifi)
                if (finalConn.responseCode != 200) {
                    throw IllegalStateException("HTTP ${finalConn.responseCode} для $name")
                }
                finalConn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress(done, maxOf(total, done))
                        }
                    }
                }
                finalConn.disconnect()
                if (!part.renameTo(target)) throw IllegalStateException("Не удалось сохранить $name")
            }
            File(dir, "ready").writeText("ok")
        } finally {
            callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        }
    }

    /** GitHub перенаправляет на другой домен — обрабатываем вручную, чтобы остаться на Wi‑Fi. */
    private fun followRedirects(start: HttpURLConnection, wifi: Network?): HttpURLConnection {
        var conn = start
        repeat(5) {
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: return conn
                conn.disconnect()
                val next = URL(URL(conn.url, loc).toString())
                conn = (wifi?.openConnection(next) ?: next.openConnection()) as HttpURLConnection
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
            } else {
                return conn
            }
        }
        return conn
    }
}
