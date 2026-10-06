package com.sarmat.perevodchik.phone

import android.app.Application
import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.sarmat.perevodchik.Languages
import com.sarmat.perevodchik.OfflineEars
import com.sarmat.perevodchik.OfflineVoice
import com.sarmat.perevodchik.TextTranslator
import com.sarmat.perevodchik.VoicePack
import com.sarmat.perevodchik.VoicePackDownloader
import com.sarmat.perevodchik.VoiceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Кто говорит: A — я, B — собеседник. */
enum class Side { A, B }

data class Exchange(
    val side: Side,
    val src: String,
    val tgt: String,
    val text: String,
    val translation: String,
    val fromWatch: Boolean = false
)

class PhoneViewModel(app: Application) : AndroidViewModel(app), TextToSpeech.OnInitListener {

    private val prefs = app.getSharedPreferences("phone", Context.MODE_PRIVATE)

    var langA by mutableStateOf(prefs.getString("a", "ru") ?: "ru")
        private set
    var langB by mutableStateOf(prefs.getString("b", "fi") ?: "fi")
        private set
    var faceToFace by mutableStateOf(prefs.getBoolean("face", false))
        private set
    var autoSpeak by mutableStateOf(prefs.getBoolean("autoSpeak", true))
        private set
    var slowSpeech by mutableStateOf(prefs.getBoolean("slow", false))
        private set
    var voiceId by mutableStateOf(prefs.getInt("voice2", 0).coerceIn(0, OfflineVoice.VOICES - 1))
        private set
    var useOfflineVoice by mutableStateOf(prefs.getBoolean("offlineVoice", true))
        private set

    var status by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set
    var recordingSide by mutableStateOf<Side?>(null)
        private set
    var recognizing by mutableStateOf(false)
        private set
    var micLevel by mutableStateOf(0f)
        private set
    /** Кто сейчас говорит/ждёт перевод — чтобы показывать подсказки на его половине. */
    var activeSide by mutableStateOf<Side?>(null)
        private set

    val exchanges = mutableStateListOf<Exchange>()

    // Пакеты
    val packInstalled = mutableStateMapOf<VoicePack, Boolean>()
    val packProgress = mutableStateMapOf<VoicePack, Float>()
    val packProgressText = mutableStateMapOf<VoicePack, String>()
    val downloadedLangs = mutableStateListOf<String>()
    val downloadingLangs = mutableStateListOf<String>()

    private val recorder = VoiceRecorder()
    private var listenJob: Job? = null
    private val tts = TextToSpeech(app, this)
    private var ttsReady = false
    private val voice: OfflineVoice get() = Engine.voice

    init {
        Engine.init(app)
        refreshPacks()
        refreshLangs()
        loadHistory()
        Engine.warmUp(langA)
        viewModelScope.launch {
            Engine.watchEvents.collect { e ->
                addExchange(Exchange(Side.A, e.src, e.tgt, e.text, e.translation, fromWatch = true))
            }
        }
    }

    // ---------- Языки ----------

    fun setLang(side: Side, code: String) {
        if (side == Side.A) {
            if (code == langB) langB = langA
            langA = code
        } else {
            if (code == langA) langA = langB
            langB = code
        }
        prefs.edit().putString("a", langA).putString("b", langB).apply()
        downloadLang(code)
    }

    fun swap() {
        val a = langA
        langA = langB
        langB = a
        prefs.edit().putString("a", langA).putString("b", langB).apply()
    }

    fun isLangReady(code: String) = code == "en" || code in downloadedLangs

    fun refreshLangs() {
        viewModelScope.launch {
            runCatching {
                val set = RemoteModelManager.getInstance()
                    .getDownloadedModels(TranslateRemoteModel::class.java).await()
                downloadedLangs.clear()
                downloadedLangs.addAll(set.map { it.language })
            }
        }
    }

    fun downloadLang(code: String) {
        if (isLangReady(code) || code in downloadingLangs) return
        viewModelScope.launch {
            downloadingLangs.add(code)
            try {
                RemoteModelManager.getInstance().download(
                    TranslateRemoteModel.Builder(code).build(),
                    DownloadConditions.Builder().build()
                ).await()
            } catch (e: Exception) {
                status = "Не скачался язык: ${Languages.name(code)}. Нужен интернет один раз"
            } finally {
                downloadingLangs.remove(code)
                refreshLangs()
            }
        }
    }

