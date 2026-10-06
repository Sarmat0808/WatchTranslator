package com.sarmat.perevodchik

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
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(val src: String, val tgt: String, val input: String, val output: String)

class TranslatorViewModel(app: Application) : AndroidViewModel(app), TextToSpeech.OnInitListener {

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var source by mutableStateOf(prefs.getString("src", "ru") ?: "ru")
        private set
    var target by mutableStateOf(prefs.getString("tgt", "fi") ?: "fi")
        private set
    var inputText by mutableStateOf("")
        private set
    var outputText by mutableStateOf("")
        private set
    var outputLang by mutableStateOf("fi")
        private set
    var status by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set
    var autoSpeak by mutableStateOf(prefs.getBoolean("autoSpeak", true))
        private set
    var slowSpeech by mutableStateOf(prefs.getBoolean("slow", false))
        private set
    var missingVoice by mutableStateOf<String?>(null)
        private set

    var voiceId by mutableStateOf(prefs.getInt("voice2", 0).coerceIn(0, OfflineVoice.VOICES - 1))
        private set
    var useOfflineVoice by mutableStateOf(prefs.getBoolean("offlineVoice", true))
        private set

    // Офлайн-пакеты голоса и микрофона
    var ttsInstalled by mutableStateOf(VoicePack.TTS.isInstalled(app))
        private set
    var asrInstalled by mutableStateOf(VoicePack.ASR.isInstalled(app))
        private set
    var ttsProgress by mutableStateOf<Float?>(null)
        private set
    var asrProgress by mutableStateOf<Float?>(null)
        private set
    var ttsProgressText by mutableStateOf("")
        private set
    var asrProgressText by mutableStateOf("")
        private set

    // Запись с микрофона
    var recording by mutableStateOf(false)
        private set
    var recognizing by mutableStateOf(false)
        private set
    var micLevel by mutableStateOf(0f)
        private set
    var speaking by mutableStateOf(false)
        private set

    // Телефон рядом с приложением «Переводчик»: он распознаёт и переводит за часы
    private val phoneLink = PhoneLink(app)
    var usePhone by mutableStateOf(prefs.getBoolean("usePhone", true))
        private set
    var phoneConnected by mutableStateOf(false)
        private set

    private val offlineVoice = OfflineVoice(app)
    private val offlineEars = OfflineEars(app)
    private val recorder = VoiceRecorder()
    private var listenJob: Job? = null

    val downloaded = mutableStateListOf<String>()
    val downloading = mutableStateListOf<String>()
    val history = mutableStateListOf<HistoryItem>()

    private val tts = TextToSpeech(app, this)
    private var ttsReady = false
    private val translators = HashMap<String, Translator>()
    private val modelManager = RemoteModelManager.getInstance()
    private val anyNetwork = DownloadConditions.Builder().build()

    init {
        loadHistory()
        refreshModels()
        refreshPhone()
        // Модели НЕ грузим при запуске: у часов мало памяти, приложение должно открываться мгновенно.
        // Они загружаются, пока человек говорит (см. startListening).
    }

