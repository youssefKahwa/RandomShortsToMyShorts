#!/usr/bin/env python
"""
Transcribes a video/audio file's speech with real timestamps (faster-whisper, local/offline) and
translates each line into English (Argos Translate, local/offline). Prints a JSON array of
{"start", "end", "text"} to stdout - drop it straight into a RandomShortsToMyShorts
instructions.json "segments" array, or let the app do that automatically via the "autoScript"
field.

Usage:
  python transcribe_translate.py --input video.mp4 --source-lang ar --model small --device cpu
  python transcribe_translate.py --input video.mp4 --source-lang ar --translation-engine none
    (leaves "text" untranslated - used when the Java side calls Claude for translation instead)

Both faster-whisper and argostranslate must already be installed (see setup-auto-script.ps1).
"""
import argparse
import json
import sys

# Windows' console codepage can't print Arabic/accented text directly (same issue noted in the
# yt-auto project). Force UTF-8 regardless of how this script is invoked, since Java reads our
# stdout as UTF-8 - a mismatch here would corrupt anything beyond plain ASCII.
sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, help="Path to the video or audio file")
    parser.add_argument("--source-lang", required=True, help="ISO 639-1 code of the spoken language, e.g. ar, fr, es")
    parser.add_argument("--model", default="small", help="Whisper model size: tiny/base/small/medium/large-v3")
    parser.add_argument("--device", default="cpu", help="cpu or cuda")
    parser.add_argument("--translation-engine", default="argos", choices=["argos", "none"],
                         help="'argos' translates locally here; 'none' leaves the text untranslated "
                              "(used when the Java side hands translation off to Claude instead)")
    args = parser.parse_args()

    from faster_whisper import WhisperModel

    compute_type = "int8" if args.device == "cpu" else "float16"
    model = WhisperModel(args.model, device=args.device, compute_type=compute_type)
    segments, _info = model.transcribe(args.input, language=args.source_lang, vad_filter=True)

    translator = None
    if args.translation_engine == "argos" and args.source_lang != "en":
        translator = _load_translator(args.source_lang, "en")

    results = []
    for seg in segments:
        text = seg.text.strip()
        if not text:
            continue
        english = translator.translate(text) if translator else text
        results.append({"start": round(seg.start, 3), "end": round(seg.end, 3), "text": english})

    print(json.dumps(results, ensure_ascii=False))


def _load_translator(from_code, to_code):
    import argostranslate.translate

    installed = argostranslate.translate.get_installed_languages()
    from_lang = next((l for l in installed if l.code == from_code), None)
    to_lang = next((l for l in installed if l.code == to_code), None)

    if not from_lang or not to_lang:
        raise RuntimeError(
            "No Argos Translate package installed for '{}' -> '{}'. Run setup-auto-script.ps1, "
            "or install one manually - see https://www.argosopentech.com/argospm/index/ for the "
            "list of available language pairs.".format(from_code, to_code)
        )
    return from_lang.get_translation(to_lang)


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(str(e), file=sys.stderr)
        sys.exit(1)
