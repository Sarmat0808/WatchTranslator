"""Сравнение перевода FI↔RU: через английский (как сейчас) против прямых моделей."""
import time

import sys

import torch
from transformers import AutoModelForSeq2SeqLM, AutoTokenizer, MarianMTModel, MarianTokenizer

torch.set_num_threads(4)

sys.path.insert(0, "tools")
from eval_phrases import FI, RU  # noqa: E402

cache = {}


def marian(name):
    if name not in cache:
        cache[name] = (MarianTokenizer.from_pretrained(name), MarianMTModel.from_pretrained(name).eval())
    return cache[name]


def run_marian(name, texts):
    tok, m = marian(name)
    out = []
    for t in texts:
        b = tok([t], return_tensors="pt")
        with torch.no_grad():
            g = m.generate(**b, num_beams=1, max_new_tokens=200)
        out.append(tok.decode(g[0], skip_special_tokens=True))
    return out


def run_multi(name, texts, src, tgt):
    key = name
    if key not in cache:
        cache[key] = (AutoTokenizer.from_pretrained(name), AutoModelForSeq2SeqLM.from_pretrained(name).eval())
    tok, m = cache[key]
    out = []
    for t in texts:
        if "m2m100" in name:
            tok.src_lang = src
            b = tok(t, return_tensors="pt")
            forced = tok.get_lang_id(tgt)
        else:
            tok.src_lang = src
            b = tok(t, return_tensors="pt")
            forced = tok.convert_tokens_to_ids(tgt)
        with torch.no_grad():
            g = m.generate(**b, forced_bos_token_id=forced, num_beams=1, max_new_tokens=200)
        out.append(tok.decode(g[0], skip_special_tokens=True))
    return out


def show(title, src, fn):
    t = time.time()
    try:
        res = fn()
    except Exception as e:  # noqa
        print(f"\n### {title}: FAILED {e}")
        return
    dt = time.time() - t
    print(f"\n### {title}  ({dt / len(src):.2f}s per sentence)")
    for s, r in zip(src, res):
        print(f"  {s}\n    -> {r}")


H = "Helsinki-NLP/"
show("FI>RU via English (now)", FI, lambda: run_marian(H + "opus-mt-en-ru", run_marian(H + "opus-mt-fi-en", FI)))
show("FI>RU direct opus-mt-fi-ru", FI, lambda: run_marian(H + "opus-mt-fi-ru", FI))
show("FI>RU m2m100_418M", FI, lambda: run_multi("facebook/m2m100_418M", FI, "fi", "ru"))
show("FI>RU NLLB-600M (reference)", FI, lambda: run_multi("facebook/nllb-200-distilled-600M", FI, "fin_Latn", "rus_Cyrl"))

show("RU>FI via English (now)", RU, lambda: run_marian(H + "opus-mt-en-fi", run_marian(H + "opus-mt-ru-en", RU)))
show("RU>FI direct opus-mt-ru-fi", RU, lambda: run_marian(H + "opus-mt-ru-fi", RU))
show("RU>FI m2m100_418M", RU, lambda: run_multi("facebook/m2m100_418M", RU, "ru", "fi"))
show("RU>FI NLLB-600M (reference)", RU, lambda: run_multi("facebook/nllb-200-distilled-600M", RU, "rus_Cyrl", "fin_Latn"))
