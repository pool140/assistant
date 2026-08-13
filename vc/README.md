# VoiceControl - Assistant Build

This version is based on VoiceControl-v3 and adds:

- Wake word default: "يا مساعد".
- Better Arabic normalization and app aliases.
- Automatic fallback to installed app labels, so apps do not all have to be manually registered.
- Real app launching with error handling.
- Up/down scrolling commands.
- Accessibility node-based button discovery before coordinate fallback.
- Semantic command for "افتح محادثة صوت مع شات جي بي تي": opens ChatGPT, waits for the UI, finds a voice/talk control and clicks it.
- Voice confirmation after successful operations.
- Start-on-boot preference after the user explicitly starts the assistant.
- Existing calibration/gesture features remain available.

## Important Android limitation

Android's public `SpeechRecognizer` API owns the microphone for recognition sessions and may end a session after speech/silence. It does not expose a supported API for one infinitely-open microphone session with continuous recognition. This build therefore keeps the assistant service alive and automatically re-arms recognition, while extending idle silence handling; a truly single-open-microphone implementation requires replacing the recognition layer with a continuous audio engine plus an ASR model/service.

The app must not be used to bypass Android's lock-screen authentication. Accessibility can act only within the security boundaries imposed by Android.
