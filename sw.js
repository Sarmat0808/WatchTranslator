// Сервис-воркер: приложение открывается и работает без интернета.
const VERSION = '4a38890-muwn3fbk';
const SHELL = 'shell-' + VERSION;
const MODELS = 'models-v1';

const FILES = [
  "./app.js",
  "./audio.js",
  "./build-id.txt",
  "./camera.js",
  "./engine-routes.js",
  "./i18n.js",
  "./icons/apple-touch-icon.png",
  "./icons/icon-192.png",
  "./icons/icon-512.png",
  "./icons/icon-maskable-512.png",
  "./index.html",
  "./manifest.webmanifest",
  "./ort/ort-wasm-simd-threaded.jsep.mjs",
  "./ort/ort-wasm-simd-threaded.jsep.wasm",
  "./ort/ort-wasm-simd-threaded.mjs",
  "./ort/ort-wasm-simd-threaded.wasm",
  "./style.css",
  "./vendor/transformers.js",
  "./worker.js",
  "./"
];

self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(SHELL).then((c) => c.addAll(FILES.map((f) => new Request(f, { cache: 'reload' }))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k.startsWith('shell-') && k !== SHELL).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return; // модели с Hugging Face кэширует сама библиотека

  // Наши сконвертированные модели: один раз скачали — дальше всегда из кэша
  if (/\/(models|tess|tess-core)\/|tesseract|franc/.test(url.pathname)) {
    e.respondWith(
      caches.open(MODELS).then(async (c) => {
        const hit = await c.match(req, { ignoreSearch: true });
        if (hit) return hit;
        const res = await fetch(req);
        if (res.ok) c.put(req, res.clone());
        return res;
      })
    );
    return;
  }

  // Файлы приложения: из кэша, в фоне обновляем
  e.respondWith(
    caches.open(SHELL).then(async (c) => {
      const hit = await c.match(req, { ignoreSearch: true });
      const net = fetch(req)
        .then((res) => {
          if (res.ok && url.pathname.indexOf('/models/') < 0) c.put(req, res.clone());
          return res;
        })
        .catch(() => null);
      if (hit) return hit;
      const res = await net;
      if (res) return res;
      if (req.mode === 'navigate') return (await c.match('./index.html')) || Response.error();
      return Response.error();
    })
  );
});
