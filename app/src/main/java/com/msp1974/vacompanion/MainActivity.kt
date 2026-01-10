package com.msp1974.vacompanion

import android.Manifest.permission
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.provider.Settings
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.camera.core.ExperimentalMirrorMode
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.service.VAForegroundService
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.settings.BackgroundTaskStatus
import com.msp1974.vacompanion.ui.VADialog
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.ui.components.VADialog
import com.msp1974.vacompanion.ui.layouts.BlackScreen
import com.msp1974.vacompanion.ui.layouts.ConnectionScreen
import com.msp1974.vacompanion.ui.layouts.WebViewScreen
import com.msp1974.vacompanion.ui.theme.AppTheme
import com.msp1974.vacompanion.utils.AuthUtils
import com.msp1974.vacompanion.utils.CustomWebView
import com.msp1974.vacompanion.utils.CustomWebViewClient
import com.msp1974.vacompanion.utils.DeviceCapabilitiesManager
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.FirebaseManager
import com.msp1974.vacompanion.utils.Helpers
import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.utils.ScreenUtils
import com.msp1974.vacompanion.utils.Updater
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread
import kotlin.getValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber

class MainActivity : AppCompatActivity(), EventListener, ComponentCallbacks2 {
    val viewModel: VAViewModel by viewModels()

    private val log = Logger()
    private val firebase = FirebaseManager.getInstance()

    private lateinit var config: APPConfig

    // Dual WebView setup for seamless switching between HA and external URL
    private lateinit var haWebView: CustomWebView
    private lateinit var externalWebView: CustomWebView
    private lateinit var haWebViewClient: CustomWebViewClient
    private lateinit var externalWebViewClient: CustomWebViewClient

    // Legacy reference - points to the currently active WebView
    private val webView: CustomWebView
        get() = if (viewModel.vacaState.value.showingHAView) haWebView else externalWebView

    private lateinit var screen: ScreenUtils
    private lateinit var updater: Updater
    private var screenOrientation: Int = 0
    private var updateProcessComplete: Boolean = true
    private var initialised: Boolean = false
    private var hasNetwork: Boolean = false
    private var screenOffStartUp: Boolean = false
    private var screenOffInProgress: Boolean = false
    private var screenSleepWaitJob: Job? = null

    @OptIn(ExperimentalMirrorMode::class)
    @SuppressLint("HardwareIds", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {

        viewModel.bind(APPConfig.getInstance(this), resources)

        val splashscreen = installSplashScreen()
        var keepSplashScreen = true

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        splashscreen.setKeepOnScreenCondition { keepSplashScreen }

        config = APPConfig.getInstance(this)
        screen = ScreenUtils(this)
        updater = Updater(this)

        onBackPressedDispatcher.addCallback(this, onBackButton)
        setFirebaseUserProperties()

        log.i(
                "#################################################################################################"
        )
        log.i("Starting View Assist Companion App")
        log.i("Version ${config.version}")
        log.i("Android version: ${Helpers.getAndroidVersion()}")
        log.i("Name: ${Helpers.getDeviceName()}")
        log.i("Serial: ${Build.SERIAL}")
        log.i("UUID: ${config.uuid}")
        log.i(
                "#################################################################################################"
        )

        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        Thread.setDefaultUncaughtExceptionHandler(AppExceptionHandler(this))

        setStatus(getString(R.string.status_initialising))
        keepSplashScreen = false

        registerWifiMonitor()

        // Init webview setup
        initWebView()

        // Hide system bars
        screen.hideSystemUI(window)

        // Wake screen on boot if off - keep black.
        if (!screen.isScreenOn() && !screen.isScreenOff()) {
            Timber.i("Performing screen off startup....")
            screenOffStartUp = true
            screenWake(true)
        } else {
            // Set screen timeout to 10m during loading
            // It will get reset on start satellite
            Timber.i("Performing screen on startup....")
            screen.setScreenTimeout(600000)
        }

        setContent {
            val vaUiState by viewModel.vacaState.collectAsState()
            AppTheme(darkMode = config.darkMode, dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    if (vaUiState.satelliteRunning) {
                        if (vaUiState.screenBlank) {
                            BlackScreen()
                        } else {
                            WebViewScreen(haWebView = haWebView, externalWebView = externalWebView)
                        }
                    } else {
                        if (vaUiState.screenBlank) {
                            BlackScreen()
                        } else {
                            ConnectionScreen()
                        }
                    }
                    when {
                        vaUiState.alertDialog != null -> {
                            VADialog(
                                    onDismissRequest = { vaUiState.alertDialog!!.onDismiss() },
                                    onConfirmation = { vaUiState.alertDialog!!.onConfirm() },
                                    dialogTitle = vaUiState.alertDialog!!.title,
                                    dialogText = vaUiState.alertDialog!!.message,
                                    confirmText = vaUiState.alertDialog!!.confirmText,
                                    dismissText = vaUiState.alertDialog!!.dismissText
                            )
                        }
                    }
                }
            }
        }

        // Check and get required user permissions
        log.d("Checking permissions")
        checkAndRequestPermissions()
    }

