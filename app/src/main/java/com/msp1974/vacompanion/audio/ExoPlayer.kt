package com.msp1974.vacompanion.audio

import android.content.Context
import android.os.Handler
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber
import kotlin.concurrent.thread
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer


class VAMediaPlayer(val context: Context) {
    private val config: APPConfig = APPConfig.getInstance(context)
    private var currentVolume: Float = config.musicVolume
    private var mediaPlayer: ExoPlayer? = null
    var isVolumeDucked: Boolean = false
    private var lastPlayedUrl: String = ""
    private var lastPlayedVolume: Float = config.musicVolume

    companion object {
        @Volatile
        private var instance: VAMediaPlayer? = null

        fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                instance ?: VAMediaPlayer(context).also { instance = it }
            }
    }

    fun play(url: String) {
        lastPlayedUrl = url
        Handler(context.mainLooper).post({
            try {
                if (mediaPlayer != null && mediaPlayer!!.isPlaying) {
                    mediaPlayer!!.stop()
                }
            } catch (e: IllegalStateException) {
                // Here is media player is stopped
            }

            try {
                mediaPlayer = ExoPlayer.Builder(context).build()
                val mediaItem = MediaItem.fromUri(url.toUri())
                mediaPlayer!!.setMediaItem(mediaItem)
                // Prepare the player.
                mediaPlayer!!.prepare()
                // Start the playback.
                mediaPlayer!!.play()
                Timber.i("Music started")
            } catch (ex: Exception) {
                Timber.e("Error playing music: $ex")
                ex.printStackTrace()
            }
        })
    }

    fun pause() {
        Handler(context.mainLooper).post({
            try {
                mediaPlayer!!.pause()
                Timber.i("Music paused")
            } catch (ex: Exception) {
                Timber.e("Error pausing music: $ex")
            }
        })
    }

    fun resume() {
        Handler(context.mainLooper).post({
            try {
                mediaPlayer!!.play()
                Timber.i("Music resumed")
            } catch (ex: Exception) {
                Timber.e("Error resuming music: $ex")
            }
        })
    }

    fun stop() {
        Handler(context.mainLooper).post({
            try {
                if (mediaPlayer != null) {
                    Timber.d("Stopping music player - isPlaying: ${mediaPlayer!!.isPlaying}")
                    mediaPlayer!!.stop()
                    mediaPlayer!!.release()
                    mediaPlayer = null
                    config.musicPlaying = false
                    Timber.i("Music stopped")
                } else {
                    Timber.w("Cannot stop music - mediaPlayer is null")
                    config.musicPlaying = false
                }
            } catch (e: Exception) {
                Timber.e("Error stopping music: $e")
                config.musicPlaying = false
            }
        })
    }

    fun setVolume(volume: Float) {
        lastPlayedVolume = volume
        Handler(context.mainLooper).post({
            if (!isVolumeDucked && mediaPlayer != null) {
                mediaPlayer!!.volume = volume
            }
            currentVolume = volume
            Timber.i("Music volume set to $volume")
        })
    }

    fun restartPlayback() {
        if (lastPlayedUrl.isNotEmpty()) {
            Timber.i("Restarting music playback: $lastPlayedUrl")
            play(lastPlayedUrl)
            setVolume(lastPlayedVolume)
        } else {
            Timber.w("Cannot restart playback - no URL stored")
        }
    }

    fun duckVolume() {
        Handler(context.mainLooper).post({
            if (!isVolumeDucked && mediaPlayer != null) {
                if (mediaPlayer!!.isPlaying) {
                    val vol = config.duckingVolume
                    if (vol < config.musicVolume) {
                        Timber.d("Ducking music volume from $currentVolume to $vol")
                        mediaPlayer!!.volume = vol
                        isVolumeDucked = true
                    } else {
                        Timber.d("Not ducking music volume as it is lower than ducking volume of ${config.duckingVolume} at ${config.musicVolume}")
                    }
                }
            }
        })
    }

    fun unDuckVolume() {
        if (isVolumeDucked) {
            Timber.i("Restoring music volume to ${currentVolume}")
            thread(name = "volumeUnducking") {
                val steps = 3
                val diffStepVolume = (currentVolume - config.duckingVolume) / steps
                for (i in 1..steps) {
                    val vol = config.duckingVolume + (diffStepVolume * i)
                    Handler(context.mainLooper).post({
                        mediaPlayer!!.volume = vol
                    })
                    if (i < steps) {
                        Thread.sleep(250)
                    }
                }
            }
            isVolumeDucked = false
        }
    }
}
