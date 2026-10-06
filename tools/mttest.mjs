// Проверка движка перевода приложения для телефона (assets/mt) в Chromium — как во встроенном браузере Android.
import { createRequire } from 'node:module';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
const require = createRequire(path.join(process.cwd(), 'tools', 'webbuild', 'package.json'));
const { chromium } = require('playwright');
const ROOT = path.join(process.cwd(), 'mobile/src/main/assets');
const T = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.wasm': 'application/wasm' };
const server = http.createServer((q, r) => {
  const f = path.join(ROOT, decodeURIComponent(new URL(q.url, 'http://x').pathname).replace(/^\/assets\//, '/'));
  if (!fs.existsSync(f)) { r.writeHead(404); return r.end(); }
  r.writeHead(200, { 'Content-Type': T[path.extname(f)] || 'application/octet-stream' });
  fs.createReadStream(f).pipe(r);
});
await new Promise((ok) => server.listen(8090, ok));
const browser = await chromium.launch();
const page = await browser.newPage();
page.on('console', (m) => console.log('[console]', m.text().slice(0, 200)));
await page.addInitScript(() => {
  window.__res = {}; window.__ready = false;
  window.AndroidBridge = {
    onReady: () => { window.__ready = true; },
    onResult: (id, json) => { window.__res[id] = JSON.parse(json); },
    onProgress: (id, l, t) => { window.__prog = [l, t]; },
  };
});
await page.goto('http://localhost:8090/assets/mt/mt.html');
await page.waitForFunction(() => window.__ready, null, { timeout: 60000 });
const run = async (id, cmd, args) => {
  await page.evaluate(([i, c, a]) => window.mt(i, c, a), [id, cmd, args]);
  await page.waitForFunction((i) => window.__res[i], id, { timeout: 600000 });
  const r = await page.evaluate((i) => window.__res[i], id);
  console.log(cmd, JSON.stringify(args), '=>', JSON.stringify(r));
  return r;
};
try {
  await run('1', 'status', { a: 'ru', b: 'fi' });
  await run('2', 'prepare', { a: 'ru', b: 'fi' });
  await run('3', 'status', { a: 'ru', b: 'fi' });
  await run('4', 'translate', { text: 'Добрый день! Где находится ближайшая аптека?', src: 'ru', tgt: 'fi' });
  await run('5', 'translate', { text: 'Hyvää päivää. Missä on lähin bussipysäkki?', src: 'fi', tgt: 'ru' });
  await run('6', 'translate', { text: 'Добро утро, къде е най-близката аптека?', src: 'bg', tgt: 'fi' });
  await run('7', 'translate', { text: 'Hello', src: 'en', tgt: 'ko' });
  await run('8', 'status', { a: 'ru', b: 'tr' });
} catch (e) { console.log('FATAL', e.message); process.exitCode = 1; }
await browser.close(); server.close(); process.exit(process.exitCode || 0);
