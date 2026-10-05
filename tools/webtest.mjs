// Автопроверка веб-версии в движках Safari (WebKit) и Chrome (Chromium):
// распознавание речи, перевод, работа без интернета, скриншоты экранов iPhone.
import { createRequire } from 'node:module';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';

const require = createRequire(path.join(process.cwd(), 'tools', 'webbuild', 'package.json'));
const { chromium, webkit, devices } = require('playwright');

const SITE = path.join(process.cwd(), 'site');
const OUT = path.join(process.cwd(), 'webtest-out');
fs.mkdirSync(OUT, { recursive: true });

const TYPES = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.json': 'application/json', '.wasm': 'application/wasm', '.png': 'image/png', '.wav': 'audio/wav',
  '.webmanifest': 'application/manifest+json', '.onnx': 'application/octet-stream', '.txt': 'text/plain',
};
const server = http.createServer((req, res) => {
  let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
  if (p.endsWith('/')) p += 'index.html';
  const f = path.join(SITE, p);
  if (!f.startsWith(SITE) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) {
    res.writeHead(404);
    return res.end('not found');
  }
  res.writeHead(200, { 'Content-Type': TYPES[path.extname(f)] || 'application/octet-stream' });
  fs.createReadStream(f).pipe(res);
});
await new Promise((r) => server.listen(8080, r));
const URL0 = 'http://localhost:8080/';

const voiceCases = [
  { src: 'ru', tgt: 'fi', wav: 'test/ru.wav' },
  { src: 'fi', tgt: 'ru', wav: 'test/fi.wav' },
  { src: 'bg', tgt: 'ru', wav: 'test/bg.wav' },
  { src: 'en', tgt: 'es', wav: 'test/en.wav' },
  { src: 'de', tgt: 'fr', wav: 'test/de.wav' },
  { src: 'es', tgt: 'de', wav: 'test/es.wav' },
  { src: 'fr', tgt: 'ru', wav: 'test/fr.wav' },
  { src: 'uk', tgt: 'ru', wav: 'test/uk.wav' },
  { src: 'ja', tgt: 'en', wav: 'test/ja.wav' },
  { src: 'tr', tgt: 'de', wav: 'test/tr.wav' },
  { src: 'pl', tgt: 'en', wav: 'test/pl.wav' },
  { src: 'it', tgt: 'es', wav: 'test/it.wav' },
];
const textCases = ['ja', 'ko', 'tr', 'pl', 'th', 'pt', 'ar', 'zh', 'he', 'el', 'et', 'bg', 'hi', 'vi', 'ro', 'cs']
  .map((tgt) => ({ src: 'en', tgt, text: 'Good morning, where is the nearest pharmacy?' }))
  .concat([
    { src: 'ru', tgt: 'bg', text: 'Добрый день! Сколько стоит билет до центра?' },
    { src: 'bg', tgt: 'fi', text: 'Благодаря, много сте любезни.' },
    { src: 'fi', tgt: 'bg', text: 'Kiitos paljon, olet todella ystävällinen.' },
  ]);

const report = {};
const withTimeout = (p, ms) => Promise.race([p, new Promise((r) => setTimeout(() => r(null), ms))]);
const log = (...a) => { console.log(...a); fs.appendFileSync(path.join(OUT, 'log.txt'), a.join(' ') + '\n'); };

