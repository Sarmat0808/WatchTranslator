"""Тестовые фразы для автопроверки веб-версии: озвучиваем офлайн-голосом и сохраняем WAV 16 кГц."""
import os
import sys
import wave
from math import gcd

import numpy as np
import sherpa_onnx
from scipy.signal import resample_poly

T = sys.argv[1]
OUT = sys.argv[2]
os.makedirs(OUT, exist_ok=True)

tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
    supertonic=sherpa_onnx.OfflineTtsSupertonicModelConfig(
        duration_predictor=f"{T}/tts-duration_predictor.int8.onnx",
        text_encoder=f"{T}/tts-text_encoder.int8.onnx",
        vector_estimator=f"{T}/tts-vector_estimator.int8.onnx",
        vocoder=f"{T}/tts-vocoder.int8.onnx",
        tts_json=f"{T}/tts-tts.json",
        unicode_indexer=f"{T}/tts-unicode_indexer.bin",
        voice_style=f"{T}/tts-voice.bin"),
    num_threads=2)))

PHRASES = {
    "ru": "Здравствуйте, где находится ближайшая аптека?",
    "fi": "Hyvää huomenta, missä on lähin apteekki?",
    "bg": "Добро утро, къде е най-близката аптека?",
    "en": "Good morning, where is the nearest pharmacy?",
    "es": "Buenos días, ¿dónde está la farmacia más cercana?",
    "de": "Guten Morgen, wo ist die nächste Apotheke?",
    "fr": "Bonjour, où est la pharmacie la plus proche ?",
    "uk": "Доброго ранку, де найближча аптека?",
    "ja": "おはようございます。一番近い薬局はどこですか？",
    "tr": "Günaydın, en yakın eczane nerede?",
    "pl": "Dzień dobry, gdzie jest najbliższa apteka?",
    "it": "Buongiorno, dov'è la farmacia più vicina?",
}

for lang, text in PHRASES.items():
    cfg = sherpa_onnx.GenerationConfig()
    cfg.sid = 3
    cfg.num_steps = 6
    cfg.extra["lang"] = lang
    a = tts.generate(text, cfg)
    s = np.array(a.samples, dtype=np.float32)
    g = gcd(16000, a.sample_rate)
    s = resample_poly(s, 16000 // g, a.sample_rate // g)
    s = np.concatenate([np.zeros(4000), s, np.zeros(6000)])  # тишина по краям, как в жизни
    pcm = (np.clip(s, -1, 1) * 32767).astype(np.int16)
    with wave.open(f"{OUT}/{lang}.wav", "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes(pcm.tobytes())
    print(lang, len(pcm) / 16000, "s")