    /** Часы свернули приложение — освобождаем память (модели загрузятся снова, пока человек говорит). */
    fun trimMemory() {
        if (recording || recognizing || speaking) return
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { offlineEars.release() }
            runCatching { offlineVoice.release() }
        }
    }

    /** Время начала скачивания словаря (для отображения хода загрузки). */
    val downloadStarted = mutableStateMapOf<String, Long>()

    private fun preloadVoice() {
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { offlineVoice.load() }
        }
    }

    fun refreshPhone() {
        viewModelScope.launch {
            phoneConnected = phoneLink.findPhone() != null
        }
    }

    fun toggleUsePhone() {
        usePhone = !usePhone
        prefs.edit().putBoolean("usePhone", usePhone).apply()
        if (usePhone) refreshPhone()
    }

    // ---------- Офлайн-пакеты ----------

    val anyPackDownloading get() = ttsProgress != null || asrProgress != null

    fun downloadPack(pack: VoicePack) {
        if (pack == VoicePack.TTS && ttsProgress != null) return
        if (pack == VoicePack.ASR && asrProgress != null) return
        val app = getApplication<Application>()
        setPackProgress(pack, 0f, "Подключаюсь…")
        viewModelScope.launch {
            try {
                var lastMb = -1L
                VoicePackDownloader.download(app, pack) { done, total ->
                    val mb = done / 1_048_576
                    if (mb == lastMb) return@download
                    lastMb = mb
                    val all = total / 1_048_576
                    viewModelScope.launch {
                        setPackProgress(pack, done.toFloat() / total, "$mb из ~$all МБ")
                    }
                }
                if (pack == VoicePack.TTS) {
                    ttsInstalled = true
                    preloadVoice()
                } else {
                    asrInstalled = true
                }
                status = "Готово: ${pack.title} работает без интернета"
            } catch (e: Exception) {
                status = "Не скачалось: ${pack.title}. Включите Wi‑Fi и попробуйте снова"
            } finally {
                setPackProgress(pack, null, "")
            }
        }
    }

    private fun setPackProgress(pack: VoicePack, value: Float?, text: String) {
        if (pack == VoicePack.TTS) {
            ttsProgress = value
            ttsProgressText = text
        } else {
            asrProgress = value
            asrProgressText = text
        }
    }

    fun deletePack(pack: VoicePack) {
        val app = getApplication<Application>()
        if (pack == VoicePack.TTS) {
            offlineVoice.release()
            pack.delete(app)
            ttsInstalled = false
        } else {
            offlineEars.release()
            pack.delete(app)
            asrInstalled = false
        }
        status = "Удалено: ${pack.title}"
    }

    // ---------- Офлайн-микрофон ----------

    fun canListenOffline() =
        (asrInstalled && offlineEars.supports(source)) || (usePhone && phoneConnected)

    fun startListening() {
        if (recording || recognizing) return
        offlineVoice.stop()
        if (tts.isSpeaking) tts.stop()
        val lang = source
        status = null
        recording = true
        micLevel = 0f
        // Пока человек говорит, в фоне готовим модели — так ответ приходит быстрее
        val tgtNow = target
        viewModelScope.launch(Dispatchers.Default) {
            if (!(usePhone && phoneConnected) && asrInstalled && offlineEars.supports(lang)) {
                runCatching { offlineEars.load(lang) }
            }
            if (useOfflineVoice && ttsInstalled && offlineVoice.supports(tgtNow)) {
                runCatching { offlineVoice.load() }
            }
        }
        listenJob = viewModelScope.launch {
            try {
                val audio = withContext(Dispatchers.IO) {
                    recorder.record { lvl -> micLevel = lvl }
                }
                recording = false
                if (audio.size < OfflineEars.RATE / 3) {
                    status = "Не услышал речь. Попробуйте ближе к часам"
                    return@launch
                }
                recognizing = true
                val tgt = target
                // 1) Сначала пробуем телефон — он мощнее и точнее
                if (usePhone) {
                    val node = phoneLink.findPhone()
                    phoneConnected = node != null
                    if (node != null) {
                        val res = runCatching {
                            phoneLink.recognizeAndTranslate(node, audio, lang, tgt)
                        }.getOrNull()
                        if (res != null) {
                            recognizing = false
                            if (res.text.isBlank()) {
                                status = "Не разобрал. Скажите ещё раз или используйте «Написать»"
                            } else {
                                showResult(lang, tgt, res.text, res.translation)
                            }
                            return@launch
                        }
                    }
                }
                // 2) Сами, на часах
                if (!(asrInstalled && offlineEars.supports(lang))) {
                    recognizing = false
                    status = "Телефон не ответил. Скачайте «Офлайн микрофон» для часов"
                    return@launch
                }
                val text = withContext(Dispatchers.Default) {
                    offlineEars.recognize(audio, lang)
                }
                recognizing = false
                if (text.isBlank()) {
                    status = "Не разобрал. Скажите ещё раз или используйте «Написать»"
                } else {
                    translate(text)
                }
            } catch (e: Exception) {
                status = "Ошибка микрофона: ${e.localizedMessage ?: ""}"
            } finally {
                recording = false
                recognizing = false
                micLevel = 0f
            }
        }
    }

    private fun showResult(src: String, tgt: String, text: String, translation: String) {
        inputText = text
        outputText = translation
        outputLang = tgt
        status = null
        addHistory(HistoryItem(src, tgt, text, translation))
        if (autoSpeak) speak(translation, tgt)
    }

    /** Закончить запись досрочно (кнопка «Готово»). */
    fun finishListening() {
        recorder.stopRequested = true
    }

    fun cancelListening() {
        listenJob?.cancel()
        recording = false
        recognizing = false
    }

    // ---------- Языки ----------

    fun setLanguage(forSource: Boolean, code: String) {
        if (forSource) {
            if (code == target) target = source
            source = code
        } else {
            if (code == source) source = target
            target = code
        }
        saveLangs()
    }

    fun swapLanguages() {
        val s = source
        source = target
        target = s
        saveLangs()
    }

    private fun saveLangs() {
        prefs.edit().putString("src", source).putString("tgt", target).apply()
    }

    fun isReady(code: String) = code == "en" || code in downloaded

    // ---------- Модели (офлайн-словари) ----------

    fun refreshModels() {
        viewModelScope.launch {
            try {
                val set = modelManager.getDownloadedModels(TranslateRemoteModel::class.java).await()
                downloaded.clear()
                downloaded.addAll(set.map { it.language })
            } catch (_: Exception) {
            }
        }
    }

    fun download(code: String) {
        if (isReady(code) || code in downloading) return
        viewModelScope.launch {
            downloading.add(code)
            downloadStarted[code] = System.currentTimeMillis()
            status = "Скачиваю: ${Languages.name(code)}…"
            try {
                modelManager.download(TranslateRemoteModel.Builder(code).build(), anyNetwork).await()
                status = "Готово офлайн: ${Languages.name(code)}"
            } catch (e: Exception) {
                status = "Не скачалось (${Languages.name(code)}). Включите Wi‑Fi на часах"
            } finally {
                downloading.remove(code)
                downloadStarted.remove(code)
                refreshModels()
            }
        }
    }

    fun delete(code: String) {
        if (code == "en") {
            status = "Английский встроен, его нельзя удалить"
            return
        }
        viewModelScope.launch {
            try {
                modelManager.deleteDownloadedModel(TranslateRemoteModel.Builder(code).build()).await()
                status = "Удалено: ${Languages.name(code)}"
            } catch (e: Exception) {
                status = "Не удалось удалить"
            } finally {
                refreshModels()
            }
        }
    }

    // ---------- Перевод ----------

    fun translate(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val src = source
        val tgt = target
        inputText = clean
        outputText = ""
        busy = true
        status = null
        missingVoice = null
        viewModelScope.launch {
            val missing = listOf(src, tgt).filter { !isReady(it) }
            if (missing.isNotEmpty()) {
                status = "Скачиваю: " + missing.joinToString { Languages.name(it) } + "…"
            }
            try {
                val translator = translators.getOrPut("$src>$tgt") {
                    Translation.getClient(
                        TranslatorOptions.Builder()
                            .setSourceLanguage(src)
                            .setTargetLanguage(tgt)
                            .build()
                    )
                }
                translator.downloadModelIfNeeded(anyNetwork).await()
                val result = translator.translate(clean).await()
                outputText = result
                outputLang = tgt
                status = null
                addHistory(HistoryItem(src, tgt, clean, result))
                if (autoSpeak) speak(result, tgt)
            } catch (e: Exception) {
                status = if (missing.isNotEmpty())
                    "Нет словаря (" + missing.joinToString { Languages.name(it) } +
                        ") и нет интернета. Скачайте его в «Языки офлайн»"
                else
                    "Ошибка перевода: ${e.localizedMessage ?: "неизвестно"}"
            } finally {
                busy = false
                if (missing.isNotEmpty()) refreshModels()
            }
        }
    }

    // ---------- Голос ----------

    override fun onInit(result: Int) {
        ttsReady = result == TextToSpeech.SUCCESS
        if (!ttsReady) status = "Синтез речи на часах недоступен"
    }

    fun speakLast() {
        if (outputText.isNotEmpty()) speak(outputText, outputLang)
    }

    fun speak(text: String, code: String) {
        if (useOfflineVoice && ttsInstalled && offlineVoice.supports(code)) {
            viewModelScope.launch {
                speaking = true
                missingVoice = null
                try {
                    withContext(Dispatchers.Default) {
                        offlineVoice.speak(text, code, voiceId, if (slowSpeech) 0.8f else 1.0f, steps = 4)
                    }
                } catch (e: Exception) {
                    systemSpeak(text, code)
                } finally {
                    speaking = false
                }
            }
            return
        }
        systemSpeak(text, code)
    }

    fun nextVoice() {
        voiceId = (voiceId + 1) % OfflineVoice.VOICES
        prefs.edit().putInt("voice2", voiceId).apply()
        val sample = if (outputText.isNotEmpty()) outputText else when (target) {
            "fi" -> "Hei, tämä on uusi ääni."
            "bg" -> "Здравей, това е новият глас."
            "ru" -> "Привет, это новый голос."
            else -> "Hello, this is the new voice."
        }
        speak(sample, if (outputText.isNotEmpty()) outputLang else target)
    }

    fun toggleOfflineVoice() {
        useOfflineVoice = !useOfflineVoice
        prefs.edit().putBoolean("offlineVoice", useOfflineVoice).apply()
    }

    fun offlineVoiceSupports(code: String) = offlineVoice.supports(code)
    fun offlineEarsSupports(code: String) = offlineEars.supports(code)

    private fun systemSpeak(text: String, code: String) {
        if (!ttsReady) {
            status = "Голос ещё загружается, нажмите 🔊 через секунду"
            return
        }
        val r = tts.setLanguage(Languages.ttsLocale(code))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            missingVoice = code
            status = if (offlineVoice.supports(code) && !ttsInstalled)
                "Нет голоса для языка: ${Languages.name(code)}. Скачайте «Офлайн голос» в «Языки офлайн»"
            else
                "Нет голоса для языка: ${Languages.name(code)}"
            return
        }
        missingVoice = null
        tts.setSpeechRate(if (slowSpeech) 0.75f else 1.0f)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "perevod")
    }

    fun toggleAutoSpeak() {
        autoSpeak = !autoSpeak
        prefs.edit().putBoolean("autoSpeak", autoSpeak).apply()
    }

    fun toggleSlow() {
        slowSpeech = !slowSpeech
        prefs.edit().putBoolean("slow", slowSpeech).apply()
    }

    // ---------- История ----------

    fun useHistory(item: HistoryItem) {
        source = item.src
        target = item.tgt
        saveLangs()
        inputText = item.input
        outputText = item.output
        outputLang = item.tgt
        status = null
        if (autoSpeak) speak(item.output, item.tgt)
    }

    fun clearHistory() {
        history.clear()
        saveHistory()
    }

    private fun addHistory(item: HistoryItem) {
        history.removeAll { it.input == item.input && it.src == item.src && it.tgt == item.tgt }
        history.add(0, item)
        while (history.size > 30) history.removeAt(history.size - 1)
        saveHistory()
    }

    private fun saveHistory() {
        val arr = JSONArray()
        history.forEach {
            arr.put(
                JSONObject().put("s", it.src).put("t", it.tgt)
                    .put("i", it.input).put("o", it.output)
            )
        }
        prefs.edit().putString("history", arr.toString()).apply()
    }

    private fun loadHistory() {
        try {
            val arr = JSONArray(prefs.getString("history", "[]"))
            for (n in 0 until arr.length()) {
                val o = arr.getJSONObject(n)
                history.add(HistoryItem(o.getString("s"), o.getString("t"), o.getString("i"), o.getString("o")))
            }
        } catch (_: Exception) {
        }
    }

    override fun onCleared() {
        translators.values.forEach { it.close() }
        recorder.stopRequested = true
        offlineVoice.release()
        offlineEars.release()
        tts.stop()
        tts.shutdown()
        super.onCleared()
    }
}
