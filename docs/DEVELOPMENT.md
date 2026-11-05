# Development notes

How Android Apple Music TV works inside, what was measured, and what to watch out for when changing it.

## Overview
A single-activity app. A full-screen `WebView` loads `https://music.apple.com/`, and a transparent overlay (`CursorView`) draws the remote's highlight on top. Everything that makes Apple's page TV-friendly is either Kotlin around the WebView or a small script injected at document start.

| File | Role |
|---|---|
| `MainActivity.kt` | WebView setup, remote navigation, injected scripts (`LIGHTER_CSS_JS`, `TARGETS_JS`, `ROW_STEP_JS`, ...) |
| `CursorView.kt` | Pointer, focus ring, glide, idle fade |
| `TvInput.kt` | Native text entry for every apple.com frame, including the cross-origin sign-in frame |
| `MusicSession.kt` | Bridges MusicKit's playback state to an Android `MediaSession` (media keys, launchers, screensavers) |
| `PlaybackService.kt` | Foreground `mediaPlayback` service while music plays, so it keeps priority after Home |
| `WidevineCert.kt` | Serves Apple's Widevine service certificate from disk (see below) |

## WebView setup
- **Desktop identity:** a desktop Chrome user agent built from the WebView's own Chrome version, so Apple serves the full web player.
- **Layout width:** `setInitialScale` lays the page out about 1400 CSS px wide (`CSS_WIDTH`).
- **DRM:** `RESOURCE_PROTECTED_MEDIA_ID` is granted for `apple.com` / `*.apple.com` only.
- **Sign-in pop-ups:** multiple windows are on; a pop-up opens as a child WebView on top, and Back closes it.
- **Links:** only http/https load; app schemes (`itms:`, `intent:`) are ignored.
- **Background playback:** the WebView isn't paused in `onPause`; `setRendererPriorityPolicy(IMPORTANT)` keeps the renderer from being the first thing Android reclaims.
- **Zoom off:** removes the ~300 ms double-tap wait on taps.

## Remote navigation
- **Targets:** `TARGETS_JS` returns the visible clickable elements (walking open shadow roots) as rects in view px. An `IntersectionObserver` tracks which candidates are on screen, so only those are measured. Items under the floating player bar are skipped, and the bar's own rect is returned (flag `9`) so the app can tell "on the bar" from "near the bottom".
- **Never block a key press on the page.** Jumps use the cached targets; `refresh()` runs one request at a time after actions. Blocking on the page queued presses up and then fired them all at once.
- **Jumps:** `pick()` scores candidates by distance in the arrow's direction plus 3× the sideways offset. Up/Down stay in their column (sidebar vs page). Down on the page passes over the player bar and scrolls instead; the bar is reached only at the end of a page.
- **Song lists:** when the highlight is on a `.songs-list-row`, Up/Down go through `ROW_STEP_JS`, which finds the neighbouring row, scrolls `.scrollable-page` so it sits in the middle band (30–62% of the height), and returns its rect.
- **Clicks** are touch taps (`SOURCE_TOUCHSCREEN`). Synthetic mouse clicks are ignored by WebView. OK on a song row presses that row's own Play button (`PLAY_ROW_JS`), since a tap on a row only selects it.
- **New pages:** after a click or Back, the first Down/Right waits for the new page (Apple shows placeholders first), and the highlight moves to the page's Play button by itself (`PLAY_BUTTON_JS`, polled every 400 ms for up to ~6 s, only once the address has changed).
- **Hover** is sent once the pointer rests (500 ms). Every hover restyles Apple's page.

## Making Apple's page lighter
All in `LIGHTER_CSS_JS`, music.apple.com only:
- **Previews:** playlist pages contain `<audio data-testid="preview-player" preload="auto">` elements that downloaded six ~3 MB previews on every open and delayed Play/Shuffle by ~20 s. `setAttribute`, the `preload` setter and `new Audio()` are hooked so previews stay at `preload="none"` until played.
- **Album covers:** each cover's load handler ran `getComputedStyle(img).opacity` right after changing the page's state, forcing a full restyle per cover (~130 ms × ~100 covers). For `img.artwork-component__contents` only, `getComputedStyle` answers `opacity` as `1` (other properties come from the browser). Busy time after opening a playlist went from 15–18 s to 3–4 s.
- **Rendering:** off-screen `.songs-list-row`, `main section` and shelves use `content-visibility: auto`. `backdrop-filter`, lyric blur, the animated now-playing canvas and the equalizer animation are off. Muted, looping cover videos never load. The page is told `prefers-reduced-motion`.
- **Audio buffer:** Apple's player kept ~5 minutes of audio buffered ahead. Its audio pieces (Range requests to `aod-ssl.itunes.apple.com`) are fetched with `window.fetch`, which is wrapped so a piece waits while more than 90 s is buffered ahead of `#apple-music-player`'s current time (polling every 2 s, released at once on abort, held at most 5 minutes). New or skipped-to songs start empty, so they aren't held.
- **Player bar:** hidden (`html.tv-no-music`) 4 s after playback stops, shown again when it starts (MusicKit `playbackStateDidChange`).

