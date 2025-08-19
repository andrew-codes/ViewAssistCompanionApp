package com.msp1974.vacompanion

import android.annotation.SuppressLint
import android.app.PendingIntent.getActivity
import android.app.UiModeManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.jsinterface.ExternalAuthCallback
import com.msp1974.vacompanion.jsinterface.WebAppInterface
import com.msp1974.vacompanion.jsinterface.WebViewJavascriptInterface
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.AuthUtils
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.utils.ScreenUtils
import androidx.core.graphics.drawable.toDrawable
import com.google.android.material.snackbar.Snackbar
import com.msp1974.vacompanion.utils.FirebaseManager
import com.msp1974.vacompanion.utils.InternalWebViewClient
import com.msp1974.vacompanion.utils.WebClientCallback
import kotlin.math.abs

public class WebViewActivity : AppCompatActivity(), SwipeRefreshLayout.OnRefreshListener, EventListener,
    WebClientCallback {
    private var log = Logger()
    private val firebase = FirebaseManager.getInstance()
    private lateinit var config: APPConfig
    private lateinit var screen: ScreenUtils

    private var swipeRefreshLayout: SwipeRefreshLayout? = null
    private var webView: WebView? = null
    private var popup: PopupWindow? = null

    private var maxPrediction: Float = 0.0f
    private var diagnosticIterations = 0

    @SuppressLint("SetJavaScriptEnabled", "SetTextI18n", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        log.d("Starting WebViewActivity")

        super.onCreate(savedInstanceState)
        this.enableEdgeToEdge()

        screen = ScreenUtils(this)
        config = APPConfig.getInstance(this)

        // Check for recovered WebViewActivity while screen off.
        // Restart MainActivity if necessary
        if (!screen.isScreenOn() || !config.backgroundTaskRunning) {
            if (isTaskRoot) {
                log.d("Launching MainActivity from Webview Activity")
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
            }
            config.eventBroadcaster.removeListener(this)
            screen.setDeviceBrightnessMode(true)
            finish()
        }

        setContentView(R.layout.activity_webview)

        webView = findViewById(R.id.haWebview)
        swipeRefreshLayout = findViewById(R.id.swiperefresh)

        // Initial states
        setDarkMode(config.darkMode)
        swipeRefreshLayout?.setEnabled(config.swipeRefresh)
        screen.setDeviceBrightnessMode(config.screenAutoBrightness)
        if (!config.screenAutoBrightness) {
            setScreenBrightness(config.screenBrightness)
        }
        setScreenAlwaysOn(config.screenAlwaysOn)

        // Add config change listeners
        config.eventBroadcaster.addListener(this)

        // Initiate broadcast receiver for action broadcasts
        val satelliteBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                log.i("Handling broadcast in webview activity.  Event is ${intent.action}")
                if (intent.action == BroadcastSender.SATELLITE_STOPPED) {
                    runOnUiThread {
                        LocalBroadcastManager.getInstance(context).unregisterReceiver(this)
                        if (popup != null && popup!!.isShowing) {
                            popup?.dismiss()
                        }
                        swipeRefreshLayout!!.removeAllViews()
                        webView!!.removeAllViews()
                        webView!!.destroy()
                    }
                    finish()
                }
                if (intent.action == BroadcastSender.TOAST_MESSAGE) {
                    runOnUiThread {
                        val snackbar = Snackbar.make(findViewById(android.R.id.content),intent.getStringExtra("extra").toString(), Snackbar.LENGTH_LONG)
                        snackbar.show()
                    }
                }
                if (intent.action == BroadcastSender.REFRESH) {
                    runOnUiThread {
                        onRefresh()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BroadcastSender.SATELLITE_STOPPED)
            addAction(BroadcastSender.TOAST_MESSAGE)
            addAction(BroadcastSender.REFRESH)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(satelliteBroadcastReceiver, filter)

        // Handle back action
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(1000) {
                onBackPressedDispatcher.onBackPressed()
            }
        }

        // Setup WebView and load html page
        swipeRefreshLayout?.setOnRefreshListener(this);


        val policy = ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)

        initialiseWebView(webView)
        loadInitURL()
    }

    override fun onEventTriggered(event: Event) {
        when (event.eventName) {
            "screenBrightness" -> {
                log.i("Screen brightness changed to ${event.newValue}")
                runOnUiThread {
                    setScreenBrightness(event.newValue as Float)
                }
            }
            "swipeRefresh" -> {
                log.i("Swipe refresh changed to ${event.newValue}")
                runOnUiThread {
                    swipeRefreshLayout?.setEnabled(event.newValue as Boolean)
                }
            }
            "screenAutoBrightness" -> {
                log.i("Screen Auto Brightness changed to ${event.newValue}")
                runOnUiThread {
                    setScreenAutoBrightness(event.newValue as Boolean)
                }
            }
            "screenAlwaysOn" -> {
                log.i("Screen Always On changed to ${event.newValue}")
                runOnUiThread {
                    setScreenAlwaysOn(event.newValue as Boolean)
                }
            }
            "darkMode" -> {
                log.i("Dark mode changed to ${event.newValue}")
                runOnUiThread {
                    setDarkMode(event.newValue as Boolean)
                }
            }
            "diagnosticsEnabled" -> {
                log.i("Diagnostics enabled changed to ${event.newValue}")
                runOnUiThread {
                    showDiagnosticsPopup(event.newValue as Boolean)
                }
            }
            "diagnosticStats" -> {
                runOnUiThread {
                    updateDiagnosticStats(event.oldValue as Float, event.newValue as Float)
                }
            }
        }
    }

    fun getHAUrl(): String {
        if (config.homeAssistantURL == "") {
            return "http://${config.homeAssistantConnectedIP}:${config.homeAssistantHTTPPort}"
        }
        return config.homeAssistantURL
    }

    fun loadInitURL() {
        //If we have auth token, load the home assistant URL
        //If not, do auth
        if (config.accessToken != "") {
            // We have valid token , load url
            log.d("Have auth token, logging in...")
            webView!!.loadUrl(AuthUtils.getURL(getHAUrl()))
        } else {
            // We need to ask for login
            log.d("No auth token stored. Requesting login")
            webView!!.loadUrl(AuthUtils.getAuthUrl(getHAUrl()))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun initialiseWebView(view: WebView?) {
        if (view != null) {
            // Add javascript interface for view assist
            view.addJavascriptInterface(WebAppInterface(applicationContext), "ViewAssistApp")
            // Add JS interface for HA external auth support
            view.addJavascriptInterface(
                WebViewJavascriptInterface(externalAuthCallback),
                "externalApp"
            )

            view.webViewClient = InternalWebViewClient(config, resources, this)

            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                allowFileAccess = true
                allowContentAccess = true
                setSupportZoom(true)
                loadWithOverviewMode = true
                useWideViewPort = true
                setRenderPriority(WebSettings.RenderPriority.HIGH)
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            view.removeAllViews()
        }

    }

    // Add external auth callback for HA authentication
    val externalAuthCallback = object : ExternalAuthCallback {
        override fun onRequestExternalAuth() {
            log.d("External auth callback in progress...")
            if (System.currentTimeMillis() > config.tokenExpiry && config.refreshToken != "") {
                // Need to get new access token as it has expired
                val success: Boolean = reAuthWithRefreshToken()
                if (success) {
                    log.d("Authorising with token")
                    callAuthJS()
                } else {
                    log.d("Failed to refresh auth token.  Proceeding to login screen")
                    runOnUiThread {
                        webView!!.loadUrl(AuthUtils.getAuthUrl(getHAUrl()))
                    }
                }
            } else {
                log.d("Auth token is still valid - authorising")
                callAuthJS()
            }
        }

        private fun callAuthJS() {
            runOnUiThread {
                webView!!.evaluateJavascript(
                    "window.externalAuthSetToken(true, {\n" +
                        "\"access_token\": \"${config.accessToken}\",\n" +
                        "\"expires_in\": 1800\n" +
                        "});",
                    null
                )
            }
        }

        private fun reAuthWithRefreshToken(): Boolean {
            log.i("Auth token has expired.  Requesting new token using refresh token")
            val auth = AuthUtils.refreshAccessToken(
                getHAUrl(),
                config.refreshToken,
                config.ignoreSSLErrors
            )
            if (auth.accessToken != "" && auth.expires > System.currentTimeMillis()) {
                log.d("Received new auth token")
                config.accessToken = auth.accessToken
                config.tokenExpiry = auth.expires
                return true
            } else {
                return false
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Ignore back button
        if (false) {
            super.onBackPressed()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val visibility: Int
        if (hasFocus) {
            // Set current activity
            config.currentActivity = "WebViewActivity"

            ScreenUtils(this).hideStatusAndActionBars()
            actionBar?.hide()
        }
    }

    override fun onRefresh() {
        log.d("Reloading WebView URL")
        webView!!.removeAllViews()
        loadInitURL()
    }

    override fun onResume() {
        // Check for screen off or background task not running and if so exit
        if (!screen.isScreenOn() || !config.backgroundTaskRunning) {
            config.eventBroadcaster.removeListener(this)
            screen.setDeviceBrightnessMode(true)
            finishActivity()
        }
        super.onResume()
        // Keep screen on
        setScreenAlwaysOn(config.screenAlwaysOn)
    }

    override fun onDestroy() {
        config.eventBroadcaster.removeListener(this)
        screen.setDeviceBrightnessMode(true)
        super.onDestroy()
    }

    fun setScreenBrightness(brightness: Float) {
        try {
            if (screen.canWriteScreenSetting()) {
                screen.setScreenBrightness((brightness * 255).toInt())
            } else {
                val layout: WindowManager.LayoutParams? = this.window?.attributes
                layout?.screenBrightness = brightness
                this.window?.attributes = layout
            }
        } catch (e: Exception) {
            firebase.logException(e)
            e.printStackTrace()
        }
    }

    fun setScreenAutoBrightness(state: Boolean) {
        if (!state) {
            screen.setDeviceBrightnessMode(false)
            setScreenBrightness(config.screenBrightness)
        } else {
            screen.setDeviceBrightnessMode(true)
        }
    }

    fun setScreenAlwaysOn(state: Boolean) {
        // wake lock
        if (state) {
            screen.wakeScreen()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            window.decorView.keepScreenOn = true
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.decorView.keepScreenOn = false
        }
    }

    fun setDarkMode(state: Boolean) {
        if(WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
            WebSettingsCompat.setForceDarkStrategy(webView!!.getSettings(), if (state) WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY else WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING);
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(webView!!.settings, if (state) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF)
        }

        val uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            uiModeManager.setApplicationNightMode(if (state) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO)
        } else {
            AppCompatDelegate.setDefaultNightMode(if (state) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        }
    }

    override fun finishActivity() {
        finish()
    }


    override fun showDiagnosticsPopup(show: Boolean) {
        if (show) {
            try {
                if (popup == null) {
                    val view: View =
                        this@WebViewActivity.layoutInflater.inflate(R.layout.popup_diagnostics, null)

                    popup = PopupWindow(
                        view,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    popup?.setBackgroundDrawable(Color.WHITE.toDrawable())
                    popup?.showAtLocation(webView, Gravity.TOP, 0, 0)
                    firebase.logEvent(FirebaseManager.DIAGNOSTIC_POPUP_SHOWN,mapOf())
                }
            } catch (e: Exception) {
                log.e("Error showing diagnostics popup: ${e.message}")
                firebase.logException(e)
            }

        } else {
            if (popup != null && popup!!.isShowing) {
                log.d("Dismissing diagnostics popup")
                popup?.dismiss()
                popup = null
            }
        }
    }

    fun updateDiagnosticStats(level: Float, prediction: Float) {
        if (popup != null && popup!!.isShowing) {
            if (diagnosticIterations > 40) {
                maxPrediction = prediction
                diagnosticIterations = 0
            }
            if (prediction > maxPrediction) {
                maxPrediction = prediction
                diagnosticIterations = 0
            } else {
                ++diagnosticIterations
            }
            val micLevel = "Mic Audio Level: ${"%.4f".format(level * 10)}"
            val wakeWordPrediction = "Wake Word Prediction: ${"%.1f".format(abs(maxPrediction) * 10)}"
            popup?.contentView?.findViewById<TextView>(R.id.audioLevel)?.text = micLevel
            popup?.contentView?.findViewById<TextView>(R.id.wakePrediction)?.text = wakeWordPrediction
            popup?.update()
        }
    }

}