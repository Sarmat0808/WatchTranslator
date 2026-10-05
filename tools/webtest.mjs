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
// как в приложении: для этих языков берётся точное распознавание
const ACC = new Set(['fi', 'bg', 'uk', 'et', 'el', 'he', 'th', 'hu', 'cs', 'ro', 'da', 'sv', 'hi', 'vi', 'id', 'ar', 'tr', 'ko']);
for (const c of voiceCases) c.quality = ACC.has(c.src) || ACC.has(c.tgt) ? 'accurate' : 'fast';
const textCases = ['ja', 'ko', 'tr', 'pl', 'th', 'pt', 'ar', 'zh', 'he', 'el', 'et', 'bg', 'hi', 'vi', 'ro', 'cs']
  .map((tgt) => ({ src: 'en', tgt, text: 'Good morning, where is the nearest pharmacy?' }))
  .concat([
    { src: 'ru', tgt: 'bg', text: 'Добрый день! Сколько стоит билет до центра?' },
    { src: 'bg', tgt: 'fi', text: 'Благодаря, много сте любезни.' },
    { src: 'fi', tgt: 'bg', text: 'Kiitos paljon, olet todella ystävällinen.' },
  ]);

const isolated = [
  { src: 'bg', tgt: 'en', text: 'Добро утро, къде е най-близката аптека?', fresh: true },
  { src: 'en', tgt: 'bg', text: 'Good morning, where is the nearest pharmacy?', fresh: true },
  { src: 'en', tgt: 'et', text: 'Good morning, where is the nearest pharmacy?', fresh: true },
  { src: 'en', tgt: 'el', text: 'Good morning, where is the nearest pharmacy?', fresh: true },
  { src: 'en', tgt: 'he', text: 'Good morning, where is the nearest pharmacy?', fresh: true },
  { src: 'en', tgt: 'pl', text: 'Good morning, where is the nearest pharmacy?', fresh: true },
  { src: 'en', tgt: 'ja', text: 'Good morning, where is the nearest pharmacy?', fresh: true },
  { src: 'en', tgt: 'tr', text: 'Good morning, where is the nearest pharmacy?', fresh: true },
  { src: 'en', tgt: 'ko', text: 'Good morning, where is the nearest pharmacy?', fresh: true },
  { src: 'ru', tgt: 'ko', text: 'Доброе утро, где ближайшая аптека?', fresh: true },
  { src: 'fi', tgt: 'tr', text: 'Hyvää huomenta, missä on lähin apteekki?', fresh: true },
  { src: 'en', tgt: 'th', text: 'Good morning, where is the nearest pharmacy?', fresh: true },
];
const report = {};
// Сторож: тест никогда не висит дольше 65 минут
const watchdog = setTimeout(() => {
  console.error('WATCHDOG: test took too long, exiting');
  try { fs.writeFileSync(path.join(OUT, 'report.json'), JSON.stringify(report, null, 2)); } catch (_) {}
  process.exit(2);
}, 65 * 60 * 1000);
const browsers = [];
const withTimeout = (p, ms) => Promise.race([p, new Promise((r) => setTimeout(() => r(null), ms))]);
const log = (...a) => { console.log(...a); fs.appendFileSync(path.join(OUT, 'log.txt'), a.join(' ') + '\n'); };