async function runEngine(name, type, device) {
  log(`\n===== ${name} =====`);
  const browser = await type.launch();
  const ctx = await browser.newContext({ ...device, locale: 'ru-RU' });
  const page = await ctx.newPage();
  page.on('console', (m) => { if (m.type() === 'error') log(`[console.${m.type()}]`, m.text().slice(0, 300)); });
  page.on('pageerror', (e) => log('[pageerror]', e.message));
  await page.goto(URL0);
  await page.waitForFunction(() => typeof window.__selftest === 'function', null, { timeout: 60000 });
  await page.evaluate(() => navigator.serviceWorker && navigator.serviceWorker.ready);
  page.setDefaultTimeout(0);

  const cases = name === 'chromium' ? voiceCases.concat(textCases) : voiceCases.slice(0, 4).concat(textCases.slice(0, 4));
  const t0 = Date.now();
  const r = { results: [] };
  for (const c of cases) {
    const one = await withTimeout(page.evaluate((cc) => window.__selftest([cc]), c), 300000);
    const x = one ? one.results[0] : { ...c, error: 'TIMEOUT 300s' };
    if (one && !r.i18n) r.i18n = one.i18n;
    log(`[${x.src}->${x.tgt}] ${x.error ? 'ERROR ' + x.error : ''}`);
    if (x.wav) log(`   heard (${x.asrLoadMs}+${x.asrRunMs} ms): ${x.heard}`);
    else log(`   text: ${x.text}`);
    log(`   translation (${x.mtMs} ms): ${x.translation}`);
    r.results.push(x);
    if (!one) break;
  }
  log(`selftest done in ${((Date.now() - t0) / 1000).toFixed(0)}s; i18n problems: ${JSON.stringify(r.i18n)}`);
  report[name] = r;

  // Точное распознавание (только Chromium — для сравнения)
  if (name === 'chromium') {
    const acc = (await withTimeout(page.evaluate((c) => window.__selftest(c),
      voiceCases.slice(0, 3).map((c) => ({ ...c, quality: 'accurate' }))), 900000)) || { results: [{ error: 'TIMEOUT' }] };
    for (const x of acc.results) log(`[accurate ${x.src}] ${x.error || ''} (${x.asrLoadMs}+${x.asrRunMs} ms): ${x.heard}`);
    report[name + '-accurate'] = acc;
  }

  // Без интернета: перезагрузка страницы и повторный перевод
  await ctx.setOffline(true);
  await page.reload();
  await page.waitForFunction(() => typeof window.__selftest === 'function', null, { timeout: 60000 });
  const off = (await withTimeout(page.evaluate((c) => window.__selftest(c), [voiceCases[0], voiceCases[1], voiceCases[2]]), 600000)) || { results: [{ error: 'TIMEOUT' }] };
  for (const x of off.results) log(`[OFFLINE ${x.src}->${x.tgt}] ${x.error || 'ok'} | ${x.heard} => ${x.translation}`);
  report[name + '-offline'] = off;
  const st = await page.evaluate(() => window.__status('ru', 'fi'));
  log('[OFFLINE status ru/fi]', JSON.stringify(st));
  await ctx.setOffline(false);
  await browser.close();
}

async function screenshots() {
  const browser = await webkit.launch();
  for (const [loc, tag] of [['en-US', 'en'], ['ru-RU', 'ru'], ['fi-FI', 'fi'], ['ar-SA', 'ar'], ['ja-JP', 'ja'], ['bg-BG', 'bg']]) {
    const ctx = await browser.newContext({ ...devices['iPhone 15'], locale: loc });
    const page = await ctx.newPage();
    await page.goto(URL0);
    await page.waitForTimeout(2500);
    await page.screenshot({ path: path.join(OUT, `iphone-${tag}-main.png`) });
    if (tag === 'ru' || tag === 'en') {
      await page.click('#setBtn');
      await page.waitForTimeout(500);
      await page.screenshot({ path: path.join(OUT, `iphone-${tag}-settings.png`) });
      await page.click('#setClose');
      await page.click('#modeBtn');
      await page.waitForTimeout(400);
      await page.screenshot({ path: path.join(OUT, `iphone-${tag}-face.png`) });
    }
    await ctx.close();
  }
  await browser.close();
}

try {
  await screenshots();
  await runEngine('chromium', chromium, devices['Pixel 7']);
  await runEngine('webkit', webkit, devices['iPhone 15']);
} catch (e) {
  log('FATAL', e.stack || e);
  process.exitCode = 1;
} finally {
  fs.writeFileSync(path.join(OUT, 'report.json'), JSON.stringify(report, null, 2));
  server.close();
}
