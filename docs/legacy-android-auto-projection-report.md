# Legacy Android Auto Projection Architecture Report

## Executive summary

FermataX's built-in Web, YouTube, and Stremio interfaces are directly rendered Android views hosted by a legacy projected `CarActivity`. They are not produced by FermataX's separate screen-mirroring subsystem.

The direct UI path has four essential parts:

1. A service whose intent filter declares:
   - `com.google.android.gms.car.category.CATEGORY_PROJECTION`
   - `com.google.android.gms.car.category.CATEGORY_PROJECTION_OEM`
2. A service implementation extending `com.google.android.apps.auto.sdk.CarActivityService`.
3. A projected activity extending `com.google.android.apps.auto.sdk.CarActivity`.
4. An automotive descriptor declaring the legacy `service` and `projection` capabilities.

The two categories are discovery metadata, but they are not sufficient by themselves. The Android Auto host expects the bound service to implement the private `CarActivityService` protocol supplied by `aauto.aar`.

## Evidence from FermataX

Repository reviewed: <https://github.com/chuoinho/FermataX>

### Service declaration

`fermata/src/auto/AndroidManifest.xml` declares `me.app.fermatax.auto.CarService` with `CATEGORY_PROJECTION` and `CATEGORY_PROJECTION_OEM`.

It also references `fermata/src/auto/res/xml/automotive_app_desc.xml`, which declares:

```xml
<uses name="media" />
<uses name="service" />
<uses name="projection" />
```

### Service and activity protocol

`fermata/src/auto/java/me/app/fermatax/auto/CarService.java`:

```java
public class CarService extends CarActivityService {
    @Override
    public Class<? extends CarActivity> getCarActivity() {
        return MainCarActivity.class;
    }
}
```

`fermata/src/auto/java/me/app/fermatax/auto/MainCarActivity.java` extends `CarActivity`. Its `onCreate` path initializes the same application delegate and fragment infrastructure used by the phone UI.

### Direct browser rendering

The Web add-on uses ordinary Android fragments and a `WebView` subclass:

- `modules/web/src/main/java/me/aap/fermata/addon/web/WebBrowserFragment.java`
- `modules/web/src/main/java/me/aap/fermata/addon/web/yt/YoutubeFragment.java`
- `modules/web/src/main/java/me/aap/fermata/addon/web/stremio/StremioWebFragment.java`

`YoutubeFragment` and `StremioWebFragment` derive from the browser fragment. `MainCarActivity` attaches these fragments to its own fragment manager. This is direct rendering in the projected activity, not `MediaProjection`, `VirtualDisplay`, or arbitrary-app mirroring.

### SDK dependency

`fermata/build.gradle` includes:

```gradle
implementation files('lib/auto/aauto.aar')
```

Binary inspection confirms that the artifact contains:

- `com.google.android.apps.auto.sdk.CarActivity`
- `com.google.android.apps.auto.sdk.CarActivityService`
- `com.google.android.apps.auto.sdk.CarUiController`

`CarActivity` exposes `setContentView`, `getSupportFragmentManager`, `getCarUiController`, and normal activity lifecycle methods. This is what allows FermataX to host ordinary Android views directly.

## What is not required for the direct Web/YouTube/Stremio path

The following FermataX components belong to other features and are not required to host its built-in browser fragments:

- `MirrorService`
- `MirrorDisplay`
- `MediaProjection`
- `VirtualDisplay`
- Xposed input hooks
- root shell input dispatch
- Accessibility gesture dispatch

Those mechanisms support arbitrary activity or screen mirroring. They are separate from `CarService` plus `MainCarActivity`.

## Artifact and license evidence

The root FermataX repository declares GPL-3.0 and has publicly distributed `fermata/lib/auto/aauto.aar` throughout its upstream history.

The following qualifications were also observed:

- The AAR contains no embedded `LICENSE`, `NOTICE`, or `COPYING` file.
- FermataX's `THIRD_PARTY_NOTICES.md` does not separately identify the AAR.
- Public unofficial `aauto-sdk` repositories describe it as an unofficial Android Auto SDK and demonstrate the same service/activity API.

Therefore, the public repository provides evidence of intended redistribution under the project distribution, while the artifact itself does not provide a separate explicit license statement. The sample records both facts instead of asserting a license conclusion that is not present in the artifact.

## Standalone POC mapping

The project under `samples/legacy-projection-poc` implements the minimum architecture:

