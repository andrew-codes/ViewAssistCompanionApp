package com.msp1974.vacompanion.ui

import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.lifecycle.ViewModel
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.service.AudioRouteOption
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.Helpers
import com.msp1974.vacompanion.utils.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs


data class State(
    val statusMessage: String = "",
    var orientation: Int = Configuration.ORIENTATION_LANDSCAPE,

    var launchOnBoot: Boolean = true,
    var satelliteRunning: Boolean = false,
    var swipeRefreshEnabled: Boolean = false,
    var isDND: Boolean = false,

    var appInfo: Map<String, String> = mapOf(),
    var diagnosticInfo: DiagnosticInfo = DiagnosticInfo(),

    var showAlertDialog: Boolean = false,
    var alertDialog: VADialog? = null,
    var updates: UpdateStatus = UpdateStatus(),

)

class VAViewModel: ViewModel(), EventListener {
    private val _vacaState = MutableStateFlow(State())
    private val log = Logger()
    val vacaState: StateFlow<State> = _vacaState.asStateFlow()
    var config: APPConfig? = null
    var resources: Resources? = null
    var maxDetectionLevel: Float = 0f
    var holdIterations: Int = 0

    init {
        _vacaState.value = State()
    }

    fun bind(config: APPConfig, resources: Resources) {
        this.config = config
        this.resources = resources
        this.config?.eventBroadcaster?.addListener(this)

        initValues()
        buildAppInfo()
    }

    fun initValues() {
        _vacaState.update { currentState ->
            currentState.copy(
                launchOnBoot = config!!.startOnBoot,
                swipeRefreshEnabled = config!!.swipeRefresh
            )
        }
    }

    var launchOnBoot: Boolean
        get() = config!!.startOnBoot
        set(value) {
            _vacaState.update { currentState ->
                currentState.copy(
                    launchOnBoot = value
                )
            }
            config!!.startOnBoot = value
        }

    override fun onEventTriggered(event: Event) {
        var consumed = true
        when (event.eventName) {
            "pairedDeviceID" -> buildAppInfo()
            "swipeRefresh" -> {
                _vacaState.update { currentState ->
                    currentState.copy(
                        swipeRefreshEnabled = event.newValue as Boolean
                    )
                }
            }
            "doNotDisturb" -> {
                _vacaState.update { currentState ->
                    currentState.copy(
                        isDND = event.newValue as Boolean
                    )
                }
            }
            "diagnosticsEnabled" -> {
                _vacaState.update { currentState ->
                    currentState.copy(
                        diagnosticInfo = _vacaState.value.diagnosticInfo.copy(
                            show = event.newValue as Boolean
                        )
                    )
                }
            }
            "diagnosticStats" -> {
                val data = event.newValue as DiagnosticInfo
                consumed = false  //Do not log event as very numerous

                if (holdIterations > 40) {
                    maxDetectionLevel = data.detectionLevel
                    holdIterations = 0
                }
                if (data.detectionLevel > maxDetectionLevel) {
                    maxDetectionLevel = data.detectionLevel
                    holdIterations = 0
                } else {
                    ++holdIterations
                }

                _vacaState.update { currentState ->
                    currentState.copy(
                        diagnosticInfo = data.copy(
                            detectionLevel = maxDetectionLevel
                        )
                    )
                }
            }
            else -> consumed = false
        }
        if (consumed) {
            log.d("ViewModel - Event: ${event.eventName} - ${event.newValue}")
        }
    }

    fun showUpdateDialog(alertDialog: VADialog) {
        val alert = VADialog(
            title = alertDialog.title,
            message = alertDialog.message,
            confirmText = alertDialog.confirmText,
            dismissText = alertDialog.dismissText,
            confirmCallback = {
                _vacaState.update { currentState ->
                    currentState.copy(
                        alertDialog = null
                    )
                }
                alertDialog.confirmCallback()
            },
            dismissCallback = {
                _vacaState.update { currentState ->
                    currentState.copy(
                        alertDialog = null
                    )
                }
                alertDialog.dismissCallback()
            },
        )

        _vacaState.update { currentState ->
            currentState.copy(
                alertDialog = alert,
            )
        }
    }

    fun setSatelliteRunning(isRunning: Boolean) {
        _vacaState.update { currentState ->
            currentState.copy(
                satelliteRunning = isRunning
            )
        }
    }

    fun setStatusMessage(statusMessage: String) {
        _vacaState.update { currentState ->
            currentState.copy(
                statusMessage = statusMessage
            )
        }
    }

    private fun buildAppInfo() {
       _vacaState.update { currentState ->
            currentState.copy(
                appInfo = mapOf(
                    "Version" to config!!.version,
                    "IP Address" to Helpers.getIpv4HostAddress(),
                    "Port" to APPConfig.SERVER_PORT.toString(),
                    "UUID" to config!!.uuid,
                    "Paired to" to config!!.pairedDeviceID,
                )
           )
       }
    }

    fun checkForUpdate() {
        BroadcastSender.sendBroadcast(config!!.context, BroadcastSender.VERSION_MISMATCH)
    }

    fun clearPairedDevice() {
        val d = VADialog(
            title = "Clear Paired Device Entry",
            message = "This will delete the currently paired Home Assistant server and allow another server to connect and pair to this device.",
            confirmText = "Confirm",
            dismissText = "Cancel",
            confirmCallback = {
                config!!.pairedDeviceID = ""
            },
            dismissCallback = {}
        )
        showUpdateDialog(d)
    }


}

class VADialog(
    val title: String = "AlertDialog",
    val message: String = "Message",
    val confirmText: String = "Yes",
    val dismissText: String = "No",
    val confirmCallback: () -> Unit,
    val dismissCallback: () -> Unit
) {
    fun onConfirm() {
        confirmCallback()
    }

    fun onDismiss() {
        dismissCallback()
    }
}

data class UpdateStatus(
    var updateAvailable: Boolean = false,
    var availableVersion: String = "0.0.0"
)

data class DiagnosticInfo(
    var show: Boolean = false,
    var audioLevel: Float = 0f,
    var detectionThreshold: Float = 0f,
    var detectionLevel: Float = 0f,
    var mode: AudioRouteOption = AudioRouteOption.NONE,
    var vadDetection: Boolean = false
)