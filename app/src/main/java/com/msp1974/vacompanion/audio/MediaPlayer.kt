package com.msp1974.vacompanion.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Logger
import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.concurrent.thread

class VAMediaPlayer(val context: Context) {
    private val log = Logger()
    private val config: APPConfig = APPConfig.getInstance(context)

    private var currentVolume: Float = config.musicVolume
    private var mediaPlayer: MediaPlayer = MediaPlayer()
    var isVolumeDucked: Boolean = false

    companion object {
        @Volatile
        private var instance: VAMediaPlayer? = null

        fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                instance ?: VAMediaPlayer(context).also { instance = it }
            }
    }

    fun play(url: String) {
        try {
            if (mediaPlayer.isPlaying) {
                mediaPlayer.stop()
            }
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(url)
                prepare() // might take long! (for buffering, etc)
            }
            mediaPlayer.start()
            log.i("Music started")
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun pause() {
        if (mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            log.i("Music paused")
        }
    }

    fun resume() {
        mediaPlayer.start()
        log.i("Music resumed")
    }

    fun stop() {
        try {
            if (mediaPlayer.isPlaying) {
                mediaPlayer.stop()
                log.i("Music stopped")
            }
        } catch (e: Exception) {
            log.e("Error stopping music: $e")
        }
    }

    fun setVolume(volume: Float) {
        if (!isVolumeDucked) {
            mediaPlayer.setVolume(volume, volume)
        }
        currentVolume = volume
        log.i("Music volume set to $volume")
    }

    fun duckVolume() {
        if (!isVolumeDucked) {
            if (mediaPlayer.isPlaying) {
                val vol = config.duckingVolume
                if (vol < config.musicVolume) {
                    log.d("Ducking music volume from $currentVolume to $vol")
                    mediaPlayer.setVolume(vol, vol)
                    isVolumeDucked = true
                } else {
                    log.d("Not ducking mMusic volume as it is lower than ducking volume of ${config.duckingVolume} at ${config.musicVolume}")
                }
            }
        }
    }

    fun unDuckVolume() {
        if (isVolumeDucked) {
            log.i("Restoring music volume to ${currentVolume}")
            thread(name="volumeUnducking") {
                val steps = 3
                val diffStepVolume = (currentVolume - config.duckingVolume) / steps
                for (i in 1..steps) {
                    val vol = config.duckingVolume + (diffStepVolume * i)
                    mediaPlayer.setVolume(vol, vol)
                    if (i < steps) { Thread.sleep(250) }
                }
            }
            isVolumeDucked = false
        }
    }
}
