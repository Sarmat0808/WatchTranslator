#!/bin/bash
# Офлайн-распознавание текста для веб-версии: Tesseract (Apache 2.0) + franc (MIT) + языковые данные.
set -uo pipefail
cd tools/webbuild
npm install --no-audit --no-fund --no-progress tesseract.js@6 tesseract.js-core@6 franc-min@6 esbuild@0.24 < /dev/null || exit 1
LANGS="eng fin swe deu spa fra ita por pol tur nld ron ces dan hun vie ind est rus bul ukr ell ara heb chi_sim jpn kor hin tha"
for l in $LANGS; do
  npm install --no-audit --no-fund --no-progress "@tesseract.js-data/$l" < /dev/null > /dev/null 2>&1 || echo "no data package for $l"
done
cd ../..
S=site
mkdir -p $S/vendor/tess-core $S/tess
NM=tools/webbuild/node_modules
cp $NM/tesseract.js/dist/tesseract.esm.min.js $S/vendor/
cp $NM/tesseract.js/dist/worker.min.js $S/vendor/tesseract-worker.min.js
# Ядро: только варианты с SIMD/LSTM (подходят всем современным iPhone и Android) + запасной без SIMD
for f in tesseract-core-simd-lstm.wasm.js tesseract-core-simd-lstm.wasm tesseract-core-lstm.wasm.js tesseract-core-lstm.wasm \
         tesseract-core-simd.wasm.js tesseract-core-simd.wasm tesseract-core.wasm.js tesseract-core.wasm; do
  [ -f "$NM/tesseract.js-core/$f" ] && cp "$NM/tesseract.js-core/$f" $S/vendor/tess-core/
done
# Языковые данные: предпочитаем быстрые (fast/int), иначе любые
for l in $LANGS; do
  d=$NM/@tesseract.js-data/$l
  [ -d "$d" ] || continue
  f=$(find "$d" -name "$l.traineddata.gz" | grep -E "best_int" | head -1)
  [ -z "$f" ] && f=$(find "$d" -name "$l.traineddata.gz" | head -1)
  [ -n "$f" ] && cp "$f" $S/tess/
done
# franc одним файлом
npx --prefix tools/webbuild esbuild "$NM/franc-min/index.js" --bundle --format=esm --minify --outfile=$S/vendor/franc.js || exit 1
ls -la $S/vendor $S/vendor/tess-core
du -sh $S/tess; ls $S/tess
