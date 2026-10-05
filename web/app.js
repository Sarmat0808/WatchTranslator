import { t, setUiLang, detectUiLang, uiLang, langName, nativeName, UI_LANGS, checkI18n } from './i18n.js';
import { LANGS, WHISPER, modelsForPair } from './engine-routes.js';
import { Recorder, parseWav, getAudioContext } from './audio.js';

// ---------- Настройки ----------
const store = {
  get(k, d) {
    try {
      const v = localStorage.getItem('pt.' + k);
      return v === null ? d : JSON.parse(v);
    } catch (_) {
      return d;
    }
  },
  set(k, v) {
    try { localStorage.setItem('pt.' + k, JSON.stringify(v)); } catch (_) {}
  },
};

const S = {
  ui: store.get('ui', 'auto'),
  a: store.get('a', null),
  b: store.get('b', null),
  autoSpeak: store.get('autoSpeak', true),
  slow: store.get('slow', false),
  quality: store.get('quality', 'fast'),
  face: store.get('face', false),
  history: store.get('history', []),
};

setUiLang(S.ui === 'auto' ? detectUiLang() : S.ui);
if (!S.a) S.a = LANGS.includes(uiLang()) ? uiLang() : 'en';
if (!S.b) S.b = S.a === 'en' ? 'es' : 'en';

const $ = (s) => document.querySelector(s);
const el = (tag, cls, text) => {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
};

// ---------- Связь с рабочим потоком ----------
const worker = new Worker(new URL('./worker.js', import.meta.url), { type: 'module' });
let reqSeq = 0;
const pending = new Map();
worker.onmessage = (e) => {
  const m = e.data;
  const p = pending.get(m.reqId);
  if (!p) return;
  if (m.type === 'progress') {
    p.onProgress && p.onProgress(m);
    return;
  }
  pending.delete(m.reqId);
  if (m.error) p.reject(new Error(m.error));
  else p.resolve(m);
};
worker.onerror = (e) => console.error('worker error', e.message);

function call(cmd, data = {}, onProgress) {
  const reqId = ++reqSeq;
  return new Promise((resolve, reject) => {
    pending.set(reqId, { resolve, reject, onProgress });
    worker.postMessage({ reqId, cmd, ...data }, data.audio ? [data.audio.buffer] : []);
  });
}

// ---------- Голос ----------
let voices = [];
function loadVoices() {
  voices = window.speechSynthesis ? speechSynthesis.getVoices() : [];
}
if (window.speechSynthesis) {
  loadVoices();
  speechSynthesis.onvoiceschanged = loadVoices;
}

function pickVoice(code) {
  const list = voices.filter((v) => v.lang && v.lang.toLowerCase().replace('_', '-').startsWith(code));
  if (!list.length) return null;
  const score = (v) =>
    (/(premium|enhanced|siri|neural|natural)/i.test(v.name) ? 4 : 0) + (v.localService ? 2 : 0) + (v.default ? 1 : 0);
  return list.sort((x, y) => score(y) - score(x))[0];
}

let speechUnlocked = false;
function unlockSpeech() {
  if (speechUnlocked || !window.speechSynthesis) return;
  speechUnlocked = true;
  try {
    const u = new SpeechSynthesisUtterance(' ');
    u.volume = 0;
    speechSynthesis.speak(u);
  } catch (_) {}
  try { getAudioContext().resume(); } catch (_) {}
}

function speak(text, code) {
  if (!window.speechSynthesis || !text) return;
  if (!voices.length) loadVoices();
  const v = pickVoice(code);
  if (!v) {
    toast(t('noVoice') + ' (' + langName(code) + ')');
    return;
  }
  speechSynthesis.cancel();
  const u = new SpeechSynthesisUtterance(text);
  u.voice = v;
  u.lang = v.lang;
  u.rate = S.slow ? 0.75 : 0.95;
  speechSynthesis.speak(u);
}

// ---------- Сообщения ----------
let toastTimer;
function toast(msg, ms = 4500) {
  const s = $('#status');
  s.textContent = msg;
  s.hidden = !msg;
  clearTimeout(toastTimer);
  if (msg && ms) toastTimer = setTimeout(() => { s.hidden = true; }, ms);
}

function friendlyError(err) {
  const m = String(err && err.message ? err.message : err);
  if (/fetch|network|load|404|offline/i.test(m) && !navigator.onLine) return t('notDownloaded');
  if (/fetch|network/i.test(m)) return t('notDownloaded');
  return t('error');
}

