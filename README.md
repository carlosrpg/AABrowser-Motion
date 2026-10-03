# AA Browser Motion

AA Browser Motion is an Android 15+ WebView browser for Android Auto head units, maintained by [carlosrpg](https://github.com/carlosrpg).

This repository is a fork of [kododake/AABrowser](https://github.com/kododake/AABrowser). The original project and its contributors remain the source of the core browser implementation; fork-specific changes are maintained in this repository under GPLv3.

> [!WARNING]
> AA Browser Motion is intended for development, testing, and use only while safely parked. Do not interact with the app while driving. Follow local laws and keep your attention on the road.

## Features

- Material 3 Expressive interface designed for wide car displays
- Multi-tab WebView browsing with session restore
- Bookmarks and configurable start-page quick links
- Mobile, desktop, and custom user-agent profiles
- Fullscreen media, QR sharing, site permissions, and display scaling

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

AA Browser Motion is licensed under the [GNU General Public License v3.0](LICENSE). Preserve upstream copyright and attribution notices when redistributing modified versions.
