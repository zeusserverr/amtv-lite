package com.ralph.applemusictv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * On-screen pointer for the remote. Held arrows glide it (accelerating; at the top/bottom edge it scrolls
 * the page instead); [animateTo] slides it to a snap target, and [focus] draws a TV-style ring around
 * the item it snapped to. [onMove] fires as it moves (used for hover).
 */
class CursorView(
    context: Context,
    private val onMove: (x: Float, y: Float) -> Unit,
    private val onScroll: (x: Float, y: Float, direction: Float) -> Unit,
) : View(context) {

    var px = -1f
        private set
    var py = -1f
        private set

    /** The item the pointer is snapped to (view coordinates), or null when moving freely. */
    var focus: RectF? = null
        set(value) {
            field = value
            wake()
        }

    private val held = LinkedHashSet<Int>()
    private var heldSince = 0L
    private var targetX = Float.NaN
    private var targetY = Float.NaN
    private var lastActive = SystemClock.uptimeMillis()
    private var lastScroll = 0L
    private var lastFrame = 0L
    private var clickPulse = 0L
    private val shownAt = SystemClock.uptimeMillis()
    private var looping = false
    private val shownRing = RectF()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE }
    private val ringGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE }
    private val hintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 20, 20, 24) }
    private val hintText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val arrow = Path()

    private val u get() = width / 1920f
    val isGliding: Boolean get() = held.isNotEmpty()

    fun press(keyCode: Int) {
        if (held.isEmpty()) heldSince = SystemClock.uptimeMillis()
        held += keyCode
        focus = null
        targetX = Float.NaN
        wake()
    }

    fun release(keyCode: Int) {
        held -= keyCode
        wake()
    }

    /** Places the pointer and its ring on [r] at once (the list moved under them; nothing should slide). */
    fun jumpTo(r: RectF) {
        focus = RectF(r)
        shownRing.set(r)
        px = r.centerX().coerceIn(0f, width - 1f)
        py = r.centerY().coerceIn(0f, height - 1f)
        targetX = Float.NaN
        onMove(px, py)
        wake()
    }

    fun animateTo(x: Float, y: Float) {
        targetX = x.coerceIn(0f, width - 1f)
        targetY = y.coerceIn(0f, height - 1f)
        wake()
    }

    fun pulse() {
        clickPulse = SystemClock.uptimeMillis()
        wake()
    }

    private fun wake() {
        lastActive = SystemClock.uptimeMillis()
        kick()
    }

    private fun kick() {
        if (!looping) {
            looping = true
            lastFrame = SystemClock.uptimeMillis()
            postOnAnimation(frame)
        }
    }

    private val fadeLater = Runnable { kick() }

    // Redraw only while something moves (glide, slide, ring, click pulse, fade-out, startup hint);
    // an always-redrawing overlay also forces the page underneath to recomposite every frame.
    private val frame = object : Runnable {
        override fun run() {
            step()
            invalidate()
            val now = SystemClock.uptimeMillis()
            val idle = now - lastActive
            val f = focus
            val ringMoving = f != null && (shownRing.isEmpty || abs(shownRing.left - f.left) + abs(shownRing.top - f.top) +
                abs(shownRing.right - f.right) + abs(shownRing.bottom - f.bottom) > 1f)
            val busy = held.isNotEmpty() || !targetX.isNaN() || ringMoving || now - clickPulse < 300 ||
                now - shownAt < 9000 || idle in IDLE_FADE_MS..(IDLE_FADE_MS + 600)
            if (busy) {
                postOnAnimation(this)
            } else {
                looping = false
                removeCallbacks(fadeLater)
                if (idle < IDLE_FADE_MS) postDelayed(fadeLater, IDLE_FADE_MS - idle)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (px < 0) { px = w / 2f; py = h / 2f }
        px = px.coerceIn(0f, w - 1f)
        py = py.coerceIn(0f, h - 1f)
        wake()
    }

    private fun step() {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastFrame).coerceIn(0L, 100L) / 1000f
        lastFrame = now
        if (width == 0) return

        // smooth slide to a snap target
        if (!targetX.isNaN()) {
            val k = 1f - exp(-dt * 18f)
            px += (targetX - px) * k
            py += (targetY - py) * k
            if (abs(targetX - px) < 0.5f && abs(targetY - py) < 0.5f) {
                px = targetX
                py = targetY
                targetX = Float.NaN
            }
            lastActive = now
            onMove(px, py)
        }

        // ring follows its target smoothly too
        focus?.let { f ->
            if (shownRing.isEmpty) shownRing.set(f)
            val k = 1f - exp(-dt * 18f)
            shownRing.set(
                shownRing.left + (f.left - shownRing.left) * k, shownRing.top + (f.top - shownRing.top) * k,
                shownRing.right + (f.right - shownRing.right) * k, shownRing.bottom + (f.bottom - shownRing.bottom) * k,
            )
        }

        if (held.isEmpty()) return
        val t = (now - heldSince) / 1000f
        val speed = (7f + 30f * min(1f, t / 0.7f)) * u * (dt * 60f)
        var dx = 0f
        var dy = 0f
        if (KeyEvent.KEYCODE_DPAD_LEFT in held) dx -= speed
        if (KeyEvent.KEYCODE_DPAD_RIGHT in held) dx += speed
        if (KeyEvent.KEYCODE_DPAD_UP in held) dy -= speed
        if (KeyEvent.KEYCODE_DPAD_DOWN in held) dy += speed

        val edge = 4f * u
        if (dy < 0 && py <= edge || dy > 0 && py >= height - 1 - edge) {
            if (now - lastScroll > 70) {
                lastScroll = now
                onScroll(px, py, if (dy < 0) 1f else -1f)
            }
            dy = 0f
        }
        px = (px + dx).coerceIn(0f, width - 1f)
        py = (py + dy).coerceIn(0f, height - 1f)
        lastActive = now
        onMove(px, py)
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val idle = now - lastActive
        val alpha = if (idle < IDLE_FADE_MS) 1f else (1f - (idle - IDLE_FADE_MS) / 500f).coerceIn(0f, 1f)

        val f = focus
        if (f != null && !shownRing.isEmpty) {
            val pad = 6f * u
            val r = min(18f * u, shownRing.height() / 2f + pad)
            ringGlow.strokeWidth = 10f * u
            ringGlow.alpha = 60
            c.drawRoundRect(shownRing.left - pad, shownRing.top - pad, shownRing.right + pad, shownRing.bottom + pad, r, r, ringGlow)
            ring.strokeWidth = 3.5f * u
            ring.alpha = 235
            c.drawRoundRect(shownRing.left - pad, shownRing.top - pad, shownRing.right + pad, shownRing.bottom + pad, r, r, ring)
        } else {
            shownRing.setEmpty()
        }

        // a small pointer while snapped, the full arrow while moving freely
        val s = (if (f != null) 22f else 34f) * u
        if (alpha > 0f) {
            arrow.reset()
            arrow.moveTo(px, py)
            arrow.lineTo(px, py + s)
            arrow.lineTo(px + s * 0.28f, py + s * 0.74f)
            arrow.lineTo(px + s * 0.48f, py + s * 1.12f)
            arrow.lineTo(px + s * 0.62f, py + s * 1.05f)
            arrow.lineTo(px + s * 0.43f, py + s * 0.68f)
            arrow.lineTo(px + s * 0.8f, py + s * 0.68f)
            arrow.close()
            outline.strokeWidth = 3f * u
            outline.alpha = (255 * alpha).toInt()
            fill.alpha = (255 * alpha).toInt()
            c.drawPath(arrow, fill)
            c.drawPath(arrow, outline)
        }
        val p = now - clickPulse
        if (p < 300) {
            ring.strokeWidth = 3f * u
            ring.alpha = (200 * (1f - p / 300f)).toInt()
            c.drawCircle(px, py, (10f + p / 10f) * u, ring)
        }
        if (now - shownAt < 9000) drawHint(c, ((9000 - (now - shownAt)) / 600f).coerceIn(0f, 1f))
    }

    private fun drawHint(c: Canvas, a: Float) {
        val text = "Tap arrows: jump to items   ·   Hold arrows: glide   ·   OK: select   ·   Back: go back   ·   ▶❚❚: play/pause"
        hintText.textSize = 26f * u
        val w = hintText.measureText(text) + 60f * u
        val left = (width - w) / 2f
        val top = height - 110f * u
        hintBg.alpha = (200 * a).toInt()
        hintText.alpha = (255 * a).toInt()
        c.drawRoundRect(left, top, left + w, top + 56f * u, 28f * u, 28f * u, hintBg)
        c.drawText(text, left + 30f * u, top + 37f * u, hintText)
    }

    private companion object {
        const val IDLE_FADE_MS = 4000L
    }
}
