# Legacy Android Auto Projection POC

This standalone single-module Android project demonstrates the same legacy projection entry-point shape used by Fermata/FermataX for its direct car UI:

- `ProjectionCarService` extends `com.google.android.apps.auto.sdk.CarActivityService`.
- `ProjectedDemoActivity` extends `com.google.android.apps.auto.sdk.CarActivity`.
- The service declares `CATEGORY_PROJECTION` and `CATEGORY_PROJECTION_OEM`.
- `automotive_app_desc.xml` declares the legacy `service` and `projection` capabilities.

Both projection entries render the same interactive Android View hierarchy. The sample has no `WebView`, media player, screen mirroring, `MediaProjection`, root, Accessibility service, or Xposed integration.

The APK exposes two Android Auto entries:

- **Legacy Projection POC** uses the private `CarActivityService` protocol. On portrait Coolwalk hosts, Android Auto may keep this non-navigation entry in a split tile beside the map.
- **Projection POC Fullscreen** uses an AndroidX navigation `CarAppService`, creates an app-owned `VirtualDisplay` on the host navigation surface, and shows an ordinary Android `Presentation` containing standard Views. Android Auto allocates this surface as the primary fullscreen map area.

The legacy activity cannot force the host to remove the map split. Fullscreen allocation is controlled by Android Auto and is the reason the second navigation-surface entry exists.

`ResponsiveProjectionContentView.java` is the shared content source for both `ProjectedDemoActivity` and `ProjectedContentPresentation`. It switches between vertical and horizontal arrangements and adjusts padding and text sizing whenever its projected bounds change. Another Android View implementation can replace this shared content without changing either projection protocol. Select Android Auto's pan control on the fullscreen template to enable drag/scroll forwarding.

`ProjectionPerspectiveCoordinator` makes the two protocols mutually exclusive within the app process: opening the legacy activity finishes an active AndroidX car app, and opening the fullscreen screen finishes an active legacy activity. The pinned legacy SDK exposes `CarActivityHost.finish()` but does not surface it through `CarActivity`, so `LegacyProjectionFinisher` is a narrow adapter for that bundled host API. The shared **Change perspective** button closes the current protocol and returns to Android Auto's launcher with the target entry identified. Android Auto does not expose a supported API for directly cross-launching a private legacy `CarActivityService` from AndroidX, or an AndroidX `CarAppService` from the legacy SDK.

## Build

The project uses Java 21, Android Gradle Plugin 9.4.1, compile SDK 37, and the checked-in Gradle wrapper:

```powershell
.\gradlew.bat app:assembleDebug
```

The APK is written to:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Signed release APK

Configure a stable local signing key once:

```powershell
.\scripts\setup-release-signing.ps1
```

Then build and verify a signed release APK:

```powershell
.\scripts\build-release-apk.ps1
```

Use `-Clean` when a clean release build is required:

```powershell
.\scripts\build-release-apk.ps1 -Clean
```

The shareable APK is copied to:

```text
dist\LegacyProjectionPoc-1.0-release.apk
```

The ignored `release.keystore` and `local.properties` files must be backed up and reused so future versions can update an installed release.

## DHU test outline

1. Install the APK on the Android 15+ phone used by the DHU.
2. Enable Android Auto developer mode and **Unknown sources**.
3. Start the Android Auto head-unit server on the phone.
4. Start `desktop-head-unit.exe`.
5. Enable **Legacy Projection POC** and **Projection POC Fullscreen** in Android Auto's launcher customization if they are not already visible.
6. Open **Projection POC Fullscreen** to test the host-allocated fullscreen navigation surface.

The sample exists to document and test the legacy service/activity protocol. The private projection host ultimately decides whether the service is admitted and displayed.

## Dependency provenance

`app\libs\aauto.aar` is copied from the public FermataX repository artifact at `fermata/lib/auto/aauto.aar`. The artifact exposes the legacy `com.google.android.apps.auto.sdk` API. It contains no embedded license or notice file; see `docs\legacy-android-auto-projection-report.md` in the parent repository for the evidence and qualification used by this POC.
