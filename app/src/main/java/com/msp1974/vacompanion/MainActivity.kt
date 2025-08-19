package com.msp1974.vacompanion

import android.Manifest.permission
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.service.VABackgroundService
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.DeviceCapabilitiesManager
import com.msp1974.vacompanion.utils.FirebaseManager
import com.msp1974.vacompanion.utils.Helpers
import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.utils.ScreenUtils
import com.msp1974.vacompanion.utils.Updater
import kotlin.concurrent.thread
import kotlin.reflect.typeOf


class MainActivity : AppCompatActivity() {
    private lateinit var config: APPConfig
    private val log = Logger()
    private val firebase = FirebaseManager.getInstance()

    private lateinit var screen: ScreenUtils
    private lateinit var updater: Updater
    private var screenOrientation: Int = 0
    private var updateProcessComplete: Boolean = true

    @SuppressLint("HardwareIds", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_waiting)
        this.enableEdgeToEdge()

        config = APPConfig.getInstance(this)
        screen = ScreenUtils(this)
        updater = Updater(this)
        screenOrientation = resources.configuration.orientation

        setFirebaseUserProperties()

        if (!isTaskRoot && Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            log.i("MainActivity is not root")
            finish();
            return;
        }
        log.i("#################################################################################################")
        log.i("Starting View Assist Companion App")
        log.i("Version ${config.version}")
        log.i("Android version: ${Helpers.getAndroidVersion()}")
        log.i("Name: ${Helpers.getDeviceName()}")
        log.i("Serial: ${Build.SERIAL}")
        log.i("UUID: ${config.uuid}")
        log.i("#################################################################################################")

        // Show info on screen
        val logo = findViewById<ImageView>(R.id.vaLogo)
        val ip = findViewById<TextView>(R.id.ip)
        val version = findViewById<TextView>(R.id.version)
        val uuid = findViewById<TextView>(R.id.uuid)
        val pairedDevice = findViewById<TextView>(R.id.paired_with)
        val startOnBoot = findViewById<SwitchCompat>(R.id.startOnBoot)

        setScreenLayout()

        version.text = config.version
        ip.text = Helpers.getIpv4HostAddress()
        uuid.text = config.uuid

        if (config.pairedDeviceID != "") {
            pairedDevice.text = config.pairedDeviceID
        }

        // Long press on paired device id to clear pairing
        logo.setOnLongClickListener {
            if (config.pairedDeviceID != "") {
                val alertDialog = AlertDialog.Builder(this)
                alertDialog.apply {
                    setTitle("Clear Paired Device Entry")
                    setMessage("This will delete the currently paired Home Assistant server and allow another server to connect and pair to this device.")
                    setPositiveButton("Confirm") { _: DialogInterface?, _: Int ->
                        config.accessToken = ""
                        config.pairedDeviceID = ""
                        pairedDevice.text = "Not paired"
                    }
                    setNegativeButton("Cancel") { _: DialogInterface?, _: Int -> }
                }.create().show()
            }
            false
        }

        startOnBoot.setChecked(config.startOnBoot)
        startOnBoot.setOnCheckedChangeListener({ _, isChecked ->
            config.startOnBoot = isChecked
        })

