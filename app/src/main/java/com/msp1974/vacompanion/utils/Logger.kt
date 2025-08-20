package com.msp1974.vacompanion.utils

import android.os.Bundle
import android.util.Log
import androidx.core.os.bundleOf
import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics

class Logger {
    companion object {
        const val TAG = "ViewAssistCA"
    }
    fun d(message: String) {
        Log.d(TAG, message)
    }
    fun e(message: String) {
        Log.e(TAG, message)
    }
    fun i(message: String) {
        Log.i(TAG, message)
    }
    fun w(message: String) {
        Log.w(TAG, message)
    }
}

class FirebaseManager {
    private val firebaseAnalytics = Firebase.analytics
    private val firebaseCrashlytics = Firebase.crashlytics

    companion object {
        @Volatile
        private var instance: FirebaseManager? = null

        fun getInstance() =
            instance ?: synchronized(this) {
                instance ?: FirebaseManager().also { instance = it }
            }

        const val DIAGNOSTIC_POPUP_SHOWN = "diagnostic_popup_shown"
        const val WAKE_WORD_DETECTED = "wake_word_detected"
        const val SATELLITE_ALREADY_RUNNING_MAIN = "satellite_already_running_main"
        const val RENDER_PROCESS_GONE = "render_process_gone"
        const val MAIN_ACTIVITY_BACKGROUND_TASK_ALREADY_RUNNING = "main_background_task_already_running"
    }

    fun Map<String, Any?>.toBundle(): Bundle = bundleOf(*this.toList().toTypedArray())

    fun setCustomKeys(keys: Map<String, Any>) {
        keys.map {
            firebaseCrashlytics.setCustomKey(it.key, it.value.toString())
        }
    }

    fun logEvent(event: String, params: Map<String, String>) {
        firebaseAnalytics.logEvent(event, params.toBundle())
    }

    fun setUserProperty(key: String, value: String) {
        firebaseAnalytics.setUserProperty(key, value)
    }

    fun addToCrashLog(message: String) {
        firebaseCrashlytics.log(message)
    }

    fun logException(exception: Exception) {
        firebaseCrashlytics.recordException(exception)
    }
}