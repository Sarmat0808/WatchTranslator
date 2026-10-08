package com.sarmat.perevodchik.phone

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.sarmat.perevodchik.VoicePack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * «Умный переводчик»: нейросеть Gemma 4 E2B прямо на телефоне (LiteRT-LM, видеоядро).
 * Переводит напрямую FI↔RU (без английского посредника), понимает разговорную речь
 * и вежливую форму. Без интернета.
 */
object LlmTranslator {
    private val lock = Any()
    private var engine: Engine? = null

    /** На чём работает модель: GPU (быстро) или CPU (запасной вариант). */
    @Volatile
    var backend = ""
        private set

    fun isInstalled(ctx: Context) = VoicePack.LLM.isInstalled(ctx)

    private fun modelPath(ctx: Context) =
        VoicePack.LLM.file(ctx, VoicePack.LLM_FILE).absolutePath

    private fun load(ctx: Context): Engine {
        engine?.let { return it }
        val path = modelPath(ctx)
        val cache = ctx.cacheDir.path
        val e = try {
            Engine(EngineConfig(modelPath = path, backend = Backend.GPU(), cacheDir = cache))
                .also { it.initialize() }
                .also { backend = "GPU" }
        } catch (t: Throwable) {
            Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), cacheDir = cache))
                .also { it.initialize() }
                .also { backend = "CPU" }
        }
        engine = e
        return e
    }

    /** Загрузить модель заранее (до 10 секунд), чтобы первая фраза была быстрой. */
    fun warmUp(ctx: Context) {
        if (!isInstalled(ctx)) return
        synchronized(lock) { runCatching { load(ctx) } }
    }

    fun release() {
        synchronized(lock) {
            runCatching { engine?.close() }
            engine = null
        }
    }

    private fun english(code: String): String =
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }

    private fun prompt(text: String, src: String, tgt: String): String {
        val s = english(src)
        val t = english(tgt)
        return "Translate this $s speech into $t. It is one turn of a phone call or a " +
            "face-to-face conversation. Keep the meaning exact, natural spoken style, polite " +
            "form if the original is polite. Keep names of organizations and services as they " +
            "are (Kela, TE-palvelut, Työmarkkinatori, OmaKanta, Wilma). " +
            "Output only the $t translation.\n\n$text"
    }

    /** Убираем то, что иногда добавляет модель: кавычки, пояснения, служебные метки. */
    private fun clean(raw: String): String {
        var t = raw.trim()
        t = t.replace(Regex("<\\|?channel\\|?>.*?<\\|?channel\\|?>", RegexOption.DOT_MATCHES_ALL), "")
        t = t.replace(Regex("<[^>]{1,20}>"), "").trim()
        if (t.length > 1 && (t.startsWith('"') && t.endsWith('"') || t.startsWith('«') && t.endsWith('»'))) {
            t = t.substring(1, t.length - 1).trim()
        }
        return t
    }

    suspend fun translate(ctx: Context, text: String, src: String, tgt: String): String =
        withContext(Dispatchers.Default) {
            synchronized(lock) {
                val e = load(ctx)
                val config = ConversationConfig(
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                    maxOutputToken = 400
                )
                e.createConversation(config).use { c ->
                    clean(c.sendMessage(Message.user(prompt(text, src, tgt))).toString())
                }
            }
        }
}
