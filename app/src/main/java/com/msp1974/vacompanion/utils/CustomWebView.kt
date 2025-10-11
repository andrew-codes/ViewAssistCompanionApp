package com.msp1974.vacompanion.utils

import android.annotation.SuppressLint
import kotlin.jvm.JvmOverloads
import android.content.Context
import android.view.MotionEvent
import android.content.res.Resources.NotFoundException
import android.util.AttributeSet
import android.webkit.*

@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class CustomWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : WebView(context, attrs, defStyleAttr) {

    private val log = Logger()
    private var requestDisallow = false
    private val androidInterface: Any = object : Any() {
        @JavascriptInterface
        fun requestScrollEvents() {
            requestDisallow = true
        }
    }

    fun initialise() {
        log.d("Initialising WebView")
        setFocusable(true)
        setFocusableInTouchMode(true)

        setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, false)
        setLayerType(LAYER_TYPE_HARDWARE, null)

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            safeBrowsingEnabled = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(true)
            builtInZoomControls = false
            useWideViewPort = false
            loadWithOverviewMode = true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (requestDisallow) {
            requestDisallowInterceptTouchEvent(true)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> requestDisallow = false
        }
        return super.onTouchEvent(event)
    }

    companion object {
        fun getView(context: Context): CustomWebView {
            return try {
                CustomWebView(context)
            } catch (e: NotFoundException) {
                CustomWebView(context.applicationContext)
            }
        }
    }
}