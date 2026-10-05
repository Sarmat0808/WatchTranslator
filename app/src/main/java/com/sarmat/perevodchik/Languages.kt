package com.sarmat.perevodchik

import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

object Languages {

    /** Главные языки — показываются первыми. */
    val featured = listOf(
        "ru", "fi", "bg", "en", "uk", "et", "sv", "de", "es", "fr", "it", "pt",
        "pl", "tr", "ar", "zh", "ja", "ko", "hi", "nl", "el", "ro", "cs", "lt", "lv"
    )

    private val names = mapOf(
        "ru" to "Русский", "fi" to "Финский", "bg" to "Болгарский", "en" to "Английский",
        "uk" to "Украинский", "et" to "Эстонский", "sv" to "Шведский", "de" to "Немецкий",
        "af" to "Африкаанс", "sq" to "Албанский", "ar" to "Арабский", "be" to "Белорусский",
        "bn" to "Бенгальский", "hu" to "Венгерский", "vi" to "Вьетнамский", "cy" to "Валлийский",
        "ht" to "Гаитянский", "gl" to "Галисийский", "el" to "Греческий", "ka" to "Грузинский",
        "gu" to "Гуджарати", "da" to "Датский", "he" to "Иврит", "id" to "Индонезийский",
        "ga" to "Ирландский", "is" to "Исландский", "es" to "Испанский", "it" to "Итальянский",
        "kn" to "Каннада", "ca" to "Каталанский", "zh" to "Китайский", "ko" to "Корейский",
        "lv" to "Латышский", "lt" to "Литовский", "mk" to "Македонский", "ms" to "Малайский",
        "mt" to "Мальтийский", "mr" to "Маратхи", "nl" to "Нидерландский", "no" to "Норвежский",
        "fa" to "Персидский", "pl" to "Польский", "pt" to "Португальский", "ro" to "Румынский",
        "sk" to "Словацкий", "sl" to "Словенский", "sw" to "Суахили", "tl" to "Филиппинский",
        "th" to "Тайский", "ta" to "Тамильский", "te" to "Телугу", "tr" to "Турецкий",
        "ur" to "Урду", "fr" to "Французский", "hi" to "Хинди", "hr" to "Хорватский",
        "cs" to "Чешский", "eo" to "Эсперанто", "ja" to "Японский"
    )

    /** Все языки ML Kit: сначала главные, потом остальные по алфавиту. */
    val all: List<String> by lazy {
        val supported = TranslateLanguage.getAllLanguages().toSet()
        val rest = supported.filter { it !in featured }.sortedBy { name(it) }
        featured.filter { it in supported } + rest
    }

    fun name(code: String): String =
        names[code] ?: Locale.forLanguageTag(code).getDisplayLanguage(Locale.forLanguageTag("ru"))
            .replaceFirstChar { it.uppercase() }

    fun short(code: String): String = code.uppercase()

    /** Локаль для синтеза речи. */
    fun ttsLocale(code: String): Locale = when (code) {
        "no" -> Locale.forLanguageTag("nb-NO")
        "tl" -> Locale.forLanguageTag("fil-PH")
        else -> Locale.forLanguageTag(code)
    }

    /** Тег для распознавания речи. */
    fun speechTag(code: String): String = when (code) {
        "ru" -> "ru-RU"
        "fi" -> "fi-FI"
        "bg" -> "bg-BG"
        "en" -> "en-US"
        "uk" -> "uk-UA"
        "et" -> "et-EE"
        "sv" -> "sv-SE"
        "de" -> "de-DE"
        "no" -> "nb-NO"
        "tl" -> "fil-PH"
        else -> code
    }
}
