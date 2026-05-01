# Offline Hy-MT Translation Research Plan

Last updated: 2026-05-01

## Goal

Explore whether ScreenTranslate can run real offline translation after OCR by using Tencent Hunyuan's compact Hy-MT translation model family.

The target research pipeline is:

```text
Screen capture -> OCR text blocks -> Offline translation -> Overlay translated text
```

This is a graduation-project research track. The first goal is to produce measurable experimental results, not a production-stable translator.

## Candidate Model

Primary candidate:

- `Hy-MT1.5-1.8B-1.25bit`
- Reported model size: about 440MB
- Reported base model: 1.8B parameters
- Reported compression: 3.3GB FP16 model compressed to 1.25-bit weights through Sherry quantization
- Reported scope: 33 languages, 5 dialect or minority language variants, 1,056 translation directions
- Target use: on-device offline translation on mobile devices

Useful links:

- Tencent model card: https://huggingface.co/tencent/Hy-MT1.5-1.8B-1.25bit
- Tencent GGUF model card: https://huggingface.co/tencent/Hy-MT1.5-1.8B-1.25bit-GGUF
- Tencent HY-MT repository: https://github.com/Tencent-Hunyuan/HY-MT
- Android demo APK link from the model card: https://huggingface.co/AngelSlim/Hy-MT1.5-1.8B-1.25bit-GGUF/resolve/main/Hy-MT-demo.apk

## Current Repo State

The repo already has the main architecture needed for this experiment:

- `core:capture`: MediaProjection screen capture.
- `core:ocr`: ML Kit OCR.
- `core:overlay`: WindowManager overlay rendering.
- `core:translation`: translation engine abstraction, repository, Room cache, online engine placeholder, offline placeholder.
- `feature:translator`: pipeline/use cases and `ScreenCaptureService`.

Important current gap:

- `ScreenCaptureService` currently runs OCR directly and renders OCR text.
- `TranslationPipeline` exists, but the foreground service does not yet use it for live translation.
- `OpusMtEngine` is only a placeholder and does not translate.

## Phase 1: Prove Translation Flow Without Hy-MT

Purpose: make the app display translated text through the existing architecture before adding native model inference.

Tasks:

1. Add a small experimental translation engine:
   - Option A: debug engine that prefixes text, for example `[vi] <source>`.
   - Option B: use existing online engine if API/auth is configured.
2. Change live service flow so OCR results go through `TranslationRepository`.
3. Render translated text in overlay instead of raw OCR text.
4. Add service status messages for:
   - OCR block count
   - translation success count
   - translation latency
   - fallback to raw OCR text

Expected result:

- The overlay visibly changes from OCR text to translated/debug text.
- No native model runtime is required yet.
- This confirms the app wiring before the Hy-MT integration.

## Phase 2: Add Hy-MT Experimental Engine Shell

Purpose: add the code boundaries for Hy-MT without requiring the model to run yet.

Tasks:

1. Create a new engine class:
   - `core/translation/src/main/java/.../offline/HunyuanMtEngine.kt`
2. Keep it behind `TranslationEngine`.
3. Extend `ModelManager` for Hy-MT paths:
   - `filesDir/translation-models/hy-mt/model.gguf`
   - optional tokenizer/config files if required by runtime
4. Add model availability states:
   - missing
   - present
   - loading
   - loaded
   - failed
5. Add log/status output for model path, file size, load time, and runtime errors.

Expected result:

- The app can detect whether the Hy-MT model file exists.
- The translation engine can fail gracefully and fall back to OCR/debug output.

## Phase 3: Runtime Experiment

Purpose: test whether the Hy-MT model can run inside this Android app.

Preferred experiment order:

1. Test Tencent's provided Android demo APK on the target Android 11 device.
2. If demo performance is acceptable, inspect the model format and runtime direction.
3. Try the GGUF model path first because it is the easiest direction for a llama.cpp-style native bridge.
4. If the 1.25-bit STQ runtime is not available or not stable, test one of these fallbacks:
   - 2-bit Hy-MT GGUF
   - HY-MT1.5-1.8B-GGUF regular quantized variant
   - online translation fallback for comparison