| FermataX concept | POC implementation |
|---|---|
| `CarService` | `ProjectionCarService` |
| `MainCarActivity` | `ProjectedDemoActivity` |
| Projection categories | Same two service categories |
| Legacy descriptor | `service` and `projection` uses |
| Direct Android UI | Shared responsive `ResponsiveProjectionContentView` |
| Web/YouTube/Stremio | Intentionally omitted |

Both projection entries instantiate the same ordinary Android View implementation. The component reflows between vertical and horizontal arrangements and adjusts padding and typography when its projected bounds change. Its interactive button also provides a visible input-forwarding test independently of browser behavior.

## Portrait host fullscreen behavior

The legacy `CarActivity` can hide its own app header and menu button, but it cannot force Android Auto's Coolwalk host to remove a map/app split. On portrait displays, the host may allocate a non-navigation projection service only a secondary tile.

The sample therefore also exposes `Projection POC Fullscreen`, implemented with:

- `androidx.car.app.CarAppService`
- `androidx.car.app.category.NAVIGATION`
- `androidx.car.app.ACCESS_SURFACE`
- `androidx.car.app.NAVIGATION_TEMPLATES`
- `NavigationTemplate`
- `AppManager.setSurfaceCallback`

This second entry attaches an app-owned `VirtualDisplay` to the host-provided navigation surface and shows an ordinary Android `Presentation` on that display. Touch and scroll callbacks from Android Auto are converted to `MotionEvent` instances and dispatched into the presentation's View hierarchy.

`ProjectedDemoActivity` and `ProjectedContentPresentation` both host `ResponsiveProjectionContentView`, so differences between split and fullscreen modes come from host window allocation rather than separate app content implementations.

The two protocols are mutually exclusive in the sample. `ProjectionPerspectiveCoordinator` finishes the active fullscreen car app when the legacy activity opens and finishes the legacy activity when the fullscreen screen opens. A shared **Change perspective** control returns to Android Auto's launcher and identifies the other entry.

This launcher handoff is intentional. AndroidX `CarContext.startCarApp` may target only the `CarAppService` associated with that `CarContext`, while the private legacy SDK's `startCarActivity` operates on its own projected-activity protocol. Neither API provides a supported direct cross-launch from one protocol to the other.

The pinned `aauto.aar` contains `CarActivityHost.finish()`, but `CarActivity` does not expose a public finish method. `LegacyProjectionFinisher` therefore locates the bundled host reference and invokes that host operation. The adapter is intentionally isolated because it depends on the internal structure of this exact legacy artifact.

This demonstrates the separate architecture Fermata calls its full-screen or FS path while retaining the ability to host ordinary Android Views. It is not a setting that can be applied to the legacy `CarActivity`; it is a distinct Android Auto service, surface, virtual-display, and input-forwarding contract.

The `NavigationTemplate` also exposes Android Auto's `Action.PAN`, matching the interaction mechanism used by Fermata's FS service. Pan mode allows host drag gestures to reach the surface callback and then the hosted View hierarchy.

## Operational constraints

- Android Auto must discover and admit the private projection service.
- Developer mode and Android Auto's **Unknown sources** option are typically needed for sideloaded applications.
- The DHU can exercise the host protocol, but real head units may apply different allowlists or host admission policy.
- Merely adding the two categories to a normal Android activity does not create a projected car activity.

## Conclusion

The peer implementation reaches a direct browser-capable car UI through a legacy private projection service and activity API, not through an AndroidX template category and not through screen mirroring for its built-in Web/YouTube/Stremio features. The accompanying POC isolates that architecture in a minimal project without a `WebView`.

## AABrowser implementation decision

AABrowser now exposes both entry points through `CarActivityService`. `FullscreenBrowserActivity` inherits `SplitScreenBrowserActivity`; both use the same browser shell, responsive host view, keyboard controller, and WebView input buffer. Android Auto supplies the activity's available bounds, so the content remeasures for a split tile or a larger/full-screen allocation without a separate view implementation. The app no longer creates a `VirtualDisplay` or `Presentation`; Android Auto may create its own backing display for the private projection protocol.

Both entry points share the custom projected keyboard and local text buffer for WebView inputs. Keystrokes update the local buffer immediately and mirror the focused DOM value with input events, rather than committing every character through the WebView `InputConnection`. The phone `MainActivity` remains on the standard Android system keyboard. Android Auto controls the actual window allocation; `CarActivity` cannot force the host to use navigation fullscreen.

The shared host positions the keyboard above bottom system/overlay insets and visible-window occlusion, updating when bounds or insets change. The Compose menu address field also reports focus loss so the projected keyboard is dismissed when that field is no longer active.