// ---------- Готовность к офлайну ----------
let pairReady = false;
async function refreshReady() {
  const specs = modelsForPair(S.a, S.b);
  try {
    const r = await call('status', { specs, quality: S.quality });
    pairReady = r.asr && r.mt.every(Boolean);
  } catch (_) {
    pairReady = false;
  }
  renderPrepare();
  if (pairReady) call('warm', { specs, quality: S.quality }).catch(() => {});
}

function approxMb() {
  const asr = S.quality === 'accurate' ? 250 : 135;
  return asr + modelsForPair(S.a, S.b).length * 75;
}

let downloading = false;
async function downloadPair() {
  if (downloading) return;
  downloading = true;
  renderPrepare();
  try {
    if (navigator.storage && navigator.storage.persist) await navigator.storage.persist();
  } catch (_) {}
  const specs = modelsForPair(S.a, S.b);
  try {
    await call('prepare', { specs, quality: S.quality }, (p) => updateProgress(p));
    downloading = false;
    await refreshReady();
    toast(t('readyOffline'));
  } catch (e) {
    downloading = false;
    renderPrepare();
    toast(friendlyError(e), 8000);
  }
}

let progressState = { loaded: 0, total: 0 };
function updateProgress(p) {
  progressState = p;
  const bar = $('#prepBar');
  const txt = $('#prepTxt');
  if (bar && p.total) {
    bar.style.width = Math.min(100, (p.loaded / p.total) * 100).toFixed(0) + '%';
    txt.textContent = `${t('downloading')} ${(p.loaded / 1048576).toFixed(0)} / ${(p.total / 1048576).toFixed(0)} MB`;
  }
  const busy = $('#busyTxt');
  if (busy && !$('#busy').hidden && p.total) {
    busy.textContent = `${t('loading')} ${Math.round((p.loaded / p.total) * 100)}%`;
  }
}

function renderPrepare() {
  const box = $('#prepare');
  box.innerHTML = '';
  if (pairReady) {
    box.hidden = true;
    return;
  }
  box.hidden = false;
  box.append(el('div', 'prep-title', t('prepareTitle')));
  box.append(el('div', 'prep-text', `${t('prepareText')} (~${approxMb()} MB)`));
  if (downloading) {
    const track = el('div', 'bar');
    const fill = el('div', 'bar-fill');
    fill.id = 'prepBar';
    track.append(fill);
    box.append(track);
    const txt = el('div', 'prep-text', t('downloading'));
    txt.id = 'prepTxt';
    box.append(txt);
    updateProgress(progressState);
  } else {
    const btn = el('button', 'btn primary', t('download'));
    btn.onclick = () => { unlockSpeech(); downloadPair(); };
    box.append(btn);
  }
}

// ---------- Языки ----------
function fillSelect(sel, value) {
  sel.innerHTML = '';
  const sorted = [...LANGS].sort((x, y) => langName(x).localeCompare(langName(y), uiLang()));
  for (const code of sorted) {
    const o = el('option', null, langName(code));
    o.value = code;
    if (code === value) o.selected = true;
    sel.append(o);
  }
}

function setLangs(a, b) {
  if (a === b) b = a === S.a ? S.b : S.a;
  S.a = a;
  S.b = b;
  store.set('a', a);
  store.set('b', b);
  fillSelect($('#langA'), S.a);
  fillSelect($('#langB'), S.b);
  renderMics();
  renderFace();
  refreshReady();
}

// ---------- Разговор ----------
function addExchange(x) {
  S.history.push(x);
  if (S.history.length > 100) S.history.splice(0, S.history.length - 100);
  store.set('history', S.history);
  renderChat();
  renderFace();
}

function bubble(x) {
  const b = el('div', 'bubble ' + (x.side === 'A' ? 'me' : 'them'));
  b.append(el('div', 'orig', x.text));
  const row = el('div', 'tr-row');
  const tr = el('div', 'tr', x.tr);
  tr.lang = x.tgt;
  const sp = el('button', 'icon', '🔊');
  sp.setAttribute('aria-label', 'speak');
  sp.onclick = () => { unlockSpeech(); speak(x.tr, x.tgt); };
  row.append(tr, sp);
  b.append(row);
  b.onclick = (ev) => {
    if (ev.target === sp) return;
    navigator.clipboard && navigator.clipboard.writeText(x.tr).then(() => toast(t('copied'), 1500)).catch(() => {});
  };
  return b;
}

function renderChat() {
  const c = $('#chat');
  c.innerHTML = '';
  if (!S.history.length) {
    const e = el('div', 'empty');
    e.append(el('div', 'empty-icon', '🎙️'), el('div', 'empty-title', t('tapMic')), el('div', 'empty-sub', t('worksOffline')));
    c.append(e);
    return;
  }
  for (const x of S.history) c.append(bubble(x));
  requestAnimationFrame(() => { c.scrollTop = c.scrollHeight; });
}