async function runEngine(name, type, device) {
  log(`\n===== ${name} =====`);
  const browser = await type.launch();
  browsers.push(browser);
  const ctx = await browser.newContext({ ...device, locale: 'ru-RU' });
  const page = await ctx.newPage();
  page.on('console', (m) => { if (m.type() === 'error') log(`[console.${m.type()}]`, m.text().slice(0, 300)); });
  page.on('pageerror', (e) => log('[pageerror]', e.message));
  await page.goto(URL0);
  await page.waitForFunction(() => typeof window.__selftest === 'function', null, { timeout: 60000 });
  await page.evaluate(() => navigator.serviceWorker && navigator.serviceWorker.ready);
  await page.reload();
  await page.waitForFunction(() => typeof window.__selftest === 'function', null, { timeout: 60000 });
  log('controlled by SW:', await page.evaluate(() => !!navigator.serviceWorker.controller));
  page.setDefaultTimeout(0);

  const cases = name === 'chromium'
    ? isolated.concat(voiceCases, textCases)
    : isolated.slice(0, 2).concat(voiceCases.slice(0, 4), textCases.slice(0, 4));
  const t0 = Date.now();
  const r = { results: [] };
  for (const c of cases) {
    const one = await withTimeout(page.evaluate((cc) => window.__selftest([cc], { fresh: !!cc.fresh }), c), 300000);
    const x = one ? one.results[0] : { ...c, error: 'TIMEOUT 300s' };
    if (one && !r.i18n) r.i18n = one.i18n;
    log(`[${x.src}->${x.tgt}] ${x.error ? 'ERROR ' + x.error : ''}`);
    if (x.wav) log(`   heard [${x.quality}] (${x.asrLoadMs}+${x.asrRunMs} ms): ${x.heard}`);
    else log(`   text: ${x.text}`);
    log(`   translation (${x.mtMs} ms): ${x.translation}`);
    r.results.push(x);
    if (!one) break;
    if (x.error && /ENGINE_CRASH/.test(x.error)) log('   (engine crashed — app restarts it automatically)');
  }
  log(`selftest done in ${((Date.now() - t0) / 1000).toFixed(0)}s; i18n problems: ${JSON.stringify(r.i18n)}`);
  report[name] = r;

  // Точное распознавание (только Chromium — для сравнения)
  if (name === 'chromium') {
    const acc = (await withTimeout(page.evaluate((c) => window.__selftest(c),
      voiceCases.slice(0, 3).map((c) => ({ ...c, quality: 'precise' }))), 900000)) || { results: [{ error: 'TIMEOUT' }] };
    for (const x of acc.results) log(`[precise ${x.src}] ${x.error || ''} (${x.asrLoadMs}+${x.asrRunMs} ms): ${x.heard} => ${x.translation}`);
    report[name + '-accurate'] = acc;
  }

  await browser.close();
}

/** Как у реального пользователя: одна пара языков, скачали — и работаем без интернета. */
async function runOffline(name, type, device) {
  log(`\n===== OFFLINE ${name} =====`);
  const browser = await type.launch();
  browsers.push(browser);
  const ctx = await browser.newContext({ ...device, locale: 'ru-RU' });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => log('[pageerror]', e.message));
  await page.goto(URL0);
  await page.waitForFunction(() => typeof window.__selftest === 'function', null, { timeout: 60000 });
  await page.evaluate(() => navigator.serviceWorker && navigator.serviceWorker.ready);
  await page.reload();
  await page.waitForFunction(() => typeof window.__selftest === 'function', null, { timeout: 60000 });
  page.setDefaultTimeout(0);
  const pair = [voiceCases[0], voiceCases[1]]; // ru->fi, fi->ru (точное распознавание)
  const on = (await withTimeout(page.evaluate((c) => window.__selftest(c), pair), 900000)) || { results: [{ error: 'TIMEOUT' }] };
  for (const x of on.results) log(`[online ${x.src}->${x.tgt}] ${x.error || 'ok'} | ${x.heard} => ${x.translation}`);
  const dump = () => page.evaluate(async () => {
    const out = {};
    for (const n of await caches.keys()) out[n] = (await (await caches.open(n)).keys()).length;
    out.estimateMB = navigator.storage && navigator.storage.estimate ? Math.round((await navigator.storage.estimate()).usage / 1048576) : null;
    return out;
  });
  log('[caches]', JSON.stringify(await dump()));
  await ctx.setOffline(true);
  try {
    await page.reload();
  } catch (e) {
    log('reload offline failed in this engine:', e.message.split('\n')[0]);
    await browser.close();
    return;
  }
  await page.waitForFunction(() => typeof window.__selftest === 'function', null, { timeout: 60000 });
  log('[caches offline]', JSON.stringify(await dump()));
  log('[status offline]', JSON.stringify(await page.evaluate(() => window.__status('ru', 'fi', 'accurate'))));
  const off = (await withTimeout(page.evaluate((c) => window.__selftest(c), pair), 600000)) || { results: [{ error: 'TIMEOUT' }] };
  for (const x of off.results) log(`[OFFLINE ${x.src}->${x.tgt}] ${x.error || 'ok'} | ${x.heard} => ${x.translation}`);
  report[name + '-offline'] = off;
  await browser.close();
}

async function screenshots() {
  const browser = await webkit.launch();
  browsers.push(browser);
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
  await runOffline('chromium', chromium, devices['Pixel 7']);
  await runOffline('webkit', webkit, devices['iPhone 15']);
} catch (e) {
  log('FATAL', e.stack || e);
  process.exitCode = 1;
} finally {
  fs.writeFileSync(path.join(OUT, 'report.json'), JSON.stringify(report, null, 2));
  for (const b of browsers) await Promise.race([b.close().catch(() => {}), new Promise((r) => setTimeout(r, 10000))]);
  server.closeAllConnections?.();
  server.close();
  clearTimeout(watchdog);
  // Явный выход: не ждём зависших запросов браузера
  process.exit(process.exitCode || 0);
}
