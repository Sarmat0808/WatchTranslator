// Мост между приложением Android и движком перевода (тот же, что в веб-версии).
import { route, modelsForPair } from './engine-routes.js';

const MODELS = 'https://sarmat0808.github.io/WatchTranslator/models/';
const worker = new Worker(new URL('./worker.js?models=' + encodeURIComponent(MODELS), import.meta.url), {
  type: 'module',
});

let seq = 0;
const pending = new Map();
worker.onmessage = (e) => {
  const m = e.data;
  const p = pending.get(m.reqId);
  if (!p) return;
  if (m.type === 'progress') {
    if (p.androidId) AndroidBridge.onProgress(p.androidId, m.loaded || 0, m.total || 0);
    return;
  }
  pending.delete(m.reqId);
  if (m.error) p.reject(new Error(m.error));
  else p.resolve(m);
};

function call(cmd, data, androidId) {
  const reqId = ++seq;
  return new Promise((resolve, reject) => {
    pending.set(reqId, { resolve, reject, androidId });
    worker.postMessage({ reqId, cmd, ...data });
  });
}

// Модель NLLB (корейский, турецкий, тайский) запрещено использовать в платных приложениях —
// для этих языков приложение переводит через Google ML Kit.
const allowed = (src, tgt) => route(src, tgt).every((s) => s && !s.nllb);
const specsFor = (a, b) => modelsForPair(a, b).filter((s) => !s.nllb);

window.mt = async (id, cmd, args) => {
  const reply = (obj) => AndroidBridge.onResult(id, JSON.stringify(obj));
  try {
    if (cmd === 'translate') {
      if (!allowed(args.src, args.tgt)) return reply({ fallback: true });
      const r = await call('translate', { text: args.text, src: args.src, tgt: args.tgt }, id);
      reply({ text: r.text, ms: Math.round(r.runMs || 0) });
    } else if (cmd === 'status') {
      if (!allowed(args.a, args.b) || !allowed(args.b, args.a)) return reply({ supported: false });
      const r = await call('status', { specs: specsFor(args.a, args.b), quality: null }, id);
      reply({ supported: true, ready: r.mt.every(Boolean) });
    } else if (cmd === 'prepare') {
      await call('prepare', { specs: specsFor(args.a, args.b), quality: null }, id);
      reply({ ok: true });
    } else {
      reply({ error: 'unknown command' });
    }
  } catch (e) {
    reply({ error: String(e && e.message ? e.message : e) });
  }
};

AndroidBridge.onReady();
