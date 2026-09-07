import json
import os
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(ROOT, "vendor", "python"))

from vosk import Model, KaldiRecognizer, SetLogLevel


MODEL_PATH = os.path.join(ROOT, "models", "model-small-cn")
SAMPLE_RATE = 16000
SetLogLevel(-1)


def main() -> int:
    if len(sys.argv) != 2:
        print(json.dumps({"ok": False, "error": "audio path required"}))
        return 2
    audio_path = sys.argv[1]
    if not os.path.isfile(audio_path):
        print(json.dumps({"ok": False, "error": "audio not found"}))
        return 2
    try:
        model = Model(MODEL_PATH)
        recognizer = KaldiRecognizer(model, SAMPLE_RATE)
        recognizer.SetWords(False)
        with open(audio_path, "rb") as source:
            while True:
                chunk = source.read(6400)
                if not chunk:
                    break
                if recognizer.AcceptWaveform(chunk):
                    pass
        result = json.loads(recognizer.FinalResult())
        text = str(result.get("text", "")).strip()
        print(json.dumps({"ok": True, "text": text}))
        return 0
    except Exception as error:
        print(json.dumps({"ok": False, "error": str(error)}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
