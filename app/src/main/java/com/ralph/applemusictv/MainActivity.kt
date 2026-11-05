package com.ralph.applemusictv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONTokener
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Full-screen wrapper around the Apple Music web player (music.apple.com) for Android TV:
 * desktop user agent, Widevine (protected media) allowed for apple.com, sign-in pop-ups kept in-app,
 * native TV text entry ([TvInput]) and a remote-driven pointer that snaps to clickable items.
 */
class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var web: TvWebView
    private var popup: TvWebView? = null
    private lateinit var cursor: CursorView
    private lateinit var input: TvInput
    private val handler = Handler(Looper.getMainLooper())

    // Arrow presses jump between items right away. Holding an arrow repeats the jumps, getting faster
    // (like a TV list); only where there is nothing to jump to (e.g. Apple's sign-in frame) does a held
    // arrow glide the pointer freely.
    private val repeaters = HashMap<Int, Runnable>()
    private val gliding = HashSet<Int>()
    private var lastJumpMoved = false

    // Clickable items on screen. Jumps always use this cached list right away; it is refreshed in the
    // background, one request at a time, because the page's JS thread can be busy for a long time and
    // making each press wait for it queued presses up and then fired them all at once.
    private var targets: List<RectF> = emptyList()
    private var primaryTargets: List<RectF> = emptyList() // Play / Shuffle buttons
    private var playTargets: List<RectF> = emptyList() // just the Play buttons
    private var fetching = false
    private var targetsAt = 0L
    /** When the page last changed (a click, Back, a scroll): targets read before then describe another page. */
    private var pageChangedAt = 0L

    private fun pageChanged() { pageChangedAt = SystemClock.uptimeMillis() }

    /** A page was just opened (click or Back): the first Down/Right enters it at the top, see [enterNewPage]. */
    private var newPage = false
    private var newPageTries = 0
    /** The address when [newPage] was set: a click that plays something without leaving the page isn't a new page. */
    private var newPageFrom: String? = null
    private val refreshTargets = Runnable { refresh() }

    private fun refresh(then: (() -> Unit)? = null) {
        if (fetching) {
            if (then != null) handler.postDelayed({ refresh(then) }, 100)
            return
        }
        fetching = true
        val asked = SystemClock.uptimeMillis()
        fetchTargets { all, primary ->
            fetching = false
            targets = all
            primaryTargets = primary
            targetsAt = asked
            then?.invoke()
        }
    }

    // hover is throttled: the page re-styles on every hover event, which made sliding janky
    private var lastHover = 0L
    private val trailingHover = Runnable { sendHover() }

    private val top: WebView get() = popup ?: web
    private val u: Float get() = root.width / 1920f

    private lateinit var music: MusicSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        input = TvInput(this)
        music = MusicSession(this) { action -> web.evaluateJavascript(MEDIA_JS.replace("%ACTION%", action), null) }
        // debug builds only: lets Chrome DevTools (over adb) profile the page
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = newWebView()
        root.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        music.attach(web)
        cursor = CursorView(this, onMove = ::hover, onScroll = ::scroll)
        root.addView(cursor, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        CookieManager.getInstance().setAcceptCookie(true)
        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) web.loadUrl(HOME)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        invalidateTargets(1000)
    }

    // The WebView is deliberately not paused here, so music keeps playing after leaving the app.
    override fun onPause() {
        handler.removeCallbacks(refreshTargets)
        CookieManager.getInstance().flush()
        super.onPause()
    }

    /** Re-read the clickable items soon (after the page has had a moment to change). */
    private fun invalidateTargets(delayMs: Long) {
        handler.removeCallbacks(refreshTargets)
        handler.postDelayed(refreshTargets, delayMs)
    }

    override fun onDestroy() {
        input.dismiss()
        closePopup()
        web.destroy()
        music.release()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): TvWebView = TvWebView(this).apply {
        setBackgroundColor(Color.BLACK)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            // no double-tap zoom: otherwise every click waits ~300 ms to see if a second tap follows
            setSupportZoom(false)
            builtInZoomControls = false
            setSupportMultipleWindows(true)
            useWideViewPort = false
            loadWithOverviewMode = false
            userAgentString = desktopUserAgent()
        }
        // The SHIELD runs close to its 3 GB; keep the page's renderer from being the first thing squeezed.
        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        // Lay the page out about CSS_WIDTH CSS pixels wide, so the desktop player fits and reads well on a TV.
        setInitialScale((resources.displayMetrics.widthPixels * 100f / CSS_WIDTH).roundToInt())
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webChromeClient = chrome
        webViewClient = client
        input.attach(this)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(this, LIGHTER_CSS_JS, setOf("https://music.apple.com"))
        }
    }

    /** Desktop Chrome on Windows, using this WebView's real Chrome version. */
    private fun desktopUserAgent(): String {
        val major = Regex("Chrome/(\\d+)").find(WebSettings.getDefaultUserAgent(this))?.groupValues?.get(1) ?: "126"
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$major.0.0.0 Safari/537.36"
    }

    private val chrome = object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            val host = request.origin.host.orEmpty()
            val apple = host == "apple.com" || host.endsWith(".apple.com")
            runOnUiThread {
                if (apple && PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in request.resources) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                } else {
                    request.deny()
                }
            }
        }

        // Apple ID sign-in may open a pop-up window; show it on top, inside the app.
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            closePopup()
            val child = newWebView()
            root.addView(child, root.indexOfChild(cursor), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            popup = child
            cursor.focus = null
            (resultMsg.obj as WebView.WebViewTransport).webView = child
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            if (window == popup) closePopup()
        }
    }

    private val client = object : WebViewClient() {
        // Apple's Widevine service certificate: public, 705 bytes and the same for everyone, but marked "no-store",
        // so it was fetched again before every song, and on the TV that request took ~5 s while the song itself
        // was downloading. It's served from a kept copy at once and refreshed in the background.
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            if (request.method != "GET" || url.host != "play.itunes.apple.com" || url.path?.endsWith("/wa/widevineCert") != true) return null
            return WidevineCert.response(this@MainActivity, url.toString())
        }

        // Stay on web pages; ignore app links (itms:, intent:, ...) that would leave the TV stuck.
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme
            return scheme != "http" && scheme != "https"
        }

        override fun onPageFinished(view: WebView, url: String) {
            CookieManager.getInstance().flush()
            pageChanged()
            invalidateTargets(300)
        }
        // (xp.apple.com analytics was blocked in v0.4; Apple's code then threw an "XHR error" every few
        //  seconds and retried, so it is no longer blocked)
    }

    private fun closePopup() {
        popup?.let {
            root.removeView(it)
            it.destroy()
        }
        popup = null
        cursor.focus = null
        pageChanged()
        invalidateTargets(200)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> onArrow(e)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> if (down && e.repeatCount == 0) click()
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> if (!down) back()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND -> if (down && e.repeatCount == 0) media(e.keyCode)
            else -> return super.dispatchKeyEvent(e)
        }
        return true
    }

    // ---- song lists (playlists, albums): one song per press, the list scrolls under a steady highlight ----
    private var onSongRow = false
    private var rowBusy = false
    private var rowQueued = 0

    /**
     * Up/Down on a song row: the page finds the neighbouring row itself, scrolls it into the middle band if
     * needed, and reports where it is. No guessing from a stale list of targets, no mouse-wheel overshoot.
     */
    private val rowUnstick = Runnable { rowBusy = false }

    private fun stepRow(code: Int) {
        if (rowBusy) { rowQueued = code; return }
        val f = cursor.focus ?: return
        rowBusy = true
        handler.postDelayed(rowUnstick, 1500) // if the page never answers (popup closed...), don't stay stuck
        val js = withPoint(ROW_STEP_JS, f.centerX(), f.centerY()).replace("%DIR%", if (code == KeyEvent.KEYCODE_DPAD_DOWN) "1" else "-1")
        top.evaluateJavascript(js) { result ->
            rowBusy = false
            handler.removeCallbacks(rowUnstick)
            val arr = runCatching { JSONArray(JSONTokener(result).nextValue() as String) }.getOrNull()
            if (arr == null || arr.length() < 4) {
                // the first or last song (or not a list after all): leave the list the usual way, with targets
                // re-read now (the list just scrolled under the old ones)
                onSongRow = false
                rowQueued = 0
                handler.removeCallbacks(refreshTargets)
                refresh { jumpWith(code, retry = true) }
                return@evaluateJavascript
            } else {
                val k = top.width / arr.getDouble(4).toFloat()
                val l = arr.getDouble(0).toFloat() * k
                val t = arr.getDouble(1).toFloat() * k
                cursor.jumpTo(RectF(l, t, l + arr.getDouble(2).toFloat() * k, t + arr.getDouble(3).toFloat() * k))
                lastJumpMoved = true
                invalidateTargets(300)
            }
            val next = rowQueued
            rowQueued = 0
            if (next != 0) stepRow(next)
        }
    }

    /** Fills in a point given in view px, plus the view width the page uses to convert it to CSS px. */
    private fun withPoint(js: String, x: Float, y: Float) =
        js.replace("%X%", x.toString()).replace("%Y%", y.toString()).replace("%W%", top.width.toString())

    private fun onArrow(e: KeyEvent) {
        val code = e.keyCode
        if (e.action == KeyEvent.ACTION_DOWN) autoGen++
        val vertical = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
        if (onSongRow && vertical && cursor.focus != null && e.action == KeyEvent.ACTION_DOWN) {
            repeaters.remove(code)?.let { handler.removeCallbacks(it) }
            if (gliding.remove(code)) cursor.release(code)
            // held: one song every REPEAT_ROW_MS, never faster than the page answers
            if (e.repeatCount == 0 || e.eventTime - lastRowStep >= REPEAT_ROW_MS) {
                lastRowStep = e.eventTime
                stepRow(code)
            }
            return
        }
        if (e.action == KeyEvent.ACTION_DOWN) {
            if (e.repeatCount == 0 && code !in repeaters && code !in gliding) {
                jump(code, retry = true)
                var interval = HOLD_MS
                val repeat = object : Runnable {
                    override fun run() {
                        if (!lastJumpMoved) {
                            // nothing to jump to here: glide freely instead
                            repeaters.remove(code)
                            gliding += code
                            cursor.press(code)
                            return
                        }
                        jump(code, retry = true)
                        interval = max(REPEAT_MIN_MS, (interval * 0.75f).toLong())
                        handler.postDelayed(this, interval)
                    }
                }
                repeaters[code] = repeat
                handler.postDelayed(repeat, HOLD_MS)
            }
        } else if (e.action == KeyEvent.ACTION_UP) {
            repeaters.remove(code)?.let { handler.removeCallbacks(it) }
            if (gliding.remove(code)) {
                cursor.release(code)
                if (gliding.isEmpty()) snapNearest()
            }
        }
    }

    /** Move to the best clickable item in the arrow's direction; at the end of the page, scroll and retry. */
    private fun jump(code: Int, retry: Boolean) {
        // right after opening a playlist (or going back) the cached items belong to the previous page: the
        // first press reads the new page first, so it doesn't jump to where things used to be
        if (targets.isEmpty() || targetsAt < pageChangedAt) refresh { jumpWith(code, retry) } else jumpWith(code, retry)
    }

    /**
     * The first Down/Right after opening a page: the pointer is still where the old page's tile was, and Apple's
     * page draws placeholders for a moment before its content. Wait (up to ~5 s) for the content, then land on
     * its Play button or first item, instead of on the player bar or the sidebar below the pointer.
     */
    /** Bumped by every arrow press: a press made while the page loads wins over the automatic placement. */
    private var autoGen = 0

    /**
     * After opening a playlist or album, put the pointer on its Play button by itself once the page has drawn
     * it (checked every 250 ms for up to ~5 s). Pages without one (grids, Home) leave the pointer alone.
     */
    private fun autoEnter(gen: Int, tries: Int) {
        if (tries > 14) return
        handler.postDelayed({
            if (gen != autoGen) return@postDelayed
            // the address can take a second or more to change on a busy TV; until it does, keep checking (cheap)
            if (top.url == newPageFrom) { autoEnter(gen, tries + 1); return@postDelayed }
            // only look for the page's Play button: a full scan of the page while it loads slowed the loading
            top.evaluateJavascript(PLAY_BUTTON_JS.replace("%W%", top.width.toString())) { result ->
                if (gen != autoGen) return@evaluateJavascript
                val a = runCatching { JSONArray(JSONTokener(result).nextValue() as String) }.getOrNull()
                if (a == null || a.length() < 4) { autoEnter(gen, tries + 1); return@evaluateJavascript }
                val l = a.getDouble(0).toFloat(); val t = a.getDouble(1).toFloat()
                newPage = false
                snapTo(RectF(l, t, l + a.getDouble(2).toFloat(), t + a.getDouble(3).toFloat()))
                // one full read once the song list (drawn after the Play button) has had time to appear
                invalidateTargets(1200)
            }
        }, 400)
    }

    private fun enterNewPage(code: Int, retry: Boolean): Boolean {
        if (!newPage || (code != KeyEvent.KEYCODE_DPAD_DOWN && code != KeyEvent.KEYCODE_DPAD_RIGHT)) return false
        if (top.url == newPageFrom) {
            // the address hasn't changed: give a slow page half a second to start, else it was no page change at all
            if (newPageTries < 2) {
                newPageTries++
                lastJumpMoved = true
                handler.postDelayed({ refresh { jumpWith(code, retry) } }, 250)
                return true
            }
            newPage = false
            newPageTries = 0
            return false
        }
        val entry = enterContent()
        if (entry != null) {
            newPage = false
            newPageTries = 0
            lastJumpMoved = true
            snapTo(entry)
            return true
        }
        if (++newPageTries > 20) { newPage = false; newPageTries = 0; return true } // ~5 s: stay put
        lastJumpMoved = true // a held key keeps waiting rather than gliding
        handler.postDelayed({ refresh { jumpWith(code, retry) } }, 250)
        return true
    }

    private fun jumpWith(code: Int, retry: Boolean) {
        if (enterNewPage(code, retry)) return
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_LEFT) newPage = false
        val best = (if (code == KeyEvent.KEYCODE_DPAD_RIGHT && inSidebar()) enterContent() else null) ?: pick(targets, code)
        lastJumpMoved = best != null
        if (best != null && retry && best.centerY() > root.height * 0.86f && cursor.py < root.height * 0.86f) {
            // about to drop into the floating player bar: the songs above it may just not be read yet
            handler.removeCallbacks(refreshTargets)
            refresh { jumpWith(code, retry = false) }
            return
        }
        if (best != null) {
            snapTo(best)
            if (SystemClock.uptimeMillis() - targetsAt > 4000) invalidateTargets(300)
        } else if (retry && code == KeyEvent.KEYCODE_DPAD_DOWN && cursor.py > root.height * 0.86f) {
            lastJumpMoved = false // on the player bar: the bottom of the screen
        } else if (retry && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
            lastJumpMoved = true // scrolling counts as moving, so a held arrow keeps going
            val dir = if (code == KeyEvent.KEYCODE_DPAD_UP) 1f else -1f
            repeat(4) { scroll(cursor.px, cursor.py, dir) }
            handler.removeCallbacks(refreshTargets)
            handler.postDelayed({ refresh { jumpWith(code, retry = false) } }, 250)
        }
    }

    /** The pointer is in the left navigation column. */
    private fun inSidebar() = cursor.px < root.width * SIDEBAR_FRACTION

    /**
     * Right from the sidebar enters the page at its main action: the Play (or Shuffle) button if the page
     * has one on screen, otherwise the top-left item of the content, instead of whatever is level with the pointer.
     */
    private fun enterContent(): RectF? {
        val edge = root.width * SIDEBAR_FRACTION
        val bar = root.height * 0.86f // the floating player bar has its own Play and Shuffle: never the page's
        // the Play button first, then Shuffle
        for (list in listOf(playTargets, primaryTargets)) {
            list.filter { it.left >= edge && it.top >= 0f && it.centerY() < bar }
                .minWithOrNull(compareBy({ it.top }, { it.left }))?.let { return it }
        }
        val row = 40f * u
        return targets.filter { it.left >= edge && it.top >= 0f && it.centerY() < bar }
            .minWithOrNull(compareBy({ (it.top / row).toInt() }, { it.left }))
    }

    private fun snapNearest() {
        refresh {
            val list = targets
            val x = cursor.px
            val y = cursor.py
            val best = list.minByOrNull { r -> distance(r, x, y) * 10_000f + r.width() * r.height() / 1e6f }
            if (best != null && distance(best, x, y) < 140f * u) snapTo(best)
        }
    }

    private var lastRowStep = 0L

    private fun snapTo(r: RectF) {
        cursor.focus = RectF(r)
        cursor.animateTo(r.centerX(), r.centerY())
        checkSongRow(r)
    }

    /** Is the item just reached a song row? Then Up/Down switch to song-by-song. */
    private fun checkSongRow(r: RectF) {
        val asked = ++rowCheck
        top.evaluateJavascript(withPoint(IS_ROW_JS, r.centerX(), r.centerY())) {
            if (asked == rowCheck) onSongRow = it == "true"
        }
    }

    /** Bumped whenever row mode is left, so a late answer about an earlier item can't switch it back on. */
    private var rowCheck = 0

    private fun leaveRows() {
        onSongRow = false
        rowCheck++
    }

    private fun pick(targets: List<RectF>, code: Int): RectF? {
        val x = cursor.px
        val y = cursor.py
        val current = cursor.focus
        val minStep = 8f * u
        val edge = root.width * SIDEBAR_FRACTION
        val fromSidebar = x < edge
        val vertical = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
        var best: RectF? = null
        var bestScore = Float.MAX_VALUE
        for (r in targets) {
            if (current != null && same(r, current)) continue
            if (current == null && r.contains(x, y)) continue
            // Up/Down stay in their column: the sidebar, or the page (and its player bar)
            if (vertical && (r.centerX() < edge) != fromSidebar) continue
            val cx = r.centerX()
            val cy = r.centerY()
            val (primary, secondary) = when (code) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (cx - x > minStep) max(0f, r.left - x) to gap(y, r.top, r.bottom) else continue
                KeyEvent.KEYCODE_DPAD_LEFT -> if (x - cx > minStep) max(0f, x - r.right) to gap(y, r.top, r.bottom) else continue
                KeyEvent.KEYCODE_DPAD_DOWN -> if (cy - y > minStep) max(0f, r.top - y) to gap(x, r.left, r.right) else continue
                KeyEvent.KEYCODE_DPAD_UP -> if (y - cy > minStep) max(0f, y - r.bottom) to gap(x, r.left, r.right) else continue
                else -> continue
            }
            val score = primary + secondary * 3f
            if (score < bestScore) {
                bestScore = score
                best = r
            }
        }
        return best
    }

    private fun gap(v: Float, a: Float, b: Float) = if (v < a) a - v else if (v > b) v - b else 0f

    private fun distance(r: RectF, x: Float, y: Float) = hypot(gap(x, r.left, r.right), gap(y, r.top, r.bottom))

    private fun same(a: RectF, b: RectF) =
        abs(a.left - b.left) < 2 && abs(a.top - b.top) < 2 && abs(a.right - b.right) < 2 && abs(a.bottom - b.bottom) < 2

    /** Visible, unobscured clickable elements of the top page (including inside web components), in view pixels. */
    private fun fetchTargets(callback: (all: List<RectF>, primary: List<RectF>) -> Unit) {
        val view = top
        view.evaluateJavascript(TARGETS_JS.replace("%W%", view.width.toString())) { result ->
            val all = ArrayList<RectF>()
            val primary = ArrayList<RectF>()
            val play = ArrayList<RectF>()
            runCatching {
                val arr = JSONArray(JSONTokener(result).nextValue() as String)
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONArray(i)
                    val l = a.getDouble(0).toFloat()
                    val t = a.getDouble(1).toFloat()
                    val r = RectF(l, t, l + a.getDouble(2).toFloat(), t + a.getDouble(3).toFloat())
                    all += r
                    when (a.optInt(4)) {
                        1 -> { primary += r; play += r }
                        2 -> primary += r
                    }
                }
            }
            playTargets = play
            callback(all, primary)
        }
    }

    private fun back() {
        when {
            popup != null -> closePopup()
            web.canGoBack() -> { cursor.focus = null; leaveRows(); pageChanged(); newPage = true; newPageFrom = top.url; web.goBack(); invalidateTargets(400) }
            else -> moveTaskToBack(true) // leave the app but keep the music playing
        }
    }

    /**
     * A touch tap at the pointer. (Synthetic mouse-button events are ignored by WebView's input path;
     * with page zoom off, taps no longer wait for a possible double-tap.)
     */
    private fun click() {
        val f = cursor.focus
        if (onSongRow && f != null) {
            // the ring covers the whole row (not one link in it): play, wherever its centre falls
            val whole = f.width() > top.width * 0.4f
            top.evaluateJavascript(withPoint(PLAY_ROW_JS, f.centerX(), f.centerY()).replace("%WHOLE%", whole.toString())) {
                if (it == "true") cursor.pulse() else tap()
            }
            return
        }
        tap()
    }

    /** A touch tap at the pointer. */
    private fun tap() {
        val before = top.url // the page changes its address during the tap itself
        val x = cursor.px
        val y = cursor.py
        val t = SystemClock.uptimeMillis()
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to t, MotionEvent.ACTION_UP to t + 40)) {
            val ev = MotionEvent.obtain(t, time, action, x, y, 0)
            ev.source = InputDevice.SOURCE_TOUCHSCREEN
            top.dispatchTouchEvent(ev)
            ev.recycle()
        }
        cursor.pulse()
        leaveRows()
        pageChanged()
        newPage = true
        newPageFrom = before
        autoEnter(++autoGen, 0)
        // the page usually changes after a click; re-read it once it has settled (not while it renders)
        handler.postDelayed({ cursor.focus = null }, 250)
        invalidateTargets(1500)
    }

    private fun hover(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        handler.removeCallbacks(trailingHover)
        if (now - lastHover >= HOVER_MS) sendHover() else handler.postDelayed(trailingHover, HOVER_MS)
    }

    private fun sendHover() {
        lastHover = SystemClock.uptimeMillis()
        sendMouse(MotionEvent.ACTION_HOVER_MOVE, cursor.px, cursor.py, 0f)
    }

    private fun scroll(x: Float, y: Float, direction: Float) {
        cursor.focus = null
        leaveRows()
        sendMouse(MotionEvent.ACTION_SCROLL, x, y, direction)
        invalidateTargets(250)
    }

    private fun sendMouse(action: Int, x: Float, y: Float, vscroll: Float) {
        val t = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            if (vscroll != 0f) setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll)
        })
        val ev = MotionEvent.obtain(t, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        top.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    private fun media(keyCode: Int) {
        val action = when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "toggle"
            KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
            KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "next"
            else -> "previous"
        }
        web.evaluateJavascript(MEDIA_JS.replace("%ACTION%", action), null)
    }

    companion object {
        private const val HOME = "https://music.apple.com/"
        private const val CSS_WIDTH = 1400f
        private const val HOLD_MS = 350L
        private const val REPEAT_MIN_MS = 70L
        private const val SIDEBAR_FRACTION = 0.2f
        private const val HOVER_MS = 250L
        private const val REPEAT_ROW_MS = 110L

        // The page's own Play button (not the player bar's), in view px, or null. A few querySelector calls only.
        private const val PLAY_BUTTON_JS = """
            (function (viewWidth) {
              var s = viewWidth / innerWidth, bar = document.querySelector('.chrome-player');
              var list = document.querySelectorAll('button');
              for (var i = 0; i < list.length; i++) {
                var b = list[i];
                if (bar && bar.contains(b)) continue;
                var label = (b.getAttribute('aria-label') || b.textContent || '').trim().toLowerCase();
                if (label !== 'play') continue;
                var r = b.getBoundingClientRect();
                if (r.width < 8 || r.height < 8 || r.top < 0 || r.bottom > innerHeight * 0.86) continue;
                return JSON.stringify([r.left * s, r.top * s, r.width * s, r.height * s]);
              }
              return null;
            })(%W%)
        """

        // The song row at a point (CSS px), or null. Rows are Apple's .songs-list-row (role=row).
        private const val ROW_AT_JS = """
            function rowAt(x, y) {
              x /= (%W% / innerWidth); y /= (%W% / innerWidth);
              var el = document.elementFromPoint(x, y);
              while (el && el.shadowRoot && el.shadowRoot.elementFromPoint) {
                var inner = el.shadowRoot.elementFromPoint(x, y);
                if (!inner || inner === el) break;
                el = inner;
              }
              return el && el.closest ? el.closest('.songs-list-row') : null;
            }
        """

        private const val IS_ROW_JS = "(function(){ $ROW_AT_JS return !!rowAt(%X%, %Y%); })()"

        // Next/previous song row: scrolled (instantly) so it sits between 30% and 62% of the screen height,
        // above the floating player bar. Returns [left, top, width, height, innerWidth] in CSS px, or null.
        private const val ROW_STEP_JS = """
            (function (x, y, dir) {
              $ROW_AT_JS
              var row = rowAt(x, y);
              if (!row) return null;
              var next = dir > 0 ? row.nextElementSibling : row.previousElementSibling;
              while (next && !(next.classList && next.classList.contains('songs-list-row'))) {
                next = dir > 0 ? next.nextElementSibling : next.previousElementSibling;
              }
              if (!next) return null;
              var rc = next.getBoundingClientRect(), H = innerHeight;
              var lo = H * 0.30, hi = H * 0.62, dy = 0;
              if (rc.bottom > hi) dy = rc.bottom - hi; else if (rc.top < lo) dy = rc.top - lo;
              if (dy !== 0) {
                var sc = next.parentElement;
                while (sc && sc !== document.body) {
                  var ov = getComputedStyle(sc).overflowY;
                  if ((ov === 'auto' || ov === 'scroll') && sc.scrollHeight > sc.clientHeight) break;
                  sc = sc.parentElement;
                }
                if (sc && sc !== document.body) sc.scrollTo({ top: sc.scrollTop + dy, behavior: 'instant' });
                else window.scrollBy({ top: dy, behavior: 'instant' });
                rc = next.getBoundingClientRect();
              }
              return JSON.stringify([rc.left, rc.top, rc.width, rc.height, innerWidth]);
            })(%X%, %Y%, %DIR%)
        """

        // OK on a song row (not on a link or button inside it): press the row's own Play button, which plays the
        // song right away (a tap on the row only selects it). Returns true if it did.
        private const val PLAY_ROW_JS = """
            (function (x, y) {
              $ROW_AT_JS
              var row = rowAt(x, y);
              if (!row) return false;
              var s = %W% / innerWidth, el = document.elementFromPoint(x / s, y / s);
              var link = el && el.closest ? el.closest('a[href], button') : null;
              if (!%WHOLE% && link && row.contains(link) && !/^play\b/i.test(link.getAttribute('aria-label') || '')) return false;
              var btn = row.querySelector('button[aria-label^="Play"], button[class*="play-button"]');
              if (!btn) return false;
              btn.click();
              return true;
            })(%X%, %Y%)
        """

        // Apple's blur effects (player bar, menus, unfocused lyric lines) are costly on TV chips: drop them.
        // Also report "prefers reduced motion", so the player picks its lighter animations (e.g. lyric scrolling).
        private const val LIGHTER_CSS_JS = """
            (function () {
              var realMatchMedia = window.matchMedia;
              window.matchMedia = function (query) {
                var m = /prefers-reduced-motion\s*:\s*(reduce|no-preference)/i.exec(query || '');
                if (!m) return realMatchMedia.call(window, query);
                var noop = function () {};
                return { matches: m[1].toLowerCase() === 'reduce', media: query, onchange: null,
                         addListener: noop, removeListener: noop, addEventListener: noop,
                         removeEventListener: noop, dispatchEvent: function () { return false; } };
              };
              function add() {
                var s = document.createElement('style');
                s.textContent =
                  // keep every selector simple: descendant rules like '[class*=x] *' and '::before/::after' variants
                  // made one playlist open spend ~14 s recalculating styles
                  '* { backdrop-filter: none !important; -webkit-backdrop-filter: none !important; }' +
                  '[class*="lyric"] { filter: none !important; }' +
                  // "only render what's in view": off-screen shelves/sections skip layout and paint until
                  // scrolled near (their size is remembered, so the page doesn't jump)
                  'main section, [class*="shelf" i] { content-visibility: auto; contain-intrinsic-size: auto 480px; }' +
                  // songs in long playlists/albums: rows off screen skip style and layout (a playlist page restyled
                  // ~5,600 elements per change); 54 px is a row's height, so the list keeps its length and scroll
                  '.songs-list-row { content-visibility: auto; contain-intrinsic-size: auto 54px; }' +
                  // the animated Now Playing / lyrics background is a canvas redrawn every frame (>100% main-thread
                  // time while visible); the view keeps its normal colors without it
                  'canvas { display: none !important; }' +
                  // the "now playing" equalizer bars loop forever and restyle every frame
                  '[class*="playback-bars"] { animation: none !important; }';
                (document.head || document.documentElement).appendChild(s);
              }
              if (document.documentElement) add(); else document.addEventListener('DOMContentLoaded', add);

              // Animated album covers are muted, looping videos; each one is a video decode running in the
              // background. Stop them (the still cover underneath shows instead). Music videos aren't muted/looped.
              function calm(v) {
                if (!v || v.tagName !== 'VIDEO' || v.__calmed || !(v.loop && v.muted)) return;
                v.__calmed = true;
                v.autoplay = false;
                v.preload = 'none';
                try { v.pause(); } catch (e) {}
                if (srcProp) srcProp.set.call(v, '');
                v.removeAttribute('src');
                Array.prototype.forEach.call(v.querySelectorAll('source'), function (s) { s.remove(); });
                try { v.load(); } catch (e) {}
                v.style.display = 'none';
              }
              // Refuse to start them at all, so they never download video (~150 requests per page load otherwise).
              function isAmbient(m) { return m.tagName === 'VIDEO' && m.loop && m.muted; }
              var srcProp = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
              Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                configurable: true, enumerable: true, get: srcProp.get,
                set: function (v) {
                  if (isAmbient(this)) { calm(this); return; }
                  if (isPreview(v)) this.preload = 'none'; // see the previews note below
                  srcProp.set.call(this, v);
                }
              });
              // 30-second previews (AudioPreview URLs) were created with new Audio(url) and fully buffered in advance,
              // unplayed (six at a time). They now load only when played: Audio() starts them at preload "none",
              // and the page can't raise a preview's preload again.
              function isPreview(v) { return typeof v === 'string' && v.indexOf('AudioPreview') >= 0; }
              // ...and the playlist page's own preview players: <audio data-testid="preview-player" preload="auto">
              // with a <source>, built from attributes (six of them, ~3 MB each, downloaded on opening a playlist,
              // which held up Shuffle/Play for ~20 s). Their preload attribute is kept at "none".
              function isPreviewEl(el) { return el.tagName === 'AUDIO' && el.getAttribute('data-testid') === 'preview-player'; }
              var realSetAttribute = Element.prototype.setAttribute;
              Element.prototype.setAttribute = function (name, value) {
                if (this.tagName === 'AUDIO') {
                  if (name === 'preload' && isPreviewEl(this)) value = 'none';
                  else if (name === 'data-testid' && value === 'preview-player') realSetAttribute.call(this, 'preload', 'none');
                }
                return realSetAttribute.call(this, name, value);
              };
              var RealAudio = window.Audio;
              var TvAudio = function (src) { var a = new RealAudio(); a.preload = 'none'; if (src !== undefined) a.src = src; return a; };
              TvAudio.prototype = RealAudio.prototype;
              window.Audio = TvAudio;
              var preloadProp = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'preload');
              Object.defineProperty(HTMLMediaElement.prototype, 'preload', {
                configurable: true, enumerable: true, get: preloadProp.get,
                set: function (v) {
                  var src = this.getAttribute('src') || this.currentSrc || '';
                  preloadProp.set.call(this, (isPreview(src) || isPreviewEl(this)) && v !== 'none' ? 'none' : v);
                }
              });
              var realPlay = HTMLMediaElement.prototype.play;
              HTMLMediaElement.prototype.play = function () {
                if (isAmbient(this)) { calm(this); return Promise.resolve(); }
                return realPlay.apply(this, arguments);
              };
              document.addEventListener('play', function (e) { calm(e.target); }, true);
              // (no MutationObserver: scanning every added node cost CPU on each page navigation;
              //  the src/play patches above already stop these videos)
              document.addEventListener('loadedmetadata', function (e) { calm(e.target); }, true);
            })();
        """

        // Prefer the player's own MusicKit API; fall back to the page's media element.
        private const val MEDIA_JS = """
            (function(a) {
              try {
                var m = window.MusicKit && MusicKit.getInstance();
                if (m) {
                  if (a === 'toggle') { m.isPlaying ? m.pause() : m.play(); }
                  else if (a === 'play') { m.play(); }
                  else if (a === 'pause') { m.pause(); }
                  else if (a === 'next') { m.skipToNextItem(); }
                  else { m.skipToPreviousItem(); }
                  return;
                }
              } catch (e) {}
              var el = document.querySelector('audio, video');
              if (!el) return;
              if (a === 'toggle') { el.paused ? el.play() : el.pause(); }
              else if (a === 'play') { el.play(); }
              else if (a === 'pause') { el.pause(); }
            })('%ACTION%');
        """

        // Clickable, visible, not covered by something else (e.g. a dialog). Walks open shadow roots,
        // since the player is built from web components. Returns [[left, top, width, height], ...] in view px.
        // "Only measure what's in view": an IntersectionObserver (kept on window between calls) tracks which
        // candidates are on screen as the page scrolls, so only those few dozen get getBoundingClientRect
        // instead of every button on a long playlist (that was ~3% of the CPU).
        private const val TARGETS_JS = """
            (function(viewWidth) {
              var s = viewWidth / window.innerWidth, W = innerWidth, H = innerHeight;
              var SEL = 'a[href],button,[role=button],[role=link],[role=tab],[role=menuitem],[role=option],' +
                        '[role=checkbox],[role=switch],[role=slider],input,textarea,select,summary,[tabindex]:not([tabindex="-1"])';
              var out = [], seen = {};
              var st = window.__tvTargets || (window.__tvTargets = { known: new WeakSet(), inView: new Set(), fresh: true });
              if (!st.io) st.io = new IntersectionObserver(function (entries) {
                entries.forEach(function (e) { if (e.isIntersecting) st.inView.add(e.target); else st.inView.delete(e.target); });
              }, { rootMargin: '100px' });
              function outerHost(el) {
                var n = el;
                while (n.getRootNode && n.getRootNode() !== document && n.getRootNode().host) n = n.getRootNode().host;
                return n;
              }
              function visible(el, l, t, r, b) {
                var x = (l + r) / 2, y = (t + b) / 2;
                var rootNode = el.getRootNode();
                var hit = rootNode.elementFromPoint ? rootNode.elementFromPoint(x, y) : null;
                if (!hit || !(hit === el || el.contains(hit) || hit.contains(el))) return false;
                var host = outerHost(el), top = document.elementFromPoint(x, y);
                return !!top && (top === host || host.contains(top) || top.contains(host));
              }
              // elementFromPoint per item was ~3% of the CPU; it only matters while a dialog covers the page
              var modal = document.querySelector('[aria-modal="true"], dialog[open], [role="dialog"]');
              var bar = document.querySelector('.chrome-player'), br = bar && bar.getBoundingClientRect();
              function add(el) {
                if (out.length >= 500) return;
                var rc = el.getBoundingClientRect();
                if (rc.width < 8 || rc.height < 8) return;
                var l = Math.max(0, rc.left), t = Math.max(0, rc.top), r = Math.min(W, rc.right), b = Math.min(H, rc.bottom);
                if (r - l < 6 || b - t < 6) return;
                if (modal && !visible(el, l, t, r, b)) return;
                if (br && br.width > 0) {
                  var cx = (l + r) / 2, cy = (t + b) / 2;
                  if (cx >= br.left && cx <= br.right && cy >= br.top - 8 && cy <= br.bottom && !bar.contains(el) && !bar.contains(outerHost(el))) return;
                }
                var key = [l, t, r, b].map(Math.round).join(',');
                if (seen[key]) return;
                seen[key] = 1;
                out.push([l * s, t * s, (r - l) * s, (b - t) * s, isPrimary(el)]);
              }
              // the page's main actions (Play / Shuffle), so "right from the sidebar" can land on them
              function isPrimary(el) {
                if (el.tagName !== 'BUTTON') return 0;
                var label = (el.getAttribute('aria-label') || el.textContent || '').trim().toLowerCase();
                return label === 'play' ? 1 : label === 'shuffle' ? 2 : 0;
              }
              var found = [], newcomers = [];
              function walk(rootNode) {
                rootNode.querySelectorAll(SEL).forEach(function (el) {
                  found.push(el);
                  if (!st.known.has(el)) { st.known.add(el); st.io.observe(el); newcomers.push(el); }
                });
                rootNode.querySelectorAll('*').forEach(function (n) { if (n.shadowRoot) walk(n.shadowRoot); });
              }
              walk(document);
              if (st.fresh) {
                // first call: the observer hasn't reported yet, so measure everything once
                st.fresh = false;
                found.forEach(add);
              } else {
                st.inView.forEach(function (el) { if (el.isConnected) add(el); else st.inView.delete(el); });
                // items that just appeared (a page that just opened) aren't reported by the observer yet:
                // measure those directly this once, so jumps reach a new page immediately
                newcomers.forEach(add);
              }
              return JSON.stringify(out);
            })(%W%);
        """
    }
}
