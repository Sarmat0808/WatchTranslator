package com.sarmat.perevodchik.phone

import java.util.Locale

/**
 * Текст запроса к «Умному переводчику» и очистка ответа.
 * Без Android — тот же файл проверяется автотестом на сервере (tools/llmtest).
 */
object LlmPrompt {
    private fun english(code: String): String =
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }

    fun build(text: String, src: String, tgt: String): String {
        val s = english(src)
        val t = english(tgt)
        return "Translate this $s speech into $t. It is one turn of a phone call or a " +
            "face-to-face conversation. Keep the meaning exact, natural spoken style, polite " +
            "form if the original is polite. Keep names of organizations and services as they " +
            "are (Kela, TE-palvelut, Työmarkkinatori, OmaKanta, Wilma). " +
            "Output only the $t translation.\n\n$text"
    }

    /** Убираем то, что иногда добавляет модель: кавычки, пояснения, служебные метки. */
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
