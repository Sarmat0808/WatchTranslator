// Перевод с камеры для iPhone/веб-версии — полностью на устройстве:
// распознавание текста (Tesseract), определение языка (franc), перевод (тот же движок).
import { uiLang, langName } from './i18n.js';

const base = new URL('./', import.meta.url).href;

// Коды языков Tesseract и franc (ISO 639-3)
const TESS = {
  en: 'eng', fi: 'fin', sv: 'swe', de: 'deu', es: 'spa', fr: 'fra', it: 'ita', pt: 'por', pl: 'pol',
  tr: 'tur', nl: 'nld', ro: 'ron', cs: 'ces', da: 'dan', hu: 'hun', vi: 'vie', id: 'ind', et: 'est',
  ru: 'rus', bg: 'bul', uk: 'ukr', el: 'ell', ar: 'ara', he: 'heb', zh: 'chi_sim', ja: 'jpn', ko: 'kor',
  hi: 'hin', th: 'tha',
};
const FRANC = {
  eng: 'en', fin: 'fi', swe: 'sv', deu: 'de', spa: 'es', fra: 'fr', ita: 'it', por: 'pt', pol: 'pl',
  tur: 'tr', nld: 'nl', ron: 'ro', ces: 'cs', dan: 'da', hun: 'hu', vie: 'vi', ind: 'id', est: 'et',
  rus: 'ru', bul: 'bg', ukr: 'uk', ell: 'el', arb: 'ar', heb: 'he', cmn: 'zh', jpn: 'ja', kor: 'ko',
  hin: 'hi', tha: 'th',
};

// Тексты экрана камеры (на остальных языках интерфейса — по-английски)
const W = {
  en: ['Camera', 'Take photo', 'Gallery', 'Photo', 'Text', 'Reading text…', 'No text found — move closer and hold steady', 'Original', 'Auto', 'Translating', 'Copied'],
  ru: ['Камера', 'Снять', 'Галерея', 'Фото', 'Текст', 'Читаю текст…', 'Текст не найден — поднесите ближе и держите ровно', 'Оригинал', 'Авто', 'Перевожу', 'Скопировано'],
  fi: ['Kamera', 'Ota kuva', 'Galleria', 'Kuva', 'Teksti', 'Luen tekstiä…', 'Tekstiä ei löytynyt — tuo lähemmäs ja pidä vakaana', 'Alkuperäinen', 'Auto', 'Käännän', 'Kopioitu'],
  bg: ['Камера', 'Снимай', 'Галерия', 'Снимка', 'Текст', 'Чета текста…', 'Не е открит текст — приближете и задръжте неподвижно', 'Оригинал', 'Авто', 'Превеждам', 'Копирано'],
  uk: ['Камера', 'Зняти', 'Галерея', 'Фото', 'Текст', 'Читаю текст…', 'Текст не знайдено — піднесіть ближче й тримайте рівно', 'Оригінал', 'Авто', 'Перекладаю', 'Скопійовано'],
  de: ['Kamera', 'Foto', 'Galerie', 'Foto', 'Text', 'Lese Text…', 'Kein Text gefunden — näher heran und ruhig halten', 'Original', 'Auto', 'Übersetze', 'Kopiert'],
  es: ['Cámara', 'Hacer foto', 'Galería', 'Foto', 'Texto', 'Leyendo texto…', 'No se encontró texto: acércate y mantén firme', 'Original', 'Auto', 'Traduciendo', 'Copiado'],
  fr: ['Caméra', 'Photo', 'Galerie', 'Photo', 'Texte', 'Lecture du texte…', 'Aucun texte trouvé — rapprochez-vous et tenez stable', 'Original', 'Auto', 'Traduction', 'Copié'],
  it: ['Fotocamera', 'Scatta', 'Galleria', 'Foto', 'Testo', 'Leggo il testo…', 'Nessun testo trovato — avvicinati e tieni fermo', 'Originale', 'Auto', 'Traduco', 'Copiato'],
  pt: ['Câmera', 'Tirar foto', 'Galeria', 'Foto', 'Texto', 'Lendo texto…', 'Nenhum texto encontrado — aproxime e segure firme', 'Original', 'Auto', 'Traduzindo', 'Copiado'],
  sv: ['Kamera', 'Ta foto', 'Galleri', 'Foto', 'Text', 'Läser text…', 'Ingen text hittades — gå närmare och håll stilla', 'Original', 'Auto', 'Översätter', 'Kopierat'],
  et: ['Kaamera', 'Pildista', 'Galerii', 'Foto', 'Tekst', 'Loen teksti…', 'Teksti ei leitud — mine lähemale ja hoia paigal', 'Originaal', 'Auto', 'Tõlgin', 'Kopeeritud'],
  pl: ['Aparat', 'Zrób zdjęcie', 'Galeria', 'Zdjęcie', 'Tekst', 'Czytam tekst…', 'Nie znaleziono tekstu — podejdź bliżej i trzymaj stabilnie', 'Oryginał', 'Auto', 'Tłumaczę', 'Skopiowano'],
  tr: ['Kamera', 'Fotoğraf çek', 'Galeri', 'Fotoğraf', 'Metin', 'Metin okunuyor…', 'Metin bulunamadı — yaklaşın ve sabit tutun', 'Orijinal', 'Otomatik', 'Çevriliyor', 'Kopyalandı'],
};
const w = (i) => (W[uiLang()] || W.en)[i];

