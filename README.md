# Gallery Akio

Ứng dụng thư viện ảnh/video viết bằng Kotlin + Jetpack Compose, dựng lại từ bản APK 2.0 đã decompile của `com.appslab.gallery`
(mã tham khảo nằm ở `../decompiled/sources/com/appslab/gallery`).

- Tên hiển thị: **Gallery Akio**
- Package / namespace / applicationId: `com.example.galleryakio` (cài song song được với bản cũ `com.appslab.gallery`)
- AGP 9.4.1, Kotlin 2.4.10, compileSdk 37, targetSdk 36, minSdk 24, Java 17

## Yêu cầu

- Android Studio (dùng JDK đi kèm `jbr`) hoặc JDK 17+
- Android SDK, đường dẫn đặt trong `local.properties` (`sdk.dir=...`)
- `adb` trong PATH (`%LOCALAPPDATA%\Android\Sdk\platform-tools`)
- Điện thoại bật **Developer options → USB debugging**, cắm USB và bấm *Allow* khi máy hỏi

Đặt `JAVA_HOME` trước khi chạy Gradle:

PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

Git Bash:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
```

## Build release và cài thẳng vào máy qua USB

Kiểm tra máy đã nhận:

```bash
adb devices
```

Phải thấy một dòng `<serial>    device`. Nếu là `unauthorized`, mở khoá màn hình điện thoại và chấp nhận hộp thoại USB debugging.

Build release (R8 minify + shrink resources) rồi cài luôn:

PowerShell:

```powershell
.\gradlew.bat installRelease
```

Git Bash:

```bash
./gradlew installRelease
```

Mở app sau khi cài:

```bash
adb shell am start -n com.example.galleryakio/.MainActivity
```

Gộp cả ba bước trong một lệnh (PowerShell):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat installRelease && adb shell am start -n com.example.galleryakio/.MainActivity
```

Cách khác: build APK rồi tự cài bằng `adb` (`-r` giữ dữ liệu, `-d` cho phép hạ versionCode):

```bash
./gradlew assembleRelease
```

```bash
adb install -r -d app/build/outputs/apk/release/app-release.apk
```

Có nhiều máy cắm cùng lúc thì chọn máy bằng serial:

```bash
adb -s <serial> install -r -d app/build/outputs/apk/release/app-release.apk
```

Hoặc với Gradle: đặt biến môi trường `ANDROID_SERIAL=<serial>` trước khi chạy `installRelease`.

### Lưu ý về chữ ký

Bản release hiện ký bằng **debug key** (`signingConfig = signingConfigs.getByName("debug")` trong `app/build.gradle.kts`),
nên cài đè lên bản debug được mà không mất dữ liệu. Trước khi phát hành, thay bằng key thật. Nếu trên máy đã có bản ký
bằng key khác, `adb install` sẽ báo `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; khi đó phải gỡ bản cũ (mất dữ liệu app):

```bash
adb uninstall com.example.galleryakio
```

## Build debug

```bash
./gradlew assembleDebug
```

```bash
./gradlew installDebug
```

APK nằm ở `app/build/outputs/apk/debug/app-debug.apk`.

## Test trên máy

**Không dùng `connectedAndroidTest`**: task này gỡ app sau khi chạy, xoá luôn dữ liệu và cài đặt. Cài APK test rồi chạy bằng `am instrument`:

```bash
./gradlew installDebug installDebugAndroidTest
```

```bash
adb shell am instrument -w com.example.galleryakio.test/androidx.test.runner.AndroidJUnitRunner
```

Chạy một class:

```bash
adb shell am instrument -w -e class com.example.galleryakio.PinchZoomTest com.example.galleryakio.test/androidx.test.runner.AndroidJUnitRunner
```

Các test có sẵn: `PinchJankTest`, `PinchZoomTest`, `ViewerTransitionTest`, `ViewerZoomTest`.

## Cấu trúc

Mã nguồn trong `app/src/main/java/com/example/galleryakio`:

| Đường dẫn | Chức năng |
|---|---|
| `GalleryApplication.kt`, `MainActivity.kt` | Khởi tạo app, media indexer, activity duy nhất |
| `data/Models.kt` | MediaItem, Album, các enum sắp xếp / lọc / định dạng ngày |
| `data/MediaRepository.kt` | Truy vấn MediaStore, theo dõi thay đổi, thùng rác / xoá / đổi tên |
| `data/Settings.kt` | SharedPreferences dạng StateFlow (giữ nguyên key của `GalleryPrefs` bản gốc) |
| `data/ThumbnailFetcher.kt` | Giải mã thumbnail cho Coil |
| `data/search/*` | Tìm kiếm offline: gắn nhãn ảnh, nhận diện khuôn mặt, OCR (ML Kit), chỉ mục và danh mục |
| `ui/Navigation.kt` | Điều hướng giữa các màn hình |
| `ui/GalleryViewModel.kt`, `ui/SearchViewModel.kt` | Nguồn dữ liệu chung cho thư viện và tìm kiếm |
| `ui/theme/Theme.kt` | 6 theme, sáng và tối |
| `ui/components/*` | Lưới media, fast scroller, thanh điều hướng kính mờ (liquid glass), zoom, chuyển cảnh, trình phát video |
| `ui/screens/MainTabsScreen.kt` | Tab PHOTOS / ALBUMS / SEARCH, header tự ẩn, menu ⋮ |
| `ui/screens/PhotosScreen.kt` | Lưới ảnh theo ngày, chọn nhiều, chia sẻ, xoá |
| `ui/screens/AlbumsScreen.kt` | Album, ghim album, chi tiết album, mục yêu thích |
| `ui/screens/PhotoDetailScreen.kt` | Xem ảnh vuốt ngang, pinch/double-tap zoom, video, EXIF, đổi tên, đặt làm hình nền |
| `ui/screens/SearchScreen.kt` | Tìm kiếm theo nhãn, khuôn mặt, chữ trong ảnh |
| `ui/screens/SettingsScreen.kt` | Theme, lưới, sắp xếp, bộ lọc, định dạng ngày, xem ảnh, cache, thông tin |
| `ui/screens/TrashScreen.kt` | Thùng rác hệ thống: khôi phục, xoá, dọn sạch |

## Khác với bản gốc

- Bỏ `LIMIT 1000` / `LIMIT 500` trong truy vấn: thư viện lớn được tải đầy đủ.
- Một `ContentObserver` có debounce và một cache dữ liệu chung thay vì mỗi màn hình một cái.
- Thumbnail lưới giải mã đúng kích thước ô; trình xem dùng thumbnail làm placeholder.
- Dùng thùng rác hệ thống (`MediaStore.createTrashRequest`) thay vì copy file.
- Tìm kiếm dùng model ML Kit đóng gói sẵn, chạy offline. APK release khoảng 127 MB, phần lớn là model ML Kit.

## Lộ trình

1. **Xong**: thư viện ảnh cơ bản.
2. Trình chỉnh sửa ảnh: cắt, xoay, lật, chỉnh màu, 16 filter, vẽ (`edit/*`, `EditScreenKt`).
3. Ảnh ẩn và vault: mật khẩu, sinh trắc học, khoá app (`HideScreenKt`, `VaultPasswordManager`, `BiometricHelper`).
4. Tìm kiếm và AI: **đã có** gắn nhãn, khuôn mặt, OCR; còn màn AI image (`AIImageScreenKt`, `AIImageViewModel`).
5. Billing / premium (`billing/BillingManager`, `premium/*`), quản lý album (tạo, chuyển, sao chép, ảnh bìa).
