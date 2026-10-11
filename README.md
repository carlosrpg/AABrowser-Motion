# Motion

Motion is an Android 15+ WebView browser for Android Auto projection, maintained by [carlosrpg](https://github.com/carlosrpg).

This repository is a fork of [kododake/AABrowser](https://github.com/kododake/AABrowser). The original project and its contributors remain the source of the core browser implementation; fork-specific changes are maintained in this repository under GPLv3.

> [!WARNING]
> Motion is intended for development, testing, and use only while safely parked. Do not interact with the app while driving.
> Follow local laws and keep your attention on the road.
> I'm not responsible for your bad choices!

## New Features

- Two Android Auto car modes: split-screen and fullscreen
- YouTube media controls and voice search on the phone and Android Auto head units

> [!Important]
> Android Auto reserves the majority of the screen for Navigation,
> This App uses two services, one intended for split-screen mode and the other for fullscreen mode. The fullscreen service takes over the entire screen when activated, but it uses a Virtual Display to manage its content, causing it to be extremely resource-intensive and affecting the performance considerably.

Phone voice search is declared as the `actions.intent.PLAY_MEDIA` App Action in `app/src/main/res/xml/shortcuts.xml` and handled through Android's `MEDIA_PLAY_FROM_SEARCH` intent. App Actions are registered with Google through Play Console, not by sideloading alone. In Play Console, accept the App Actions terms under **Advanced settings > App Actions**, upload the bundle to an internal or closed test track, and install it from that track. Only reviewed actions are available to test-track users by default; to test an unreviewed action, join Google's [App Actions Development Program](https://groups.google.com/g/app-actions-development-program) with the test account. Access can take up to three hours. App Actions review is separate from the Android app review. The Google Assistant plugin previously used to create local previews has been removed from the JetBrains Marketplace. Gemini does not currently invoke this App Action for custom media apps.

## Requirements

- Android 15/API 35 or newer
- Java 21
- Android SDK 37

## Build

Configure the Android SDK through `ANDROID_HOME` or an untracked `local.properties` file:

```properties
sdk.dir=C:\\Users\\<user>\\AppData\\Local\\Android\\Sdk
```

Build the signed release APK on Windows:

```powershell
.\scripts\build-phone-apk.ps1
```

The script locates the standard Android SDK, builds the locally signed release APK, verifies its signature and Android Auto metadata, and copies it to `dist/`.

Optional flags:

```powershell
.\scripts\build-phone-apk.ps1 -Clean -RunTests
```

Build the signed Android App Bundle for Google Play with:

```powershell
.\gradlew.bat app:bundleRelease
```

The bundle is written to `app\build\outputs\bundle\release\app-release.aab` and uses the same stable release signing configuration. Google Play App Signing should be enabled when creating the Play listing; retain the upload key and its credentials for future updates.

Generate the ignored release key once before the first build:

```powershell
.\scripts\setup-release-signing.ps1
.\scripts\build-phone-apk.ps1 -RunTests
```

The generated `release.keystore` and signing values in `local.properties` are excluded by `.gitignore`. Back up both files securely; every future APK update must use the same key.

The first locally signed release cannot update an installed debug-signed or upstream-signed APK because their certificates differ. Uninstall the existing package once, install the new release, and use `adb install -r` for later builds signed with this same local key.

Run local tests and Android Lint:

```powershell
.\gradlew.bat app:testDebugUnitTest
.\gradlew.bat app:lintDebug
```

## Continuous delivery

Every push to `main` runs the GitHub Actions workflow in `.github/workflows/main-release.yml`. It runs unit tests, builds and verifies a signed release APK, uploads it as a workflow artifact, and creates a prerelease tagged `main-<run number>` with the APK attached.

Configure these GitHub Actions repository secrets before enabling releases:

- `RELEASE_KEYSTORE_BASE64`: Base64-encoded contents of the stable `release.keystore`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

Generate the keystore secret value on Windows with:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore"))
```

The workflow must use the same backed-up keystore as local releases so GitHub-built APKs remain upgrade-compatible.

Download published builds from [GitHub Releases](https://github.com/carlosrpg/AABrowser-Motion/releases).

## License

Motion is licensed under the [GNU General Public License v3.0](LICENSE). Preserve upstream copyright and attribution notices when redistributing modified versions.