let tess = null; // { worker, langs }
let franc = null;

async function ocrWorker(langs) {
  const key = langs.join('+');
  if (tess && tess.key === key) return tess.worker;
  if (tess) {
    try { await tess.worker.terminate(); } catch (_) {}
    tess = null;
  }
  const T = await import('./vendor/tesseract.esm.min.js');
  const createWorker = T.createWorker || (T.default && T.default.createWorker);
  const worker = await createWorker(key, 1, {
    workerPath: base + 'vendor/tesseract-worker.min.js',
    corePath: base + 'vendor/tess-core/',
    langPath: base + 'tess',
    gzip: true,
    cacheMethod: 'write',
    workerBlobURL: false,
  });
  tess = { key, worker };
  return worker;
}

async function detect(text, allowed, fallback) {
  try {
    if (!franc) franc = await import('./vendor/franc.js');
    const only = allowed.map((c) => Object.keys(FRANC).find((k) => FRANC[k] === c)).filter(Boolean);
    const clean = text.replace(/[0-9/:._%?=&#@-]+/g, ' ').trim();
    if (clean.replace(/\s/g, '').length < 12) return fallback;
    const r = franc.franc(clean, { only, minLength: 8 });
    return FRANC[r] || fallback;
  } catch (_) {
    return fallback;
  }
}

/** Загрузить фото с учётом поворота, уменьшить до разумного размера. */
async function loadImage(file) {
  const bmp = await createImageBitmap(file, { imageOrientation: 'from-image' });
  const max = 2200;
  const k = Math.min(1, max / Math.max(bmp.width, bmp.height));
  const c = document.createElement('canvas');
  c.width = Math.round(bmp.width * k);
  c.height = Math.round(bmp.height * k);
  c.getContext('2d').drawImage(bmp, 0, 0, c.width, c.height);
  return c;
}

/** Рисует перевод поверх текста; шрифт подбирается, чтобы перевод поместился целиком. */
function render(photo, pieces) {
  const c = document.createElement('canvas');
  c.width = photo.width;
  c.height = photo.height;
  const g = c.getContext('2d');
  g.drawImage(photo, 0, 0);
  for (const p of pieces) {
    if (!p.tr) continue;
    const { x0, y0, x1, y1 } = p.box;
    const lineH = (y1 - y0) / p.lines;
    const pad = Math.max(2, lineH * 0.12);
    const width = x1 - x0;
    let size = lineH * 0.78;
    const minSize = Math.max(9, lineH * 0.42);
    let lines;
    for (;;) {
      g.font = `600 ${size}px -apple-system, system-ui, sans-serif`;
      lines = wrap(g, p.tr, width);
      if (lines.length * size * 1.15 <= y1 - y0 || size <= minSize) break;
      size *= 0.92;
    }
    const h = Math.max(y1 - y0, lines.length * size * 1.15) + 2 * pad;
    g.fillStyle = 'rgba(255,255,255,0.95)';
    roundRect(g, x0 - pad, y0 - pad, width + 2 * pad, h, pad * 1.5);
    g.fill();
    g.strokeStyle = 'rgba(30,136,229,0.6)';
    g.lineWidth = Math.max(1.5, c.width / 600);
    g.stroke();
    g.fillStyle = '#000';
    g.textBaseline = 'top';
    lines.forEach((ln, i) => g.fillText(ln, x0, y0 + i * size * 1.15));
  }
  return c;
}

function wrap(g, text, width) {
  const out = [];
  let cur = '';
  for (const word of text.split(/\s+/)) {
    const test = cur ? cur + ' ' + word : word;
    if (g.measureText(test).width <= width || !cur) cur = test;
    else {
      out.push(cur);
      cur = word;
    }
  }
  if (cur) out.push(cur);
  return out;
}

function roundRect(g, x, y, w2, h, r) {
  g.beginPath();
  g.moveTo(x + r, y);
  g.arcTo(x + w2, y, x + w2, y + h, r);
  g.arcTo(x + w2, y + h, x, y + h, r);
  g.arcTo(x, y + h, x, y, r);
  g.arcTo(x, y, x + w2, y, r);
  g.closePath();
}

/** Увеличение двумя пальцами, перетаскивание, двойное касание. */
function zoomable(box, target) {
  let s = 1, tx = 0, ty = 0;
  const pts = new Map();
  let start = null;
  let lastTap = 0;
  const apply = () => { target.style.transform = `translate(${tx}px,${ty}px) scale(${s})`; };
  box.style.touchAction = 'none';
  box.onpointerdown = (e) => {
    box.setPointerCapture(e.pointerId);
    pts.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pts.size === 1) {
      const now = Date.now();
      if (now - lastTap < 300) {
        if (s > 1.1) { s = 1; tx = ty = 0; } else s = 2.5;
        apply();
      }
      lastTap = now;
    }
    start = snapshot();
  };
  const snapshot = () => {
    const a = [...pts.values()];
    const d = a.length > 1 ? Math.hypot(a[0].x - a[1].x, a[0].y - a[1].y) : 0;
    const cx = a.reduce((m, p) => m + p.x, 0) / a.length;
    const cy = a.reduce((m, p) => m + p.y, 0) / a.length;
    return { d, cx, cy, s, tx, ty };
  };
  box.onpointermove = (e) => {
    if (!pts.has(e.pointerId) || !start) return;
    pts.set(e.pointerId, { x: e.clientX, y: e.clientY });
    const now = snapshot();
    if (pts.size > 1 && start.d > 0) s = Math.min(6, Math.max(1, (start.s * now.d) / start.d));
    if (s > 1) {
      tx = start.tx + (now.cx - start.cx);
      ty = start.ty + (now.cy - start.cy);
    } else { tx = ty = 0; }
    apply();
  };
  const end = (e) => {
    pts.delete(e.pointerId);
    start = pts.size ? snapshot() : null;
  };
  box.onpointerup = end;
  box.onpointercancel = end;
  return () => { s = 1; tx = ty = 0; apply(); };
}

/**
 * Открывает экран камеры.
 * ctx: { myLang, partnerLang, translate(text, src, tgt) -> Promise<string>, speak(text, lang), toast(msg) }
 */
export function openCamera(ctx) {
  const tgt = ctx.myLang;
  let src = 'auto';
  let photo = null;
  let pieces = [];
  let tab = 0;
  let font = 22;
  let showOrig = false;
  let run = 0;

  const root = document.createElement('div');
  root.className = 'cam';
  root.innerHTML = `
    <div class="cam-top">
      <button class="icon cam-back" aria-label="back">←</button>
      <div class="cam-chips"></div>
    </div>
    <div class="cam-info"></div>
    <div class="cam-stage">
      <div class="cam-empty">
        <div class="cam-big">📷</div>
        <label class="btn primary cam-shoot">${w(1)}<input type="file" accept="image/*" capture="environment" hidden></label>
        <label class="btn cam-gallery">🖼 ${w(2)}<input type="file" accept="image/*" hidden></label>
      </div>
      <div class="cam-view" hidden><canvas class="cam-canvas"></canvas></div>
      <div class="cam-text" hidden></div>
      <div class="cam-busy" hidden><span class="spinner"></span><span class="cam-busy-t"></span></div>
    </div>
    <div class="cam-bar" hidden>
      <button class="btn cam-tab" data-t="0">🖼 ${w(3)}</button>
      <button class="btn cam-tab" data-t="1">📄 ${w(4)}</button>
      <button class="icon cam-full" aria-label="fullscreen">⛶</button>
      <button class="icon cam-aminus" hidden>A−</button>
      <button class="icon cam-aplus" hidden>A+</button>
      <button class="icon cam-copy" aria-label="copy">📋</button>
      <button class="icon cam-share" aria-label="share">📤</button>
      <button class="icon cam-new" aria-label="new photo">📷</button>
    </div>
    <div class="cam-bar2" hidden>
      <button class="btn cam-orig">${w(7)}</button>
      <button class="btn cam-speak">🔊</button>
    </div>`;
  document.body.appendChild(root);
  document.body.classList.add('cam-open');
  const $ = (s) => root.querySelector(s);
  const resetZoom = zoomable($('.cam-view'), $('.cam-canvas'));

  const close = () => {
    root.remove();
    document.body.classList.remove('cam-open');
  };
  $('.cam-back').onclick = close;

  // Выбор языка текста: Авто + несколько популярных
  const chips = () => {
    const box = $('.cam-chips');
    box.innerHTML = '';
    const opts = ['auto', ...new Set([ctx.partnerLang, 'en', 'fi', 'sv', 'de'])].filter((c) => c === 'auto' || (c !== tgt && TESS[c]));
    for (const c of opts) {
      const b = document.createElement('button');
      b.className = 'chip' + (c === src ? ' on' : '');
      b.textContent = c === 'auto' ? '🔍 ' + w(8) : langName(c);
      b.onclick = () => { src = c; chips(); if (photo) analyze(); };
      box.appendChild(b);
    }
  };
  chips();

  const info = (detected, done, total) => {
    let s = (src === 'auto' && detected ? langName(detected) + ' → ' : '→ ') + langName(tgt);
    if (total && done < total) s += ` · ${w(9)} ${done}/${total}`;
    $('.cam-info').textContent = s;
  };
  info();

  const busy = (on, text = '') => {
    $('.cam-busy').hidden = !on;
    $('.cam-busy-t').textContent = text;
  };

  const showTab = () => {
    const has = !!photo && pieces.length > 0;
    $('.cam-empty').hidden = !!photo;
    $('.cam-view').hidden = !photo || tab !== 0;
    $('.cam-text').hidden = !has || tab !== 1;
    $('.cam-bar').hidden = !photo;
    $('.cam-bar2').hidden = !has || tab !== 1;
    $('.cam-full').hidden = tab !== 0;
    $('.cam-aminus').hidden = tab !== 1;
    $('.cam-aplus').hidden = tab !== 1;
    root.querySelectorAll('.cam-tab').forEach((b) => b.classList.toggle('primary', +b.dataset.t === tab));
  };

  const draw = () => {
    if (!photo) return;
    const out = render(photo, pieces);
    const cv = $('.cam-canvas');
    cv.width = out.width;
    cv.height = out.height;
    cv.getContext('2d').drawImage(out, 0, 0);
    drawText();
  };

  const drawText = () => {
    const box = $('.cam-text');
    box.innerHTML = '';
    box.style.fontSize = font + 'px';
    for (const p of pieces) {
      const d = document.createElement('div');
      d.className = 'cam-par';
      if (showOrig) {
        const o = document.createElement('div');
        o.className = 'cam-orig';
        o.textContent = p.text;
        d.appendChild(o);
      }
      const t = document.createElement('div');
      t.textContent = p.tr || '…';
      d.appendChild(t);
      box.appendChild(d);
    }
  };

  const fullText = () => pieces.map((p) => (showOrig ? `${p.text}\n→ ${p.tr}` : p.tr)).join('\n\n');

  async function analyze() {
    const my = ++run;
    pieces = [];
    tab = 0;
    resetZoom();
    showTab();
    draw();
    busy(true, w(5));
    try {
      const candidates = src === 'auto'
        ? [...new Set([ctx.partnerLang, 'en', ctx.myLang])].filter((c) => TESS[c])
        : [src];
      const worker = await ocrWorker(candidates.map((c) => TESS[c]));
      const { data } = await worker.recognize(photo, {}, { blocks: true, text: true });
      if (my !== run) return;
      const found = [];
      for (const b of data.blocks || []) {
        for (const par of b.paragraphs || []) {
          const text = (par.lines || []).map((l) => l.text.trim()).join(' ').replace(/\s+/g, ' ').trim();
          const letters = (text.match(/\p{L}/gu) || []).length;
          if (letters < 3 || (par.confidence ?? 100) < 35) continue;
          found.push({ box: par.bbox, lines: Math.max(1, (par.lines || []).length), text, tr: '' });
        }
      }
      found.sort((a, b) => (Math.floor(a.box.y0 / 40) - Math.floor(b.box.y0 / 40)) || a.box.x0 - b.box.x0);
      busy(false);
      if (!found.length) {
        ctx.toast(w(6));
        return;
      }
      let main = src;
      if (src === 'auto') main = await detect(found.map((p) => p.text).join(' '), candidates, ctx.partnerLang);
      for (const p of found) p.lang = src === 'auto' ? await detect(p.text, candidates, main) : src;
      pieces = found;
      info(main, 0, found.length);
      draw();
      showTab();
      // Сначала длинные фразы, потом короткие подписи
      const order = found.map((_, i) => i).sort((a, b) => found[b].text.length - found[a].text.length);
      let done = 0;
      for (const i of order) {
        if (my !== run) return;
        const p = found[i];
        try {
          p.tr = p.lang === tgt ? p.text : await ctx.translate(p.text, p.lang, tgt);
        } catch (_) {
          p.tr = '—';
        }
        done++;
        info(main, done, found.length);
        draw();
      }
    } catch (e) {
      console.error(e);
      busy(false);
      ctx.toast(String(e && e.message ? e.message : e));
    }
  }

  const onFile = async (input) => {
    const f = input.files && input.files[0];
    input.value = '';
    if (!f) return;
    busy(true, w(5));
    try {
      photo = await loadImage(f);
      analyze();
    } catch (e) {
      busy(false);
      ctx.toast(String(e.message || e));
    }
  };
  root.querySelectorAll('input[type=file]').forEach((i) => { i.onchange = () => onFile(i); });

  root.querySelectorAll('.cam-tab').forEach((b) => { b.onclick = () => { tab = +b.dataset.t; showTab(); }; });
  $('.cam-full').onclick = () => { root.classList.toggle('full'); resetZoom(); };
  $('.cam-aminus').onclick = () => { font = Math.max(14, font - 3); drawText(); };
  $('.cam-aplus').onclick = () => { font = Math.min(44, font + 3); drawText(); };
  $('.cam-orig').onclick = () => { showOrig = !showOrig; drawText(); };
  $('.cam-speak').onclick = () => ctx.speak(pieces.map((p) => p.tr).join('. '), tgt);
  $('.cam-copy').onclick = async () => {
    try { await navigator.clipboard.writeText(fullText()); ctx.toast(w(10)); } catch (_) {}
  };
  $('.cam-share').onclick = async () => {
    const text = fullText();
    try {
      if (tab === 0 && photo) {
        const blob = await new Promise((r) => $('.cam-canvas').toBlob(r, 'image/jpeg', 0.9));
        const file = new File([blob], 'translation.jpg', { type: 'image/jpeg' });
        if (navigator.canShare && navigator.canShare({ files: [file] })) {
          await navigator.share({ files: [file], text });
          return;
        }
      }
      if (navigator.share) await navigator.share({ text });
      else { await navigator.clipboard.writeText(text); ctx.toast(w(10)); }
    } catch (_) { /* отменили */ }
  };
  $('.cam-new').onclick = () => {
    photo = null;
    pieces = [];
    run++;
    root.classList.remove('full');
    info();
    showTab();
  };
  showTab();
}

/** Заранее скачать распознавание текста для пары языков (для работы без интернета). */
export async function prepareCamera(a, b) {
  const langs = [...new Set([b, 'en', a])].filter((c) => TESS[c]).map((c) => TESS[c]);
  await ocrWorker(langs);
  try { if (!franc) franc = await import('./vendor/franc.js'); } catch (_) {}
}

/** Для автотестов: распознать текст на картинке, определить язык и перевести. */
export async function __test(canvas, candidates, tgt, translate) {
  const worker = await ocrWorker(candidates.map((c) => TESS[c]));
  const t0 = performance.now();
  const { data } = await worker.recognize(canvas, {}, { blocks: true, text: true });
  const t1 = performance.now();
  const out = [];
  for (const b of data.blocks || []) for (const par of b.paragraphs || []) {
    const text = (par.lines || []).map((l) => l.text.trim()).join(' ').replace(/\s+/g, ' ').trim();
    if (!text) continue;
    const lang = await detect(text, candidates, candidates[0]);
    const tr = lang === tgt ? text : await translate(text, lang, tgt);
    out.push({ text, lang, tr });
  }
  return { ocrMs: Math.round(t1 - t0), pieces: out };
}
