// Тяжёлая работа в отдельном потоке, чтобы интерфейс не тормозил:
// распознавание речи (Whisper) и перевод (opus-mt), всё на устройстве.
import { pipeline, env } from './vendor/transformers.js';
import { route } from './engine-routes.js';

const base = new URL('./', self.location.href).href;
env.useBrowserCache = true;
env.allowRemoteModels = true;
env.localModelPath = base + 'models/';
env.backends.onnx.wasm.wasmPaths = base + 'ort/';
env.backends.onnx.wasm.proxy = false;

export const ASR_MODELS = {
  fast: {
    id: 'onnx-community/whisper-base',
    dtype: { encoder_model: 'fp32', decoder_model_merged: 'q8' },
  },
  accurate: {
    id: 'onnx-community/whisper-small',
    dtype: { encoder_model: 'q8', decoder_model_merged: 'q8' },
  },
  // точнее, но тяжелее (кодировщик без сжатия) — проверяется в тестах
  precise: {
    id: 'onnx-community/whisper-small',
    dtype: { encoder_model: 'fp32', decoder_model_merged: 'q8' },
  },
};

const cache = new Map(); // id -> Promise<pipeline>
let loadChain = Promise.resolve();

function progressFor(reqId, label) {
  const files = new Map();
  return (p) => {
    if (p.status === 'progress' && p.file) {
      files.set(p.file, { loaded: p.loaded || 0, total: p.total || 0 });
      let loaded = 0;
      let total = 0;
      for (const f of files.values()) {
        loaded += f.loaded;
        total += f.total;
      }
      self.postMessage({ type: 'progress', reqId, label, loaded, total });
    }
  };
}

// Сколько моделей перевода держать в памяти одновременно (iPhone не любит много)
const MAX_MT = 4;
const lru = []; // ключи моделей перевода, от старых к новым

async function dispose(key) {
  const p = cache.get(key);
  cache.delete(key);
  try { const m = await p; await m.dispose(); } catch (_) {}
}

/** Загружает модель (по одной за раз — так меньше пиковая память). */
function load(task, spec, reqId) {
  const key = spec.id + (spec.dtypeKey || '');
  if (cache.has(key)) {
    if (task === 'translation') {
      const i = lru.indexOf(key);
      if (i >= 0) {
        lru.splice(i, 1);
        lru.push(key);
      }
    }
    return cache.get(key);
  }
  const p = loadChain.then(async () => {
    if (task === 'translation') {
      while (lru.length >= MAX_MT) await dispose(lru.shift());
    } else {
      // одна модель распознавания: другую выгружаем
      for (const k of [...cache.keys()]) if (k.startsWith('onnx-community/whisper') && k !== key) await dispose(k);
    }
    env.allowLocalModels = !!spec.local;
    env.allowRemoteModels = !spec.local;
    const opts = { progress_callback: progressFor(reqId, spec.id), device: 'wasm', dtype: spec.dtype || 'q8' };
    const pipe = await pipeline(task, spec.id, opts);
    if (task === 'translation') lru.push(key);
    return pipe;
  });
  loadChain = p.catch(() => {}); // ошибка одной модели не ломает следующие
  cache.set(key, p);
  p.catch(() => {
    cache.delete(key);
    const i = lru.indexOf(key);
    if (i >= 0) lru.splice(i, 1);
  });
  return p;
}

function asrSpec(quality) {
  const m = ASR_MODELS[quality] || ASR_MODELS.fast;
  return { id: m.id, dtype: m.dtype, dtypeKey: quality };
}

/** Whisper иногда повторяет фразу или выдумывает «субтитры» на тишине. */
function cleanup(text) {
  let t = (text || '').trim();
  const junk = [/^\(.*\)$/, /^\[.*\]$/, /продолжение следует/i, /субтитры/i, /thanks? for watching/i,
    /kiitos katsomisesta/i, /amara\.org/i];
  if (junk.some((r) => r.test(t))) return '';
  const parts = t.split(/(?<=[.!?…。！？])\s+/).map((s) => s.trim()).filter(Boolean);
  const out = [];
  const norm = (s) => s.toLowerCase().replace(/[.!?…,。！？]+$/u, '');
  for (const p of parts) {
    const k = norm(p);
    if (!out.some((q) => norm(q) === k || (k.length >= 8 && norm(q).startsWith(k)))) out.push(p);
  }
  return out.join(' ');
}

