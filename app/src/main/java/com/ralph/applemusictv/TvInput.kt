package com.ralph.applemusictv

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/** A WebView that never opens the system keyboard itself; [TvInput] handles text entry instead. */
class TvWebView(context: Context) : WebView(context) {
    var nativeKeyboard = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
        if (nativeKeyboard) super.onCreateInputConnection(outAttrs) else null

    override fun onCheckIsTextEditor(): Boolean = nativeKeyboard && super.onCheckIsTextEditor()
}

/**
 * TV-style text entry for web pages. A script in every apple.com frame (including the cross-origin
 * Apple ID sign-in frame) reports clicks on text fields; we show a native text panel at the top of the
 * screen and write what's typed back into that field as you type. Done/Go submits the field's form.
 */
class TvInput(private val activity: Activity) {
    private var dialog: Dialog? = null

    /** True if this WebView supports the per-frame bridge (then the page's own keyboard is turned off). */
    fun attach(web: TvWebView): Boolean {
        val ok = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        if (!ok) return false
        WebViewCompat.addWebMessageListener(web, BRIDGE, ORIGINS) { _, message, _, _, reply ->
            val m = runCatching { JSONObject(message.data ?: "") }.getOrNull() ?: return@addWebMessageListener
            if (m.optString("type") == "edit") activity.runOnUiThread { show(m, reply) }
        }
        WebViewCompat.addDocumentStartJavaScript(web, SCRIPT, ORIGINS)
        web.nativeKeyboard = false
        return true
    }

    /** Set by the activity: the mic button on search boxes starts voice search with it. */
    var onVoice: (() -> Unit)? = null

    private fun show(field: JSONObject, reply: JavaScriptReplyProxy) {
        dialog?.dismiss()
        val dp = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val type = field.optString("inputType", "text").lowercase()

        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(28), px(18), px(28), px(20))
            background = GradientDrawable().apply {
                setColor(0xF2202024.toInt())
                cornerRadius = px(18).toFloat()
            }
        }
        val title = field.optString("label").ifBlank { activity.getString(if (type == "password") R.string.input_password else R.string.input_text) }
        panel.addView(TextView(activity).apply {
            text = title
            textSize = 16f
            setTextColor(0xFFB0B0B8.toInt())
        })
        val edit = EditText(activity).apply {
            setText(field.optString("value"))
            setSelection(text.length)
            textSize = 24f
            setTextColor(Color.WHITE)
            setSingleLine()
            inputType = when (type) {
                "password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                "email" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                "tel" -> InputType.TYPE_CLASS_PHONE
                "number" -> InputType.TYPE_CLASS_NUMBER
                "url" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                else -> InputType.TYPE_CLASS_TEXT
            }
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        panel.addView(edit, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(6) })
        // a search box can also be spoken to: Down from the text, then OK on the mic
        val voice = onVoice
        if (type == "search" && voice != null) {
            panel.addView(TextView(activity).apply {
                text = activity.getString(R.string.input_voice)
                textSize = 18f
                setTextColor(Color.WHITE)
                setPadding(px(16), px(10), px(16), px(10))
                isFocusable = true
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                        setColor(0x40FFFFFF); cornerRadius = px(10).toFloat()
                    })
                    addState(intArrayOf(), GradientDrawable().apply {
                        setColor(0x18FFFFFF); cornerRadius = px(10).toFloat()
                    })
                }
                setOnClickListener { dialog?.dismiss(); voice() }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = px(12) })
        }

        // what's typed goes into the page once typing pauses, not letter by letter: on a search box every change
        // makes Apple fetch suggestions and rebuild them (~0.5-1 s of a busy page each), so a name typed letter by
        // letter froze the TV again and again. Other fields (sign-in) still get each change.
        val pause = if (type == "search") TYPE_PAUSE_MS else 0L
        val sendTyped = Runnable { send(reply, edit.text.toString(), submit = false) }
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable) {
                edit.removeCallbacks(sendTyped)
                if (pause == 0L) sendTyped.run() else edit.postDelayed(sendTyped, pause)
            }
        })
        val d = Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
        edit.setOnEditorActionListener { v, action, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE || enter) {
                edit.removeCallbacks(sendTyped)
                send(reply, v.text.toString(), submit = true)
                d.dismiss()
                true
            } else {
                false
            }
        }

        d.setContentView(panel)
        d.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply {
                width = (activity.resources.displayMetrics.widthPixels * 0.6f).toInt()
                y = px(40)
                dimAmount = 0.35f
            }
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
        d.setOnDismissListener { if (dialog === d) dialog = null }
        dialog = d
        d.show()
        edit.requestFocus()
    }

    private fun send(reply: JavaScriptReplyProxy, value: String, submit: Boolean) {
        runCatching { reply.postMessage(JSONObject().put("value", value).put("submit", submit).toString()) }
    }

    fun dismiss() {
        dialog?.dismiss()
    }

    companion object {
        private const val BRIDGE = "tvInput"
        private const val TYPE_PAUSE_MS = 700L
        private val ORIGINS = setOf("https://apple.com", "https://*.apple.com")

        private val SCRIPT = """
            (function () {
              if (window.__tvInput) return;
              window.__tvInput = true;
              var bridge = window.$BRIDGE;
              if (!bridge) return;
              var current = null;
              var TEXT = ['text', 'search', 'email', 'password', 'tel', 'url', 'number', ''];
              function isText(el) {
                if (!el) return false;
                if (el.isContentEditable) return true;
                if (el.tagName === 'TEXTAREA') return true;
                if (el.tagName !== 'INPUT') return false;
                return TEXT.indexOf((el.getAttribute('type') || 'text').toLowerCase()) >= 0;
              }
              function label(el) {
                return el.getAttribute('aria-label') || el.placeholder ||
                  (el.labels && el.labels[0] && el.labels[0].innerText) || '';
              }
              document.addEventListener('click', function (e) {
                var el = (e.composedPath && e.composedPath()[0]) || e.target;
                if (!isText(el)) return;
                current = el;
                bridge.postMessage(JSON.stringify({
                  type: 'edit',
                  inputType: el.isContentEditable ? 'text' : (el.getAttribute('type') || 'text'),
                  label: label(el),
                  value: el.isContentEditable ? el.innerText : el.value
                }));
              }, true);
              function setValue(el, v) {
                if (el.isContentEditable) { el.innerText = v; }
                else {
                  var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                  Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, v);
                }
                el.dispatchEvent(new Event('input', { bubbles: true }));
              }
              function submit(el) {
                el.dispatchEvent(new Event('change', { bubbles: true }));
                var form = el.form;
                if (form && form.requestSubmit) { try { form.requestSubmit(); return; } catch (e) {} }
                ['keydown', 'keypress', 'keyup'].forEach(function (t) {
                  el.dispatchEvent(new KeyboardEvent(t, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
                });
              }
              bridge.onmessage = function (event) {
                var el = current;
                if (!el) return;
                var m = JSON.parse(event.data);
                setValue(el, m.value);
                if (m.submit) submit(el);
              };
            })();
        """.trimIndent()
    }
}
