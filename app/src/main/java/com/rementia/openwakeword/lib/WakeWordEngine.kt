package com.rementia.openwakeword.lib

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import com.rementia.openwakeword.lib.audio.AudioProcessor
import com.rementia.openwakeword.lib.ml.OnnxModelRunner
import com.rementia.openwakeword.lib.model.WakeWordDetection
import com.rementia.openwakeword.lib.model.WakeWordModel
import com.rementia.openwakeword.lib.model.WakeWordScore
import com.rementia.openwakeword.lib.model.DetectionMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

/**
 * Main entry point for wake word detection using ONNX Runtime.
 *
 * This class manages multiple wake word models and emits detection events through a Kotlin Flow.
 * It provides real-time audio processing with configurable detection modes and cooldown periods.
 */
class WakeWordEngine(
    private val context: Context,
    private val models: List<WakeWordModel>,
    private val detectionMode: DetectionMode = DetectionMode.SINGLE_BEST,
    private val detectionCooldownMs: Long = 2000L,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {

    private val assetManager: AssetManager = context.assets
    private val modelProcessors = mutableMapOf<WakeWordModel, ModelProcessor>()
    private val detectionCooldowns = mutableMapOf<String, Long>()

    var isEnabled = true

    private var _audioProcessor: AudioProcessor = AudioProcessor(assetManager)
    private val _detections = MutableSharedFlow<WakeWordDetection>()
    private val _scores = MutableSharedFlow<WakeWordScore>()

    /**
     * Flow of wake word detection events.
     *
     * This Flow emits [WakeWordDetection] objects whenever a wake word is detected.
     * The Flow is hot and shared, meaning multiple collectors will receive the same events.
     *
     * ## Example: Basic Collection
     * ```kotlin
     * engine.detections.collect { detection ->
     *     showToast("${detection.model.name} detected!")
     * }
     * ```
     *
     * ## Example: Filtering High-Confidence Detections
     * ```kotlin
     * engine.detections
     *     .filter { it.score > 0.8f }
     *     .collect { detection ->
     *         // Only process high-confidence detections
     *     }
     * ```
     *
     * ## Example: Debouncing Rapid Detections
     * ```kotlin
     * engine.detections
     *     .debounce(500) // Additional debounce on top of cooldown
     *     .collect { detection ->
     *         // Process debounced detections
     *     }
     * ```
     */
    val detections: Flow<WakeWordDetection> = _detections.asSharedFlow()

    /**
     * Flow of real-time wake word scores.
     *
     * This Flow emits [WakeWordScore] objects continuously for all models,
     * regardless of whether they exceed the detection threshold.
     * Useful for real-time monitoring and visualization.
     */
    val scores: Flow<WakeWordScore> = _scores.asSharedFlow()

    private var processingJob: Job? = null

    init {
        require(models.isNotEmpty()) { "At least one wake word model must be provided" }
        initializeModels()
    }

    private fun initializeModels() {
        models.forEach { model ->
            val processor = ModelProcessor(assetManager, model)
            modelProcessors[model] = processor
        }
    }

    fun addModel(model: WakeWordModel) {
        /**
        Add model to detections
        */
        Timber.w("Adding model ${model.name} to engine")
        modelProcessors.forEach {(wakeWordModel, processor) ->
            if (wakeWordModel.name == model.name) {
                throw IllegalArgumentException("Model with name ${model.name} already exists")
            }
        }
        modelProcessors[model] = ModelProcessor(assetManager, model)
    }

    fun removeModel(modelName: String) {
        /**
        Remove model from detections
        */
        Timber.w("Removing model $modelName from engine")
        modelProcessors.forEach {(wakeWordModel, processor) ->
            if (wakeWordModel.name == modelName) {
                processor.close()
                modelProcessors.remove(wakeWordModel)
                return
            }
        }
        throw IllegalArgumentException("Model with name $modelName not found")
    }

    @SuppressLint("DefaultLocale")
    fun processAudio(audioBuffer: FloatArray) {
        // Process all models in parallel and collect results
        if (processingJob != null && processingJob!!.isActive) {
            processingJob?.cancel()
        }

        if (isEnabled) {
            processingJob = scope.launch {
                val audioFeatures = _audioProcessor.getAudioFeatures(audioBuffer)
                val detectionResults = modelProcessors.map { (model, processor) ->
                    async {
                        try {
                            withTimeout(80.milliseconds) {
                                val score = processor.process(audioFeatures)

                                // Emit real-time score
                                _scores.emit(WakeWordScore(model, score))

                                if (score > model.threshold) {
                                    Timber.d(
                                        "DETECTION! ${model.name} - Score: ${
                                            String.format("%.5f", score)
                                        } > Threshold: ${String.format("%.5f", model.threshold)}"
                                    )
                                    DetectionResult(
                                        model = model,
                                        score = score,
                                        difference = score - model.threshold,
                                        index = 1 //index
                                    )
                                } else {
                                    null
                                }
                            }
                        } catch (e: TimeoutCancellationException) {
                            Timber.d("Timeout processing model ${model.name} ->$e")
                            null
                        } catch (e: CancellationException) {
                            Timber.d("Job cancelled processing model ${model.name} ->$e")
                            null
                        } catch (e: Exception) {
                            Timber.e("Error processing model ${model.name} ->$e")
                            e.printStackTrace()
                            null
                        }
                    }

                }.awaitAll().filterNotNull()


                // Process results based on detection mode
                when (detectionMode) {
                    DetectionMode.SINGLE_BEST -> {
                        // Select the best detection based on score-threshold difference
                        detectionResults.maxByOrNull { result ->
                            // Primary: difference, Secondary: inverse index (lower index = higher priority)
                            result.difference * 1000 - result.index * 0.001
                        }?.let { result ->
                            emitDetection(result.model, result.score)
                        }
                    }

                    DetectionMode.ALL -> {
                        // Emit all detections that passed threshold
                        detectionResults.forEach { result ->
                            emitDetection(result.model, result.score)
                        }
                    }
                }
            }
        }
    }

    /**
     * Emits a detection event with cooldown check.
     *
     * This internal method handles the cooldown logic to prevent duplicate detections
     * within the configured cooldown period.
     *
     * @param model The wake word model that triggered the detection
     * @param score The confidence score of the detection
     */
    private suspend fun emitDetection(model: WakeWordModel, score: Float) {
        val now = System.currentTimeMillis()
        val lastDetection = detectionCooldowns[model.name]

        if (lastDetection == null || detectionCooldownMs == 0L || now - lastDetection >= detectionCooldownMs) {
            Timber.d("Emitting detection for ${model.name} with score ${String.format("%.5f", score)}")
            _detections.emit(
                WakeWordDetection(
                    model = model,
                    score = score
                )
            )
            detectionCooldowns[model.name] = now
        } else {
            Timber.d("Detection skipped due to cooldown: ${model.name}")
        }
    }

    fun enable() {
        isEnabled = true
    }

    fun disable() {
        isEnabled = false
    }

    fun reset() {
        _audioProcessor.reset()
    }

    /**
     * Stops wake word detection.
     *
     * This method stops audio recording and cancels all ongoing detection processing.
     * The engine can be restarted by calling [start] again.
     *
     * ## Example
     * ```kotlin
     * override fun onPause() {
     *     super.onPause()
     *     engine.stop() // Stop detection when app goes to background
     * }
     * ```
     *
     * @see start
     */
    fun stop() {
        processingJob?.cancel()
        processingJob = null
    }

    /**
     * Releases all resources used by the engine.
     *
     * This method should be called when the engine is no longer needed to free up memory
     * and system resources. After calling this method, the engine cannot be reused.
     *
     * ## Important
     * Always call this method in your Activity/Fragment's onDestroy() to prevent memory leaks.
     *
     * ## Example
     * ```kotlin
     * override fun onDestroy() {
     *     super.onDestroy()
     *     wakeWordEngine?.release()
     * }
     * ```
     *
     * This method will:
     * - Stop any ongoing detection
     * - Release ONNX Runtime sessions
     * - Free audio processing resources
     * - Clear internal caches
     */
    fun release() {
        stop()
        modelProcessors.values.forEach { it.close() }
        modelProcessors.clear()
    }

    /**
     * Internal data class for detection results with metadata.
     */
    private data class DetectionResult(
        val model: WakeWordModel,
        val score: Float,
        val difference: Float,
        val index: Int
    )

    /**
     * Internal class to process audio for a specific model.
     */
    private inner class ModelProcessor(
        assetManager: AssetManager,
        model: WakeWordModel
    ) : AutoCloseable {

        private val modelRunner = OnnxModelRunner(assetManager, model)

        fun process(audioFeatures: Array<Array<FloatArray>>): Float {
            val score = modelRunner.predictWakeWord(audioFeatures)
            return score
        }

        override fun close() {
            modelRunner.close()
        }
    }
}