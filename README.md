# VoiceControl v2 — Continuous Arabic Assistant

This version changes the voice architecture from Android `SpeechRecognizer` sessions to a single continuously-open `AudioRecord` capture stream.

## What changed

- One microphone session per assistant activation; no 3-second open/close loop.
- Local Arabic ASR using sherpa-onnx + Moonshine v2 Arabic model.
- Speech is segmented locally while the same microphone stream remains open.
- Arabic app-name matching with aliases such as `فيس بوك`, `فيسبوك`, `شات جي بي تي`, `واتساب`, etc.
- Opening an app no longer depends on Accessibility being connected.
- Accessibility can find and click a likely ChatGPT voice-chat button from the live UI tree.
- Scroll up/down commands are supported.
- Voice confirmation is muted from the recognizer temporarily so the assistant does not react to its own confirmation.
- Android 14 microphone foreground-service type is declared and passed to `startForeground()`.
- GitHub Actions downloads the Arabic model and builds the APK.
- Release upload uses `--clobber` instead of deleting a missing asset.

## Important Android limitation

A microphone foreground service must be started while the app is in an allowed/visible state and Android 14+ restricts starting a microphone foreground service from `BOOT_COMPLETED`. The project therefore does not attempt to bypass this restriction.

## Model

The workflow downloads:
`sherpa-onnx-moonshine-base-ar-quantized-2026-02-27`

The model is about 142 MB before APK packaging. Recognition runs locally and does not require internet access after the model is installed.


Build fix: sherpa-onnx JitPack dependency uses the official tag format `v1.13.4`. The Arabic Moonshine v2 model files are downloaded during CI from the official model repository.
