# Changelog

## 0.10.0 — 2026-11-05
- **Now Playing:** a full-screen view with the cover, song, artist, album and progress on a background tinted from the cover. OK plays or pauses; ◀ ▶ skip.
- **Screensaver:** after 2 minutes without a button press, Now Playing dims and drifts slowly, and keeps the TV's own screensaver away while music plays. The first press wakes it.
- **Quick menu:** hold Back to jump to Now Playing, voice search, Search, Home, New, Radio, Recently Added or Playlists.
- **Voice search:** say an artist, album or song.
- **Languages:** the app's own text is now in Spanish, French, German, Portuguese, Italian, Japanese and Russian, as well as English.

## 0.9.4 — 2026-11-05
- Renamed to **AMTV Lite** (Apple Music TV Lite). Same app: it updates in place and you stay signed in.

## 0.9.3 — 2026-11-04
- **Fixed:** after the TV sleeps, the app could come back to a blank page until it was restarted. It now recovers by itself and reopens the page you were on (you stay signed in).
- Building from source no longer needs Gradle installed (`./gradlew`), and every change is built automatically on GitHub.

## 0.9.2 — 2026-11-04
First public release.

**Navigation**
- Up/Down move one song at a time in playlists and albums, with the list scrolling under a steady highlight.
- Opening a playlist or album puts the highlight on its **Play** button.
- OK on a song plays it straight away.
- Down scrolls long pages instead of stopping on the player bar; the sidebar's small Edit button is skipped.

**Faster and lighter**
- Song previews are no longer downloaded when a playlist opens (about 17 MB and ~20 s of delay each time).
- Apple's DRM certificate is kept on the device: Play/Shuffle reaches the playback license in ~2 s instead of 9–20 s.
- Opening a playlist keeps the page busy for 3–4 s instead of 15–18 s (album covers no longer force full-page restyles).
- Audio is buffered about 90 s ahead instead of about 5 minutes, so skips waste less data.
- The player bar hides when nothing is playing.

## 0.8 — 2026-11-01
- Remote play/pause/skip and the current song are shared with Android's media controls (launchers, screensavers).
- Keeps playing with foreground priority after pressing Home.

## 0.1–0.7
- Full-screen Apple Music web player for Android TV: remote pointer with jump-to-item navigation, native text entry for sign-in and search, background playback, lighter rendering for TV hardware.
