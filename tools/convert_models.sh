#!/bin/bash
# Конвертирует модели перевода Helsinki-NLP в ONNX для браузера (как это делал Xenova):
# тот же набор версий, что и у готовых моделей Xenova, — иначе декодер получается сломанным.
set -u
git clone -q --depth 1 --branch 2.17.2 https://github.com/huggingface/transformers.js tjs2
cd tjs2
pip install -q "torch==2.0.1" --index-url https://download.pytorch.org/whl/cpu
pip install -q "transformers[torch]==4.33.2" "optimum==1.13.2" "onnx==1.13.1" "onnxruntime==1.15.1" \
  "numpy<2" tqdm sentencepiece protobuf==3.20.3 sacremoses "torch==2.0.1"
mkdir -p ../converted/local
: > ../converted/list.txt
for m in opus-mt-bg-en opus-mt-en-bg opus-mt-en-et opus-mt-en-el opus-mt-en-he \
         opus-mt-en-sla opus-tatoeba-en-ja opus-mt-tc-big-en-tr opus-mt-tc-big-en-ko; do
  echo "===== $m ====="
  if ! python -m scripts.convert --quantize --model_id "Helsinki-NLP/$m"; then
    echo "FAILED $m"; continue
  fi
  d=models/Helsinki-NLP/$m
  if [ ! -f "$d/onnx/decoder_model_merged_quantized.onnx" ]; then echo "NO MERGED $m"; ls "$d/onnx"; continue; fi
  mkdir -p ../converted/local/$m/onnx
  cp $d/*.json ../converted/local/$m/
  cp $d/onnx/encoder_model_quantized.onnx $d/onnx/decoder_model_merged_quantized.onnx ../converted/local/$m/onnx/
  echo "$m" >> ../converted/list.txt
  du -sh ../converted/local/$m
  rm -rf "$d"
done
echo "CONVERTED:"; cat ../converted/list.txt
du -sh ../converted/local
