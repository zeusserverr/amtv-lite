package com.ralph.applemusictv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Tells Android what the web player is playing. WebView doesn't do this on its own the way Chrome does, so
 * without it the XMB's Now Playing card, the remote's play/pause (while in another app) and the screensaver's
 * song line can't see Apple Music. A small page script listens to MusicKit's events and posts the song here;
 * Android's play/pause/skip come back through [control].
 */
class MusicSession(private val context: Context, private val control: (action: String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val session = MediaSession(context, "AppleMusicWeb").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = control("play")
            override fun onPause() = control("pause")
            override fun onStop() = control("pause")
            override fun onSkipToNext() = control("next")
            override fun onSkipToPrevious() = control("previous")
        })
    }
    private var artUrl: String? = null
    private var art: Bitmap? = null
    private var last: JSONObject? = null

    fun attach(web: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        WebViewCompat.addWebMessageListener(web, BRIDGE, ORIGINS) { _, message, _, isMainFrame, _ ->
            if (!isMainFrame) return@addWebMessageListener
            val m = runCatching { JSONObject(message.data ?: "") }.getOrNull() ?: return@addWebMessageListener
            main.post { update(m) }
        }
        WebViewCompat.addDocumentStartJavaScript(web, SCRIPT, ORIGINS)
    }

    private fun update(m: JSONObject) {
        last = m
        val title = m.optString("title")
        if (title.isEmpty()) {
            session.isActive = false
            setPlaying(false, m)
            return
        }
        val url = m.optString("art").takeIf { it.startsWith("https://") }
        if (url != artUrl) {
            artUrl = url
            art = null
            if (url != null) thread(isDaemon = true) {
                val bmp = runCatching {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    try { conn.inputStream.use { BitmapFactory.decodeStream(it) } } finally { conn.disconnect() }
                }.getOrNull()
                main.post { if (artUrl == url) { art = bmp; last?.let { publish(it) } } }
            }
        }
        publish(m)
    }

    private fun publish(m: JSONObject) {
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, m.optString("title"))
            .putString(MediaMetadata.METADATA_KEY_ARTIST, m.optString("artist"))
            .putString(MediaMetadata.METADATA_KEY_ALBUM, m.optString("album"))
            .putLong(MediaMetadata.METADATA_KEY_DURATION, m.optLong("duration"))
            .apply { art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
            .build())
        val playing = m.optBoolean("playing")
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                m.optLong("position"), if (playing) 1f else 0f, SystemClock.elapsedRealtime())
            .build())
        session.isActive = true
        setPlaying(playing, m)
    }

    private var foreground = false
    private var lastTitle = ""
    private val stopLater = Runnable {
        if (foreground) { PlaybackService.stopped(context); foreground = false }
    }

    /**
     * Keeps the app at "playing music" priority while it plays (see [PlaybackService]). A pause only gives
     * that up after 30 s, so skipping tracks or a quick pause doesn't start and stop the service back to back.
     */
    private fun setPlaying(playing: Boolean, m: JSONObject) {
        val title = m.optString("title")
        if (playing) {
            main.removeCallbacks(stopLater)
            if (!foreground || title != lastTitle) PlaybackService.playing(context, title, m.optString("artist"), session.sessionToken)
            foreground = true
        } else if (foreground) {
            main.removeCallbacks(stopLater)
            main.postDelayed(stopLater, 30_000)
        }
        lastTitle = title
    }

    /** The page went away (its renderer was stopped): nothing is playing any more. */
    fun pageLost() = update(JSONObject().put("title", ""))

    fun release() {
        main.removeCallbacks(stopLater)
        if (foreground) PlaybackService.stopped(context)
        session.release()
    }

    private companion object {
        const val BRIDGE = "tvMedia"
        val ORIGINS = setOf("https://music.apple.com")

        // Waits for the player, then reports on every song or play/pause change (no polling once found).
        const val SCRIPT = """
            (function () {
              if (window.top !== window) return;
              function report(m) {
                try {
                  var it = m.nowPlayingItem;
                  var art = it && it.artwork && it.artwork.url ? it.artwork.url.replace('{w}', '512').replace('{h}', '512').replace('{f}', 'jpg') : '';
                  tvMedia.postMessage(JSON.stringify({
                    title: it ? (it.title || '') : '', artist: it ? (it.artistName || '') : '',
                    album: it ? (it.albumName || '') : '', art: art,
                    duration: it && it.playbackDuration ? Math.round(it.playbackDuration) : 0,
                    position: Math.round((m.currentPlaybackTime || 0) * 1000), playing: !!m.isPlaying
                  }));
                } catch (e) {}
              }
              var tries = 0;
              var wait = setInterval(function () {
                var m = null;
                try { m = window.MusicKit && MusicKit.getInstance(); } catch (e) {}
                if (m) {
                  clearInterval(wait);
                  ['playbackStateDidChange', 'nowPlayingItemDidChange', 'mediaItemStateDidChange'].forEach(function (ev) {
                    try { m.addEventListener(ev, function () { report(m); }); } catch (e) {}
                  });
                  report(m);
                } else if (++tries > 120) clearInterval(wait);
              }, 1000);
            })();
        """
    }
}