        // Check and get required user permissions
        log.d("Checking permissions")
        checkAndRequestPermissions()

    }

    fun setFirebaseUserProperties() {
        firebase.setUserProperty("webview_version", DeviceCapabilitiesManager(this).getWebViewVersion())
        firebase.setUserProperty("device_signature", Helpers.getDeviceName().toString())
    }

    fun initialise() {
        if (!hasPermissions()) {
            findViewById<TextView>(R.id.status_message).text = "No permissions"
            return
        }
        var hasNetwork = Helpers.isNetworkAvailable(this)
        if (!this.hasWindowFocus() || !hasNetwork) {
            if (!hasNetwork) {
                findViewById<TextView>(R.id.status_message).text = "Waiting for network..."
            }
            Handler(Looper.getMainLooper()).postDelayed({
                initialise()
            }, 1000)
            return
        }

        findViewById<TextView>(R.id.ip).text = Helpers.getIpv4HostAddress()

        if (APPConfig.ENABLE_UPDATER && config.hasWriteExternalStoragePermission) {
            updateProcessComplete = false
            findViewById<TextView>(R.id.status_message).text = "Checking for updates..."
            thread(name = "Updater thread") {
                updateHandler()
            }
        }


        // Initiate wake word broadcast receiver
        val satelliteBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BroadcastSender.SATELLITE_STARTED -> {
                        if (config.currentActivity != "WebViewActivity" && screen.isScreenOn()) {
                            runWebViewIntent()
                        }
                    }
                }

            }
        }
        val filter = IntentFilter().apply {
            addAction(BroadcastSender.SATELLITE_STARTED)
            addAction(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(satelliteBroadcastReceiver, filter)

        // Start background tasks
        runBackgroundTasks()
    }

    fun setScreenLayout() {
        val imageView = findViewById<ImageView>(R.id.vaLogo)
        val orientation = resources.configuration.orientation
        val screenHeight: Int = Resources.getSystem().displayMetrics.widthPixels;
        val screenWidth: Int = Resources.getSystem().displayMetrics.heightPixels;

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            imageView.layoutParams.height = (screenHeight * 0.3).toInt()
            imageView.layoutParams.width = screenWidth.coerceAtMost((imageView.layoutParams.height * 1.6).toInt())
        } else {
            imageView.layoutParams.width = (screenHeight * 0.85).toInt()
            imageView.layoutParams.height = (imageView.layoutParams.width * 0.6).toInt()
        }

        log.i("Screen: w$screenWidth h$screenHeight o$orientation, Logo: w${imageView.layoutParams.width} h${imageView.layoutParams.height}")
    }

    // Listening to the orientation config
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation != screenOrientation) {
            setScreenLayout()
        }
    }

    override fun onResume() {
        super.onResume()
        log.d("Main Activity resumed")
        if (screen.isScreenOn() && config.isRunning) {
            log.d("Resuming webView activity")
            firebase.logEvent(FirebaseManager.SATELLITE_ALREADY_RUNNING_MAIN, mapOf())
            runWebViewIntent()
        }
    }

    @SuppressLint("Wakelock")
    override fun onDestroy() {
        log.d("Main Activity destroyed")
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            log.d("Main Activity window focus changed")
            config.currentActivity = "MainActivity"
            // Hide status and action bars
            ScreenUtils(this).hideStatusAndActionBars()
            val pairedWith = findViewById<TextView>(R.id.paired_with)
            if (config.pairedDeviceID != "") {
                pairedWith.text = config.pairedDeviceID
            }
        }
    }

    private fun runBackgroundTasks() {
        if ( config.backgroundTaskRunning ) {
            log.w("Background task already running.  Not starting from MainActivity")
            firebase.logEvent("main_activity_background_task_already_running", mapOf())
            return
        }
        if (!updateProcessComplete) {
            Handler(Looper.getMainLooper()).postDelayed({
                runBackgroundTasks()
            }, 1000)
            return
        }
        log.d("Starting background tasks")
        findViewById<TextView>(R.id.status_message).text = "Waiting for connection..."
        val serviceIntent = Intent(this.applicationContext, VABackgroundService::class.java)
        startService(serviceIntent)
    }

    private fun runWebViewIntent() {
        log.d("Loading WebView activity")
        val webIntent = Intent(this@MainActivity, WebViewActivity::class.java)
        webIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(webIntent)
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
            //initialise()
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

    private fun updateHandler() {
        Looper.prepare()
        val u = updater.isUpdateAvailable()
        if (u) {
            log.d("Update available $u - ${updater.latestRelease.downloadURL}")
            runOnUiThread {
                val updateDialog = AlertDialog.Builder(this)
                updateDialog.apply {
                    setTitle("New VACA App version")
                    setMessage("There is a new version of VACA available.  Click to install.")
                    setPositiveButton("Install") { _: DialogInterface?, _: Int ->
                        log.d("Install requested")
                        findViewById<TextView>(R.id.status_message).text = "Downloading update..."
                        updater.requestDownload { uri ->
                            if (uri != "") {
                                log.d("Download complete = $uri")
                                findViewById<TextView>(R.id.status_message).text =
                                    "Installing update..."
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
                                    //val content = FileProvider.getUriForFile(context, context.applicationContext.packageName + ".provider",
                                    //    File(uri)
                                    //)
                                    //log.d("Content = $content")
                                    intent.setData(uri.toUri())
                                    intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    onUpdateAppActivityResult.launch(intent)
                                } else {
                                    val intent = Intent(Intent.ACTION_VIEW)
                                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    //output file is the apk downloaded earlier
                                    intent.setDataAndType(
                                        uri.toUri(),
                                        "application/vnd.android.package-archive"
                                    );
                                    onUpdateAppActivityResult.launch(intent)
                                }
                            } else {
                                updateDialog.apply {
                                    setTitle("Error downloading update")
                                    setMessage("There was an error downloading the update.  This could be caused by a lack of disk space or an error accessing the internet.")
                                    setPositiveButton("OK") { _: DialogInterface?, _: Int ->
                                        updateProcessComplete = true
                                    }
                                }.create().show()
                            }
                        }
                    }
                    setNegativeButton("Remind me later") { _: DialogInterface?, _: Int ->
                        updateProcessComplete = true
                    }
                }.create().show()
            }
        } else {
            updateProcessComplete = true
        }
    }

    private val onUpdateAppActivityResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updateProcessComplete = true
    }
}

