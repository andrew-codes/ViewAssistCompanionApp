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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.Display
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.service.VABackgroundService
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.ui.VADialog
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
import kotlin.concurrent.thread
import kotlin.getValue


class MainActivity : ComponentActivity(), EventListener, ComponentCallbacks2 {
    val viewModel: VAViewModel by viewModels()

    private val log = Logger()
    private val firebase = FirebaseManager.getInstance()

    private lateinit var config: APPConfig
    private lateinit var webView: CustomWebView
    private lateinit var webViewClient: CustomWebViewClient

    private lateinit var screen: ScreenUtils
    private lateinit var updater: Updater
    private var screenOrientation: Int = 0
    private var updateProcessComplete: Boolean = true
    private var firstLoad: Boolean = true
    private var screenTimeout: Int = 30000
    private var tempScreenTimeout: Int = 0

    @SuppressLint("HardwareIds", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {

        viewModel.bind(APPConfig.getInstance(this),resources)

        val splashscreen = installSplashScreen()
        var keepSplashScreen = true

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        splashscreen.setKeepOnScreenCondition { keepSplashScreen }

        config = APPConfig.getInstance(this)
        screen = ScreenUtils(this)
        updater = Updater(this)

        // If we get in a mess with short screen timeout to turn off
        // screen, set a decent timeout here.
        screenTimeout = screen.getScreenTimeout()
        if (screenTimeout < 15000) {
            screenTimeout = 15000
            screen.setScreenTimeout(screenTimeout)
        }

        webView = CustomWebView.getView(this)
        webViewClient = CustomWebViewClient(viewModel)
        webView.webViewClient = webViewClient

        onBackPressedDispatcher.addCallback(this, onBackButton)
        setFirebaseUserProperties()

        log.i("#################################################################################################")
        log.i("Starting View Assist Companion App")
        log.i("Version ${config.version}")
        log.i("Android version: ${Helpers.getAndroidVersion()}")
        log.i("Name: ${Helpers.getDeviceName()}")
        log.i("Serial: ${Build.SERIAL}")
        log.i("UUID: ${config.uuid}")
        log.i("#################################################################################################")

        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)

        setStatus(getString(R.string.status_initialising))
        keepSplashScreen = false

        // Init webview setup
        initWebView()

        // Hide system bars
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        setContent {
            AppTheme(darkMode = false, dynamicColor = false) {
                val vaUiState by viewModel.vacaState.collectAsState()

                Surface(
                    modifier = Modifier
                        .fillMaxSize(),
                    color = Color.Black
                ) {
                    if (vaUiState.satelliteRunning) {
                        if (!vaUiState.screenOn) {
                            BlackScreen()
                        } else {
                            firstLoad = false
                            WebViewScreen(webView)
                        }
                    } else {
                        ConnectionScreen()
                    }
                    when {
                        vaUiState.alertDialog != null -> {
                            VADialog(
                                onDismissRequest = {
                                    vaUiState.alertDialog!!.onDismiss()
                                },
                                onConfirmation = {
                                    vaUiState.alertDialog!!.onConfirm()
                                },
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
        webView.apply {
            initialise()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        webViewClient.initialise(webView)
    }

    val onBackButton = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {}
    }

    fun setFirebaseUserProperties() {
        val webViewVersion = DeviceCapabilitiesManager(this).getWebViewVersion()
        firebase.setUserProperty("webview_version", webViewVersion)
        firebase.setUserProperty("device_signature", Helpers.getDeviceName().toString())

        firebase.setCustomKeys(mapOf(
            "Webview" to webViewVersion,
            "Device" to Helpers.getDeviceName().toString()
        ))
    }

    fun initialise() {
        if (!hasPermissions()) {
            setStatus(getString(R.string.status_no_permissions))
            return
        }
        val hasNetwork = Helpers.isNetworkAvailable(this)
        if (!hasNetwork) {
            setStatus(getString(R.string.status_waiting_for_network))
            Handler(Looper.getMainLooper()).postDelayed({
                initialise()
            }, 1000)
            return
        }

        // Make volume keys adjust music stream
        volumeControlStream = AudioManager.STREAM_MUSIC

        // Add broadcast receiver
        val filter = IntentFilter().apply {
            addAction(BroadcastSender.SATELLITE_STARTED)
            addAction(BroadcastSender.SATELLITE_STOPPED)
            addAction(BroadcastSender.VERSION_MISMATCH)
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(satelliteBroadcastReceiver, filter)

        config.eventBroadcaster.addListener(this)

        config.currentActivity = "Main"

        // Start background tasks
        runBackgroundTasks()
    }

    // Initiate wake word broadcast receiver
    val satelliteBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BroadcastSender.SATELLITE_STARTED -> {
                    viewModel.setSatelliteRunning(true)
                    setZoomLevel(config.zoomLevel)
                    val url = AuthUtils.getURL(webViewClient.getHAUrl())
                    log.d("Loading URL: $url")
                    webView.loadUrl(url)
                }
                BroadcastSender.SATELLITE_STOPPED -> {
                    viewModel.setSatelliteRunning(false)
                }
                BroadcastSender.VERSION_MISMATCH -> {
                    runUpdateRoutine()
                }
            }

        }
    }

    fun setStatus(status: String) {
        viewModel.setStatusMessage(status)
    }

    fun runUpdateRoutine() {
        if (config.hasWriteExternalStoragePermission && updateProcessComplete) {
            updateProcessComplete = false
            setStatus(getString(R.string.status_checking_for_update))
            thread(name = "Updater thread") {
                checkForUpdate()
            }
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

        setScreenAlwaysOn(config.screenAlwaysOn)
    }

    override fun onDestroy() {
        log.d("Main Activity destroyed")
        screen.setScreenTimeout(screenTimeout)
        config.eventBroadcaster.removeListener(this)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(satelliteBroadcastReceiver)
        super.onDestroy()
    }

    private fun runBackgroundTasks() {
        if ( config.backgroundTaskRunning ) {
            log.w("Background task already running.  Not starting from MainActivity")
            firebase.logEvent(FirebaseManager.MAIN_ACTIVITY_BACKGROUND_TASK_ALREADY_RUNNING, mapOf())
            if (config.isRunning) {
                viewModel.setSatelliteRunning(true)
                setZoomLevel(config.zoomLevel)
                val url = AuthUtils.getURL(webViewClient.getHAUrl())
                log.d("Loading URL: $url")
                webView.loadUrl(url)
            } else {
                setStatus(getString(R.string.status_waiting_for_connection))
            }
            return
        }
        if (!updateProcessComplete) {
            Handler(Looper.getMainLooper()).postDelayed({
                runBackgroundTasks()
            }, 1000)
            return
        }
        log.d("Starting background tasks")
        setStatus(getString(R.string.status_waiting_for_connection))
        Intent(this.applicationContext, VABackgroundService::class.java).also {
            it.action = VABackgroundService.Actions.START.toString()
            startService(it)
        }

    }

    override fun onEventTriggered(event: Event) {
        var consumed = true

        when (event.eventName) {
            "refresh" -> runOnUiThread { webView.reload() }
            "zoomLevel" -> runOnUiThread { setZoomLevel(event.newValue as Int) }
            "darkMode" -> runOnUiThread { setDarkMode(event.newValue as Boolean) }
            "screenAlwaysOn" -> runOnUiThread { setScreenAlwaysOn(event.newValue as Boolean, true) }
            "screenAutoBrightness" -> runOnUiThread { setScreenAutoBrightness(event.newValue as Boolean) }
            "screenBrightness" -> runOnUiThread { setScreenBrightness(event.newValue as Float) }
            "screenWake" -> runOnUiThread { screenWake() }
            "screenSleep" -> runOnUiThread { screenSleep() }
            else -> consumed = false
        }
        if (consumed) {
            log.d("MainActivity - Event: ${event.eventName} - ${event.newValue}")
        }
    }

    fun screenWake() {
        viewModel.setScreenOn(true)
        screen.setScreenTimeout(screenTimeout)
        setScreenAlwaysOn(config.screenAlwaysOn, true)
        setScreenAutoBrightness(config.screenAutoBrightness)
        setScreenBrightness(config.screenBrightness)
        screen.wakeScreen()
    }

    fun screenSleep() {
        viewModel.setScreenOn(false)
        if (tempScreenTimeout == 0) {
            screenTimeout = screen.getScreenTimeout()
            tempScreenTimeout = 1000
        }
        screen.setScreenTimeout(tempScreenTimeout)
        setScreenAlwaysOn(false)
        setScreenAutoBrightness(false)
        setScreenBrightness(0.01f)
        Handler(Looper.getMainLooper()).postDelayed({
            resetScreenParams()
        }, 1000)
    }

    private fun resetScreenParams() {
        var isScreenOff = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            isScreenOff = display.state == Display.STATE_OFF
        } else {
            isScreenOff = windowManager.defaultDisplay.state == Display.STATE_OFF
        }

        if (isScreenOff) {
            log.d("Screen off")
            viewModel.setScreenOn(true)
            screen.setScreenTimeout(screenTimeout)
            setScreenAutoBrightness(config.screenAutoBrightness)
            setScreenBrightness(config.screenBrightness)
            tempScreenTimeout = 0
        } else {
            if (!viewModel.vacaState.value.screenOn) {
                log.d("Waiting for screen off")
                Handler(Looper.getMainLooper()).postDelayed({
                    resetScreenParams()
                }, 1000)
            }
        }
    }

    fun setZoomLevel(level: Int) {
        if (level == 0) {
            webView.settings.useWideViewPort = true
        } else {
            webView.settings.useWideViewPort = false
            webView.setInitialScale(level)
        }

    }

    fun setDarkMode(isDark: Boolean) {
        log.d("Setting dark mode: $isDark")

        // Set device dark mode
        val uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            uiModeManager.setApplicationNightMode(if (isDark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO)
        } else {
            uiModeManager.nightMode = if (isDark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
        }

        // Set webview dark mode if supported for older devices
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(
                webView.settings,
                if (isDark) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
            )
        }
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

    fun setScreenAlwaysOn(state: Boolean, turnScreenOn: Boolean = false) {
        // wake lock
        if (state) {
            if (turnScreenOn && !screen.isScreenOn()) {
                screen.wakeScreen()
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            window.decorView.keepScreenOn = true
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.decorView.keepScreenOn = false
        }
    }

    private fun hasPermissions(): Boolean {
        val result: Boolean = config.hasRecordAudioPermission && config.hasPostNotificationPermission
        return result
    }

    private fun checkAndRequestPermissions() {
        var requiredPermissions: Array<String> = arrayOf()
        var requestID: Int = 0

        log.d("Checking main permissions")

        if (ContextCompat.checkSelfPermission(this, permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions += permission.RECORD_AUDIO
            requestID += RECORD_AUDIO_PERMISSIONS_REQUEST
            config.hasRecordAudioPermission = false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions += permission.POST_NOTIFICATIONS
                requestID += NOTIFICATION_PERMISSIONS_REQUEST
                config.hasPostNotificationPermission = false
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions += permission.WRITE_EXTERNAL_STORAGE
                requestID += WRITE_EXTERNAL_STORAGE_PERMISSIONS_REQUEST
                config.hasWriteExternalStoragePermission = false
            }
        }

        if (requiredPermissions.isNotEmpty()) {
            log.d("Requesting main permissions")
            log.d("Permissions: ${requiredPermissions.map { it }}")
            ActivityCompat.requestPermissions(
                this, requiredPermissions, requestID
            )
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
                if (permissions[i] == permission.RECORD_AUDIO && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    log.d("Permission granted: ${permissions[i]}")
                    config.hasRecordAudioPermission = true
                }
                if (permissions[i] == permission.POST_NOTIFICATIONS && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    log.d("Permission granted: ${permissions[i]}")
                    config.hasPostNotificationPermission = true
                }
                if (permissions[i] == permission.WRITE_EXTERNAL_STORAGE && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    log.d("Permission granted: ${permissions[i]}")
                    config.hasWriteExternalStoragePermission = true
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
        private const val NOTIFICATION_PERMISSIONS_REQUEST = 300
        private const val WRITE_EXTERNAL_STORAGE_PERMISSIONS_REQUEST = 400
    }

    private val onWriteSettingsPermissionActivityResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        checkAndRequestNotificationAccessPolicyPermission()
    }

    private fun checkAndRequestWriteSettingsPermission() {
        val unsupportedDevices = listOf("None")
        if (config.canSetScreenWritePermission && !ScreenUtils(this).canWriteScreenSetting() && Helpers.getDeviceName().toString() !in unsupportedDevices) {
            val alertDialog = AlertDialog.Builder(this)
            log.d("Requesting write settings permission")
            alertDialog.apply {
                setTitle("Write Settings Permission Required")
                setMessage("This application needs this permission to control the Auto brightness setting.  If your device requires explicit permission, the screen will launch for you to enable it.")
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
            }.create().show()
        } else {
            log.d("Write settings permission ${if (!config.canSetScreenWritePermission) "not required" else "already granted"}")
            checkAndRequestNotificationAccessPolicyPermission()
        }
    }

    private val onNotificationAccessPolicyPermissionActivityResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        initialise()
    }

    private fun checkAndRequestNotificationAccessPolicyPermission() {
        val notificationManager =  this.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val unsupportedDevices = listOf("LenovoCD-24502F", "Google iot_msm8x53_som")
        if (!notificationManager.isNotificationPolicyAccessGranted && Helpers.getDeviceName().toString() !in unsupportedDevices) {
            // If not granted, prompt the user to give permission.
            val alertDialog = AlertDialog.Builder(this)
            log.d("Requesting notification access policy permission")
            alertDialog.apply {
                setTitle("Notification Policy Access Permission Required")
                setMessage("This application needs this permission to control the Do Not Disturb setting.  If your device has this capability and requires explicit permission, the screen will launch for you to enable it.")
                setPositiveButton("Got it") { _: DialogInterface?, _: Int ->
                    try {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        onNotificationAccessPolicyPermissionActivityResult.launch(intent)
                    } catch (e: Exception) {
                        log.i("Device does not require explicit permission")
                        initialise()
                    }
                }
            }.create().show()
        } else {
            log.d("Notification access policy permission already granted")
            initialise()
        }
    }

    private fun checkForUpdate() {
        Looper.prepare()
        if (updater.isUpdateAvailable(config.minRequiredApkVersion)) {
            log.d("Update available - ${updater.latestRelease.downloadURL}")

            val a = VADialog(
                title = "Update Required",
                message = "You require a minimum of v${config.minRequiredApkVersion} of this app to connect to your server.  Do you wish to download and install this version now?",
                confirmCallback = {
                    downloadAndInstallUpdate()
                },
                dismissCallback = {
                    updateProcessComplete = true
                    viewModel.vacaState.value.updates.updateAvailable = true
                    setStatus(getString(R.string.status_app_update_required, config.minRequiredApkVersion))
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
                    intent.setDataAndType(
                        uri.toUri(),
                        "application/vnd.android.package-archive"
                    );
                    onUpdateAppActivityResult.launch(intent)
                }
            } else {
                val b = VADialog(
                    title = "Error downloading update",
                    message = "There was an error downloading the update.  This could be caused by a lack of disk space or an error accessing the internet.",
                    confirmCallback = {
                        updateProcessComplete = true
                        setStatus(getString(R.string.status_app_update_required, config.minRequiredApkVersion))
                    },
                    dismissCallback = {}
                )
                viewModel.showUpdateDialog(b)
            }
        }
    }

    private val onUpdateAppActivityResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
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

