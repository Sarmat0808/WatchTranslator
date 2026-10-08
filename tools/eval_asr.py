"""Сравнение распознавания речи для FI и RU: Whisper base/small/turbo против Parakeet v3.

Фразы озвучиваются офлайн-голосом (Supertonic), затем звук «портится» как в жизни
(телефонная линия 300–3400 Гц + шум комнаты), и каждая модель распознаёт его обратно.
Печатаем время обработки и процент ошибок по буквам (CER).
"""
import glob
import re
import sys
import time
from math import gcd

import numpy as np
import sherpa_onnx
from scipy.signal import butter, resample_poly, sosfilt

TTS = sys.argv[1]          # папка с tts-*.onnx
MODELS = sys.argv[2]       # папка с распакованными ASR-моделями
THREADS = 4

PHRASES = {
    "fi": [
        "Hyvää päivää, soitan Kelasta. Hakemuksenne on käsittelyssä, mutta tarvitsemme vielä palkkatodistuksen.",
        "Voisitteko tulla vastaanotolle huomenna kello kymmenen?",
        "Työmarkkinatorilla on teille uusi työpaikkailmoitus rakennusalalta.",
        "Lähettäkää liitteet sähköisesti oma asiointi -palvelun kautta.",
        "Kiitos, näkemiin.",
    ],
    "ru": [
        "Здравствуйте, я хотел бы узнать, когда будет рассмотрено моё заявление.",
        "Извините, я плохо говорю по-фински, можно говорить медленнее?",
        "Я работал на стройке два месяца, но работа закончилась.",
        "Спасибо, до свидания.",
    ],
}

tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
    supertonic=sherpa_onnx.OfflineTtsSupertonicModelConfig(
        duration_predictor=f"{TTS}/tts-duration_predictor.int8.onnx",
        text_encoder=f"{TTS}/tts-text_encoder.int8.onnx",
        vector_estimator=f"{TTS}/tts-vector_estimator.int8.onnx",
        vocoder=f"{TTS}/tts-vocoder.int8.onnx",
        tts_json=f"{TTS}/tts-tts.json",
        unicode_indexer=f"{TTS}/tts-unicode_indexer.bin",
        voice_style=f"{TTS}/tts-voice.bin"),
    num_threads=THREADS)))

rng = np.random.default_rng(7)
phone_band = butter(4, [300, 3400], btype="band", fs=16000, output="sos")


def speak(text, lang, sid):
    cfg = sherpa_onnx.GenerationConfig()
    cfg.sid = sid
    cfg.num_steps = 6
    cfg.extra["lang"] = lang
    a = tts.generate(text, cfg)
    s = np.array(a.samples, dtype=np.float32)
    g = gcd(16000, a.sample_rate)
    s = resample_poly(s, 16000 // g, a.sample_rate // g).astype(np.float32)
    # как звук из динамика телефона: узкая полоса + шум комнаты
    s = sosfilt(phone_band, s).astype(np.float32)
    s = s / (np.abs(s).max() + 1e-6) * 0.5
    s = s + rng.normal(0, 0.01, s.shape).astype(np.float32)
    pad = np.zeros(3200, dtype=np.float32)
    return np.concatenate([pad, s, pad])


def norm(t):
    t = t.lower().replace("ё", "е")
    t = re.sub(r"[^\w\s]", " ", t)
    return " ".join(t.split())


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


clips = []
for lang, items in PHRASES.items():
    for i, text in enumerate(items):
        clips.append((lang, text, speak(text, lang, [0, 2, 9, 5][i % 4])))
print("clips:", len(clips), "total audio:", round(sum(len(c[2]) for c in clips) / 16000, 1), "s")


def whisper(name):
    d = f"{MODELS}/sherpa-onnx-whisper-{name}"
    def make(lang):
        return sherpa_onnx.OfflineRecognizer.from_whisper(
            encoder=f"{d}/{name}-encoder.int8.onnx", decoder=f"{d}/{name}-decoder.int8.onnx",
            tokens=f"{d}/{name}-tokens.txt", language=lang, task="transcribe",
            num_threads=THREADS, tail_paddings=1000)
    return make


def parakeet():
    d = glob.glob(f"{MODELS}/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8")[0]
    print("parakeet files:", sorted(p.split('/')[-1] for p in glob.glob(d + "/*")))
    rec = sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=glob.glob(f"{d}/encoder*.onnx")[0], decoder=glob.glob(f"{d}/decoder*.onnx")[0],
        joiner=glob.glob(f"{d}/joiner*.onnx")[0], tokens=f"{d}/tokens.txt",
        num_threads=THREADS, model_type="nemo_transducer")
    return lambda lang: rec


for title, factory in [("whisper-base", whisper("base")), ("whisper-small", whisper("small")),
                       ("whisper-turbo", whisper("turbo")), ("parakeet-v3", parakeet())]:
    print(f"\n===== {title} =====")
    cache = {}
    tot_t = tot_a = 0.0
    errs = {"fi": [], "ru": []}
    for lang, text, audio in clips:
        if lang not in cache:
            t = time.time(); cache[lang] = factory(lang); load = time.time() - t
            print(f"  load {lang}: {load:.1f}s")
        rec = cache[lang]
        st = rec.create_stream()
        st.accept_waveform(16000, audio)
        t = time.time()
        rec.decode_stream(st)
        dt = time.time() - t
        dur = len(audio) / 16000
        tot_t += dt
        tot_a += dur
        e = cer(text, st.result.text)
        errs[lang].append(e)
        print(f"  [{lang}] {dur:4.1f}s audio -> {dt:5.2f}s | CER {e*100:5.1f}% | {st.result.text}")
    print(f"  SUMMARY {title}: speed {tot_t / tot_a:.2f}x realtime, "
          f"CER fi {np.mean(errs['fi'])*100:.1f}%, ru {np.mean(errs['ru'])*100:.1f}%")
