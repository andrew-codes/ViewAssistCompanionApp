package com.msp1974.vacompanion.service

import android.app.NotificationManager
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.res.AssetManager
import android.media.AudioManager
import com.msp1974.vacompanion.Zeroconf
import com.msp1974.vacompanion.audio.AudioDSP
import com.msp1974.vacompanion.audio.AudioInCallback
import com.msp1974.vacompanion.audio.AudioRecorder
import com.msp1974.vacompanion.audio.AudioManager as AudManager
import com.msp1974.vacompanion.audio.WakeWordSoundPlayer
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.openwakeword.Model
import com.msp1974.vacompanion.openwakeword.ONNXModelRunner
import com.msp1974.vacompanion.sensors.SensorUpdatesCallback
import com.msp1974.vacompanion.sensors.Sensors
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.ui.DiagnosticInfo
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.FirebaseManager
import com.msp1974.vacompanion.utils.Helpers
import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.utils.ScreenUtils
import com.msp1974.vacompanion.wyoming.WyomingCallback
import com.msp1974.vacompanion.wyoming.WyomingTCPServer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Date
import kotlin.concurrent.thread


enum class AudioRouteOption { NONE, DETECT, STREAM}

internal class BackgroundTaskController (private val context: Context): EventListener {

    private val log = Logger()
    private val firebase = FirebaseManager.getInstance()
    private var config: APPConfig = APPConfig.getInstance(context)

    val zeroConf: Zeroconf = Zeroconf(context)

    var modelRunner: ONNXModelRunner? = null
    var model: Model? = null
    var audioRoute: AudioRouteOption = AudioRouteOption.NONE
    var recorder: AudioRecorder? = null
    val audioDSP: AudioDSP = AudioDSP()
    private var sensorRunner: Sensors? = null
    lateinit var assetManager: AssetManager
    lateinit var server: WyomingTCPServer

    var debounce: Int = 0

    object Constants {
        const val DEBOUNCE_COUNTER = 20
    }


    fun start() {
        assetManager = context.assets

        // Start wyoming server
        server = WyomingTCPServer(context, config.serverPort, object : WyomingCallback {
            override fun onSatelliteStarted() {
                log.i("Background Task - Connection detected")
                startSensors(context)
                startOpenWakeWordDetection()
                startInputAudio(context)
                audioRoute = AudioRouteOption.DETECT
                BroadcastSender.sendBroadcast(context, BroadcastSender.SATELLITE_STARTED)
                zeroConf.unregisterService()
            }

            override fun onSatelliteStopped() {
                log.i("Background Task - Disconnection detected")
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
                log.i("Streaming audio to server")
                if (audioRoute == AudioRouteOption.DETECT) {
                    audioRoute = AudioRouteOption.STREAM
                }
            }

            override fun onReleaseInputAudioStream() {
                log.i("Stopped streaming audio to server")
                if (audioRoute == AudioRouteOption.STREAM) {
                    audioRoute = AudioRouteOption.DETECT
                }
            }
        })
        thread(name="WyomingServer") { server.start() }

        // Add config change listeners
        config.eventBroadcaster.addListener(this)

        // Start mdns server
        zeroConf.registerService(config.serverPort)

        log.d("Background task initialisation completed")
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
                    log.d("Device paired, stopping Zeroconf")
                    zeroConf.unregisterService()
                } else {
                    log.d("Device unpaired, starting Zeroconf")
                    zeroConf.registerService(config.serverPort)
                }
            }
            else -> consumed = false
        }
        if (consumed) {
            log.d("BackgroundTask - Event: ${event.eventName} - ${event.newValue}")
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

    fun startInputAudio(context: Context) {
        try {
            log.i("Starting input audio")
            recorder = AudioRecorder(context, object : AudioInCallback {
                override fun onAudio(audioBuffer: ShortArray) {
                    when (audioRoute) {
                        AudioRouteOption.DETECT -> {
                            val floatBuffer = audioDSP.normaliseAudioBuffer(audioBuffer)
                            processAudioToWakeWordEngine(context, floatBuffer)
                        }
                        AudioRouteOption.STREAM -> {
                            val gAudioBuffer = audioDSP.autoGain(audioBuffer, config.micGain)
                            val bAudioBuffer = audioDSP.shortArrayToByteBuffer(gAudioBuffer)
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

                override fun onError(err: String) {
                }
            })
            thread(name="AudioInput") {recorder?.start()}
        } catch (e: Exception) {
            log.d("Error starting mic audio: ${e.message.toString()}")
            firebase.logException(e)
        }
    }

    fun stopInputAudio() {
        try {
            log.i("Stopping input audio")
            recorder?.stopRecording()
            recorder = null
        } catch (e: Exception) {
            log.d("Error stopping input audio: ${e.message.toString()}")
            firebase.logException(e)
        }
    }

    fun processAudioToWakeWordEngine(context: Context, audioBuffer: FloatArray) {
        try {
            if (model != null) {
                val res = debouncedDetection(model!!.predict_WakeWord(audioBuffer).toFloat())

                if (config.diagnosticsEnabled) {
                    sendDiagnostics(
                        audioBuffer.maxOrNull() ?: 0f,
                        res
                    )
                }

                if (res >= config.wakeWordThreshold) {
                    log.i("Wake word detected at $res, theshold is ${config.wakeWordThreshold}")
                    firebase.logEvent(
                        FirebaseManager.WAKE_WORD_DETECTED, mapOf(
                            "wake_word" to config.wakeWord,
                            "threshold" to config.wakeWordThreshold.toString(),
                            "prediction" to res.toString()
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
        } catch (e: Exception) {
            log.d("Error processing to wake word engine: ${e.message.toString()}")
            firebase.logException(e)
        }
    }

    fun debouncedDetection(prediction: Float) : Float {
        if (debounce > 0) {
            --debounce
            return 0f
        } else if (prediction >= config.wakeWordThreshold) {
            debounce = Constants.DEBOUNCE_COUNTER
        }
        return prediction
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
        zeroConf.unregisterService()
        stopInputAudio()
        stopOpenWakeWordDetection()
        stopSensors()
        server.stop()

    }

    fun startOpenWakeWordDetection() {
        // Init wake word detection
        log.i("Starting wake word detection")
        try {
            modelRunner = ONNXModelRunner(context, assetManager, config.wakeWord)
            model = Model(context, modelRunner)
        } catch (e: Exception) {
            log.d("Error starting wake word detection: ${e.message.toString()}")
            firebase.logException(e)
        }
    }

    fun stopOpenWakeWordDetection() {
        log.i("Stopping wake word detection")
        model = null
    }

    fun restartWakeWordDetection() {
        if (config.initSettings) {
            log.i("Restarting wake word detection")
            stopInputAudio()
            stopOpenWakeWordDetection()
            startOpenWakeWordDetection()
            startInputAudio(context)
        }
    }

    fun setVolume(stream: Int, volume: Float) {
        try {
            val audioManager = AudManager(context)
            audioManager.setVolume(stream, volume)
        } catch (e: Exception) {
            log.d("Error setting volume: ${e.message.toString()}")
            firebase.logException(e)
        }
    }

    fun setDoNotDisturb(enable: Boolean) {
        log.d("Setting do not disturb to $enable")
        val notificationManager =  context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (notificationManager.isNotificationPolicyAccessGranted) {
            if (enable) {
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            } else {
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        }
    }
}