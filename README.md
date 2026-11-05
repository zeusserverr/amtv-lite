<p align="center">
  <img src="docs/banner.jpg" alt="Android Apple Music TV" width="100%">
</p>

<p align="center">
  <a href="https://github.com/zeusserverr/android-apple-music-tv/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/zeusserverr/android-apple-music-tv?label=release&color=fa2d55"></a>
  <a href="https://github.com/zeusserverr/android-apple-music-tv/releases"><img alt="Downloads" src="https://img.shields.io/github/downloads/zeusserverr/android-apple-music-tv/total?color=444"></a>
  <img alt="Downloader code 4517764" src="https://img.shields.io/badge/Downloader%20code-4517764-fa2d55">
  <img alt="Android TV 8.0+" src="https://img.shields.io/badge/Android%20TV-8.0%2B-3ddc84">
  <a href="LICENSE"><img alt="MIT license" src="https://img.shields.io/badge/license-MIT-blue"></a>
</p>

<table align="center">
  <tr>
    <th>📺 On your TV</th>
    <th>💻 On a computer</th>
  </tr>
  <tr>
    <td align="center">Open the <b>Downloader</b> app and enter<br><h2><code>4517764</code></h2></td>
    <td align="center"><a href="https://github.com/zeusserverr/android-apple-music-tv/releases/latest/download/AndroidAppleMusicTV.apk"><b>⬇️ Download the APK</b></a><br><br>then <code>adb install AndroidAppleMusicTV.apk</code></td>
  </tr>
</table>

<p align="center">
  <a href="#download-and-install">Install guide</a> ·
  <a href="docs/demo-switch-playlists.mp4">Demo video</a> ·
  <a href="#screenshots">Screenshots</a> ·
  <a href="CHANGELOG.md">Changelog</a> ·
  <a href="#faq">FAQ</a>
</p>

---

The official Apple Music app isn't available for many Android TV devices, including the NVIDIA SHIELD ("Your device isn't compatible"). **Android Apple Music TV** is a full-screen, TV-friendly window onto Apple's own web player at [music.apple.com](https://music.apple.com): Apple's player does the music, and this app makes it work from the couch. It gives you a remote-controlled highlight, song-by-song lists, on-screen text entry, and playback that keeps going after you press Home.

> **Unofficial.** Not made by, endorsed by, or affiliated with Apple. "Apple Music" is a trademark of Apple Inc. The app contains no Apple code: it opens music.apple.com, and you sign in with your own Apple ID and subscription.

## Quick start

1. On your Android TV, install **Downloader** (by AFTVnews) from the Play Store.
2. Let Downloader install apps: **Settings → Apps → Special app access → Install unknown apps → Downloader**.
3. Open Downloader, enter **`4517764`**, then choose **Install** when the download finishes.
4. Open **Android Apple Music TV** and sign in with your Apple ID. That's it; you stay signed in.

