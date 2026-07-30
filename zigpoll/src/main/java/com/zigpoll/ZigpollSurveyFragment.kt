package com.zigpoll

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bottom-sheet container for the survey WebView. The page (share's /sdk
 * route) posts lifecycle messages through `ZigpollNative.postMessage`:
 * loaded / style / resize / inputFocus / inputBlur / completed / closed / error.
 */
internal class ZigpollSurveyFragment : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_URL = "url"
        private const val LOADING_HEIGHT_DP = 240
        private const val MIN_HEIGHT_DP = 180

        fun newInstance(url: String): ZigpollSurveyFragment {
            return ZigpollSurveyFragment().apply {
                arguments = Bundle().apply { putString(ARG_URL, url) }
            }
        }
    }

    private lateinit var container: FrameLayout
    private lateinit var webView: WebView
    private lateinit var spinner: ProgressBar
    private val background = GradientDrawable()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var closeSent = false
    private var lastResponses: List<Map<String, Any?>> = emptyList()
    private var heightAnimator: ValueAnimator? = null
    private var cornerRadiusPx = 0f

    private val density: Float
        get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            /* Recreated after process death -- the native callbacks are gone;
               don't resurrect a zombie survey. */
            dismissAllowingStateLoss()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.behavior.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isFitToContents = true
        }
        return dialog
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        cornerRadiusPx = 16 * density
        background.setColor(Color.WHITE)
        applyCornerRadius()

        container = FrameLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (LOADING_HEIGHT_DP * density).toInt()
            )
            background = this@ZigpollSurveyFragment.background
            clipToOutline = true
        }

        webView = WebView(requireContext()).apply {
            settings.javaScriptEnabled = true
            /* The embed's session storage throws without DOM storage. */
            settings.domStorageEnabled = true
            setBackgroundColor(Color.TRANSPARENT)
            alpha = 0f
            addJavascriptInterface(Bridge(), "ZigpollNative")
            webViewClient = SurveyWebViewClient()
            webChromeClient = SurveyWebChromeClient()
        }
        container.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        spinner = ProgressBar(requireContext())
        container.addView(
            spinner,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        val url = requireArguments().getString(ARG_URL) ?: ""
        webView.loadUrl(url)

        return container
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (!closeSent) {
            closeSent = true
            Zigpoll.onClose?.invoke(lastResponses)
        }
        Zigpoll.surveyDismissed()
    }

    override fun onDestroyView() {
        heightAnimator?.cancel()
        webView.removeJavascriptInterface("ZigpollNative")
        webView.destroy()
        super.onDestroyView()
    }

    /** Ask the page to close (runs the survey's exit rules). */
    fun closeSurvey() {
        if (!isAdded || view == null) {
            finishClose()
            return
        }
        webView.evaluateJavascript("window.Zigpoll && window.Zigpoll.close && window.Zigpoll.close();", null)
    }

    private fun finishClose() {
        if (closeSent) return
        closeSent = true
        Zigpoll.onClose?.invoke(lastResponses)
        dismissAllowingStateLoss()
    }

    private fun fail(error: String) {
        if (closeSent) return
        closeSent = true
        Zigpoll.onError?.invoke(error)
        dismissAllowingStateLoss()
    }

    // -- Bridge -----------------------------------------------------------

    private inner class Bridge {
        @JavascriptInterface
        fun postMessage(json: String) {
            mainHandler.post { handleMessage(json) }
        }
    }

    private fun handleMessage(json: String) {
        if (!isAdded || closeSent) return
        val body = try { JSONObject(json) } catch (e: Exception) { return }

        when (body.optString("type")) {
            "loaded" -> {
                spinner.visibility = View.GONE
                webView.animate().alpha(1f).setDuration(200).start()
                body.optString("background").takeIf { it.isNotEmpty() }?.let { applyBackground(it) }
                Zigpoll.onLoad?.invoke()
            }
            "style" -> applyReportedStyle(body)
            "resize" -> {
                val height = body.optDouble("height", 0.0)
                if (height > 0) adjustHeight(height)
            }
            "completed" -> {
                lastResponses = jsonArrayToList(body.optJSONArray("responses"))
                Zigpoll.onComplete?.invoke(lastResponses)
            }
            "closed" -> {
                body.optJSONArray("responses")?.let { lastResponses = jsonArrayToList(it) }
                finishClose()
            }
            "error" -> fail(body.optString("error", "Survey failed to load"))
            else -> Unit
        }
    }

    // -- Style ------------------------------------------------------------

    /**
     * Applies the style the render page measured from the survey itself, so
     * the sheet matches the merchant's display settings with no app-developer
     * involvement.
     */
    private fun applyReportedStyle(body: JSONObject) {
        body.optString("background").takeIf { it.isNotEmpty() }?.let { applyBackground(it) }

        val radius = body.optDouble("borderRadius", 0.0)
        if (radius > 0) {
            cornerRadiusPx = (radius * density).toFloat()
            applyCornerRadius()
        }

        val borderWidth = body.optDouble("borderWidth", 0.0)
        val borderColor = parseCssColor(body.optString("borderColor"))
        if (borderWidth > 0 && borderColor != null) {
            background.setStroke((borderWidth * density).toInt().coerceAtLeast(1), borderColor)
        } else {
            background.setStroke(0, Color.TRANSPARENT)
        }
    }

    private fun applyBackground(css: String) {
        parseCssColor(css)?.let { background.setColor(it) }
    }

    private fun applyCornerRadius() {
        background.cornerRadii = floatArrayOf(
            cornerRadiusPx, cornerRadiusPx,
            cornerRadiusPx, cornerRadiusPx,
            0f, 0f,
            0f, 0f
        )
    }

    /** Parses "#fff", "#ffffff", "rgb(r, g, b)", or "rgba(r, g, b, a)". */
    private fun parseCssColor(value: String?): Int? {
        val css = value?.trim()?.lowercase() ?: return null
        if (css.isEmpty()) return null

        if (css.startsWith("rgb")) {
            val numbers = css.substringAfter("(").substringBefore(")")
                .split(",")
                .mapNotNull { it.trim().toDoubleOrNull() }
            if (numbers.size < 3) return null
            val alpha = if (numbers.size >= 4) (numbers[3] * 255).toInt() else 255
            return Color.argb(alpha, numbers[0].toInt(), numbers[1].toInt(), numbers[2].toInt())
        }

        var hex = css.removePrefix("#")
        if (hex.length == 3) hex = hex.map { "$it$it" }.joinToString("")
        if (hex.length != 6) return null
        return try {
            Color.parseColor("#$hex")
        } catch (e: Exception) {
            null
        }
    }

    // -- Sizing -----------------------------------------------------------

    /** The page reports CSS px, which map 1:1 to dp in a WebView. */
    private fun adjustHeight(cssHeight: Double) {
        /* Keep the survey clear of the gesture/navigation bar: pad the
           container by the inset and include it in the sheet height. */
        val bottomInset = container.rootWindowInsets?.let { insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
        } ?: 0
        if (container.paddingBottom != bottomInset) {
            container.setPadding(0, 0, 0, bottomInset)
        }

        val maxHeight = (resources.displayMetrics.heightPixels * 0.9).toInt()
        val target = ((cssHeight * density).toInt() + bottomInset)
            .coerceAtLeast((MIN_HEIGHT_DP * density).toInt())
            .coerceAtMost(maxHeight)
        if (target == container.layoutParams.height) return

        heightAnimator?.cancel()
        heightAnimator = ValueAnimator.ofInt(container.layoutParams.height, target).apply {
            duration = 250
            addUpdateListener { animator ->
                container.layoutParams = container.layoutParams.apply {
                    height = animator.animatedValue as Int
                }
            }
            start()
        }
    }

    // -- WebView plumbing --------------------------------------------------

    private inner class SurveyWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            /* Survey links (reward links, redirect actions) open in the
               browser; only the survey page itself stays in the sheet. */
            if (!request.isForMainFrame) return false
            if (!request.hasGesture()) return false
            return try {
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                true
            } catch (e: Exception) {
                false
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                fail(error.description?.toString() ?: "Survey failed to load")
            }
        }
    }

    /** The survey's exit-confirmation rules use window.confirm. */
    private inner class SurveyWebChromeClient : WebChromeClient() {
        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
            AlertDialog.Builder(requireContext())
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
            AlertDialog.Builder(requireContext())
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.confirm() }
                .show()
            return true
        }
    }

    // -- JSON helpers ------------------------------------------------------

    private fun jsonArrayToList(array: JSONArray?): List<Map<String, Any?>> {
        if (array == null) return emptyList()
        val list = mutableListOf<Map<String, Any?>>()
        for (i in 0 until array.length()) {
            (array.optJSONObject(i))?.let { list.add(jsonObjectToMap(it)) }
        }
        return list
    }

    private fun jsonObjectToMap(json: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        for (key in json.keys()) {
            map[key] = when (val value = json.opt(key)) {
                is JSONObject -> jsonObjectToMap(value)
                is JSONArray -> jsonArrayToPlainList(value)
                JSONObject.NULL -> null
                else -> value
            }
        }
        return map
    }

    private fun jsonArrayToPlainList(array: JSONArray): List<Any?> {
        val list = mutableListOf<Any?>()
        for (i in 0 until array.length()) {
            list.add(
                when (val value = array.opt(i)) {
                    is JSONObject -> jsonObjectToMap(value)
                    is JSONArray -> jsonArrayToPlainList(value)
                    JSONObject.NULL -> null
                    else -> value
                }
            )
        }
        return list
    }
}
