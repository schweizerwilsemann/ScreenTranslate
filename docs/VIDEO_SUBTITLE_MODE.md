# Video Subtitle Translation Mode

Goal: make ScreenTranslate behave like a realtime subtitle translator for videos, not like a full-screen article OCR highlighter.

## Runtime Pipeline

1. Capture screen frames through MediaProjection.
2. Crop the subtitle ROI only: lower video area from 62% to 90% of the screen height.
3. Run ML Kit OCR on that cropped ROI.
   - Latin recognizer covers English subtitles.
   - Chinese recognizer covers Douyin/Kuaishou style Chinese subtitles.
4. Filter UI noise and group nearby OCR lines into a subtitle candidate.
5. Stabilize the candidate for at least 300 ms before committing it.
6. Detect likely source language with lightweight script heuristics.
   - CJK -> `zh`
   - Japanese kana -> `ja`
   - Hangul -> `ko`
   - Vietnamese marks -> `vi`
   - fallback -> `en`
7. Translate committed subtitles to Vietnamese using ML Kit on-device translation.
8. Render one centered subtitle panel above the OCR ROI so the app does not OCR its own overlay.

## Important Tradeoffs

- First translation for a new language pair needs internet to download the ML Kit model. After that, translation can run offline.
- Current source-language detection is heuristic, not ML language identification.
- The overlay is intentionally above the original subtitle area. This avoids the feedback loop where OCR reads translated overlay text.
- Article/document mode is not the main path anymore. Full-screen text overlays should remain a debug mode only.

## Manual Test

Use Android Studio or Windows PowerShell because WSL cannot compile this repo with the current Windows SDK path.

```powershell
.\gradlew.bat :app:assembleScreentranslateDevDebug
adb install -r app\build\outputs\apk\screentranslateDev\debug\app-screentranslateDev-debug.apk
adb logcat -s ScreenCaptureService ScreenCaptureManager ScreenOverlayManager
```

Expected log signals:

- `OCR started for subtitle ROI ...`
- `Subtitle OCR detected ... lines ...`
- `Preparing offline subtitle translation model` on first model use
- `Translated subtitle en->vi` or `Translated subtitle zh->vi`
