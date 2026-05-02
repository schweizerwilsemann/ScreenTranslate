# Phase 2: Audio Speech Translation

Goal: translate videos that do not show any subtitles on screen.

## Current Scaffold

The app now includes an audio playback probe:

- `AudioPlaybackProbe` builds an `AudioRecord` from `AudioPlaybackCaptureConfiguration`.
- `ScreenCaptureService` runs two delayed probes after subtitle mode starts.
- Probe results are logged under `ScreenCaptureService`.
- The probe does not run ASR or translation yet. It only answers whether Android is returning a real audio signal.

Expected logs:

```text
Audio probe first: signal=true rms=... peak=... samples=...
Audio probe second: signal=false rms=... peak=... samples=...
```

Interpretation:

- `signal=true`: the current source app is capturable, so internal-audio ASR is viable.
- `signal=false`: audio may be blocked, the video may be silent at that moment, or the user denied `RECORD_AUDIO`.
- `Audio probe failed`: inspect the exception; common causes are missing permission or device policy.

## Why Source Apps Can Block Audio

Android only lets a third-party app capture playback audio when the source player allows it. A source app can opt out through manifest or runtime capture policy. This means YouTube, Douyin, Kuaishou, browsers, and DRM players must be tested on device.

## Next Implementation Steps

1. Add an `AudioFrameStream` that continuously emits PCM chunks when the probe succeeds.
2. Add a VAD layer to drop silence and split speech segments.
3. Add ASR:
   - research path: Whisper.cpp / sherpa-onnx / Vosk
   - Android-friendly baseline: remote ASR endpoint for comparison only
4. Translate recognized speech with the existing `TranslationRepository`.
5. Render translated speech through the existing subtitle overlay.
6. Add fallback mode:
   - internal playback capture first
   - microphone capture when internal capture is blocked

## Manual Test

1. Build and install the app.
2. Start video subtitle mode.
3. Within 6 seconds, switch to YouTube, Douyin, or Kuaishou and play a video with audible speech/music.
4. Watch:

```powershell
adb logcat -s ScreenCaptureService ScreenCaptureManager ScreenOverlayManager
```

Record per app:

- app name and version
- Android version / device model
- whether probe result has `signal=true`
- whether video was silent or audible during the probe window
