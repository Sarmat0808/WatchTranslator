// Какие модели перевода использовать для каждой пары языков.
// Xenova/* — с Hugging Face, local/* — сконвертированы нами и лежат на сайте (./models/).
// Если прямой модели нет, переводим через английский (src → en → tgt).

export const LANGS = [
  'en', 'ru', 'fi', 'bg', 'uk', 'et', 'sv', 'de', 'es', 'fr', 'it', 'pt', 'pl', 'tr', 'ar',
  'zh', 'ja', 'ko', 'hi', 'nl', 'el', 'ro', 'cs', 'da', 'hu', 'vi', 'id', 'th', 'he',
];

// В Whisper язык задаётся тем же кодом
export const WHISPER = new Set(LANGS);

const X = (id, prefix = '') => ({ id, prefix, local: false });
const L = (id, prefix = '') => ({ id, prefix, local: true });

// Прямые модели (без английского посредника)
const DIRECT = {
  'ru>uk': X('Xenova/opus-mt-ru-uk'), 'uk>ru': X('Xenova/opus-mt-uk-ru'),
  'ru>es': X('Xenova/opus-mt-ru-es'), 'es>ru': X('Xenova/opus-mt-es-ru'),
  'ru>fr': X('Xenova/opus-mt-ru-fr'), 'fr>ru': X('Xenova/opus-mt-fr-ru'),
  'fi>de': X('Xenova/opus-mt-fi-de'),
  'de>es': X('Xenova/opus-mt-de-es'), 'es>de': X('Xenova/opus-mt-es-de'),
  'de>fr': X('Xenova/opus-mt-de-fr'), 'fr>de': X('Xenova/opus-mt-fr-de'),
  'es>fr': X('Xenova/opus-mt-es-fr'), 'fr>es': X('Xenova/opus-mt-fr-es'),
  'es>it': X('Xenova/opus-mt-es-it'), 'it>es': X('Xenova/opus-mt-it-es'),
  'it>fr': X('Xenova/opus-mt-it-fr'),
};

// Язык → английский
const TO_EN = {
  ru: X('Xenova/opus-mt-ru-en'), fi: X('Xenova/opus-mt-fi-en'), bg: L('local/opus-mt-bg-en'),
  uk: X('Xenova/opus-mt-uk-en'), et: X('Xenova/opus-mt-et-en'), sv: X('Xenova/opus-mt-sv-en'),
  de: X('Xenova/opus-mt-de-en'), es: X('Xenova/opus-mt-es-en'), fr: X('Xenova/opus-mt-fr-en'),
  it: X('Xenova/opus-mt-it-en'), pt: X('Xenova/opus-mt-ROMANCE-en'), pl: X('Xenova/opus-mt-pl-en'),
  tr: X('Xenova/opus-mt-tr-en'), ar: X('Xenova/opus-mt-ar-en'), zh: X('Xenova/opus-mt-zh-en'),
  ja: X('Xenova/opus-mt-ja-en'), ko: X('Xenova/opus-mt-ko-en'), hi: X('Xenova/opus-mt-hi-en'),
  nl: X('Xenova/opus-mt-nl-en'), el: X('Xenova/opus-mt-mul-en'), ro: X('Xenova/opus-mt-ROMANCE-en'),
  cs: X('Xenova/opus-mt-cs-en'), da: X('Xenova/opus-mt-da-en'), hu: X('Xenova/opus-mt-hu-en'),
  vi: X('Xenova/opus-mt-vi-en'), id: X('Xenova/opus-mt-id-en'), th: X('Xenova/opus-mt-th-en'),
  he: X('Xenova/opus-mt-mul-en'),
};

// Английский → язык
const FROM_EN = {
  ru: X('Xenova/opus-mt-en-ru'), fi: X('Xenova/opus-mt-en-fi'), bg: L('local/opus-mt-en-bg'),
  uk: X('Xenova/opus-mt-en-uk'), et: L('local/opus-mt-en-et'), sv: X('Xenova/opus-mt-en-sv'),
  de: X('Xenova/opus-mt-en-de'), es: X('Xenova/opus-mt-en-es'), fr: X('Xenova/opus-mt-en-fr'),
  it: X('Xenova/opus-mt-en-it'), pt: X('Xenova/opus-mt-en-ROMANCE', '>>pt<< '),
  pl: L('local/opus-mt-en-sla', '>>pol<< '), tr: null,
  ar: X('Xenova/opus-mt-en-ar', '>>ara<< '), zh: X('Xenova/opus-mt-en-zh', '>>cmn_Hans<< '),
  ja: L('local/opus-tatoeba-en-ja', '>>jpn<< '), ko: null,
  hi: X('Xenova/opus-mt-en-hi'), nl: X('Xenova/opus-mt-en-nl'), el: L('local/opus-mt-en-el'),
  ro: X('Xenova/opus-mt-en-ro'), cs: X('Xenova/opus-mt-en-cs'), da: X('Xenova/opus-mt-en-da'),
  hu: X('Xenova/opus-mt-en-hu'), vi: X('Xenova/opus-mt-en-vi'), id: X('Xenova/opus-mt-en-id'),
  th: null, he: L('local/opus-mt-en-he'),
};

// Для корейского, турецкого и тайского нет хорошей маленькой модели —
// берём многоязычную NLLB-200 (переводит напрямую, без английского посредника)
const NLLB_ID = 'Xenova/nllb-200-distilled-600M';
const NLLB = {
  en: 'eng_Latn', ru: 'rus_Cyrl', fi: 'fin_Latn', bg: 'bul_Cyrl', uk: 'ukr_Cyrl', et: 'est_Latn',
  sv: 'swe_Latn', de: 'deu_Latn', es: 'spa_Latn', fr: 'fra_Latn', it: 'ita_Latn', pt: 'por_Latn',
  pl: 'pol_Latn', tr: 'tur_Latn', ar: 'arb_Arab', zh: 'zho_Hans', ja: 'jpn_Jpan', ko: 'kor_Hang',
  hi: 'hin_Deva', nl: 'nld_Latn', el: 'ell_Grek', ro: 'ron_Latn', cs: 'ces_Latn', da: 'dan_Latn',
  hu: 'hun_Latn', vi: 'vie_Latn', id: 'ind_Latn', th: 'tha_Thai', he: 'heb_Hebr',
};
export const NLLB_TARGETS = new Set(['ko', 'tr', 'th']);

/** Цепочка шагов перевода src → tgt. */
export function route(src, tgt) {
  if (src === tgt) return [];
  if (NLLB_TARGETS.has(tgt)) {
    return [{ id: NLLB_ID, prefix: '', local: false, nllb: { src_lang: NLLB[src], tgt_lang: NLLB[tgt] } }];
  }
  const d = DIRECT[`${src}>${tgt}`];
  if (d) return [d];
  if (src === 'en') return [FROM_EN[tgt]];
  if (tgt === 'en') return [TO_EN[src]];
  return [TO_EN[src], FROM_EN[tgt]];
}

/** Все модели, нужные для разговора между a и b (в обе стороны). */
export function modelsForPair(a, b) {
  const all = [...route(a, b), ...route(b, a)];
  const seen = new Set();
  return all.filter((m) => m && !seen.has(m.id) && seen.add(m.id));
}