## Widevine certificate
Apple's service certificate (`play.itunes.apple.com/WebObjects/MZPlay.woa/wa/widevineCert`) is public, 705 bytes, identical for everyone, but served `no-store`, so it was fetched before every song, and on a TV that took ~5 s while the song itself downloaded. `shouldInterceptRequest` now answers it from `files/widevine.cert` and refreshes the file in the background. Play/Shuffle → license went from ~8.7 s to ~1.9 s.

## What can't be improved from the app
About 30% of a core while playing is Chrome's protected-audio pipeline (the MediaCodec decoder loop polled by `DoRead`), which isn't reachable from the page or the app.

## Renderer recovery
If Android stops the page's renderer (to free memory, usually while the TV sleeps) or it crashes, `onRenderProcessGone` fires once per WebView on that renderer and returns `true`:
- The dead view is removed and destroyed inside the callback, so nothing can use it afterwards. A `webGone` flag keeps queued work, media keys and `onSaveInstanceState` away from it.
- The pop-up (if any) is simply dropped. For the main view, the text-entry dialog closes and `MusicSession` is told nothing is playing.
- The new WebView is built at once if the app is on screen, otherwise in `onStart`. Rebuilding while the TV sleeps would start a new renderer under the same memory pressure and maybe without network.
- It reopens the last music.apple.com address, tracked in `doUpdateVisitedHistory` because Apple's page rarely reloads. A main-frame load error is retried up to 5 times with growing delays.
- Sign-in survives, because cookies and storage live in the app process, not the renderer.

## Measuring safely
- Fine while someone is using the TV: CDP `Network` events (without initiator stacks), `PerformanceObserver` for `longtask` / `long-animation-frame` (it names the script and the forced style/layout time), and V8 CPU sampling.
- **Avoid:** CDP `Tracing` with timeline categories, and Network capture with initiator stacks. Both stalled the app (one ANR) on a SHIELD.
- `adb input keyevent` over Wi-Fi can drop presses; space them ≥ 1.5 s apart.
- `tools/ab_playlist.py` and `tools/profile_page.py` are DevTools-protocol scripts used for these measurements (debug builds expose WebView debugging; release builds don't).

## Known issues
- The 90 s audio hold hasn't been watched over a long session; if Apple's loader times out held requests, raise `AHEAD` or remove the hold.

## Building and releasing
- Debug: `./gradlew assembleDebug`. Release: `./gradlew assembleRelease`, signed with the key named in `~/.gradle/gradle.properties` (`AMTV_STORE_FILE`, `AMTV_STORE_PASSWORD`, `AMTV_KEY_ALIAS`, `AMTV_KEY_PASSWORD`). Without those, release builds are unsigned.
- Publish: bump `versionCode`/`versionName`, rename the APK to exactly **`AndroidAppleMusicTV.apk`** (the README links `releases/latest/download/AndroidAppleMusicTV.apk`), then `gh release create vX.Y.Z AndroidAppleMusicTV.apk --latest` with the APK's SHA-256 (`sha256sum AndroidAppleMusicTV.apk`) in the notes, and add an entry to `CHANGELOG.md`.
- CI: `.github/workflows/build.yml` builds a debug APK on every push and pull request (artifact kept 14 days). Release signing never runs on GitHub; the key stays offline.
- The Downloader code **4517764** (aftv.news/4517764, registered at go.aftvnews.com) points to `releases/latest/download/AndroidAppleMusicTV.apk`, so it follows new releases as long as the asset keeps that name.
- Updates must be signed with the same key, or Android refuses to install them over the previous version.

## Ground rules
- Never handle anyone's Apple ID password: the user signs in themselves, and typed values are never logged or stored.
- Only apple.com origins get DRM and the injected scripts.
