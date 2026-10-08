package com.sarmat.perevodchik.phone

import android.app.Application
import android.content.Context
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.sarmat.perevodchik.Bridge
import com.sarmat.perevodchik.OfflineEars
import com.sarmat.perevodchik.OfflineVoice
import com.sarmat.perevodchik.TextTranslator
import com.sarmat.perevodchik.VoicePack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject

class PhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Engine.init(this)
    }
}

/** Фраза, переведённая для часов — показываем её и в приложении на телефоне. */
data class WatchExchange(val src: String, val tgt: String, val text: String, val translation: String)

/** Общий «мозг» телефона: распознавание речи и голос. Живёт всё время работы приложения. */
object Engine {
    lateinit var app: Context
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val voice: OfflineVoice by lazy { OfflineVoice(app, threads = 4) }
    private var ears: OfflineEars? = null

    private val _watchEvents = MutableSharedFlow<WatchExchange>(extraBufferCapacity = 8)
    val watchEvents: SharedFlow<WatchExchange> = _watchEvents

    /** Порядок выбора распознавания: быстрый Parakeet, затем самый точный из скачанных Whisper. */
    val asrPacks = listOf(VoicePack.ASR_FAST, VoicePack.ASR_TURBO, VoicePack.ASR_SMALL, VoicePack.ASR)
    private val whisperPacks = listOf(VoicePack.ASR_TURBO, VoicePack.ASR_SMALL, VoicePack.ASR)

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    /** Лучший скачанный пакет для этого языка. */
    fun bestAsr(lang: String): VoicePack? {
        if (VoicePack.ASR_FAST.isInstalled(app) && lang in OfflineEars.parakeetLanguages) return VoicePack.ASR_FAST
        return whisperPacks.firstOrNull { it.isInstalled(app) }
    }

    @Synchronized
    fun ears(lang: String): OfflineEars? {
        val p = bestAsr(lang) ?: return null
        val cur = ears
        if (cur != null && cur.pack == p) return cur
        cur?.release()
        val created = OfflineEars(app, p, threads = 6)
        ears = created
        return created
    }

    @Synchronized
    fun releaseEars() {
        ears?.release()
        ears = null
    }

    suspend fun recognize(samples: FloatArray, lang: String): String =
        withContext(Dispatchers.Default) {
            val e = ears(lang) ?: throw IllegalStateException("На телефоне не скачан офлайн-микрофон")
            e.recognize(samples, lang)
        }

    /** Заранее загрузить модели, чтобы первая фраза была быстрой. */
    fun warmUp(lang: String) {
        scope.launch {
            runCatching { ears(lang)?.load(lang) }
            runCatching { if (VoicePack.TTS.isInstalled(app)) voice.load() }
        }
    }

    /** Лучший доступный перевод: Helsinki (если скачан для этой пары), иначе ML Kit. */
    suspend fun translateBest(text: String, src: String, tgt: String): String {
        val useHelsinki = app.getSharedPreferences("phone", Context.MODE_PRIVATE).getBoolean("helsinki", true)
        if (useHelsinki) {
            try {
                val (sup, ready) = MtEngine.status(app, src, tgt)
                if (sup && ready) MtEngine.translate(app, text, src, tgt)?.let { if (it.isNotBlank()) return it }
            } catch (_: Exception) {
            }
        }
        return TextTranslator.translate(text, src, tgt)
    }

    internal fun emitWatch(e: WatchExchange) {
        _watchEvents.tryEmit(e)
    }
}

/** Принимает голос с часов по Bluetooth, распознаёт, переводит и отвечает. */
class BridgeService : WearableListenerService() {

    override fun onMessageReceived(event: com.google.android.gms.wearable.MessageEvent) {
        if (event.path == com.sarmat.perevodchik.LinkProtocol.PONG) WatchLink.onPong(event.data)
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != Bridge.AUDIO_PATH) return
        Engine.init(applicationContext)
        val ctx = applicationContext
        Engine.scope.launch {
            val channels = Wearable.getChannelClient(ctx)
            var id = ""
            val reply = JSONObject()
            try {
                val bytes = channels.getInputStream(channel).await().use { it.readBytes() }
                val nl = bytes.indexOf('\n'.code.toByte())
                require(nl > 0) { "Плохие данные с часов" }
                val header = JSONObject(String(bytes, 0, nl))
                id = header.getString("id")
                val src = header.getString("src")
                val tgt = header.getString("tgt")
                // Связь выключена на телефоне — часы переводят сами
                if (!WatchLink.linkEnabled(ctx)) throw IllegalStateException("link-disabled")
                val samples = Bridge.pcm16ToFloats(bytes, nl + 1)
                val text = Engine.recognize(samples, src)
                val translation =
                    if (text.isBlank()) "" else Engine.translateBest(text, src, tgt)
                reply.put("text", text).put("translation", translation)
                if (text.isNotBlank()) Engine.emitWatch(WatchExchange(src, tgt, text, translation))
            } catch (e: Exception) {
                reply.put("error", e.localizedMessage ?: "Ошибка на телефоне")
            } finally {
                runCatching { channels.close(channel).await() }
            }
            reply.put("id", id)
            runCatching {
                Wearable.getMessageClient(ctx)
                    .sendMessage(channel.nodeId, Bridge.RESULT_PATH, reply.toString().toByteArray())
                    .await()
            }
        }
    }
}