function renderMics() {
  $('#micA .mic-lang').textContent = langName(S.a);
  $('#micB .mic-lang').textContent = langName(S.b);
  $('#micA .mic-who').textContent = t('me');
  $('#micB .mic-who').textContent = t('partner');
  $('#text').placeholder = `${t('typeHere')} (${langName(S.a)})`;
}

function renderFace() {
  const last = S.history[S.history.length - 1];
  for (const side of ['A', 'B']) {
    const half = $(side === 'A' ? '#halfA' : '#halfB');
    const code = side === 'A' ? S.a : S.b;
    const msg = half.querySelector('.half-msg');
    msg.className = 'half-msg';
    if (!last) msg.textContent = langName(code);
    else if (last.side !== side) {
      msg.textContent = last.tr;
      msg.classList.add('big');
    } else msg.textContent = last.text;
    half.querySelector('.mic-lang').textContent = langName(code);
  }
}

function applyMode() {
  document.body.classList.toggle('face', S.face);
  $('#modeBtn').textContent = S.face ? '💬' : '👥';
  renderFace();
}

// ---------- Микрофон ----------
let recorder = null;
let working = false;

async function listen(side) {
  unlockSpeech();
  if (working) return;
  const src = side === 'A' ? S.a : S.b;
  const tgt = side === 'A' ? S.b : S.a;
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    toast(t('micDenied'));
    return;
  }
  if (!WHISPER.has(src)) return;
  working = true;
  if (window.speechSynthesis) speechSynthesis.cancel();
  recorder = new Recorder();
  showRec(side, true);
  let audio;
  try {
    audio = await recorder.record((lvl) => setLevel(lvl));
  } catch (e) {
    showRec(side, false);
    working = false;
    toast(t('micDenied'), 6000);
    return;
  }
  showRec(side, false);
  if (!audio) { working = false; return; }
  if (audio.length < 16000 / 3) {
    working = false;
    toast(t('noSpeech'));
    return;
  }
  await process(side, src, tgt, () => call('asr', { audio, lang: src, quality: S.quality }, updateProgress));
}

async function process(side, src, tgt, getText) {
  working = true;
  showBusy(t('recognizing'));
  try {
    const r = await getText();
    const text = (r.text || '').trim();
    if (!text) {
      toast(t('notUnderstood'));
      return;
    }
    showBusy(t('translating'));
    const tr = await call('translate', { text, src, tgt }, updateProgress);
    const x = { side, src, tgt, text, tr: tr.text, at: Date.now() };
    addExchange(x);
    if (S.autoSpeak) speak(x.tr, tgt);
  } catch (e) {
    console.error(e);
    toast(friendlyError(e), 7000);
  } finally {
    hideBusy();
    working = false;
  }
}

function showRec(side, on) {
  const r = $('#rec');
  r.hidden = !on;
  r.classList.toggle('b', side === 'B');
  $('#recLang').textContent = langName(side === 'A' ? S.a : S.b);
  setLevel(0);
}

function setLevel(l) {
  $('#recDot').style.transform = `scale(${1 + l * 0.6})`;
}

function showBusy(text) {
  $('#busy').hidden = false;
  $('#busyTxt').textContent = text;
}

function hideBusy() {
  $('#busy').hidden = true;
}

// ---------- Настройки ----------
function openSettings() {
  const d = $('#settings');
  const body = $('#setBody');
  body.innerHTML = '';
  const row = (label, control) => {
    const r = el('label', 'set-row');
    r.append(el('span', null, label), control);
    body.append(r);
  };
  const toggle = (val, fn) => {
    const i = el('input');
    i.type = 'checkbox';
    i.className = 'switch';
    i.checked = val;
    i.onchange = () => fn(i.checked);
    return i;
  };
  const uiSel = el('select');
  const auto = el('option', null, t('auto'));
  auto.value = 'auto';
  uiSel.append(auto);
  for (const c of UI_LANGS) {
    const o = el('option', null, nativeName(c));
    o.value = c;
    uiSel.append(o);
  }
  uiSel.value = S.ui;
  uiSel.onchange = () => {
    S.ui = uiSel.value;
    store.set('ui', S.ui);
    setUiLang(S.ui === 'auto' ? detectUiLang() : S.ui);
    renderAll();
    openSettings();
  };
  row(t('uiLanguage'), uiSel);
  row(t('autoSpeak'), toggle(S.autoSpeak, (v) => { S.autoSpeak = v; store.set('autoSpeak', v); }));
  row(t('slowSpeech'), toggle(S.slow, (v) => { S.slow = v; store.set('slow', v); }));
  const q = el('select');
  for (const [v, k] of [['fast', 'fast'], ['accurate', 'accurate']]) {
    const o = el('option', null, t(k));
    o.value = v;
    q.append(o);
  }
  q.value = S.quality;
  q.onchange = () => { S.quality = q.value; store.set('quality', q.value); refreshReady(); };
  row(t('recognition'), q);
  row(t('faceMode'), toggle(S.face, (v) => { S.face = v; store.set('face', v); applyMode(); }));
  const clr = el('button', 'btn', t('clearHistory'));
  clr.onclick = () => { S.history = []; store.set('history', []); renderChat(); renderFace(); d.close(); };
  body.append(clr);
  $('#setTitle').textContent = t('settings');
  $('#setClose').textContent = t('close');
  d.showModal();
}

