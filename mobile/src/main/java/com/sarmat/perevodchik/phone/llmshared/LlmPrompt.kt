package com.sarmat.perevodchik.phone

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.util.Locale

/**
 * Настройка «Умного переводчика»: инструкция, примеры перевода и очистка ответа.
 * Без Android — тот же файл проверяется автотестом на сервере (tools/llmtest).
 */
object LlmPrompt {
    private fun english(code: String): String =
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }

    fun system(src: String, tgt: String): String {
        val s = english(src)
        val t = english(tgt)
        return "You are a professional $s-$t interpreter. Every user message is one turn of a " +
            "phone call or a face-to-face conversation, transcribed from speech (it may lack " +
            "punctuation or contain small recognition errors). Translate it into $t: exact " +
            "meaning, natural spoken style, correct grammar, polite form if the original is " +
            "polite. Keep proper names of Finnish organizations unchanged in Latin letters " +
            "(for example Kela). Do not add anything that is not in the original. " +
            "Output only the $t translation."
    }

    /** Несколько образцов — маленькая модель с ними переводит заметно точнее. */
    fun examples(src: String, tgt: String): List<Pair<String, String>> = when ("$src>$tgt") {
        "fi>ru" -> listOf(
            "Soitan Kelasta, koska hakemuksestanne puuttuu liite." to
                "Я звоню из Kela, потому что к вашему заявлению не приложен документ.",
            "voitteko tulla toimistolle torstaina kello yhdeksän" to
                "Можете прийти в офис в четверг в девять?",
            "Työmarkkinatorilla on uusi työpaikka teille." to
                "На Työmarkkinatori есть для вас новая вакансия."
        )
        "ru>fi" -> listOf(
            "Извините, я не понял. Можете повторить медленнее?" to
                "Anteeksi, en ymmärtänyt. Voitteko toistaa hitaammin?",
            "я отправил справку о зарплате вчера" to
                "Lähetin palkkatodistuksen eilen.",
            "Когда мне придёт решение?" to
                "Milloin saan päätöksen?"
        )
        "bg>fi" -> listOf(
            "Извинете, не разбрах. Можете ли да повторите по-бавно?" to
                "Anteeksi, en ymmärtänyt. Voitteko toistaa hitaammin?"
        )
        "fi>bg" -> listOf(
            "Voitteko tulla vastaanotolle huomenna?" to
                "Можете ли да дойдете на прием утре?"
        )
        else -> emptyList()
    }

    fun config(src: String, tgt: String) = ConversationConfig(
        systemInstruction = Contents.of(system(src, tgt)),
        initialMessages = examples(src, tgt).flatMap { (a, b) -> listOf(Message.user(a), Message.model(b)) },
        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
        thinkingConfig = ThinkingConfig(enableThinking = false),
        maxOutputToken = 400
    )

    /** Убираем то, что иногда добавляет модель: кавычки, служебные метки. */
    fun clean(raw: String): String {
        var t = raw.trim()
        t = t.replace(Regex("<\\|?channel\\|?>.*?<\\|?channel\\|?>", RegexOption.DOT_MATCHES_ALL), "")
        t = t.replace(Regex("<[^>]{1,20}>"), "").trim()
        if (t.length > 1 && (t.startsWith('"') && t.endsWith('"') || t.startsWith('«') && t.endsWith('»'))) {
            t = t.substring(1, t.length - 1).trim()
        }
        return t
    }
}
