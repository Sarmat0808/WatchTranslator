"""Перевод FI↔RU небольшими нейросетями Gemma (через llama-server, как будет на телефоне)."""
import json
import sys
import time
import urllib.request

sys.path.insert(0, "tools")
from eval_phrases import FI, RU  # noqa: E402

TITLE = sys.argv[1]
URL = "http://127.0.0.1:8080/v1/chat/completions"
NAMES = {"fi": "Finnish", "ru": "Russian"}


def prompt(text, src, tgt):
    return (
        f"Translate this {NAMES[src]} speech into {NAMES[tgt]}. It is one turn of a phone call "
        f"or a face-to-face conversation. Keep the meaning exact, natural spoken style, polite "
        f"form if the original is polite. Output only the {NAMES[tgt]} translation.\n\n{text}"
    )


def ask(text, src, tgt):
    body = json.dumps({
        "messages": [{"role": "user", "content": prompt(text, src, tgt)}],
        "temperature": 0, "max_tokens": 200,
    }).encode()
    req = urllib.request.Request(URL, body, {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        j = json.load(r)
    return j["choices"][0]["message"]["content"].strip(), j.get("usage", {}).get("completion_tokens", 0)


for src, tgt, items in [("fi", "ru", FI), ("ru", "fi", RU)]:
    t0 = time.time()
    toks = 0
    lines = []
    for s in items:
        out, n = ask(s, src, tgt)
        toks += n
        lines.append(f"  {s}\n    -> {out}")
    dt = time.time() - t0
    print(f"\n### {src.upper()}>{tgt.upper()} {TITLE}  ({dt / len(items):.1f}s per sentence on CI CPU, "
          f"{toks / dt:.1f} tok/s)")
    print("\n".join(lines))
