# Gallery Nothing — Kotlin rewrite

This is a new Kotlin + Jetpack Compose codebase for `com.appslab.gallery`, rebuilt from the decompiled 2.0 APK
(`../decompiled`). It uses the same package name, the same `GalleryPrefs` keys, the same theme colours, the cq_mono font
and the same icons, so it can be installed over the old app without losing the user's settings.

## Build

```
gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
gradlew assembleRelease    # R8-minified; signed with the debug key until you add the Play key
```

AGP 9.4.1, Kotlin 2.4.10, compileSdk 37, minSdk 24.

## Structure

| Path | What it does | Original class |
|---|---|---|
| `data/Models.kt` | MediaItem, Album, sort/filter/date-format enums | `screens/MediaItem`, `SortOption`, `MediaFilterType`, `utils/DateFormatOption` |
| `data/MediaRepository.kt` | MediaStore queries, change observer, trash/delete/rename requests | `loadPhotosOptimized`/`loadVideosOptimized` in `PhotosScreenKt`, `AlbumsScreenKt.loadAlbums` |
| `data/Settings.kt` | SharedPreferences as a StateFlow | `GlobalSortOrder`, `MediaFilterSettings`, `ThemeManager`, `DateFormatManager` |
| `ui/theme/Theme.kt` | The 6 themes in light and dark | `theme/ThemeColors`, `ThemeManagerKt` |
| `ui/GalleryViewModel.kt` | One source of truth for the library | `MediaCache`, `AlbumsCache` |
| `ui/screens/MainTabsScreen.kt` | PHOTOS / ALBUMS / SEARCH tabs with an auto-hiding header and ⋮ menu | `MainActivityKt` (`AutoHideTabHeader`, `SlidingTabRow`, `ThreeDotMenu`) |
| `ui/screens/PhotosScreen.kt` | Grid with day headers, multi-select, share, trash | `PhotosScreenKt` |
| `ui/screens/AlbumsScreen.kt` | Albums grid, pin by long-press, album detail, favorites | `AlbumsScreenKt`, `AlbumDetailScreenKt`, `FavoriteScreenKt` |
| `ui/screens/PhotoDetailScreen.kt` | Swipe viewer, pinch/double-tap zoom, video, EXIF info, rename, set as | `PhotoDetailScreenKt` |
| `ui/screens/SettingsScreen.kt` | Theme, grid, sort, filters, date format, viewing, cache, about | `SettingsScreenKt` |
| `ui/screens/TrashScreen.kt` | System trash: restore, delete, empty | `TrashScreenKt` |

## Changes from the original

- No `LIMIT 1000` / `LIMIT 500` on queries: libraries larger than 1000 photos / 500 videos now load completely.
- One `ContentObserver` with debounce instead of one per screen, and one shared data cache instead of several.
- Grid thumbnails are decoded at cell size by Coil, and the viewer uses the thumbnail as its placeholder.
- Uses the system trash (`MediaStore.createTrashRequest`) instead of copying files.
- Release APK is about 2.6 MB. The original was 147 MB, mostly from TensorFlow Lite Flex (98 MB), which phase 3 should avoid.

## Phases

1. **Done**: core gallery (this codebase).
2. Photo editor: crop, rotate, flip, adjust, 16 filters, markup (`edit/*`, `EditScreenKt` — 170k decompiled lines).
3. Hidden media and vault: password, biometric, app protection, exit privacy (`HideScreenKt`, `VaultPasswordManager`, `UltimateHiddenMediaManager`, `BiometricHelper`).
4. Search and AI: ML Kit image labeling and face detection, categories (`SearchScreenKt`, `AIImageScreenKt`, `AIImageViewModel`).
5. Billing and premium (`billing/BillingManager`, `premium/*`), album management (create, move, copy, custom covers).

The decompiled sources in `../decompiled/sources/com/appslab/gallery` are the reference for each phase.
