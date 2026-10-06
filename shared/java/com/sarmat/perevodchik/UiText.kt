package com.sarmat.perevodchik

/** Короткие подсказки на языке каждого собеседника (режим «лицом к лицу»). Сгенерировано из web/i18n.js. */
object UiText {
    // tapMic, listening, recognizing, translating, done
    private val T: Map<String, Array<String>> = mapOf(
        "en" to arrayOf("Tap the microphone and speak", "Listening…", "Recognizing…", "Translating…", "Done"),
        "ru" to arrayOf("Нажмите на микрофон и говорите", "Слушаю…", "Распознаю…", "Перевожу…", "Готово"),
        "fi" to arrayOf("Napauta mikrofonia ja puhu", "Kuuntelen…", "Tunnistan…", "Käännän…", "Valmis"),
        "bg" to arrayOf("Натиснете микрофона и говорете", "Слушам…", "Разпознавам…", "Превеждам…", "Готово"),
        "uk" to arrayOf("Натисніть на мікрофон і говоріть", "Слухаю…", "Розпізнаю…", "Перекладаю…", "Готово"),
        "et" to arrayOf("Puuduta mikrofoni ja räägi", "Kuulan…", "Tuvastan…", "Tõlgin…", "Valmis"),
        "sv" to arrayOf("Tryck på mikrofonen och tala", "Lyssnar…", "Känner igen…", "Översätter…", "Klar"),
        "de" to arrayOf("Tippe auf das Mikrofon und sprich", "Ich höre zu…", "Erkenne…", "Übersetze…", "Fertig"),
        "es" to arrayOf("Toca el micrófono y habla", "Escuchando…", "Reconociendo…", "Traduciendo…", "Listo"),
        "fr" to arrayOf("Touchez le micro et parlez", "J’écoute…", "Reconnaissance…", "Traduction…", "Terminé"),
        "it" to arrayOf("Tocca il microfono e parla", "Ascolto…", "Riconoscimento…", "Traduzione…", "Fatto"),
        "pt" to arrayOf("Toque no microfone e fale", "Ouvindo…", "Reconhecendo…", "Traduzindo…", "Pronto"),
        "pl" to arrayOf("Dotknij mikrofonu i mów", "Słucham…", "Rozpoznaję…", "Tłumaczę…", "Gotowe"),
        "tr" to arrayOf("Mikrofona dokunun ve konuşun", "Dinliyorum…", "Tanınıyor…", "Çevriliyor…", "Tamam"),
        "ar" to arrayOf("اضغط على الميكروفون وتحدث", "أستمع…", "جارٍ التعرّف…", "جارٍ الترجمة…", "تم"),
        "zh" to arrayOf("点击麦克风开始说话", "正在聆听…", "正在识别…", "正在翻译…", "完成"),
        "ja" to arrayOf("マイクをタップして話してください", "聞いています…", "認識中…", "翻訳中…", "完了"),
        "ko" to arrayOf("마이크를 누르고 말하세요", "듣고 있어요…", "인식 중…", "번역 중…", "완료"),
        "hi" to arrayOf("माइक्रोफ़ोन दबाएँ और बोलें", "सुन रहा हूँ…", "पहचान रहा हूँ…", "अनुवाद हो रहा है…", "हो गया"),
        "nl" to arrayOf("Tik op de microfoon en spreek", "Ik luister…", "Herkennen…", "Vertalen…", "Klaar"),
        "el" to arrayOf("Πατήστε το μικρόφωνο και μιλήστε", "Ακούω…", "Αναγνώριση…", "Μετάφραση…", "Τέλος"),
        "ro" to arrayOf("Atinge microfonul și vorbește", "Ascult…", "Recunosc…", "Traduc…", "Gata"),
        "cs" to arrayOf("Klepněte na mikrofon a mluvte", "Poslouchám…", "Rozpoznávám…", "Překládám…", "Hotovo"),
        "da" to arrayOf("Tryk på mikrofonen og tal", "Lytter…", "Genkender…", "Oversætter…", "Færdig"),
        "hu" to arrayOf("Koppints a mikrofonra és beszélj", "Figyelek…", "Felismerés…", "Fordítás…", "Kész"),
        "vi" to arrayOf("Chạm vào micrô và nói", "Đang nghe…", "Đang nhận dạng…", "Đang dịch…", "Xong"),
        "id" to arrayOf("Ketuk mikrofon lalu bicara", "Mendengarkan…", "Mengenali…", "Menerjemahkan…", "Selesai"),
        "th" to arrayOf("แตะไมโครโฟนแล้วพูด", "กำลังฟัง…", "กำลังจดจำเสียง…", "กำลังแปล…", "เสร็จ"),
        "he" to arrayOf("הקישו על המיקרופון ודברו", "מקשיב…", "מזהה…", "מתרגם…", "סיום")
    )

    private fun get(lang: String, i: Int) = (T[lang] ?: T.getValue("en"))[i]
    fun tapMic(lang: String) = get(lang, 0)
    fun listening(lang: String) = get(lang, 1)
    fun recognizing(lang: String) = get(lang, 2)
    fun translating(lang: String) = get(lang, 3)
    fun done(lang: String) = get(lang, 4)
}