    fun initWebView() {
        log.d("Initializing dual WebView setup")

        // Create HA WebView
        haWebViewClient = CustomWebViewClient(viewModel)
        haWebView = CustomWebView.getView(this)
        haWebView.initialise(config, haWebViewClient)
        haWebView.layoutParams =
                ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )

        // Create External URL WebView
        externalWebViewClient = CustomWebViewClient(viewModel)
        externalWebView = CustomWebView.getView(this)
        externalWebView.initialise(config, externalWebViewClient)
        externalWebView.layoutParams =
                ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )

        // Note: HA WebView will be loaded on-demand during first navigate-to-ha event
        // (config may not be fully populated at initialization time)

        // Pre-load external URL WebView (default view)
        val defaultUrl = AuthUtils.getAppropriateUrl(config, withDashboardPath = false)
        log.d("Pre-loading external WebView with: $defaultUrl")
        externalWebView.loadUrl(defaultUrl)

        // Start with external URL view (default)
        viewModel.setShowingHAView(false)

        log.d("Dual WebView setup complete")
    }

    val onBackButton =
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {}
            }

    fun setFirebaseUserProperties() {
        val webViewVersion = DeviceCapabilitiesManager(this).getWebViewVersion()
        firebase.setUserProperty("webview_version", webViewVersion)
        firebase.setUserProperty("device_signature", Helpers.getDeviceName().toString())

        firebase.setCustomKeys(
                mapOf(
                        "Webview" to webViewVersion,
                        "Device" to Helpers.getDeviceName().toString(),
                        "UUID" to config.uuid
                )
        )
    }

    fun initialise() {
        if (!hasPermissions()) {
            setStatus(getString(R.string.status_no_permissions))
            return
        }
        if (!hasNetwork) {
            setStatus(getString(R.string.status_waiting_for_network))
            Handler(Looper.getMainLooper()).postDelayed({ initialise() }, 1000)
            return
        }

        // Make volume keys adjust music stream
        volumeControlStream = AudioManager.STREAM_MUSIC

        // Add broadcast receiver
        val filter =
                IntentFilter().apply {
                    addAction(BroadcastSender.SATELLITE_STARTED)
                    addAction(BroadcastSender.SATELLITE_STOPPED)
                    addAction(BroadcastSender.VERSION_MISMATCH)
                    addAction(BroadcastSender.WEBVIEW_CRASH)
                }
        LocalBroadcastManager.getInstance(this).registerReceiver(satelliteBroadcastReceiver, filter)

        val screenIntentFilter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
                }
        registerReceiver(satelliteBroadcastReceiver, screenIntentFilter)

        config.eventBroadcaster.addListener(this)
        config.currentActivity = "Main"

        // Start background tasks
        runBackgroundTasks()

        initialised = true
    }

    // Initiate wake word broadcast receiver
    val satelliteBroadcastReceiver: BroadcastReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    Timber.d("Broadcast received: ${intent.action}")
                    when (intent.action) {
                        BroadcastSender.SATELLITE_STARTED -> {
                            log.d("=== SATELLITE_STARTED EVENT ===")
                            log.d("Config - directURL: '${config.directURL}'")
                            log.d("Config - homeAssistantURL: '${config.homeAssistantURL}'")
                            log.d("Config - homeAssistantDashboard: '${config.homeAssistantDashboard}'")
                            log.d("Config - homeAssistantConnectedIP: '${config.homeAssistantConnectedIP}'")

                            viewModel.setSatelliteRunning(true)

                            // Load HA WebView now that config is populated
                            if ((haWebView.url == null || haWebView.url == "about:blank") &&
                                            (config.homeAssistantURL.isNotEmpty() ||
                                                    config.homeAssistantConnectedIP.isNotEmpty())
                            ) {
                                log.d("Satellite started - loading HA WebView...")
                                val haUrl = AuthUtils.getHAUrl(config, withDashboardPath = true)
                                log.d("HA URL computed: $haUrl")
                                val hasValidToken =
                                        config.accessToken.isNotEmpty() &&
                                                config.tokenExpiry > System.currentTimeMillis()

                                if (hasValidToken) {
                                    val finalUrl = AuthUtils.getURL(haUrl, true, config)
                                    log.d("Loading HA WebView with token: $finalUrl")
                                    haWebView.loadUrl(finalUrl)
                                } else {
                                    // No valid token - load auth URL to start OAuth flow
                                    val baseUrl =
                                            AuthUtils.getHAUrl(config, withDashboardPath = false)
                                    val authUrl = AuthUtils.getAuthUrl(baseUrl)
                                    log.d("Loading HA WebView auth URL (OAuth flow): $authUrl")
                                    haWebView.loadUrl(authUrl)
                                }
                            } else {
                                log.d("Skipping HA WebView load - already loaded or HA not configured")
                                log.d("  haWebView.url: ${haWebView.url}")
                            }

                            if (!screen.isScreenOn()) {
                                screenOffStartUp = true
                                if (config.screenAlwaysOn) {
                                    screenWake(blackOut = false)
                                } else {
                                    screenWake(blackOut = true)
                                }
                            }

                            screen.setScreenTimeout(config.screenTimeout)

                            // Set zoom level for both WebViews
                            haWebView.setZoomLevel(config.zoomLevel)
                            externalWebView.setZoomLevel(config.zoomLevel)

                            config.screenOn = screen.isScreenOn()

                            // Load the default view (external URL) on startup
                            log.d("Getting appropriate URL for external WebView...")
                            val defaultUrl = AuthUtils.getAppropriateUrl(config)
                            log.d("Appropriate URL computed: $defaultUrl")
                            log.d("Loading default URL in external WebView: $defaultUrl")
                            log.d("External WebView current URL before load: ${externalWebView.url}")
                            externalWebView.loadUrl(defaultUrl)

                            // Show external WebView by default
                            log.d("Setting showingHAView to FALSE (show external WebView)")
                            viewModel.setShowingHAView(false)
                            log.d("=== SATELLITE_STARTED EVENT COMPLETE ===")
                        }
                        BroadcastSender.SATELLITE_STOPPED -> {
                            viewModel.setSatelliteRunning(false)
                            if (!config.backgroundTaskRunning) {
                                finishAndRemoveTask()
                            }
                        }
                        BroadcastSender.VERSION_MISMATCH -> {
                            runUpdateRoutine()
                        }
                        BroadcastSender.WEBVIEW_CRASH -> {
                            initWebView()
                            val defaultUrl =
                                    AuthUtils.getAppropriateUrl(config, withDashboardPath = false)
                            log.d("Reloading default URL after crash: $defaultUrl")
                            externalWebView.loadUrl(defaultUrl)
                            viewModel.setShowingHAView(false)
                        }
                        Intent.ACTION_SCREEN_ON -> {
                            if (!screenOffStartUp) {
                                viewModel.setScreenBlank(false)
                                // If woken by hardware buttons set screen config
                                setScreenSettings()

                                // Always navigate to default view when screen wakes up
                                log.d(
                                        "Screen woke up - switching to default view (external WebView)"
                                )
                                viewModel.setShowingHAView(false)
                            }
                            config.screenOn = true
                        }
                        Intent.ACTION_SCREEN_OFF -> {
                            viewModel.setScreenBlank(false)
                            config.screenOn = false
                        }
                        NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                            val dndEnabled =
                                    DeviceCapabilitiesManager.isDoNotDisturbEnabled(context)
                            if (config.doNotDisturb != dndEnabled) {
                                config.doNotDisturb = dndEnabled
                            }
                        }
                    }
                }
            }

    fun registerWifiMonitor() {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        log.i("Network connection available")
                        hasNetwork = true
                        viewModel.onNetworkStateChange()
                        setStatus(getString(R.string.status_waiting_for_connection))
                    }

                    override fun onLost(network: Network) {
                        log.e("Lost network connection")
                        hasNetwork = false
                        viewModel.onNetworkStateChange()

                        val delay = 10
                        setStatus(getString(R.string.status_waiting_for_network))
                        if (config.enableNetworkRecovery) {
                            log.d("Disabling wifi for ${delay}s")
                            Helpers.enableWifi(this@MainActivity, false)
                            Handler(Looper.getMainLooper())
                                    .postDelayed(
                                            {
                                                log.d("Enabling wifi")
                                                Helpers.enableWifi(this@MainActivity, true)
                                            },
                                            (delay * 1000).toLong()
                                    )
                        }
                    }
                }
        )
    }

    fun setStatus(status: String) {
        viewModel.setStatusMessage(status)
    }

    fun runUpdateRoutine() {
        if (config.hasWriteExternalStoragePermission && updateProcessComplete) {
            updateProcessComplete = false
            setStatus(getString(R.string.status_checking_for_update))
            thread(name = "Updater thread") { checkForUpdate() }
        } else {
            setStatus(getString(R.string.status_app_update_required, config.minRequiredApkVersion))
        }
    }

    // Listening to the orientation config
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation != screenOrientation) {
            log.d("Orientation changed to ${newConfig.orientation}")
        }
    }

    override fun onResume() {
        super.onResume()
        log.d("Main Activity resumed")
        // Catch if background tasks not running
        if (initialised &&
                        Helpers.isNetworkAvailable(this) &&
                        config.backgroundTaskStatus == BackgroundTaskStatus.NOT_STARTED
        ) {
            log.e("Background task starting on resume as is is not running")
            runBackgroundTasks()
        }
        screen.hideSystemUI(window)
        screen.setScreenAlwaysOn(window, config.screenAlwaysOn)

        // Always navigate to default view when app resumes
        if (initialised) {
            log.d("App resumed - switching to default view (external WebView)")
            viewModel.setShowingHAView(false)
        }
    }

    override fun onDestroy() {
        log.d("Main Activity destroyed")
        screen.setScreenTimeout(config.screenTimeout)
        config.eventBroadcaster.removeListener(this)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(satelliteBroadcastReceiver)
        unregisterReceiver(satelliteBroadcastReceiver)
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Timber.i("Key pressed -> $keyCode")

        // Volume Up: Toggle between HA and External WebView (for manual HA authentication)
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val currentlyShowingHA = viewModel.vacaState.value.showingHAView
            viewModel.setShowingHAView(!currentlyShowingHA)
            log.d(
                    "Volume Up pressed - Switching to ${if (!currentlyShowingHA) "HA" else "External"} WebView"
            )
            return true // Consume the event
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun runBackgroundTasks() {
        if (config.backgroundTaskStatus != BackgroundTaskStatus.NOT_STARTED) {
            log.w("Background task already running.  Not starting from MainActivity")
            firebase.logEvent(
                    FirebaseManager.MAIN_ACTIVITY_BACKGROUND_TASK_ALREADY_RUNNING,
                    mapOf()
            )
            if (config.isRunning) {
                viewModel.setSatelliteRunning(true)

                // Set zoom level for both WebViews
                haWebView.setZoomLevel(config.zoomLevel)
                externalWebView.setZoomLevel(config.zoomLevel)

                // External WebView already pre-loaded at initialization
                // Show external WebView by default
                viewModel.setShowingHAView(false)
            } else {
                setStatus(getString(R.string.status_waiting_for_connection))
            }
            return
        }
        config.backgroundTaskStatus = BackgroundTaskStatus.STARTING

        if (!updateProcessComplete) {
            Handler(Looper.getMainLooper()).postDelayed({ runBackgroundTasks() }, 1000)
            return
        }
        log.d("Starting background tasks")
        setStatus(getString(R.string.status_waiting_for_connection))
        try {
            Intent(this.applicationContext, VAForegroundService::class.java).also {
                it.action = VAForegroundService.Actions.START.toString()
                startService(it)
            }
        } catch (ex: Exception) {
            log.w("Error starting background tasks - ${ex.message}")
            log.w("Waking screen to allow background tasks to start")
            config.backgroundTaskStatus = BackgroundTaskStatus.NOT_STARTED
            screenWake(true)
            screenSleep()
        }

        // Reset screenOffStartup
        if (screenOffStartUp) {
            screenOffStartUp = false
            screenSleep()
        } else {
            screenWake(false)
        }
    }

    override fun onEventTriggered(event: Event) {
        var consumed = true
        runOnUiThread {
            if (!screenOffInProgress) {
                when (event.eventName) {
                    "screenAlwaysOn" -> {
                        val enabled = event.newValue as Boolean
                        if (enabled) {
                            screenWake()
                        }
                        screen.setScreenAlwaysOn(window, enabled)
                    }
                    "screenAutoBrightness" -> {
                        if (screen.isScreenOn() and !viewModel.vacaState.value.screenBlank) {
                            screen.setScreenAutoBrightness(window, event.newValue as Boolean)
                        }
                    }
                    "screenBrightness" -> {
                        if (screen.isScreenOn() and !viewModel.vacaState.value.screenBlank) {
                            screen.setScreenBrightness(window, event.newValue as Float)
                        }
                    }
                    "screenTimeout" -> screen.setScreenTimeout(config.screenTimeout)
                    else -> consumed = false
                }
            }
            if (consumed) {
                log.d("MainActivity - Setting: ${event.eventName} - ${event.newValue}")
            }

            consumed = true

            when (event.eventName) {
                "zoomLevel" -> {
                    val zoom = event.newValue as Int
                    haWebView.setZoomLevel(zoom)
                    externalWebView.setZoomLevel(zoom)
                }
                "darkMode" -> setDarkMode(event.newValue as Boolean)
                "refresh" -> {
                    log.d("Refresh triggered - Current URL: ${webView.url}")
                    log.d("Current currentPath: '${config.currentPath}'")
                    webView.reload()
                }
                "navigate-to-ha" -> {
                    log.d("=== NAVIGATE-TO-HA EVENT TRIGGERED ===")

                    // Make sure we have HA connection settings
                    if (config.homeAssistantURL.isNotEmpty() ||
                                    config.homeAssistantConnectedIP.isNotEmpty()
                    ) {
                        // HA WebView should already be loaded by SATELLITE_STARTED
                        // Only load if it's truly not loaded yet
                        val currentUrl = haWebView.url
                        log.d("HA WebView current URL: $currentUrl")

                        if (currentUrl == null ||
                                        currentUrl == "about:blank" ||
                                        currentUrl.isEmpty()
                        ) {
                            log.w("HA WebView not loaded yet, loading now...")
                            val haUrl = AuthUtils.getHAUrl(config, withDashboardPath = true)
                            val hasValidToken =
                                    config.accessToken.isNotEmpty() &&
                                            config.tokenExpiry > System.currentTimeMillis()

                            if (hasValidToken) {
                                val finalUrl = AuthUtils.getURL(haUrl, true, config)
                                log.d("Loading HA WebView with token: $finalUrl")
                                haWebView.loadUrl(finalUrl)
                            } else {
                                val baseUrl = AuthUtils.getHAUrl(config, withDashboardPath = false)
                                val authUrl = AuthUtils.getAuthUrl(baseUrl)
                                log.d("Loading HA WebView auth URL: $authUrl")
                                haWebView.loadUrl(authUrl)
                            }
                        } else {
                            log.d(
                                    "HA WebView already loaded - just switching visibility, not reloading"
                            )
                        }

                        // Switch to HA WebView
                        viewModel.setShowingHAView(true)
                        log.d("Switched to HA WebView")
                    } else {
                        log.e("No Home Assistant connection configured!")
                    }
                    log.d("=== NAVIGATE-TO-HA EVENT COMPLETED ===")
                    log.d("=== NAVIGATE-TO-HA EVENT COMPLETED ===")
                }
                "navigate-home" -> {
                    // Switch back to external URL WebView (default view)
                    log.d("=== NAVIGATE-HOME EVENT TRIGGERED ===")

                    // Check if music is playing
                    if (viewModel.vacaState.value.musicPlaying) {
                        log.d("Music is playing - staying on HA view, not switching to external")
                        // Don't switch to external view while music is playing
                    } else {
                        log.d("No music playing - switching to external URL WebView")

                        // Check if external WebView is loaded
                        val externalCurrentUrl = externalWebView.url ?: ""
                        log.d("External WebView current URL: $externalCurrentUrl")

                        if (externalCurrentUrl.isEmpty() || externalCurrentUrl == "about:blank") {
                            // External WebView not loaded yet, load it now
                            val defaultUrl = AuthUtils.getAppropriateUrl(config)
                            log.d("Loading external URL: $defaultUrl")
                            externalWebView.loadUrl(defaultUrl)
                        } else {
                            log.d("External WebView already loaded - switching immediately")
                        }

                        // Switch to external WebView
                        viewModel.setShowingHAView(false)
                    }
                    log.d("=== NAVIGATE-HOME EVENT COMPLETED ===")
                }
                "music-started" -> {
                    log.d("=== MUSIC-STARTED EVENT ===")
                    viewModel.setMusicPlaying(true)
                    config.musicPlaying = true

                    // Keep screen on during music playback
                    screen.setScreenAlwaysOn(window, true)
                    log.d("Screen set to always on for music playback")

                    // Switch to HA view - let View Assist handle the navigation to music view
                    if (!viewModel.vacaState.value.showingHAView) {
                        log.d("Switching to HA view for music playback")
                        viewModel.setShowingHAView(true)
                    }

                    log.d(
                            "Music playing state sent to HA - View Assist integration should handle navigation"
                    )
                }
                "music-stopped" -> {
                    log.d("=== MUSIC-STOPPED EVENT ===")
                    viewModel.setMusicPlaying(false)
                    config.musicPlaying = false

                    // Restore normal screen timeout behavior
                    screen.setScreenAlwaysOn(window, config.screenAlwaysOn)
                    log.d("Screen timeout restored to config setting")

                    // Don't navigate immediately - the navigate-home timeout will handle
                    // returning to external view if music doesn't restart
                    log.d("Music stopped - waiting for navigate-home timeout or new music to start")
                }
                "pause-ha-media" -> {
                    log.d("Pausing Home Assistant media player")
                    haWebView.evaluateJavascript(
                        """
                        (function() {
                            try {
                                // Pause all HTML5 audio and video elements
                                document.querySelectorAll('audio, video').forEach(el => el.pause());
                                
                                // Call Home Assistant service to pause all media players
                                if (window.hassConnection) {
                                    window.hassConnection.then(hass => {
                                        hass.callService('media_player', 'media_pause', {entity_id: 'all'});
                                    });
                                }
                            } catch(e) {
                                console.log('Error pausing media:', e);
                            }
                        })();
                        """.trimIndent(),
                        null
                    )
                }
                "resume-ha-media" -> {
                    log.d("Resuming Home Assistant media player")
                    haWebView.evaluateJavascript(
                        """
                        (function() {
                            try {
                                // Resume all paused HTML5 audio and video elements
                                document.querySelectorAll('audio, video').forEach(el => {
                                    if (el.paused) el.play();
                                });
                                
                                // Call Home Assistant service to resume all media players
                                if (window.hassConnection) {
                                    window.hassConnection.then(hass => {
                                        hass.callService('media_player', 'media_play', {entity_id: 'all'});
                                    });
                                }
                            } catch(e) {
                                console.log('Error resuming media:', e);
                            }
                        })();
                        """.trimIndent(),
                        null
                    )
                }
                "screenWake" -> screenWake(false)
                "screenWakeBlackout" -> screenWake(true)
                "screenSleep" -> screenSleep()
                "deviceBump" -> if (config.screenOnBump) screenWake()
                "proximity" ->
                        if (config.screenOnProximity && event.newValue as Float == 0f) screenWake()
                "motion" -> onMotion()
                "showToastMessage" ->
                        Toast.makeText(this, event.newValue as String, Toast.LENGTH_SHORT).show()
                else -> consumed = false
            }
            if (consumed) {
                log.d("MainActivity - Event: ${event.eventName} - ${event.newValue}")
            }
        }
    }

    fun onMotion() {
        config.lastMotion = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        if (config.screenOnMotion) screenWake()
    }

    fun setScreenSettings() {
        screen.setScreenBrightness(window, config.screenBrightness)
        screen.setScreenAutoBrightness(window, config.screenAutoBrightness)
        screen.setScreenTimeout(config.screenTimeout)
        screen.setScreenAlwaysOn(window, config.screenAlwaysOn)
    }

    fun screenWake(blackOut: Boolean = false) {
        Timber.d("Wake screen. Blackout = $blackOut")

        // Cancel any screen sleep timer
        if (screenSleepWaitJob != null && screenSleepWaitJob!!.isActive) {
            screenSleepWaitJob!!.cancel()
        }

        viewModel.setScreenBlank(blackOut)

        if (blackOut) {
            screen.setScreenBrightness(window, 0.01f)
            screen.setScreenAutoBrightness(window, false)
            screen.setScreenTimeout(1000)
        } else {
            setScreenSettings()
        }

        screen.wakeScreen()
        config.screenOn = true
    }

    fun screenSleep() {
        Timber.d("Sleeping screen")
        // Don't sleep screen if music is playing
        if (viewModel.vacaState.value.musicPlaying) {
            log.d("Music is playing - preventing screen sleep")
            return
        }

        if (!screenOffInProgress) {
            screenOffInProgress = true
            viewModel.setScreenBlank(true)
            screen.setScreenAlwaysOn(window, false)
            screen.setScreenAutoBrightness(window, false)
            screen.setScreenBrightness(window, 0.01f)
            screen.setPartialWakeLock()
            if (screen.setScreenTimeout(1000)) {
                screenSleepWaitJob = lifecycleScope.launch { waitForScreenOff() }
            } else {
                config.screenOn = false
                screenOffInProgress = false
            }
        }
    }

    suspend fun waitForScreenOff() {
        try {
            delay(1000)
            withTimeout(15000) {
                while (!screen.isScreenOff()) {
                    delay(500)
                }
            }
        } catch (ex: Exception) {
            log.w("Timed out waiting for screen off")
            screenOffInProgress = false
            return
        }
        config.screenOn = false
        screenOffInProgress = false
        viewModel.setScreenBlank(false)
        log.d("Screen off")
    }

    fun setDarkMode(isDark: Boolean) {
        log.d("Setting dark mode: $isDark")

        if (isDark) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }

        // Set device dark mode
        val uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            uiModeManager.setApplicationNightMode(
                    if (isDark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
            )
        } else {
            uiModeManager.nightMode =
                    if (isDark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
        }

        // Refresh dark mode for both WebViews
        haWebView.refreshDarkMode()
        externalWebView.refreshDarkMode()
    }

    private fun hasPermissions(): Boolean {
        if (config.hasRecordAudioPermission) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return config.hasPostNotificationPermission
            }
            return true
        }
        return false
    }

    private fun checkAndRequestPermissions() {
        var requiredPermissions: Array<String> = arrayOf()
        var requestID: Int = 0

        log.d("Checking main permissions")

        if (ContextCompat.checkSelfPermission(this, permission.RECORD_AUDIO) !=
                        PackageManager.PERMISSION_GRANTED
        ) {
            requiredPermissions += permission.RECORD_AUDIO
            requestID += RECORD_AUDIO_PERMISSIONS_REQUEST
        } else {
            config.hasRecordAudioPermission = true
        }

        if (DeviceCapabilitiesManager(this).hasFrontCamera()) {
            if (ContextCompat.checkSelfPermission(this, permission.CAMERA) !=
                            PackageManager.PERMISSION_GRANTED
            ) {
                requiredPermissions += permission.CAMERA
                requestID += CAMERA_PERMISSIONS_REQUEST
            } else {
                config.hasCameraPermission = true
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
            ) {
                requiredPermissions += permission.POST_NOTIFICATIONS
                requestID += NOTIFICATION_PERMISSIONS_REQUEST
            } else {
                config.hasPostNotificationPermission = true
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, permission.WRITE_EXTERNAL_STORAGE) !=
                            PackageManager.PERMISSION_GRANTED
            ) {
                requiredPermissions += permission.WRITE_EXTERNAL_STORAGE
                requestID += WRITE_EXTERNAL_STORAGE_PERMISSIONS_REQUEST
            } else {
                config.hasWriteExternalStoragePermission = true
            }
        }

        if (requiredPermissions.isNotEmpty()) {
            log.d("Requesting main permissions")
            log.d("Permissions: ${requiredPermissions.map { it }}")
            ActivityCompat.requestPermissions(this, requiredPermissions, requestID)
        } else {
            log.d("Main permissions already granted")
            checkAndRequestWriteSettingsPermission()
        }
    }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<String>,
            grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (permissions.isNotEmpty()) {
            for (i in permissions.indices) {
                if (permissions[i] == permission.RECORD_AUDIO &&
                                grantResults[i] == PackageManager.PERMISSION_GRANTED
                ) {
                    log.d("Permission granted: ${permissions[i]}")
                    config.hasRecordAudioPermission = true
                }
                if (permissions[i] == permission.POST_NOTIFICATIONS &&
                                grantResults[i] == PackageManager.PERMISSION_GRANTED
                ) {
                    log.d("Permission granted: ${permissions[i]}")
                    config.hasPostNotificationPermission = true
                }
                if (permissions[i] == permission.WRITE_EXTERNAL_STORAGE &&
                                grantResults[i] == PackageManager.PERMISSION_GRANTED
                ) {
                    log.d("Permission granted: ${permissions[i]}")
                    config.hasWriteExternalStoragePermission = true
                }
                if (permissions[i] == permission.CAMERA &&
                                grantResults[i] == PackageManager.PERMISSION_GRANTED
                ) {
                    log.d("Permission granted: ${permissions[i]}")
                    config.hasCameraPermission = true
                }
            }
        }
        if (hasPermissions()) {
            log.d("Main permissions granted")
            checkAndRequestWriteSettingsPermission()
        } else {
            log.d("Main permissions not granted will not run background tasks")
            if (!config.hasRecordAudioPermission) {
                log.d("Record audio permission not granted")
            }
            if (!config.hasPostNotificationPermission) {
                log.d("Post notification permission not granted")
            }
            initialise()
        }
    }

    companion object {
        private const val RECORD_AUDIO_PERMISSIONS_REQUEST = 200
        private const val CAMERA_PERMISSIONS_REQUEST = 250
        private const val NOTIFICATION_PERMISSIONS_REQUEST = 300
        private const val WRITE_EXTERNAL_STORAGE_PERMISSIONS_REQUEST = 400
    }

    private val onWriteSettingsPermissionActivityResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                checkAndRequestNotificationAccessPolicyPermission()
            }

    private fun checkAndRequestWriteSettingsPermission() {
        if (config.canSetScreenWritePermission && !ScreenUtils(this).canWriteScreenSetting()) {
            val alertDialog = AlertDialog.Builder(this)
            log.d("Requesting write settings permission")
            alertDialog
                    .apply {
                        setTitle("Write Settings Permission Required")
                        setMessage(
                                "This application needs this permission to control the Auto brightness setting.  If your device requires explicit permission, the screen will launch for you to enable it."
                        )
                        setPositiveButton("Got it") { _: DialogInterface?, _: Int ->
                            try {
                                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                onWriteSettingsPermissionActivityResult.launch(intent)
                            } catch (e: Exception) {
                                log.i("Device does not require explicit permission")
                                config.canSetScreenWritePermission = false
                                checkAndRequestNotificationAccessPolicyPermission()
                            }
                        }
                    }
                    .create()
                    .show()
        } else {
            log.d(
                    "Write settings permission ${if (!config.canSetScreenWritePermission) "not required" else "already granted"}"
            )
            checkAndRequestNotificationAccessPolicyPermission()
        }
    }

    private val onNotificationAccessPolicyPermissionActivityResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                log.i("Notification access policy permission result -> ${it.resultCode}")
                if (it.resultCode == RESULT_CANCELED) {
                    config.canSetNotificationPolicyAccess = false
                }
                initialise()
            }

    private fun checkAndRequestNotificationAccessPolicyPermission() {
        val notificationManager = this.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (config.canSetNotificationPolicyAccess &&
                        !notificationManager.isNotificationPolicyAccessGranted
        ) {
            // If not granted, prompt the user to give permission.
            val alertDialog = AlertDialog.Builder(this)
            log.d("Requesting notification access policy permission")
            alertDialog
                    .apply {
                        setTitle("Notification Policy Access Permission Required")
                        setMessage(
                                "This application needs this permission to control the Do Not Disturb setting.  If your device has this capability and requires explicit permission, the screen will launch for you to enable it."
                        )
                        setPositiveButton("Got it") { _: DialogInterface?, _: Int ->
                            try {
                                val intent =
                                        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                                onNotificationAccessPolicyPermissionActivityResult.launch(intent)
                            } catch (e: Exception) {
                                log.i("Device does not require explicit permission")
                                initialise()
                            }
                        }
                    }
                    .create()
                    .show()
        } else {
            log.d("Notification access policy permission already granted or not supported")
            config.hasPostNotificationPermission = true
            initialise()
        }
    }

    private fun checkForUpdate() {
        Looper.prepare()
        if (updater.isUpdateAvailable(config.minRequiredApkVersion)) {
            log.d("Update available - ${updater.latestRelease.downloadURL}")

            val a =
                    VADialog(
                            title = "Update Required",
                            message =
                                    "You require a minimum of v${config.minRequiredApkVersion} of this app to connect to your server.  Do you wish to download and install this version now?",
                            confirmCallback = { downloadAndInstallUpdate() },
                            dismissCallback = {
                                updateProcessComplete = true
                                viewModel.vacaState.value.updates.updateAvailable = true
                                setStatus(
                                        getString(
                                                R.string.status_app_update_required,
                                                config.minRequiredApkVersion
                                        )
                                )
                            }
                    )
            viewModel.showUpdateDialog(a)
        } else {
            updateProcessComplete = true
            setStatus("Incompatible version. In app update not available")
        }
    }

    private fun downloadAndInstallUpdate() {
        setStatus(getString(R.string.status_downloading_update))
        updater.requestDownload { uri ->
            if (uri != "") {
                log.d("Download complete = $uri")
                setStatus(getString(R.string.status_installing_update))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
                    intent.setData(uri.toUri())
                    intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    onUpdateAppActivityResult.launch(intent)
                } else {
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.setDataAndType(uri.toUri(), "application/vnd.android.package-archive")
                    onUpdateAppActivityResult.launch(intent)
                }
            } else {
                val b =
                        VADialog(
                                title = "Error downloading update",
                                message =
                                        "There was an error downloading the update.  This could be caused by a lack of disk space or an error accessing the internet.",
                                confirmCallback = {
                                    updateProcessComplete = true
                                    setStatus(
                                            getString(
                                                    R.string.status_app_update_required,
                                                    config.minRequiredApkVersion
                                            )
                                    )
                                },
                                dismissCallback = {}
                        )
                viewModel.showUpdateDialog(b)
            }
        }
    }

    private val onUpdateAppActivityResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                updateProcessComplete = true
                setStatus("Incompatible version. In app update not available")
            }

    override fun onTrimMemory(level: Int) {
        // Try and prevent the app being killed by memory manager
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            // Release memory related to UI elements, such as bitmap caches.
            firebase.logEvent(FirebaseManager.TRIM_MEMORY_UI_HIDDEN, mapOf())
            Runtime.getRuntime().gc()
        }

        if (level >= TRIM_MEMORY_BACKGROUND) {
            // Release memory related to background processing, such as by
            // closing a database connection.
            firebase.logEvent(FirebaseManager.TRIM_MEMORY_BACKGROUND, mapOf())
            Runtime.getRuntime().gc()
        }

        super.onTrimMemory(level)
    }
}
