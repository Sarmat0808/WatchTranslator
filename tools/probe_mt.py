"""Какие модели перевода opus-mt существуют на Hugging Face (для веб-версии)."""
from huggingface_hub import HfApi

api = HfApi()
langs = ["ru","fi","bg","uk","et","sv","de","es","fr","it","pt","pl","tr","ar","zh","ja","ko",
         "hi","nl","el","ro","cs","da","hu","vi","id","th","he","no","ms","lt","lv","sk","sl","hr","sr"]

def files(repo):
    try:
        return [s.rfilename for s in api.model_info(repo, files_metadata=False).siblings]
    except Exception:
        return None

def check(src, tgt):
    out = []
    for org, name in [("Xenova", f"opus-mt-{src}-{tgt}"), ("Xenova", f"opus-mt-tc-big-{src}-{tgt}"),
                      ("Helsinki-NLP", f"opus-mt-{src}-{tgt}"), ("Helsinki-NLP", f"opus-mt-tc-big-{src}-{tgt}")]:
        f = files(f"{org}/{name}")
        if f is not None:
            onnx = any(x.endswith(".onnx") for x in f)
            out.append(f"{org}/{name}{'[onnx]' if onnx else ''}")
    return out

for l in langs:
    print(f"{l}->en: {check(l,'en')}")
    print(f"en->{l}: {check('en',l)}")
for repo in ["Xenova/opus-mt-en-mul","Xenova/opus-mt-mul-en","Helsinki-NLP/opus-mt-en-sla","Helsinki-NLP/opus-mt-sla-en",
             "Helsinki-NLP/opus-mt-en-ROMANCE","Xenova/opus-mt-en-ROMANCE","Helsinki-NLP/opus-mt-en-zls","Helsinki-NLP/opus-mt-zls-en",
             "Helsinki-NLP/opus-mt-en-grk","Helsinki-NLP/opus-mt-tc-big-en-zls","Helsinki-NLP/opus-mt-tc-big-zls-en",
             "onnx-community/whisper-base","onnx-community/whisper-small","onnx-community/whisper-tiny",
             "Xenova/nllb-200-distilled-600M","Xenova/m2m100_418M"]:
    f = files(repo)
    print(repo, "MISSING" if f is None else [x for x in f if x.endswith('.onnx')][:40])
