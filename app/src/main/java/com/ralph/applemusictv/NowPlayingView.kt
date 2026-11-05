package com.ralph.applemusictv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/**
 * Full-screen Now Playing, drawn by the app (not Apple's page): big cover, song, artist, album, progress, and a
 * background tinted from the cover. Controls show on any key and fade after a few seconds. After [AMBIENT_MS]
 * without a key press it becomes a screensaver: dimmer, text only, slowly drifting so nothing burns in; the first
 * key press brings it back. It redraws about once a second, so it costs almost nothing while music plays.
 */
class NowPlayingView(
    context: Context,
    private val control: (action: String) -> Unit,
) : View(context) {

    var state: NowState? = null
        set(value) {
            if (value?.art !== field?.art) tint = null
            field = value
            keepScreenOn = value?.playing == true // the TV's own screensaver stays away while music plays
            invalidate()
        }

    private var lastInput = SystemClock.uptimeMillis()
    private var controlsUntil = 0L
    private var ambient = false

    fun show() {
        visibility = VISIBLE
        touch()
        schedule(0L)
    }

    fun hide() {
        visibility = GONE
        keepScreenOn = false
        ambient = false
        removeCallbacks(tick)
    }

    // One redraw timer (progress, drift, the controls fading). Redraws from new songs or key presses don't start
    // another one, so the rate stays at 4 or 1 per second however long it's open.
    private val tick = Runnable {
        invalidate()
        val now = SystemClock.uptimeMillis()
        schedule(if (!ambient && now < controlsUntil + 500) 250L else 1000L)
    }

    private fun schedule(delay: Long) {
        removeCallbacks(tick)
        if (isOpen) postDelayed(tick, delay)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    val isOpen: Boolean get() = visibility == VISIBLE

    private fun touch() {
        val now = SystemClock.uptimeMillis()
        lastInput = now
        controlsUntil = now + CONTROLS_MS
        ambient = false
        invalidate()
        schedule(250L) // faster again while the controls show
    }

    /** In the screensaver: back to the normal view (the press that does this does nothing else). */
    fun wake(): Boolean {
        if (!ambient) return false
        touch()
        return true
    }

    /** Keys while open (Back is handled by the activity). Returns true if handled; volume etc. pass through. */
    fun onKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        val isMedia = code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY ||
            code == KeyEvent.KEYCODE_MEDIA_PAUSE || code == KeyEvent.KEYCODE_MEDIA_NEXT || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
            code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || code == KeyEvent.KEYCODE_MEDIA_REWIND
        val ours = isMedia || code in OUR_KEYS
        if (!ours) return false
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount > 0) return true
        // in the screensaver the first press only wakes it (media keys still work straight away)
        if (ambient && !isMedia) { touch(); return true }
        touch()
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> control("toggle")
            KeyEvent.KEYCODE_DPAD_LEFT -> control("previous")
            KeyEvent.KEYCODE_DPAD_RIGHT -> control("next")
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> control("toggle")
            KeyEvent.KEYCODE_MEDIA_PLAY -> control("play")
            KeyEvent.KEYCODE_MEDIA_PAUSE -> control("pause")
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> control("next")
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> control("previous")
        }
        return true
    }

    // ---- drawing ----
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val bold = Paint(text).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val art = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val clip = Path()
    private var tint: Int? = null
    private var blurred: Bitmap? = null

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val u = w / 1920f
        val now = SystemClock.uptimeMillis()
        if (!ambient && now - lastInput > AMBIENT_MS) ambient = true
        val s = state
        val cover = s?.art
        if (tint == null) {
            tint = cover?.let { vividColor(it) } ?: Color.rgb(250, 45, 85)
            blurred = cover?.let { Bitmap.createScaledBitmap(it, 24, 24, true) }
        }
        val t = tint!!
        // background: the cover, blurred and dark, under a gradient of its strongest color
        c.drawColor(Color.BLACK)
        blurred?.let {
            art.alpha = if (ambient) 60 else 110
            rect.set(-0.1f * w, -0.1f * h, 1.1f * w, 1.1f * h)
            c.drawBitmap(it, null, rect, art)
        }
        fill.shader = LinearGradient(0f, 0f, 0f, h, shade(t, 0.45f, 200), shade(t, 0.08f, 245), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        if (ambient) { fill.color = Color.argb(110, 0, 0, 0); c.drawRect(0f, 0f, w, h, fill) }

        // the screensaver drifts slowly (a full loop takes several minutes)
        val secs = now / 1000f
        val dx = if (ambient) sin(secs * 0.021f) * 160f * u else 0f
        val dy = if (ambient) sin(secs * 0.017f + 1f) * 70f * u else 0f
        val size = (if (ambient) 440f else 520f) * u
        val cx = 300f * u + dx
        val cy = (h - size) / 2f + dy
        rect.set(cx, cy, cx + size, cy + size)
        if (cover != null) {
            clip.reset(); clip.addRoundRect(rect, 18f * u, 18f * u, Path.Direction.CW)
            c.save(); c.clipPath(clip)
            art.alpha = if (ambient) 225 else 255
            c.drawBitmap(cover, null, rect, art)
            c.restore()
        } else {
            fill.color = Color.argb(60, 255, 255, 255)
            c.drawRoundRect(rect, 18f * u, 18f * u, fill)
        }

        val tx = cx + size + 80f * u
        val maxW = w - tx - 120f * u
        if (s == null) {
            text.textSize = 44f * u; text.alpha = 220
            c.drawText(context.getString(R.string.np_nothing), tx, h / 2f, text)
        } else {
            bold.textSize = 60f * u; bold.alpha = if (ambient) 230 else 255
            c.drawText(fit(s.title, bold, maxW), tx, cy + size * 0.36f, bold)
            text.textSize = 38f * u; text.alpha = if (ambient) 190 else 220
            c.drawText(fit(s.artist, text, maxW), tx, cy + size * 0.36f + 64f * u, text)
            text.textSize = 30f * u; text.alpha = if (ambient) 130 else 160
            if (s.album.isNotBlank()) c.drawText(fit(s.album, text, maxW), tx, cy + size * 0.36f + 112f * u, text)

            // progress
            val barY = cy + size * 0.36f + 170f * u
            val barW = min(maxW, 760f * u)
            val pos = s.positionNow()
            fill.color = Color.WHITE
            fill.alpha = 60
            rect.set(tx, barY, tx + barW, barY + 6f * u); c.drawRoundRect(rect, 3f * u, 3f * u, fill)
            if (s.durationMs > 0) {
                fill.alpha = 230
                rect.set(tx, barY, tx + barW * (pos.toFloat() / s.durationMs).coerceIn(0f, 1f), barY + 6f * u)
                c.drawRoundRect(rect, 3f * u, 3f * u, fill)
            }
            text.textSize = 26f * u; text.alpha = 170
            c.drawText(clock(pos), tx, barY + 44f * u, text)
            if (s.durationMs > 0) {
                text.textAlign = Paint.Align.RIGHT
                c.drawText(clock(s.durationMs), tx + barW, barY + 44f * u, text)
                text.textAlign = Paint.Align.LEFT
            }

            // controls, only for a few seconds after a key press
            if (!ambient && now < controlsUntil) {
                val by = barY + 130f * u
                val sz = 26f * u
                fill.alpha = 235
                drawPrev(c, tx + 40f * u, by, sz)
                if (s.playing) drawPause(c, tx + 190f * u, by, sz) else drawPlay(c, tx + 190f * u, by, sz)
                drawNext(c, tx + 340f * u, by, sz)
                text.textSize = 22f * u; text.alpha = 150
                c.drawText(context.getString(R.string.np_hint), tx, by + 90f * u, text)
            }
        }

    }

    private fun drawPlay(c: Canvas, x: Float, y: Float, s: Float) {
        clip.reset(); clip.moveTo(x - s * 0.6f, y - s); clip.lineTo(x + s, y); clip.lineTo(x - s * 0.6f, y + s); clip.close()
        c.drawPath(clip, fill)
    }

    private fun drawPause(c: Canvas, x: Float, y: Float, s: Float) {
        rect.set(x - s * 0.75f, y - s, x - s * 0.2f, y + s); c.drawRect(rect, fill)
        rect.set(x + s * 0.2f, y - s, x + s * 0.75f, y + s); c.drawRect(rect, fill)
    }

    private fun drawPrev(c: Canvas, x: Float, y: Float, s: Float) {
        rect.set(x - s, y - s, x - s * 0.75f, y + s); c.drawRect(rect, fill)
        clip.reset(); clip.moveTo(x + s, y - s); clip.lineTo(x - s * 0.6f, y); clip.lineTo(x + s, y + s); clip.close()
        c.drawPath(clip, fill)
    }

    private fun drawNext(c: Canvas, x: Float, y: Float, s: Float) {
        rect.set(x + s * 0.75f, y - s, x + s, y + s); c.drawRect(rect, fill)
        clip.reset(); clip.moveTo(x - s, y - s); clip.lineTo(x + s * 0.6f, y); clip.lineTo(x - s, y + s); clip.close()
        c.drawPath(clip, fill)
    }

    private fun fit(s: String, p: Paint, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        var end = s.length
        while (end > 1 && p.measureText(s, 0, end) + p.measureText("…") > maxW) end--
        return s.substring(0, end) + "…"
    }

    private fun clock(ms: Long): String {
        val sec = ms / 1000
        return if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)
    }

    private fun shade(c: Int, f: Float, a: Int) = Color.argb(a, (Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())

    /** The cover's strongest color (hue with the most saturated, bright pixels), or a soft grey for grey covers. */
    private fun vividColor(bmp: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(bmp, 24, 24, true)
        val weight = FloatArray(24)
        val hsv = FloatArray(3)
        var satSum = 0f
        for (y in 0 until 24) for (x in 0 until 24) {
            Color.colorToHSV(small.getPixel(x, y), hsv)
            val wgt = hsv[1] * hsv[1] * hsv[2]
            weight[(hsv[0] / 15f).toInt().coerceIn(0, 23)] += wgt
            satSum += wgt
        }
        if (small !== bmp) small.recycle()
        val best = weight.indices.maxByOrNull { weight[it] } ?: 0
        if (satSum < 4f) return Color.rgb(120, 135, 160)
        return Color.HSVToColor(floatArrayOf(best * 15f + 7.5f, 0.75f, 0.85f))
    }

    companion object {
        /** No key press for this long and Now Playing becomes a screensaver. */
        const val AMBIENT_MS = 2 * 60_000L
        const val CONTROLS_MS = 5_000L
        private val OUR_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A,
        )
    }
}
