package com.msp1974.vacompanion.service

import android.app.NotificationManager
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.res.AssetManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.msp1974.vacompanion.wyoming.Zeroconf
import com.msp1974.vacompanion.audio.AudioDSP
import com.msp1974.vacompanion.audio.AudioManager as AudManager
import com.msp1974.vacompanion.audio.WakeWordSoundPlayer
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.sensors.SensorUpdatesCallback
import com.msp1974.vacompanion.sensors.Sensors
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.ui.DiagnosticInfo
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.FirebaseManager
import com.msp1974.vacompanion.utils.Helpers
import com.msp1974.vacompanion.utils.WakeWords
import com.msp1974.vacompanion.wyoming.WyomingCallback
import com.msp1974.vacompanion.wyoming.WyomingTCPServer
import com.msp1974.viewassistcompanionapp.audio.AudioRecorder
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.DetectionMode
import com.rementia.openwakeword.lib.model.WakeWordModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber
import java.util.Date
import kotlin.concurrent.thread

enum class AudioRouteOption { NONE, DETECT, STREAM}

internal class BackgroundTaskController (private val context: Context): EventListener {

    private val firebase = FirebaseManager.getInstance()
    private var config: APPConfig = APPConfig.getInstance(context)

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private var audioInJob: Job? = null
    private var wakeWordJob: Job? = null
    private var wakeWordEngine: WakeWordEngine? = null


    val zeroConf: Zeroconf = Zeroconf(context)

    var audioRoute: AudioRouteOption = AudioRouteOption.NONE
    val audioDSP: AudioDSP = AudioDSP()
    private var sensorRunner: Sensors? = null
    lateinit var assetManager: AssetManager
    lateinit var server: WyomingTCPServer

    

    fun start() {
        assetManager = context.assets

        // Start wyoming server
        server = WyomingTCPServer(context, config.serverPort, object : WyomingCallback {
            override fun onSatelliteStarted() {
                Timber.i("Background Task - Connection detected")
                startSensors(context)
                startOpenWakeWordDetection()
                startInputAudio()
                audioRoute = AudioRouteOption.DETECT
                BroadcastSender.sendBroadcast(context, BroadcastSender.SATELLITE_STARTED)
                zeroConf.unregisterService()
            }

            override fun onSatelliteStopped() {
                Timber.i("Background Task - Disconnection detected")
                BroadcastSender.sendBroadcast(context, BroadcastSender.SATELLITE_STOPPED)
                if (sensorRunner != null) {
                    sensorRunner!!.stop()
                    sensorRunner = null
                }
                audioRoute = AudioRouteOption.NONE
                stopInputAudio()
                stopOpenWakeWordDetection()
                stopSensors()
                zeroConf.registerService(config.serverPort)
            }

            override fun onRequestInputAudioStream() {
                Timber.i("Streaming audio to server")
                if (audioRoute == AudioRouteOption.DETECT) {
                    stopOpenWakeWordDetection()
                    audioRoute = AudioRouteOption.STREAM
                }
            }

            override fun onReleaseInputAudioStream() {
                Timber.i("Stopped streaming audio to server")
                if (audioRoute == AudioRouteOption.STREAM) {
                    scope.launch {
                       startOpenWakeWordDetection()
                    }
                    audioRoute = AudioRouteOption.DETECT
                }
            }
        })
        thread(name="WyomingServer") { server.start() }

        // Add config change listeners
        config.eventBroadcaster.addListener(this)

        // Start mdns server
        zeroConf.registerService(config.serverPort)

        Timber.d("Background task initialisation completed")
    }

    override fun onEventTriggered(event: Event) {
        var consumed = true
        when (event.eventName) {
            "notificationVolume" -> {
                setVolume(AudioManager.STREAM_NOTIFICATION, event.newValue as Float)
            }
            "musicVolume" -> {
                setVolume(AudioManager.STREAM_MUSIC, event.newValue as Float)
            }
            "wakeWord" -> {
                if (audioRoute != AudioRouteOption.NONE) {
                    restartWakeWordDetection()
                }
            }
            "doNotDisturb" -> {
                setDoNotDisturb(event.newValue as Boolean)
            }
            "pairedDeviceID" -> {
                if (config.pairedDeviceID != "") {
                    Timber.d("Device paired, stopping Zeroconf")
                    zeroConf.unregisterService()
                } else {
                    Timber.d("Device unpaired, starting Zeroconf")
                    zeroConf.registerService(config.serverPort)
                }
            }
            "currentPath" -> {
                server.sendStatus(
                    buildJsonObject {
                        putJsonObject("sensors", {
                            put("current_path", event.newValue.toString())
                        })
                    }
                )
            }
            "screenOn" -> {
                val state = event.newValue as Boolean
                server.sendStatus(
                    buildJsonObject {
                        putJsonObject("sensors", {
                            put("screen_on", state)
                        })
                    }
                )
            }
            else -> consumed = false
        }
        if (consumed) {
            Timber.d("BackgroundTask - Event: ${event.eventName} - ${event.newValue}")
        }
    }

    fun startSensors(context: Context) {
        sensorRunner = Sensors(context, object : SensorUpdatesCallback {
            override fun onUpdate(data: MutableMap<String, Any>) {
                val data = buildJsonObject {
                    put("timestamp", Date().toString())
                    putJsonObject("sensors") {
                        data.map { (key, value) ->
                            if (Helpers.isNumber(value.toString())) {
                                put(key, value.toString().toFloat())
                            } else {
                                put(key, value.toString())
                            }
                        }
                    }
                }
                server.sendStatus(data)
            }
        })
    }