/** Скачана ли модель (лежит ли в кэше устройства). */
async function isCached(spec) {
  try {
    const c = await caches.open('transformers-cache');
    if (spec.local) {
      const key = base + 'models/' + spec.id + '/onnx/decoder_model_merged_quantized.onnx';
      if (await c.match(key)) return true;
      const m = await caches.open('models-v1');
      return !!(await m.match(key));
    }
    const enc = spec.dtype && spec.dtype.encoder_model === 'fp32' ? 'encoder_model.onnx' : 'encoder_model_quantized.onnx';
    return !!(await c.match(`https://huggingface.co/${spec.id}/resolve/main/onnx/${enc}`));
  } catch (_) {
    return false;
  }
}

async function translateText(text, src, tgt, reqId) {
  let cur = text;
  for (const step of route(src, tgt)) {
    if (!step) throw new Error('no-route');
    const t = await load('translation', step, reqId);
    const sentences = cur.split(/(?<=[.!?…。！？])\s+/).filter((s) => s.trim());
    const outs = [];
    for (const s of sentences.length ? sentences : [cur]) {
      const gen = { max_new_tokens: 256, num_beams: 2 };
      if (step.nllb) Object.assign(gen, step.nllb, { num_beams: 1 });
      const r = await t(step.prefix + s, gen);
      outs.push((r[0]?.translation_text || '').trim());
    }
    cur = outs.join(' ');
  }
  return cur;
}

self.onmessage = async (e) => {
  const { reqId, cmd } = e.data;
  const reply = (data) => self.postMessage({ type: 'result', reqId, ...data });
  try {
    if (cmd === 'asr') {
      const { audio, lang, quality } = e.data;
      const t0 = performance.now();
      const asr = await load('automatic-speech-recognition', asrSpec(quality), reqId);
      const t1 = performance.now();
      const r = await asr(audio, {
        language: lang,
        task: 'transcribe',
        chunk_length_s: 30,
        no_repeat_ngram_size: 4,
      });
      reply({ text: cleanup(r.text), loadMs: t1 - t0, runMs: performance.now() - t1 });
    } else if (cmd === 'translate') {
      const { text, src, tgt } = e.data;
      const t0 = performance.now();
      const out = await translateText(text, src, tgt, reqId);
      reply({ text: out, runMs: performance.now() - t0 });
    } else if (cmd === 'prepare') {
      // Скачать и подготовить всё для пары языков
      const { specs, quality } = e.data;
      if (quality) await load('automatic-speech-recognition', asrSpec(quality), reqId);
      for (const s of specs) await load('translation', s, reqId);
      reply({ ok: true });
    } else if (cmd === 'status') {
      const { specs, quality } = e.data;
      const asr = await isCached(asrSpec(quality));
      const mt = [];
      for (const s of specs) mt.push(await isCached(s));
      reply({ asr, mt });
    } else if (cmd === 'warm') {
      // Тихо загрузить в память то, что уже скачано (ничего не качаем)
      const { specs, quality } = e.data;
      try {
        if (quality && (await isCached(asrSpec(quality)))) {
          await load('automatic-speech-recognition', asrSpec(quality), reqId);
        }
        for (const s of specs) if (await isCached(s)) await load('translation', s, reqId);
      } catch (_) { /* не страшно */ }
      reply({ ok: true });
    }
  } catch (err) {
    const msg = String(err && err.message ? err.message : err);
    // Число вместо текста — внутренняя авария движка ONNX: поток нужно перезапустить
    reply({ error: /^\d+$/.test(msg) ? 'ENGINE_CRASH ' + msg : msg, crashed: /^\d+$/.test(msg) });
  }
};