    fun deleteLang(code: String) {
        if (code == "en") return
        viewModelScope.launch {
            runCatching {
                RemoteModelManager.getInstance()
                    .deleteDownloadedModel(TranslateRemoteModel.Builder(code).build()).await()
            }
            refreshLangs()
        }
    }

    // ---------- Голосовые пакеты ----------

    fun refreshPacks() {
        val app = getApplication<Application>()
        VoicePack.entries.forEach { packInstalled[it] = it.isInstalled(app) }
    }

    fun hasMic() = Engine.asrPacks.any { packInstalled[it] == true }
    fun hasVoice() = packInstalled[VoicePack.TTS] == true

    fun downloadPack(pack: VoicePack) {
        if (packProgress.containsKey(pack)) return
        val app = getApplication<Application>()
        packProgress[pack] = 0f
        packProgressText[pack] = "Подключаюсь…"
        viewModelScope.launch {
            try {
                var lastMb = -1L
                VoicePackDownloader.download(app, pack) { done, total ->
                    val mb = done / 1_048_576
                    if (mb == lastMb) return@download
                    lastMb = mb
                    viewModelScope.launch {
                        packProgress[pack] = done.toFloat() / total
                        packProgressText[pack] = "$mb из ~${total / 1_048_576} МБ"
                    }
                }
                status = "Готово: ${pack.title}"
                if (pack.isAsr) Engine.releaseEars()
                Engine.warmUp(langA)
            } catch (e: Exception) {
                status = "Не скачалось: ${pack.title}. Проверьте интернет и попробуйте снова"
            } finally {
                packProgress.remove(pack)
                packProgressText.remove(pack)
                refreshPacks()
            }
        }
    }

    fun deletePack(pack: VoicePack) {
        val app = getApplication<Application>()
        if (pack.isAsr) Engine.releaseEars() else voice.release()
        pack.delete(app)
        refreshPacks()
    }

    val anyDownloading get() = packProgress.isNotEmpty()

    // ---------- Микрофон ----------

    fun startListening(side: Side) {
        if (recordingSide != null || recognizing) return
        if (!hasMic()) {
            status = "Сначала скачайте офлайн-микрофон (⚙ Настройки)"
            return
        }
        voice.stop()
        if (tts.isSpeaking) tts.stop()
        val src = if (side == Side.A) langA else langB
        val tgt = if (side == Side.A) langB else langA
        if (!OfflineEars.languages.contains(src)) {
            status = "Голосовой ввод для языка «${Languages.name(src)}» недоступен, напишите текстом"
            return
        }
        status = null
        recordingSide = side
        activeSide = side
        micLevel = 0f
        listenJob = viewModelScope.launch {
            try {
                val audio = withContext(Dispatchers.IO) { recorder.record { micLevel = it } }
                recordingSide = null
                if (audio.size < OfflineEars.RATE / 3) {
                    status = "Не услышал речь — попробуйте ещё раз"
                    return@launch
                }
                recognizing = true
                val text = Engine.recognize(audio, src)
                recognizing = false
                if (text.isBlank()) {
                    status = "Не разобрал — скажите ещё раз"
                    return@launch
                }
                translateAndShow(side, src, tgt, text)
            } catch (e: Exception) {
                status = "Ошибка: ${e.localizedMessage ?: ""}"
            } finally {
                recordingSide = null
                recognizing = false
                micLevel = 0f
                activeSide = null
            }
        }
    }

    fun finishListening() {
        recorder.stopRequested = true
    }

    fun cancelListening() {
        listenJob?.cancel()
        recordingSide = null
        recognizing = false
    }

    // ---------- Перевод ----------

    fun translateTyped(text: String, side: Side = Side.A) {
        val t = text.trim()
        if (t.isEmpty()) return
        val src = if (side == Side.A) langA else langB
        val tgt = if (side == Side.A) langB else langA
        viewModelScope.launch { translateAndShow(side, src, tgt, t) }
    }

    /** Перевод для камеры (без озвучки и истории). */
    suspend fun translateForCamera(text: String, src: String, tgt: String): String =
        try {
            translateText(text, src, tgt)
        } catch (e: Exception) {
            if (!isLangReady(src) || !isLangReady(tgt)) downloadLang(if (!isLangReady(src)) src else tgt)
            "—"
        }

