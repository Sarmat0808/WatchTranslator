// Сборка веб-версии: копирует приложение, библиотеку перевода/распознавания и движок ONNX
// в папку site/, проставляет версию и список файлов для работы без интернета.
import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';

const root = process.cwd();
const web = path.join(root, 'web');
const site = path.join(root, 'site');
const nm = path.join(root, 'tools', 'webbuild', 'node_modules');

fs.rmSync(site, { recursive: true, force: true });
fs.cpSync(web, site, { recursive: true });

// 1) transformers.js (сборка со встроенным onnxruntime-web)
const tjs = path.join(nm, '@huggingface', 'transformers', 'dist');
const bundle = ['transformers.min.js', 'transformers.js'].map((f) => path.join(tjs, f)).find((f) => fs.existsSync(f));
if (!bundle) throw new Error('transformers bundle not found: ' + fs.readdirSync(tjs).join(', '));
const src = fs.readFileSync(bundle, 'utf8');
if (/from\s*["']onnxruntime-web["']/.test(src)) throw new Error('bundle imports onnxruntime-web externally');
fs.mkdirSync(path.join(site, 'vendor'), { recursive: true });
fs.copyFileSync(bundle, path.join(site, 'vendor', 'transformers.js'));
console.log('transformers:', path.basename(bundle), (src.length / 1e6).toFixed(1), 'MB');

// 2) движок ONNX (wasm) — только нужные файлы
const ortDir = path.join(site, 'ort');
fs.mkdirSync(ortDir, { recursive: true });
const ortSrcs = [path.join(nm, 'onnxruntime-web', 'dist'), tjs];
for (const d of ortSrcs) {
  if (!fs.existsSync(d)) continue;
  for (const f of fs.readdirSync(d)) {
    if (/^ort-wasm-simd-threaded(\.jsep)?\.(wasm|mjs)$/.test(f)) {
      fs.copyFileSync(path.join(d, f), path.join(ortDir, f));
    }
  }
}
// также под вложенным onnxruntime-web, если npm положил его внутрь пакета
const nested = path.join(nm, '@huggingface', 'transformers', 'node_modules', 'onnxruntime-web', 'dist');
if (fs.existsSync(nested)) {
  for (const f of fs.readdirSync(nested)) {
    if (/^ort-wasm-simd-threaded(\.jsep)?\.(wasm|mjs)$/.test(f)) fs.copyFileSync(path.join(nested, f), path.join(ortDir, f));
  }
}
console.log('ort:', fs.readdirSync(ortDir).map((f) => `${f} ${(fs.statSync(path.join(ortDir, f)).size / 1e6).toFixed(1)}MB`));

// 3) версия и список файлов приложения для сервис-воркера
let build = 'dev';
try { build = execSync('git rev-parse --short HEAD').toString().trim(); } catch (_) {}
build += '-' + Date.now().toString(36);
const files = [];
const walk = (dir, rel = '') => {
  for (const f of fs.readdirSync(dir)) {
    const p = path.join(dir, f);
    const r = rel ? rel + '/' + f : f;
    if (fs.statSync(p).isDirectory()) {
      if (r === 'models' || r === 'test') continue;
      walk(p, r);
    } else if (r !== 'sw.js') files.push('./' + r);
  }
};
walk(site);
files.push('./');
for (const f of ['sw.js', 'index.html']) {
  const p = path.join(site, f);
  let s = fs.readFileSync(p, 'utf8');
  s = s.replaceAll('__BUILD__', build).replace('__FILES__', JSON.stringify(files, null, 2));
  fs.writeFileSync(p, s);
}
fs.writeFileSync(path.join(site, '.nojekyll'), '');
console.log('build', build, 'shell files:', files.length);
