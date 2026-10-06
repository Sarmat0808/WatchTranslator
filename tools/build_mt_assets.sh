#!/bin/bash
# Кладёт движок перевода (тот же, что в веб-версии) внутрь приложения для телефона.
set -euo pipefail
mkdir -p tools/webbuild && cd tools/webbuild
[ -f package.json ] || npm init -y > /dev/null
npm install --no-audit --no-fund --no-progress @huggingface/transformers@3.7.6 < /dev/null
cd ../..
A=mobile/src/main/assets/mt
mkdir -p $A/vendor $A/ort
T=tools/webbuild/node_modules/@huggingface/transformers/dist
cp $T/transformers.min.js $A/vendor/transformers.js
for d in tools/webbuild/node_modules/onnxruntime-web/dist $T tools/webbuild/node_modules/@huggingface/transformers/node_modules/onnxruntime-web/dist; do
  [ -d "$d" ] || continue
  find "$d" -maxdepth 1 -regextype posix-extended -regex '.*/ort-wasm-simd-threaded(\.jsep)?\.(wasm|mjs)' -exec cp {} $A/ort/ \;
done
cp web/worker.js web/engine-routes.js $A/
ls -la $A $A/ort $A/vendor
