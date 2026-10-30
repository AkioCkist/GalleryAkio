# Gallery Akio

A fast, offline-first photo and video gallery for Android, built from the ground up with Kotlin and Jetpack Compose.

## Features

- **Library**: date-grouped photo grid with pinch-to-resize columns, fast scroller, multi-select, share and delete. Large libraries load in full.
- **Albums**: browse and pin albums, and keep favorites.
- **Viewer**: swipe between photos, pinch and double-tap zoom with sharp region decoding, video playback, EXIF details, rename and set as wallpaper.
- **Offline search**: find media by labels, faces and text in images, using on-device ML Kit models. Nothing leaves the phone.
- **Photo editor**: crop, turn, flip, straighten and skew; exposure, contrast, temperature and other adjustments; spot color; face smoothing; 12 color presets; drawing; undo.
- **Video editor**: trim, crop, turn, color adjustments, speed and mute, exported with Media3 Transformer.
- **Trash**: restore, delete or empty through the system trash.
- **Look and feel**: 6 themes in light and dark, and a liquid glass navigation bar.

Edits are always saved as a new file, so the original stays untouched.

## Tech stack

- Kotlin 2.4.10, Jetpack Compose, Material 3
- Android Gradle Plugin 9.4.1, Java 17
- minSdk 24, targetSdk 36, compileSdk 37
- Coil, Media3, ML Kit, GPUImage

Package name: `com.example.galleryakio`

## Requirements

- Android Studio (its bundled `jbr` works as the JDK), or JDK 17+
- Android SDK, with its path set in `local.properties` (`sdk.dir=...`)
- `adb` in your PATH (`%LOCALAPPDATA%\Android\Sdk\platform-tools` on Windows)
- An Android phone with **Developer options → USB debugging** turned on, connected over USB, with *Allow* accepted on the phone

Set `JAVA_HOME` before running Gradle.

PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

Git Bash:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
```

## Build a release and install it over USB

Check that the phone is connected:

```bash
adb devices
```

You should see a line reading `<serial>    device`. If it says `unauthorized`, unlock the phone and accept the USB debugging prompt.

Build the release (R8 minify and resource shrinking) and install it.

PowerShell:

```powershell
.\gradlew.bat installRelease
```

Git Bash:

```bash
./gradlew installRelease
```

Launch the app:

```bash
adb shell am start -n com.example.galleryakio/.MainActivity
```

All of it in one PowerShell command:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat installRelease && adb shell am start -n com.example.galleryakio/.MainActivity
```

### Install the APK manually

Build the APK:

```bash
./gradlew assembleRelease
```

Install it (`-r` keeps app data, `-d` allows a lower versionCode):

```bash
adb install -r -d app/build/outputs/apk/release/app-release.apk
```

With several devices connected, pick one by serial:

```bash
adb -s <serial> install -r -d app/build/outputs/apk/release/app-release.apk
```

For Gradle, set the `ANDROID_SERIAL=<serial>` environment variable before running `installRelease`.

### Signing

The release build is currently signed with the **debug key** (`signingConfig = signingConfigs.getByName("debug")` in `app/build.gradle.kts`), so it installs over a debug build and keeps its data. Replace it with a real key before distributing the app.

If the phone has a build signed with a different key, `adb install` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Uninstall the old build first. **This deletes the app's data:**

```bash
adb uninstall com.example.galleryakio
```

## Debug build

Build the APK, which lands in `app/build/outputs/apk/debug/app-debug.apk`:

```bash
./gradlew assembleDebug
```

Or build and install it:

```bash
./gradlew installDebug
```

## Device tests

**Do not use `connectedAndroidTest`.** It uninstalls the app when it finishes, which deletes the app's data and settings. Install the test APK and run it with `am instrument` instead.

Install both APKs:

```bash
./gradlew installDebug installDebugAndroidTest
```

Run all tests:

```bash
adb shell am instrument -w com.example.galleryakio.test/androidx.test.runner.AndroidJUnitRunner
```

Run a single class:

```bash
adb shell am instrument -w -e class com.example.galleryakio.PinchZoomTest com.example.galleryakio.test/androidx.test.runner.AndroidJUnitRunner
```

Available tests: `PinchJankTest`, `PinchZoomTest`, `ViewerTransitionTest`, `ViewerZoomTest`, `ZoomSharpnessTest`.

## Project structure

The project has two modules: `:app` and `:editor`.

Source in `app/src/main/java/com/example/galleryakio`:

| Path | Purpose |
|---|---|
| `GalleryApplication.kt`, `MainActivity.kt` | App startup, media indexer, single activity |
| `VideoPlaybackService.kt` | Video playback service |
| `data/Models.kt` | Media items, albums, sort, filter and date format enums |
| `data/MediaRepository.kt` | MediaStore queries, change tracking, trash, delete and rename |
| `data/MediaDetails.kt` | Media details |
| `data/Settings.kt` | SharedPreferences exposed as StateFlow |
| `data/ThumbnailFetcher.kt` | Thumbnail decoding for Coil |
| `data/search/*` | Offline image labeling, face detection, OCR, search index and categories |
| `ui/Navigation.kt` | Navigation between screens |
| `ui/GalleryViewModel.kt`, `ui/SearchViewModel.kt` | Shared state for the library and search |
| `ui/theme/Theme.kt` | Themes |
| `ui/components/*` | Media grid, fast scroller, glass navigation bar, zoom, transitions, video player |
| `ui/screens/*` | Main tabs, photos, albums, viewer, search, settings and trash screens |

Source in `editor/src/main/java/com/example/galleryakio/editor`:

| Path | Purpose |
|---|---|
| `EditorScreen.kt` | Editor entry point, opened from the viewer's Edit button |
| `EditRecipe.kt` | Every edit as data, which also drives undo |
| `ImageEditor.kt`, `ImageRender.kt` | Photo editing UI and export |
| `VideoEditor.kt`, `VideoExport.kt` | Video editing UI and export |
| `CropTool.kt`, `DecorateTool.kt`, `Marks.kt`, `ToneFilter.kt` | Crop, drawing and tone tools |

## Roadmap

- Hidden photos and a vault with password, biometrics and app lock
- Album management: create, move, copy and set a cover
- AI image tools
- Premium features

## Third-party code

The editor uses the GPUImage build published by [ImageToolbox](https://github.com/T8RIN/ImageToolbox), Copyright T8RIN (Malik Mukhametzyanov), under the Apache License 2.0. See [NOTICE](NOTICE) and [lib/README.md](lib/README.md).
