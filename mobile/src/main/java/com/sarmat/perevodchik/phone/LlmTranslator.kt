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
 * «Умный переводчик»: нейросеть Gemma 4 E4B прямо на телефоне (LiteRT-LM).
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
        val prefs = ctx.getSharedPreferences("phone", Context.MODE_PRIVATE)
        fun cpu() = Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), cacheDir = cache))
            .also { it.initialize() }
            .also { backend = "CPU" }
        // Видеоядро в 2–3 раза быстрее. Если при прошлой попытке приложение упало прямо
        // во время запуска на видеоядре (метка осталась), больше его не пробуем — только процессор.
        val gpuBroken = prefs.getBoolean("llmGpuTrying", false) || prefs.getBoolean("llmGpuFailed", false)
        val e = if (gpuBroken) {
            prefs.edit().putBoolean("llmGpuFailed", true).putBoolean("llmGpuTrying", false).commit()
            cpu()
        } else {
            prefs.edit().putBoolean("llmGpuTrying", true).commit()
            try {
                Engine(EngineConfig(modelPath = path, backend = Backend.GPU(), cacheDir = cache))
                    .also { it.initialize() }
                    .also { backend = "GPU" }
            } catch (t: Throwable) {
                prefs.edit().putBoolean("llmGpuFailed", true).putBoolean("llmGpuTrying", false).commit()
                cpu()
            }
            // метка «пробую видеоядро» снимается после первого удачного перевода
        }
        engine = e
        return e
    }

    /** Загрузить модель заранее (до 10 секунд), чтобы первая фраза была быстрой. */
    fun warmUp(ctx: Context) {
        if (!isInstalled(ctx)) return
        synchronized(lock) {
            runCatching {
                val e = load(ctx)
                // Пробная фраза: проверяет видеоядро и прогревает модель
                if (backend == "GPU") {
                    e.createConversation(LlmPrompt.config("fi", "ru")).use { it.sendMessage(Message.user("Hei")) }
                    ctx.getSharedPreferences("phone", Context.MODE_PRIVATE).edit()
                        .putBoolean("llmGpuTrying", false).apply()
                }
            }
        }
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
                val out = e.createConversation(LlmPrompt.config(src, tgt)).use { c ->
                    LlmPrompt.clean(c.sendMessage(Message.user(text)).toString())
                }
                if (backend == "GPU") {
                    val prefs = ctx.getSharedPreferences("phone", Context.MODE_PRIVATE)
                    if (prefs.getBoolean("llmGpuTrying", false)) {
                        prefs.edit().putBoolean("llmGpuTrying", false).apply()
                    }
                }
                out
            }
        }
}
