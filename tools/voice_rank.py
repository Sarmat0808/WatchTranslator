"""Какой голос самый разборчивый на каждом языке.
Озвучиваем фразу каждым из 10 голосов, распознаём лучшей моделью (Whisper turbo)
и считаем долю ошибок в буквах (CER). Чем меньше — тем чище голос."""
import time
from math import gcd

import numpy as np
import sherpa_onnx
from scipy.signal import resample_poly

T = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
W = "sherpa-onnx-whisper-turbo"

tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
    supertonic=sherpa_onnx.OfflineTtsSupertonicModelConfig(
        duration_predictor=f"{T}/duration_predictor.int8.onnx",
        text_encoder=f"{T}/text_encoder.int8.onnx",
        vector_estimator=f"{T}/vector_estimator.int8.onnx",
        vocoder=f"{T}/vocoder.int8.onnx",
        tts_json=f"{T}/tts.json",
        unicode_indexer=f"{T}/unicode_indexer.bin",
        voice_style=f"{T}/voice.bin"),
    num_threads=2)))

PHRASES = {
    "ru": "Добрый день. Скажите, пожалуйста, где здесь остановка автобуса?",
    "fi": "Hyvää päivää. Voisitteko kertoa, missä on bussipysäkki?",
    "bg": "Добър ден. Кажете, моля, къде е автобусната спирка?",
    "en": "Good afternoon. Could you tell me where the bus stop is?",
    "de": "Guten Tag. Können Sie mir sagen, wo die Bushaltestelle ist?",
    "es": "Buenas tardes. ¿Podría decirme dónde está la parada de autobús?",
}


def norm(s):
    return "".join(ch for ch in s.lower() if ch.isalnum())


def cer(ref, hyp):
    r, h = norm(ref), norm(hyp)
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            cur = d[j]
            d[j] = min(d[j] + 1, d[j - 1] + 1, prev + (r[i - 1] != h[j - 1]))
            prev = cur
    return d[len(h)] / max(1, len(r))


recs = {}
summary = {}
for lang, text in PHRASES.items():
    recs[lang] = sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=f"{W}/turbo-encoder.int8.onnx", decoder=f"{W}/turbo-decoder.int8.onnx",
        tokens=f"{W}/turbo-tokens.txt", language=lang, task="transcribe", num_threads=4)
    for steps in (3, 5, 8):
        for sid in range(10):
            cfg = sherpa_onnx.GenerationConfig()
            cfg.sid = sid
            cfg.num_steps = steps
            cfg.extra["lang"] = lang
            cfg.extra["seed"] = "42"
            t0 = time.time()
            a = tts.generate(text, cfg)
            gen = time.time() - t0
            s = np.array(a.samples, dtype=np.float32)
            g = gcd(16000, a.sample_rate)
            s16 = resample_poly(s, 16000 // g, a.sample_rate // g).astype(np.float32)
            st = recs[lang].create_stream()
            st.accept_waveform(16000, s16)
            recs[lang].decode_stream(st)
            e = cer(text, st.result.text)
            summary.setdefault((steps, sid), []).append(e)
            print(f"{lang} steps={steps} voice={sid + 1}: CER={e:.3f} gen={gen:.2f}s | {st.result.text}", flush=True)

print("\n=== Средняя ошибка по всем языкам (меньше = разборчивее) ===")
for (steps, sid), v in sorted(summary.items(), key=lambda kv: np.mean(kv[1])):
    print(f"steps={steps} voice={sid + 1}: avgCER={np.mean(v):.3f} max={np.max(v):.3f}")