Need more detail? See the [full install guide](#download-and-install).

## What it can do

| Feature | What it does |
|---|---|
| **Remote-first navigation** | The arrow keys jump between buttons, tiles and songs, with a highlight showing where you are. No mouse or touch needed. |
| **Song-by-song lists** | In playlists and albums, Up and Down move exactly one song per press while the list scrolls under a steady highlight. |
| **Lands on Play** | Open a playlist or album and the highlight moves to its **Play** button by itself. |
| **OK plays a song** | OK on a song starts it straight away (on Apple's site a single tap only selects it). |
| **Keeps playing in the background** | Press Home and the music carries on, with the right Android priority so it doesn't stutter or get closed. |
| **TV media controls** | Play/pause, next and previous on the remote work, and the song shows up in Android's media controls (and in launchers that read them). |
| **Signing in** | A native on-screen text box for Apple ID sign-in and Search, so the TV keyboard works properly. |
| **Player bar only when needed** | Apple's floating player bar hides when nothing is playing, so it doesn't cover pages. |

### Made lighter for TV hardware
Measured on an NVIDIA SHIELD (2019):
- **Much faster to start:** after Play or Shuffle, Apple's playback license arrives in about 2 seconds instead of 9–20. Apple's DRM certificate is kept on the device instead of being downloaded again for every song. The very first song after the app opens can still take a few seconds longer.
- **No wasted downloads:** Apple's page used to download six 30-second song previews (about 17 MB) every time a playlist opened. They now load only if you play one.
- **About 5 times less busy after opening a playlist** (3–4 s instead of 15–18 s), so the remote stays responsive.
- **Less audio downloaded ahead:** the app holds Apple's player to about 90 seconds ahead instead of about 5 minutes, so skipping songs wastes less data.
- **Lighter page:** off-screen song rows skip styling and layout, animated cover videos and blur effects are turned off, and hover effects are sent only when the pointer rests.

## Screenshots

<table>
  <tr>
    <td width="50%"><img src="docs/screenshots/02-home-remote.jpg" alt="Remote highlight on Home"><br><sub>Moving around Home with the remote</sub></td>
    <td width="50%"><img src="docs/screenshots/03-playlist-autoplay-focus.jpg" alt="Playlist opens with Play highlighted"><br><sub>A playlist opens with <b>Play</b> already highlighted</sub></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/04-song-by-song.jpg" alt="Song-by-song navigation"><br><sub>Down moves one song at a time</sub></td>
    <td><img src="docs/screenshots/05-playing.jpg" alt="Playing"><br><sub>Playing, with Apple's player bar</sub></td>
  </tr>
</table>

**Video (30 s):** [switching between two playlists with the remote](docs/demo-switch-playlists.mp4)

## Requirements
- An Android TV device running **Android 8.0 or newer** (tested on an NVIDIA SHIELD, Android 11).
- **Android System WebView** up to date (from the Play Store), since Apple's web player needs a recent Chrome engine.
- An **Apple Music subscription** and Apple ID.
- A TV remote (a game controller works too).

## Download and install

Every route below always installs the **newest version**: the Downloader code **`4517764`**, the short link [aftv.news/4517764](http://aftv.news/4517764), and the [direct APK link](https://github.com/zeusserverr/android-apple-music-tv/releases/latest/download/AndroidAppleMusicTV.apk). Older versions and change notes are on the [Releases page](https://github.com/zeusserverr/android-apple-music-tv/releases).

### Option 1: Downloader code (recommended)
1. Install **Downloader** by AFTVnews from the Play Store on your TV.
2. Allow it to install apps. Depending on your TV this is one of:
   - **Settings → Apps → Special app access → Install unknown apps → Downloader → Allowed**
   - **Settings → Device Preferences → Security & restrictions → Unknown sources → Downloader → On**
3. Open Downloader, select the address box and enter **`4517764`**, then choose **Go**.
4. When the download finishes, choose **Install**, then **Done**. (Downloader may offer to delete the APK file afterwards; that's safe.)
5. Open **Android Apple Music TV**, choose **Sign In** on Apple's page, and sign in with the on-screen text box.

### Option 2: from a computer with adb
1. On the TV, turn on **Developer options** (Settings → Device Preferences → About → select **Build** 7 times), then **USB debugging** or **Network debugging**.
2. [Download the APK](https://github.com/zeusserverr/android-apple-music-tv/releases/latest/download/AndroidAppleMusicTV.apk) and run:
   ```bash
   adb connect <your-TV's-IP-address>
   adb install AndroidAppleMusicTV.apk
   ```

### Updating
Use the same code or link again: the new version installs over the old one, and you stay signed in.

## Using it

| Remote | What it does |
|---|---|
| **Arrows** | Move between items; in song lists, one song per press |
| **Hold an arrow** | Keep moving; where there's nothing to jump to, the pointer glides freely |
| **OK** | Select / play |
| **Back** | Go back a page; on the first page, leave the app with the music still playing |
| **Home** | Back to your launcher; the music keeps playing |
| **Play/Pause, ⏭, ⏮** | Control playback from anywhere |

## FAQ

**Is it safe to sign in?**
You sign in on Apple's own sign-in page inside the app, just like in a browser. Because TV keyboards don't work well inside web pages, the app shows its own text box and passes what you type straight into Apple's field. It doesn't save, log or send it anywhere else, and the code is here to check (`TvInput.kt`).

**Do I need an Apple Music subscription?**
Yes. Without one, Apple's player only plays previews.

**Does it work on my TV?**
It needs Android TV 8.0 or newer with an up-to-date **Android System WebView** that supports Widevine, which covers most Android TV and Google TV devices. It's tested on an NVIDIA SHIELD. Amazon Fire TV hasn't been tested.

**Lossless, Hi-Res or Dolby Atmos?**
No. Apple's web player streams 256 kbps AAC, and this app can only play what the web player offers.

**Why not the official app?**
Apple's Android TV app isn't offered for many devices. This app works on those devices because it uses the web player Apple provides for browsers.

**Something isn't working.**
[Open an issue](https://github.com/zeusserverr/android-apple-music-tv/issues/new/choose) with your TV model and what happened. A short phone video of what the remote does helps a lot.

## Known limits
- **It's Apple's web player.** What music.apple.com supports is what you get: 256 kbps AAC, no Lossless or Dolby Atmos (the web player doesn't offer them), and it depends on Apple not changing their website in ways that break it.
- **Playback uses about half of one CPU core** (on a SHIELD), mostly Android's protected-audio decoding, which the app can't change.
- **Some devices may not play protected audio.** Apple's player needs Widevine DRM in the system WebView. Most Google TV / Android TV devices have it; Amazon Fire TV is untested.
- **Text entry** goes through a native box at the top of the screen rather than Apple's own fields.

## Building from source
Requirements: JDK 17, Android SDK (platform 34), Gradle 8.7.

```bash
gradle assembleDebug        # debug build: app/build/outputs/apk/debug/app-debug.apk
gradle assembleRelease      # release build (see "Signing" below)
```

**Signing:** release builds are signed with a key you provide in `~/.gradle/gradle.properties`:

```properties
AMTV_STORE_FILE=C:/path/to/release.keystore
AMTV_STORE_PASSWORD=...
AMTV_KEY_ALIAS=...
AMTV_KEY_PASSWORD=...
```

Keep that keystore safe. Updates must be signed with the same key, or Android refuses to install them over the old version.

## How it works
- A full-screen Android `WebView` loads music.apple.com with a desktop browser identity (so Apple serves the full web player), laid out about 1400 CSS pixels wide so it reads well on a TV.
- **Remote navigation:** the app reads the page's clickable items, picks the best one in the arrow's direction, and moves a highlight there. Clicks are sent as taps. In song lists, the page itself finds the next or previous song and scrolls it into place.
- **Widevine** is allowed for apple.com only. A foreground media service keeps playback alive in the background.
- **Lightness work** is done with small scripts injected at page start: the preview, cover and player-bar fixes listed above. The DRM certificate is served from the device via `shouldInterceptRequest`.
- Developer notes and measurements: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## License
[MIT](LICENSE) © zeusserverr. "Apple Music" and Apple are trademarks of Apple Inc.; this project is independent and unofficial.