// ---------- Установка на экран «Домой» ----------
function renderInstall() {
  const standalone = window.matchMedia('(display-mode: standalone)').matches || navigator.standalone;
  const box = $('#install');
  if (standalone || store.get('installDismissed', false)) {
    box.hidden = true;
    return;
  }
  const ios = /iphone|ipad|ipod/i.test(navigator.userAgent) ||
    (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  box.hidden = false;
  box.innerHTML = '';
  box.append(el('div', 'inst-title', t('installTitle')));
  box.append(el('div', 'inst-text', ios ? t('installIOS') + '  ⬆️' : t('installAndroid')));
  const x = el('button', 'icon close', '✕');
  x.onclick = () => { store.set('installDismissed', true); box.hidden = true; };
  box.append(x);
}

// ---------- Отрисовка ----------
function renderAll() {
  document.title = t('appName');
  $('#title').textContent = t('appName');
  $('#langALabel').textContent = t('myLanguage');
  $('#langBLabel').textContent = t('partnerLanguage');
  $('#recDone').textContent = t('done');
  $('#recCancel').textContent = t('cancel');
  $('#recTitle').textContent = t('listening');
  fillSelect($('#langA'), S.a);
  fillSelect($('#langB'), S.b);
  renderMics();
  renderChat();
  renderFace();
  renderPrepare();
  renderInstall();
  applyMode();
}

function bind() {
  $('#langA').onchange = (e) => setLangs(e.target.value, S.b);
  $('#langB').onchange = (e) => setLangs(S.a, e.target.value);
  $('#swap').onclick = () => setLangs(S.b, S.a);
  $('#micA').onclick = () => listen('A');
  $('#micB').onclick = () => listen('B');
  $('#faceMicA').onclick = () => listen('A');
  $('#faceMicB').onclick = () => listen('B');
  $('#recDone').onclick = () => recorder && recorder.stop();
  $('#recCancel').onclick = () => recorder && recorder.cancel();
  $('#modeBtn').onclick = () => { S.face = !S.face; store.set('face', S.face); applyMode(); };
  $('#setBtn').onclick = openSettings;
  $('#setClose').onclick = () => $('#settings').close();
  $('#sendForm').onsubmit = (e) => {
    e.preventDefault();
    unlockSpeech();
    const text = $('#text').value.trim();
    if (!text || working) return;
    $('#text').value = '';
    $('#text').blur();
    process('A', S.a, S.b, async () => ({ text }));
  };
  window.addEventListener('online', refreshReady);
}

renderAll();
bind();
refreshReady();

// Сервис-воркер: всё приложение доступно без интернета
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('./sw.js').catch((e) => console.warn('sw', e));
}

// ---------- Самопроверка (используется автоматическими тестами) ----------
window.__selftest = async (cases, opts = {}) => {
  const out = { i18n: checkI18n(), results: [] };
  for (const c of cases) {
    const r = { ...c };
    try {
      if (c.wav) {
        const buf = await (await fetch(c.wav)).arrayBuffer();
        const audio = parseWav(buf);
        const a = await call('asr', { audio, lang: c.src, quality: c.quality || 'fast' });
        r.heard = a.text;
        r.asrLoadMs = Math.round(a.loadMs);
        r.asrRunMs = Math.round(a.runMs);
      }
      const text = r.heard ?? c.text;
      const tr = await call('translate', { text, src: c.src, tgt: c.tgt });
      r.translation = tr.text;
      r.mtMs = Math.round(tr.runMs);
    } catch (e) {
      r.error = String(e.message || e);
    }
    out.results.push(r);
  }
  return out;
};
window.__status = (a, b, q) => call('status', { specs: modelsForPair(a, b), quality: q || 'fast' });
