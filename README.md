# ScreenTranslate

Generated Android Clean Architecture scaffold for screen translation.

## Current MVP

The app now includes a basic Android shell for the first demo flow:

- request overlay permission;
- request MediaProjection screen-capture permission;
- start/stop the foreground capture service;
- capture real screen frames with `VirtualDisplay + ImageReader`;
- run throttled ML Kit OCR on captured frames;
- render recognized text blocks on the overlay.

See [docs/MVP_STATUS.md](docs/MVP_STATUS.md) for implemented pieces, current limitations, and the next todo list.

## Modules

- `app`
- `:core:common`
- `:core:capture`
- `:core:ocr`
- `:core:translation`
- `:core:overlay`
- `:feature:translator`

## Common commands

```bash
./gradlew projects
./gradlew build
./gradlew :app:assembleScreentranslateDevDebug
./gradlew :app:assembleScreentranslateDevRelease
./gradlew :app:assembleScreentranslateProdRelease
```

## Next implementation order

1. `core:capture`
2. `core:ocr`
3. `core:translation`
4. `core:overlay`
5. `feature:translator`