    /** Единая точка перевода текста (сюда подключается движок перевода). */
    suspend fun translateText(text: String, src: String, tgt: String): String =
        TextTranslator.translate(text, src, tgt)

    private suspend fun translateAndShow(side: Side, src: String, tgt: String, text: String) {
        busy = true
        try {
            val tr = translateText(text, src, tgt)
            addExchange(Exchange(side, src, tgt, text, tr))
            if (autoSpeak) speak(tr, tgt)
        } catch (e: Exception) {
            status = if (!isLangReady(src) || !isLangReady(tgt))
                "Нет офлайн-словаря и интернета. Скачайте языки в ⚙ Настройки"
            else "Ошибка перевода: ${e.localizedMessage ?: ""}"
        } finally {
            busy = false
            refreshLangs()
        }
    }

    private fun addExchange(e: Exchange) {
        exchanges.add(e)
        while (exchanges.size > 100) exchanges.removeAt(0)
        saveHistory()
    }

    fun clearHistory() {
        exchanges.clear()
        saveHistory()
    }

    // ---------- Голос ----------

    override fun onInit(result: Int) {
        ttsReady = result == TextToSpeech.SUCCESS
    }

    fun speak(text: String, lang: String) {
        if (useOfflineVoice && hasVoice() && voice.supports(lang)) {
            viewModelScope.launch {
                try {
                    withContext(Dispatchers.Default) {
                        voice.speak(text, lang, voiceId, if (slowSpeech) 0.8f else 1.0f, steps = 5)
                    }
                } catch (e: Exception) {
                    systemSpeak(text, lang)
                }
            }
            return
        }
        systemSpeak(text, lang)
    }

    private fun systemSpeak(text: String, lang: String) {
        if (!ttsReady) return
        val r = tts.setLanguage(Languages.ttsLocale(lang))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            status = "Нет голоса для языка: ${Languages.name(lang)}"
            return
        }
        tts.setSpeechRate(if (slowSpeech) 0.75f else 1.0f)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "perevod")
    }

    fun nextVoice() {
        voiceId = (voiceId + 1) % OfflineVoice.VOICES
        prefs.edit().putInt("voice2", voiceId).apply()
        val sample = when (langB) {
            "fi" -> "Hei, tämä on uusi ääni."
            "bg" -> "Здравей, това е новият глас."
            "ru" -> "Привет, это новый голос."
            else -> "Hello, this is the new voice."
        }
        speak(sample, if (voice.supports(langB)) langB else "en")
    }

    fun toggleFace() {
        faceToFace = !faceToFace
        prefs.edit().putBoolean("face", faceToFace).apply()
    }

    fun toggleAutoSpeak() {
        autoSpeak = !autoSpeak
        prefs.edit().putBoolean("autoSpeak", autoSpeak).apply()
    }

    fun toggleSlow() {
        slowSpeech = !slowSpeech
        prefs.edit().putBoolean("slow", slowSpeech).apply()
    }

    fun toggleOfflineVoice() {
        useOfflineVoice = !useOfflineVoice
        prefs.edit().putBoolean("offlineVoice", useOfflineVoice).apply()
    }

    // ---------- История ----------

    private fun saveHistory() {
        val arr = JSONArray()
        exchanges.takeLast(50).forEach {
            arr.put(
                JSONObject().put("side", it.side.name).put("s", it.src).put("t", it.tgt)
                    .put("i", it.text).put("o", it.translation).put("w", it.fromWatch)
            )
        }
        prefs.edit().putString("history", arr.toString()).apply()
    }

    private fun loadHistory() {
        runCatching {
            val arr = JSONArray(prefs.getString("history", "[]"))
            for (n in 0 until arr.length()) {
                val o = arr.getJSONObject(n)
                exchanges.add(
                    Exchange(
                        Side.valueOf(o.getString("side")), o.getString("s"), o.getString("t"),
                        o.getString("i"), o.getString("o"), o.optBoolean("w")
                    )
                )
            }
        }
    }

    override fun onCleared() {
        recorder.stopRequested = true
        tts.stop()
        tts.shutdown()
        super.onCleared()
    }
}
