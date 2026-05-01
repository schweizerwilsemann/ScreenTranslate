# ScreenTranslate MVP Status

## Implemented

- Multi-module Android scaffold with Clean Architecture boundaries.
- Hilt DI setup across app, core, and feature modules.
- Main screen for the first permission flow:
  - overlay permission shortcut;
  - MediaProjection permission request;
  - start/stop foreground capture service.
- Foreground `ScreenCaptureService` lifecycle:
  - receives MediaProjection permission result;
  - starts a real `MediaProjection` session through `ScreenCaptureManager`;
  - keeps an ongoing notification;
  - shows overlay readiness and live capture/OCR status;
  - stops projection and overlay cleanly.
- Real screen-frame capture:
  - `VirtualDisplay` + `ImageReader`;
  - `Image` buffer conversion to `Bitmap`;
  - throttled `CapturedFrame` emission through `CaptureRepository.frames`;
  - latest-frame access through `CaptureRepository.capture()`.
- Basic OCR loop:
  - `ScreenCaptureService` runs ML Kit OCR on captured frames at a throttled interval;
  - recognized text blocks are rendered to overlay using OCR bounding boxes.
- Core modules already present:
  - `core:capture` capture interfaces and frame diff placeholder;
  - `core:ocr` ML Kit OCR engine;
  - `core:translation` Retrofit + Room cache + online/offline strategy placeholders;
  - `core:overlay` WindowManager overlay renderer;
  - `feature:translator` use cases and pipeline orchestrator.

## Current Limitations

- Capture currently uses display metrics at service start; rotation/resizing is not handled yet.
- OCR currently uses ML Kit Latin text recognition only.
- Google Translate integration still needs a real API key/auth strategy before production use.
- Offline OPUS-MT/TFLite translation is still a placeholder.
- The overlay currently renders OCR text, not translated text.

## Next Todo

1. Add frame diff before OCR so unchanged frames are skipped.
2. Connect OCR results to `TranslationPipeline`.
3. Render translated text blocks on overlay using OCR bounding boxes.
4. Add settings for source/target language, capture interval, and overlay style.
5. Add API key configuration for online translation.
6. Handle display rotation and resize by recreating the virtual display.
7. Add benchmark hooks for OCR latency, translation latency, memory, and battery usage.

## Verification

Run from project root:

```bash
./gradlew build
```
