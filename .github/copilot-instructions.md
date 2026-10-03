# Copilot instructions for AA Browser Motion

## Build, test, and lint

Run Gradle commands from the repository root. The project uses the checked-in Gradle wrapper, Java 21, Android Gradle Plugin 9.4.1, and Android SDK 37; the minimum supported device is Android 15/API 35. Configure the Android SDK through `ANDROID_HOME` or an untracked `local.properties` entry such as `sdk.dir=C:\\Users\\<user>\\AppData\\Local\\Android\\Sdk`.

The current implementation targets standard Android first. The planned platform direction is to support both standard Android and Android Automotive, including a future Android Automotive 11/API 30 compatibility effort; do not assume that future work should replace standard Android support.

```powershell
# Windows
.\scripts\build-phone-apk.ps1
.\gradlew.bat app:assembleDebug
.\gradlew.bat app:testDebugUnitTest
.\gradlew.bat app:lintDebug
```

```bash
# macOS/Linux equivalents
./gradlew app:assembleDebug
./gradlew app:testDebugUnitTest
./gradlew app:lintDebug
```

- Run one local JUnit test with:
  `.\gradlew.bat app:testDebugUnitTest --tests "com.kododake.aabrowser.ExampleUnitTest.addition_isCorrect"`
- `.\scripts\build-phone-apk.ps1` is the preferred Windows command and produces only the signed release APK. Use `-Clean` for a clean build and `-RunTests` to run unit tests first.
- Run `.\scripts\setup-release-signing.ps1` once before the first scripted build. The ignored `release.keystore` and `local.properties` must be backed up and reused for upgrades.
- Run all instrumentation tests on a connected API 35+ device/emulator with:
  `.\gradlew.bat app:connectedDebugAndroidTest`
- Run one instrumentation test with:
  `.\gradlew.bat app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.kododake.aabrowser.ExampleInstrumentedTest#useAppContext"`
- `app:assembleDebug` finalizes with `app:debugRenameApk`; the `AABrowser-Motion-<version>_debug.apk` file is written under `app/build/renamedApks/debug/`.
- Release builds are currently unminified and use the `release` signing configuration. Signing values are resolved from Gradle properties, `local.properties`, or environment variables named `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD`.
- There is no separate ktlint/detekt task; Android Lint is the repository's lint surface.
- `app:lintDebug` currently reports pre-existing Android Auto manifest and experimental WebView API errors; do not assume a lint failure was introduced by the current change without checking the text report.

## Architecture

- This is a single `:app` Android application forked from `kododake/AABrowser`. The package/application ID remains `com.kododake.aabrowser` for compatibility, while user-facing branding and release URLs use AA Browser Motion and `carlosrpg/AABrowser-Motion`. `MainActivity` is the lifecycle and callback host, while `BrowserManagers` lazily constructs the manager graph for tabs, navigation, bookmarks, the start page, permissions, theme, overlays, and UI.
- Cross-manager dependencies are intentionally routed through `BrowserManagersProvider`, `MainActivityCallbackFactory`, `WebBrowserCallbackFactory`, and small callback interfaces/data classes. Preserve this pattern when adding behavior; directly constructing or retaining peer managers can reintroduce initialization cycles.
- Each browser tab owns a real `WebView` and `SpeechRecognitionBridge`. `TabManager` controls the tab list and active tab, `BrowserTabFactory` creates configured tabs, and `ConfiguredWebView.kt` owns WebView settings and cleanup extensions. Tab close/destroy paths must release the speech bridge and call `WebView.releaseCompletely()`.
- The main screen is a hybrid ViewBinding/Compose UI. `activity_main.xml` supplies the WebView/start-page containers and several `ComposeView` hosts; feature-specific `*Views`/`*ComposeHelper` objects install Compose content into those hosts. `SettingsActivity` is the exception and uses a full Compose content root.
- Menu, bookmark, tab, settings, QR, and version sheets share one overlay. `OverlayNavigationCoordinator` is the source of truth for transitions, back handling, returning from a submenu, and frosted-glass progress. Do not independently toggle only one overlay view when a coordinator operation exists.
- Compose screens are generally stateless rendering plus callback bundles, while managers/helpers own observable `mutableStateOf` values and persistent domain state. Update manager state and refresh the relevant UI model rather than duplicating state inside composables.
- `BrowserPreferences` is the public facade for persisted settings. Its implementation is split by domain under `data/prefs` (UI, display, bookmarks/start-page, tabs, permissions, and web settings). Add persistence logic to the appropriate domain object and expose it through the facade.
- Web behavior is concentrated under `web`: WebView configuration, clients, JavaScript bridges/scripts, SSL/cleartext handling, user agents, and speech recognition. Web callbacks update the corresponding tab first and only mirror URL/title/progress into activity UI for the active tab.

## Repository conventions

- Production Kotlin files under `app/src/main/java/com/kododake/aabrowser` use the repository GPLv3 header. Run `python scripts/add_license_header.py --dry-run` to check files or omit `--dry-run` to add missing headers.
- Keep dependencies and plugin versions in `gradle/libs.versions.toml` and consume their aliases from Gradle scripts. The existing FreeDroidWarn JitPack dependency and Google OSS licenses buildscript plugin are exceptions.
- Use `BrowserPreferences` rather than accessing `SharedPreferences` from feature or UI code. Preference readers sanitize stored values; URL persistence accepts only normalized HTTP(S) URLs through `UrlFormatter`.
- Treat `BrowserTab` as an immutable snapshot: update URL, title, and active-state data with `copy` and replace the list entry. After tab changes, persist the session and refresh the Compose tab model where the existing flow does so.
- UI actions cross layers through feature callback types such as `TabCallbacks`, `BrowserCallbacks`, `SettingsCallbacks`, and manager-specific callback interfaces. Extend the relevant callback contract and factory wiring instead of passing activities/managers deep into composables.
- Reuse the expressive components and motion/theme definitions under `ui/compose/components` and `ui/compose/theme`; sheets use the shared expressive bottom-sheet/container patterns rather than standalone dialogs or unrelated Material styling.
- Car-specific manifest behavior is intentional: the launcher/navigation categories, `distractionOptimized` metadata, resizeable wide-screen layout, and Android Auto descriptors must be preserved when changing activities or manifest declarations.
