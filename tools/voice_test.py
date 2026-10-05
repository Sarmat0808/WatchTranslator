"""Проверка: озвучиваем фразы офлайн-голосом и распознаём их обратно Whisper."""
import time

import numpy as np
import sherpa_onnx

T = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
import sys
W = sys.argv[1] if len(sys.argv) > 1 else "sherpa-onnx-whisper-base"
P = W.split("-")[-1]

tts = sherpa_onnx.OfflineTts(
    sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            supertonic=sherpa_onnx.OfflineTtsSupertonicModelConfig(
                duration_predictor=f"{T}/duration_predictor.int8.onnx",
                text_encoder=f"{T}/text_encoder.int8.onnx",
                vector_estimator=f"{T}/vector_estimator.int8.onnx",
                vocoder=f"{T}/vocoder.int8.onnx",
                tts_json=f"{T}/tts.json",
                unicode_indexer=f"{T}/unicode_indexer.bin",
                voice_style=f"{T}/voice.bin",
            ),
            num_threads=2,
        )
    )
)
print("ASR model:", W)
print("TTS sample rate:", tts.sample_rate, "speakers:", tts.num_speakers)

phrases = {
    "ru": "Здравствуйте, где находится ближайшая аптека?",
    "fi": "Hyvää huomenta, missä on lähin apteekki?",
    "bg": "Добро утро, къде е най-близката аптека?",
    "en": "Good morning, where is the nearest pharmacy?",
    "es": "Buenos días, ¿dónde está la farmacia más cercana?",
    "de": "Guten Morgen, wo ist die nächste Apotheke?",
    "fr": "Bonjour, où est la pharmacie la plus proche ?",
    "uk": "Доброго ранку, де найближча аптека?",
}

for lang, text in phrases.items():
    rec = sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=f"{W}/{P}-encoder.int8.onnx",
        decoder=f"{W}/{P}-decoder.int8.onnx",
        tokens=f"{W}/{P}-tokens.txt",
        language=lang,
        task="transcribe",
        num_threads=2,
    )
    cfg = sherpa_onnx.GenerationConfig()
    cfg.sid = 0
    cfg.num_steps = 5
    cfg.speed = 1.0
    cfg.extra["lang"] = lang
    t0 = time.time()
    audio = tts.generate(text, cfg)
    t1 = time.time()
    samples = np.array(audio.samples, dtype=np.float32)
    # Whisper ждёт 16 кГц
    from math import gcd
    from scipy.signal import resample_poly
    g = gcd(16000, audio.sample_rate)
    s16 = resample_poly(samples, 16000 // g, audio.sample_rate // g).astype(np.float32)
    stream = rec.create_stream()
    stream.accept_waveform(16000, s16)
    t2 = time.time()
    rec.decode_stream(stream)
    t3 = time.time()
    dur = len(samples) / audio.sample_rate
    print(f"[{lang}] {dur:.1f}s audio | TTS {t1 - t0:.2f}s | ASR {t3 - t2:.2f}s")
    print(f"   said : {text}")
    print(f"   heard: {stream.result.text}")
