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

/** Загружает модель (по одной за раз — так меньше пиковая память). */
function load(task, spec, reqId) {
  const key = spec.id + (spec.dtypeKey || '');
  if (cache.has(key)) return cache.get(key);
  const p = (loadChain = loadChain.then(async () => {
    env.allowLocalModels = !!spec.local;
    env.allowRemoteModels = !spec.local;
    const opts = { progress_callback: progressFor(reqId, spec.id), device: 'wasm' };
    if (spec.dtype) opts.dtype = spec.dtype;
    else opts.dtype = 'q8';
    return pipeline(task, spec.id, opts);
  }));
  cache.set(key, p);
  p.catch(() => cache.delete(key));
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
    if (spec.local) {
      const c = await caches.open('models-v1');
      return !!(await c.match(base + 'models/' + spec.id + '/config.json'));
    }
    const c = await caches.open('transformers-cache');
    return !!(await c.match(`https://huggingface.co/${spec.id}/resolve/main/config.json`));
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
      const r = await t(step.prefix + s, { max_new_tokens: 256, num_beams: 2 });
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
    reply({ error: String(err && err.message ? err.message : err) });
  }
};
