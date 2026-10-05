package com.sarmat.perevodchik

import android.app.Application
import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import kotlinx.coroutines.launch
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
            status = "Скачиваю: ${Languages.name(code)}…"
            try {
                modelManager.download(TranslateRemoteModel.Builder(code).build(), anyNetwork).await()
                status = "Готово офлайн: ${Languages.name(code)}"
            } catch (e: Exception) {
                status = "Не скачалось (${Languages.name(code)}). Включите Wi‑Fi на часах"
            } finally {
                downloading.remove(code)
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
        if (!ttsReady) {
            status = "Голос ещё загружается, нажмите 🔊 через секунду"
            return
        }
        val r = tts.setLanguage(Languages.ttsLocale(code))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            missingVoice = code
            status = "Нет голоса для языка: ${Languages.name(code)}"
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
        tts.stop()
        tts.shutdown()
        super.onCleared()
    }
}
