package com.sarmat.perevodchik.phone

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.sarmat.perevodchik.VoicePack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * «Умный переводчик»: нейросеть Gemma 4 E2B прямо на телефоне (LiteRT-LM).
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
        // Процессор: для коротких фраз так же быстр, как видеоядро (~40 токенов/с на S25),
        // и надёжнее на всех телефонах. Видеоядро — только если процессор не подошёл.
        val e = try {
            Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), cacheDir = cache))
                .also { it.initialize() }
                .also { backend = "CPU" }
        } catch (t: Throwable) {
            Engine(EngineConfig(modelPath = path, backend = Backend.GPU(), cacheDir = cache))
                .also { it.initialize() }
                .also { backend = "GPU" }
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

    suspend fun translate(ctx: Context, text: String, src: String, tgt: String): String =
        withContext(Dispatchers.Default) {
            synchronized(lock) {
                val e = load(ctx)
                e.createConversation(LlmPrompt.config(src, tgt)).use { c ->
                    LlmPrompt.clean(c.sendMessage(Message.user(text)).toString())
                }
            }
        }
}
