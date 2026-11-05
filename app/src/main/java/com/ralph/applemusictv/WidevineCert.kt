package com.ralph.applemusictv

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Apple's Widevine service certificate, kept on disk (see MainActivity.client). */
object WidevineCert {
    @Volatile private var refreshing = false

    /** The kept copy right away (refreshed behind the scenes), or a normal fetch the first time. */
    fun response(context: Context, url: String): WebResourceResponse? {
        val file = File(context.filesDir, "widevine.cert")
        val kept = runCatching { file.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() }
        if (kept != null) {
            if (!refreshing) {
                refreshing = true
                thread(isDaemon = true) { try { fetch(url)?.let { save(file, it) } } finally { refreshing = false } }
            }
            return respond(kept)
        }
        val fresh = fetch(url) ?: return null // let the WebView try itself
        save(file, fresh)
        return respond(fresh)
    }

    private fun fetch(url: String): ByteArray? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        try {
            if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }.takeIf { it.size in 64..16_384 }
        } finally { c.disconnect() }
    }.getOrNull()

    private fun save(file: File, bytes: ByteArray) {
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(file)
        }
    }

    private fun respond(bytes: ByteArray) = WebResourceResponse("application/octet-stream", null, 200, "OK",
        mapOf(
            "Access-Control-Allow-Origin" to "https://music.apple.com",
            "Access-Control-Allow-Credentials" to "true",
            "Cache-Control" to "no-store",
        ), ByteArrayInputStream(bytes))
}