JNI bridge shape:

```text
init(modelPath: String): Boolean
translate(text: String, sourceLang: String, targetLang: String): String
close(): Unit
```

Android integration shape:

```text
HunyuanMtEngine -> NativeHunyuanMtRuntime -> JNI -> native runtime
```

Expected result:

- A minimal local translation call can run from Android code.
- It does not need to be fast or stable in this phase.

## Phase 4: Live Overlay Integration

Purpose: connect real offline model inference to the live capture service.

Tasks:

1. Translate OCR blocks in batches when possible.
2. Cache repeated OCR strings through existing `TranslationCache`.
3. Add throttling:
   - do not translate every frame
   - skip unchanged OCR text
   - limit max text blocks per cycle
4. Show partial results:
   - translated blocks when available
   - OCR text while translation is loading
   - clear error status when model fails

Expected result:

- ScreenTranslate can capture text from another app and overlay translated text offline.

## Phase 5: Measurement And Graduation Report Data

Collect data on at least one Android 11 device.

Metrics:

- Model file size
- Model load time
- Peak memory during load
- Idle memory after load
- OCR latency per frame
- Translation latency per block
- Translation latency per batch
- End-to-end latency from capture to overlay update
- CPU usage during continuous overlay mode
- Battery impact during 5-minute and 15-minute sessions
- Failure rate after app backgrounding, task removal, screen rotation, and permission revocation

Comparison matrix:

| Mode | Network | Model size | Latency | Quality | Notes |
| --- | --- | ---: | ---: | --- | --- |
| OCR only | No | 0 | TBD | N/A | Current baseline |
| Online translation | Yes | 0 | TBD | TBD | API/auth required |
| Hy-MT 1.25-bit | No | ~440MB | TBD | TBD | Main research target |
| Hy-MT fallback quant | No | TBD | TBD | TBD | Use if 1.25-bit runtime blocks |

## Risks

- The 1.25-bit model may require Tencent's custom STQ mobile kernel.
- The GGUF model card says the llama.cpp kernel, including STQ support, is still coming soon.
- Android memory pressure may be high even if the model file is only about 440MB.
- Model distribution may not be appropriate inside the APK because of size and license terms.
- License terms must be reviewed before publishing any APK that bundles or downloads the model.
- OCR quality will heavily affect translation quality.
- Live screen translation may need aggressive throttling to avoid battery and heat issues.

## Model Distribution Strategy

Do not bundle the model into the APK during research.

Use one of these approaches:

1. Push model manually for experiments:

```bash
adb shell run-as com.screentranslate.dev mkdir -p files/translation-models/hy-mt
adb push model.gguf /sdcard/Download/model.gguf
```

2. Add a debug-only importer that copies from shared storage into app-private storage.
3. Add a future download manager only after runtime feasibility is proven.

## Suggested Implementation Order

1. Connect live OCR output to `TranslationRepository`.
2. Add debug translation engine and prove translated overlay rendering.
3. Add `HunyuanMtEngine` shell and model file detection.
4. Add model status display to `MainActivity`.
5. Test Tencent demo APK on Android 11 target hardware.
6. Prototype native runtime outside the live service with a single text input.
7. Move native runtime behind `HunyuanMtEngine`.
8. Add batching, caching, throttling, and fallback UI.
9. Collect benchmark data.
10. Decide whether to keep Hy-MT as primary offline mode or document it as an experimental branch.

## Done Criteria For Research Prototype

- App starts without adb-granted permissions except Android-required manual overlay approval.
- App can capture OCR blocks from another app.
- App can route OCR text into a translation engine.
- App can render translated text, debug or real, on overlay.
- Hy-MT model presence is detected from app-private storage.
- At least one local Hy-MT inference attempt is measured and documented.
- Failures are visible in app status instead of only in Logcat.
