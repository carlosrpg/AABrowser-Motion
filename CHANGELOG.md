# Changelog

## Unreleased
- Preserve browser media playback when the host loses focus, and save/restore the media position if the page is recreated.
- Correct YouTube next/previous availability and actions using the player controls, including hiding unavailable actions on Shorts.
- Add a fullscreen player control for pages with a supported fullscreen button; reveal media controls and the menu FAB on touch during fullscreen playback.
- Keep text entered with the Android Auto keyboard actionable even when no search suggestions are returned.
- Update the default start-page sites.
- Keep media playing in the background when the app is not on top, on the phone and in Android Auto.
- Previous restarts the current video when it is more than 10 seconds in; otherwise it goes to the previous item.
- Long-pressing next/previous (car keys and on-screen buttons) seeks forward/backward; a tap still skips.
- Add media resumption support: the last played page is offered as a resumable item and playing it reopens the browser. Android Auto does not currently use this to pick its startup media source.
- Fix next/previous taps being treated as long presses (always seeking), and on-screen buttons acting on the other browser when the phone and car browsers are both open.
- Fix holding an on-screen next/previous button continuing to seek after release.
- In portrait, show the fullscreen control above the menu button, at the same size as the media controls.
- CI now versions the app with semantic versioning: each main build bumps only the PATCH of the latest `vX.Y.Z` tag and publishes a `vX.Y.Z` release; major/minor bumps are made by pushing a `vX.Y.Z` tag manually, which builds and releases that exact version. The version code is derived from the version.
- Rebranded the fork as AA Browser Motion, maintained by carlosrpg.
- Added automated main-branch APK prereleases through GitHub Actions.
- Added a startup safety notice with a persistent "Don't show again" option.
- Disabled the upstream project's analytics endpoint in this fork.
- Added new User Agent profiles (Firefox, Edge, Samsung Internet).
- Added a Custom User Agent profile with user-defined string.
