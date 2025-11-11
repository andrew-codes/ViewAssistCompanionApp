package com.msp1974.vacompanion.utils

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.firebase.Firebase
import com.google.firebase.crashlytics.crashlytics
import com.msp1974.vacompanion.settings.APPConfig


class ScreenUtils(val context: Context) : ContextWrapper(context) {
    var log = Logger()
    var config = APPConfig.getInstance(context)
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenTimeout: Int = 30000

    fun setScreenBrightness(
        screenBrightnessValue: Int
    ) {   // Change the screen brightness change mode to manual.
        try {
            if (canWriteScreenSetting()) {
                // Apply the screen brightness value to the system, this will change
                // the value in Settings ---> Display ---> Brightness level.
                // It will also change the screen brightness for the device.
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    screenBrightnessValue
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setDeviceBrightnessMode(automatic: Boolean = false) {
        if (!canWriteScreenSetting()) {
            return
        }
        var mode = -1
        try {
            mode = Settings.System.getInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE
            ) //this will return integer (0 or 1)
        } catch (e: Settings.SettingNotFoundException) {
            log.e("No screen brightness mode setting available")
        }
        try {
            if (automatic) {
                if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                    //reset back to automatic mode
                    Settings.System.putInt(
                        contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                    )
                }
            } else {
                if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                    //Automatic mode, need to be in manual to change brightness
                    Settings.System.putInt(
                        contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                    )
                }
            }
        } catch (e: SecurityException) {
            log.e("Error setting screen brightness mode: $e")
            Firebase.crashlytics.recordException(e)
        }
    }

    fun wakeScreen() {
        log.d("Acquiring screen on wake lock")
        if (wakeLock != null && wakeLock!!.isHeld) {
            wakeLock!!.release()
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "vacompanion.ScreenUtils:wakeLock"
        )
        wakeLock?.acquire(1000)
    }

    fun setPartialWakeLock() {
        if (wakeLock != null && wakeLock!!.isHeld) {
            wakeLock!!.release()
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "vacompanion.ScreenUtils:partialWakeLock"
        )
        wakeLock?.acquire()
    }

    fun canWriteScreenSetting(): Boolean {
        return Settings.System.canWrite(applicationContext)
    }

    fun isScreenOn(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }

    fun getScreenTimeout(): Int {
        return Settings.System.getString(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT).toInt()
    }

    fun setScreenTimeout(timeout: Int) {
        if (canWriteScreenSetting()) {
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, timeout)
        }
    }

    fun hideSystemUI(window: Window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).let { controller ->
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            val decorView: View = window.decorView
            decorView.setSystemUiVisibility(
                (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN)
            )
        }
    }
}