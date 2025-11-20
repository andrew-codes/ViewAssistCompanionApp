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
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING
import androidx.webkit.WebViewFeature
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.service.VAForegroundService
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.settings.BackgroundTaskStatus
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import kotlin.concurrent.thread
import kotlin.getValue
import kotlin.time.Clock.System.now
import kotlin.time.ExperimentalTime


class MainActivity : AppCompatActivity(), EventListener, ComponentCallbacks2 {
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
    private var initialised: Boolean = false
    private var screenTimeout: Int = 30000
    private var tempScreenTimeout: Int = 0
    private var hasNetwork: Boolean = false

    @OptIn(ExperimentalMirrorMode::class)
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
        Thread.setDefaultUncaughtExceptionHandler(AppExceptionHandler(this))

        setStatus(getString(R.string.status_initialising))
        keepSplashScreen = false

        registerWifiMonitor()

        // Init webview setup
        initWebView()

        // Hide system bars
        screen.hideSystemUI(window)
        setContent {
            val vaUiState by viewModel.vacaState.collectAsState()
            AppTheme(darkMode = config.darkMode, dynamicColor = false) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize(),
                    color = Color.Black
                ) {
                    if (vaUiState.satelliteRunning) {
                        if (!vaUiState.screenOn) {
                            BlackScreen()
                        } else {
                            WebViewScreen(webView)
                        }
                    } else {
                        if (!vaUiState.screenOn) {
                            BlackScreen()
                        } else {
                            ConnectionScreen()
                        }
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
        webViewClient = CustomWebViewClient(viewModel)
        webView = CustomWebView.getView(this)
        webView.initialise(config, webViewClient)
        webView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        )
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
            "Device" to Helpers.getDeviceName().toString(),
            "UUID" to config.uuid
        ))
    }

    fun initialise() {
        if (!hasPermissions()) {
            setStatus(getString(R.string.status_no_permissions))
            return
        }
        if (!hasNetwork) {
            setStatus(getString(R.string.status_waiting_for_network))
            Handler(Looper.getMainLooper()).postDelayed({
                initialise()
            }, 1000)
            return
        }

        // If we get in a mess with short screen timeout to turn off
        // screen, set a decent timeout here.
        screenTimeout = screen.getScreenTimeout()
        if (screenTimeout < 15000) {
            screenTimeout = 15000
            screen.setScreenTimeout(screenTimeout)
        }

        // Make volume keys adjust music stream
        volumeControlStream = AudioManager.STREAM_MUSIC

        // Add broadcast receiver
        val filter = IntentFilter().apply {
            addAction(BroadcastSender.SATELLITE_STARTED)
            addAction(BroadcastSender.SATELLITE_STOPPED)
            addAction(BroadcastSender.VERSION_MISMATCH)
            addAction(BroadcastSender.WEBVIEW_CRASH)
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(satelliteBroadcastReceiver, filter)

        val screenIntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(satelliteBroadcastReceiver, screenIntentFilter)


        config.eventBroadcaster.addListener(this)

        config.currentActivity = "Main"

        // Start background tasks
        runBackgroundTasks()

        initialised = true
    }

    // Initiate wake word broadcast receiver
    val satelliteBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BroadcastSender.SATELLITE_STARTED -> {
                    viewModel.setSatelliteRunning(true)
                    webView.setZoomLevel(config.zoomLevel)
                    config.screenOn = screen.isScreenOn()
                    val url = AuthUtils.getURL(AuthUtils.getHAUrl(config))
                    log.d("Loading URL: $url")
                    webView.loadUrl(url)
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
                    val url = AuthUtils.getURL(AuthUtils.getHAUrl(config))
                    log.d("Loading URL: $url")
                    webView.loadUrl(url)
                }
                Intent.ACTION_SCREEN_ON -> {
                    config.screenOn = true
                }
                Intent.ACTION_SCREEN_OFF -> {
                    config.screenOn = false
                    // Set screenTimeout in case it changed and this is a dream sleep
                    // not a forced sleep
                    if (tempScreenTimeout == 0) {
                        screenTimeout = screen.getScreenTimeout()
                    }
                }
            }
        }
    }


    fun registerWifiMonitor() {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
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
                    Handler(Looper.getMainLooper()).postDelayed({
                        log.d("Enabling wifi")
                        Helpers.enableWifi(this@MainActivity, true)
                    }, (delay * 1000).toLong())
                }
            }
        })
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
        // Catch if background tasks not running
        if (initialised && Helpers.isNetworkAvailable(this) && config.backgroundTaskStatus == BackgroundTaskStatus.NOT_STARTED ) {
            log.e("Background task starting on resume as is is not running")
            runBackgroundTasks()
        }
        screen.hideSystemUI(window)
        screen.setScreenAlwaysOn(window, config.screenAlwaysOn)
    }

    override fun onDestroy() {
        log.d("Main Activity destroyed")
        screen.setScreenTimeout(screenTimeout)
        config.eventBroadcaster.removeListener(this)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(satelliteBroadcastReceiver)
        unregisterReceiver(satelliteBroadcastReceiver)
        super.onDestroy()
    }

    private fun runBackgroundTasks() {
        if ( config.backgroundTaskStatus != BackgroundTaskStatus.NOT_STARTED ) {
            log.w("Background task already running.  Not starting from MainActivity")
            firebase.logEvent(FirebaseManager.MAIN_ACTIVITY_BACKGROUND_TASK_ALREADY_RUNNING, mapOf())
            if (config.isRunning) {
                viewModel.setSatelliteRunning(true)
                webView.setZoomLevel(config.zoomLevel)
                val url = AuthUtils.getURL(AuthUtils.getHAUrl(config))
                log.d("Loading URL: $url")
                webView.loadUrl(url)
            } else {
                setStatus(getString(R.string.status_waiting_for_connection))
            }
            return
        }
        config.backgroundTaskStatus = BackgroundTaskStatus.STARTING

        if (!updateProcessComplete) {
            Handler(Looper.getMainLooper()).postDelayed({
                runBackgroundTasks()
            }, 1000)
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
            viewModel.vacaState.value.screenOn = false
            screenWake(true)
            screenSleep()
        }

    }

    override fun onEventTriggered(event: Event) {
        var consumed = true

        when (event.eventName) {
            "refresh" -> runOnUiThread { webView.reload() }
            "zoomLevel" -> runOnUiThread { webView.setZoomLevel(event.newValue as Int) }
            "darkMode" -> runOnUiThread { setDarkMode(event.newValue as Boolean) }
            "screenAlwaysOn" -> runOnUiThread { screen.setScreenAlwaysOn(window, event.newValue as Boolean, true) }
            "screenAutoBrightness" -> runOnUiThread { screen.setScreenAutoBrightness(window, event.newValue as Boolean) }
            "screenBrightness" -> runOnUiThread { screen.setScreenBrightness(window, event.newValue as Float) }
            "screenWake" -> runOnUiThread { screenWake() }
            "screenSleep" -> runOnUiThread { screenSleep() }
            "deviceBump" -> runOnUiThread { if (config.screenOnBump) screenWake() }
            "proximity" -> runOnUiThread { if (config.screenOnProximity && event.newValue as Float > 0) screenWake() }
            "motion" -> runOnUiThread { onMotion() }
            "showToastMessage" -> runOnUiThread { Toast.makeText(this, event.newValue as String, Toast.LENGTH_SHORT).show() }
            else -> consumed = false
        }
        if (consumed) {
            log.d("MainActivity - Event: ${event.eventName} - ${event.newValue}")
        }
    }

    @kotlin.OptIn(ExperimentalTime::class)
    fun onMotion() {
        config.lastMotion = now().epochSeconds
        if (config.screenOnMotion) screenWake()
    }

    fun screenWake(blackOut: Boolean = false) {
        if (!blackOut) viewModel.setScreenOn(true)
        screen.setScreenAlwaysOn(window, config.screenAlwaysOn, false)
        screen.setScreenAutoBrightness(window, config.screenAutoBrightness)
        screen.setScreenBrightness(window, config.screenBrightness)
        screen.wakeScreen()
        config.screenOn = true
        if (tempScreenTimeout != 0 && screen.canWriteScreenSetting()) {
            screen.setScreenTimeout(screenTimeout)
            tempScreenTimeout = 0
        }
    }

    fun screenSleep() {
        viewModel.setScreenOn(false)
        screen.setScreenAlwaysOn(window, false)
        screen.setScreenAutoBrightness(window, false)
        screen.setScreenBrightness(window, 0.01f)
        screen.setPartialWakeLock()
        if (screen.canWriteScreenSetting()) {
            if (tempScreenTimeout == 0) {
                screenTimeout = screen.getScreenTimeout()
                tempScreenTimeout = 1000
            }
            screen.setScreenTimeout(tempScreenTimeout)
            lifecycleScope.launch {
                resetParamsOnScreenOff()
            }
        } else {
            config.screenOn = false
        }
    }

    suspend fun resetParamsOnScreenOff() {
        try {
            delay(1000)
            withTimeout(15000) {
                while (!screen.isScreenOff()) {
                    delay(500)
                }
            }
        } catch (ex: Exception) {
            log.w("Timed out waiting for screen off")
        }
        config.screenOn = false
        screen.setScreenTimeout(screenTimeout)
        tempScreenTimeout = 0
        if (screen.isScreenOff()) {
            log.d("Screen off")
            viewModel.setScreenOn(true)
            screen.setScreenAutoBrightness(window, config.screenAutoBrightness)
            screen.setScreenBrightness(window, config.screenBrightness)
        }
    }




    fun setDarkMode(isDark: Boolean) {
        log.d("Setting dark mode: $isDark")

        if (isDark) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else{
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }

        // Set device dark mode
        val uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            uiModeManager.setApplicationNightMode(if (isDark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO)
        } else {
            uiModeManager.nightMode = if (isDark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
        }

        webView.refreshDarkMode()
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

        if (ContextCompat.checkSelfPermission(this, permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions += permission.RECORD_AUDIO
            requestID += RECORD_AUDIO_PERMISSIONS_REQUEST
        } else {
            config.hasRecordAudioPermission = true
        }

        if (DeviceCapabilitiesManager(this).hasFrontCamera()) {
            if (ContextCompat.checkSelfPermission(this, permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions += permission.CAMERA
                requestID += CAMERA_PERMISSIONS_REQUEST
            } else {
                config.hasCameraPermission = true
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions += permission.POST_NOTIFICATIONS
                requestID += NOTIFICATION_PERMISSIONS_REQUEST
            } else {
                config.hasPostNotificationPermission = true
            }
        }
        /*
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions += permission.WRITE_EXTERNAL_STORAGE
                requestID += WRITE_EXTERNAL_STORAGE_PERMISSIONS_REQUEST
            } else {
                config.hasWriteExternalStoragePermission = true
            }
        }
        */

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
                if (permissions[i] == permission.CAMERA && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
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

    private val onWriteSettingsPermissionActivityResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        checkAndRequestNotificationAccessPolicyPermission()
    }

    private fun checkAndRequestWriteSettingsPermission() {
        if (config.canSetScreenWritePermission && !ScreenUtils(this).canWriteScreenSetting()) {
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
        log.i("Notification access policy permission result -> ${it.resultCode}")
        if (it.resultCode == RESULT_CANCELED) {
            config.canSetNotificationPolicyAccess = false
        }
        initialise()
    }

    private fun checkAndRequestNotificationAccessPolicyPermission() {
        val notificationManager =  this.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (config.canSetNotificationPolicyAccess && !notificationManager.isNotificationPolicyAccessGranted) {
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
            log.d("Notification access policy permission already granted or not supported")
            config.hasPostNotificationPermission = true
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

