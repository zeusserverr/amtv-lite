package com.ralph.applemusictv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.View

/**
 * Hold Back anywhere for this panel: straight to Now Playing, the main sections of Apple Music, or voice search,
 * instead of many arrow presses. Drawn by the app; Up/Down choose, OK opens, Back closes.
 */
class QuickMenuView(context: Context, private val onPick: (Item) -> Unit) : View(context) {

    enum class Item(val label: Int, val path: String?) {
        NOW_PLAYING(R.string.qm_now_playing, null),
        VOICE(R.string.qm_voice, null),
        SEARCH(R.string.qm_search, "/search"),
        HOME(R.string.qm_home, "/home"),
        NEW(R.string.qm_new, "/new"),
        RADIO(R.string.qm_radio, "/radio"),
        RECENT(R.string.qm_recent, "/library/recently-added"),
        PLAYLISTS(R.string.qm_playlists, "/library/all-playlists"),
    }

    private val items = Item.values()
    private var index = 0

    val isOpen: Boolean get() = visibility == VISIBLE

    fun show() {
        index = 0
        visibility = VISIBLE
        invalidate()
    }

    fun hide() {
        visibility = GONE
    }

    /** Keys while open (Back is handled by the activity). */
    fun onKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (index > 0) index--
            KeyEvent.KEYCODE_DPAD_DOWN -> if (index < items.size - 1) index++
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A ->
                if (e.repeatCount == 0) { hide(); onPick(items[index]) }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> Unit
            else -> return false
        }
        invalidate()
        return true
    }

    private val shade = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(240, 22, 20, 26) }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(250, 45, 85) }
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 255, 255, 255) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val title = Paint(text).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val u = w / 1920f
        c.drawRect(0f, 0f, w, h, shade)
        val pw = 560f * u
        val left = w - pw
        rect.set(left, 0f, w, h); c.drawRect(rect, panel)
        rect.set(left, 0f, left + 4f * u, h); c.drawRect(rect, accent)

        title.textSize = 30f * u; title.alpha = 255
        c.drawText(context.getString(R.string.qm_title), left + 60f * u, 150f * u, title)
        val step = 74f * u
        val y0 = 250f * u
        for ((i, item) in items.withIndex()) {
            val y = y0 + i * step
            if (i == index) {
                rect.set(left + 36f * u, y - 46f * u, w - 36f * u, y + 20f * u)
                c.drawRoundRect(rect, 12f * u, 12f * u, highlight)
            }
            text.textSize = 32f * u
            text.alpha = if (i == index) 255 else 185
            c.drawText(context.getString(item.label), left + 64f * u, y, text)
        }
        text.textSize = 22f * u; text.alpha = 130
        c.drawText(context.getString(R.string.qm_hint), left + 64f * u, h - 70f * u, text)
    }
}