    fun stopSensors() {
        sensorRunner?.stop()
    }

    fun startInputAudio() {
        val audioRecorder = AudioRecorder(context)
        if (audioRecorder.hasRecordPermission()) {
            audioInJob = scope.launch {
                audioRecorder.startRecording()
                    .collect { audioBuffer ->
                        when (audioRoute) {
                            AudioRouteOption.DETECT -> {
                                if (wakeWordEngine != null) wakeWordEngine!!.processAudio(audioBuffer)
                            }
                            AudioRouteOption.STREAM -> {
                                val gAudioBuffer = audioDSP.autoGain(audioBuffer, config.micGain)
                                val bAudioBuffer = audioDSP.floatArrayToByteBuffer(gAudioBuffer)
                                if (config.diagnosticsEnabled) {
                                    sendDiagnostics(
                                        gAudioBuffer.max() / 32768f,
                                        0f
                                    )
                                }
                                server.sendAudio(bAudioBuffer)
                            }
                            else -> {}
                        }
                    }
            }
        }
    }

    fun stopInputAudio() {
        //stopWakeWordDetection()
        Timber.i("Stopping input audio")
        audioInJob?.cancel()
        audioInJob = null
    }

    fun sendDiagnostics(audioLevel: Float, detectionLevel: Float) {
        val data = DiagnosticInfo(
            show = config.diagnosticsEnabled,
            audioLevel = audioLevel * 100,
            detectionLevel = detectionLevel * 10,
            detectionThreshold = config.wakeWordThreshold * 10,
            mode = audioRoute
        )
        val event = Event("diagnosticStats", "", data)
        config.eventBroadcaster.notifyEvent(event)
    }

    fun shutdown() {
        Timber.i("Shutting down")
        config.eventBroadcaster.removeListener(this)
        zeroConf.unregisterService()
        stopInputAudio()
        stopOpenWakeWordDetection()
        stopSensors()
        server.stop()

    }

    private fun startOpenWakeWordDetection() {
        val wakeWords = WakeWords(context).getWakeWords()
        if (config.wakeWord in wakeWords.keys) {
            val wakeWordInfo = wakeWords[config.wakeWord]!!

            val models = listOf(
                WakeWordModel(wakeWordInfo.name, wakeWordInfo.fileName, threshold = config.wakeWordThreshold)
            )
            Timber.i("Starting wake word detection with params: ${models}")
            wakeWordEngine = WakeWordEngine(
                context = context,
                models = models,
                detectionMode = DetectionMode.SINGLE_BEST,
                detectionCooldownMs = 1500L,
                scope = CoroutineScope(Dispatchers.Default)
            )
            Timber.i("Wake word detection started")

            wakeWordJob = scope.launch {
                wakeWordEngine?.detections?.collect { detection ->
                    Timber.i("${detection.model.name} detected!")
                    if (config.diagnosticsEnabled) {
                        sendDiagnostics(
                            0f,
                            detection.score
                        )
                    }

                    Timber.i("${detection.model.name} wake word detected at ${detection.score}, theshold is ${config.wakeWordThreshold}")
                    firebase.logEvent(
                        FirebaseManager.WAKE_WORD_DETECTED, mapOf(
                            "wake_word" to config.wakeWord,
                            "threshold" to config.wakeWordThreshold.toString(),
                            "prediction" to detection.score.toString()
                        )
                    )

                    // if wake up on ww, send event
                    if (config.screenOnWakeWord) {
                        config.eventBroadcaster.notifyEvent(Event("screenWake", "", ""))
                    }

                    if (config.wakeWordSound != "none") {
                        WakeWordSoundPlayer(
                            context,
                            context.resources.getIdentifier(
                                config.wakeWordSound,
                                "raw",
                                context.packageName
                            )
                        ).play()
                    }
                    BroadcastSender.sendBroadcast(context, BroadcastSender.WAKE_WORD_DETECTED)
                }
            }
        }
    }

    private fun restartWakeWordDetection() {
        Timber.i("Restarting wake word detection")
        stopOpenWakeWordDetection()
        startOpenWakeWordDetection()
    }


    private fun stopOpenWakeWordDetection() {
        if (wakeWordEngine != null) {
            wakeWordEngine?.stop()
            wakeWordEngine?.release()
            wakeWordEngine = null
        }
        if (wakeWordJob!!.isActive) {
            wakeWordJob?.cancel()
            wakeWordJob = null
        }
        Timber.d("Wake word detection stopped")
    }

    fun setVolume(stream: Int, volume: Float) {
        try {
            val audioManager = AudManager(context)
            audioManager.setVolume(stream, volume)
        } catch (e: Exception) {
            Timber.d("Error setting volume: ${e.message.toString()}")
            firebase.logException(e)
        }
    }

    fun setDoNotDisturb(enable: Boolean) {
        val notificationManager =  context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (notificationManager.isNotificationPolicyAccessGranted) {
            Timber.d("Setting do not disturb to $enable")
            if (enable) {
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            } else {
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        } else {
            Timber.w("Unable to set do not disturb, notification policy access not granted")
            config.eventBroadcaster.notifyEvent(Event("showToastMessage", "", "Unable to set do not disturb.  Permission not granted."))
        }
    }
}