package com.amar.vault

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * NativeLlamaEngine — Kotlin singleton wrapping the llama.cpp C++ inference engine.
 *
 *
 * Thread safety:
 * Uses a Kotlin Mutex to prevent overlapping generation/load requests securely.
 * Replaces hard JVM `@Synchronized` on suspend functions.
 */
object NativeLlamaEngine {
    private const val TAG = "NativeLlamaEngine"

    private var nativeAvailable = false

    init {
        try {
            System.loadLibrary("amar_llama_engine")
            nativeAvailable = true
            Log.i(TAG, "Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library: ${e.message}")
            nativeAvailable = false
        }
    }

    enum class FailureReason { NONE, TIMEOUT, OOM, LOAD_FAILED, JNI_FAILURE, CANCELLED }

    data class RuntimeMetrics(
        val ttftMs: Long,
        val totalTimeMs: Long,
        val tokensGenerated: Int,
        val tps: Float,
        val loadTimeMs: Long
    )

    data class GenerationResult(
        val text: String,
        val failureReason: FailureReason,
        val metrics: RuntimeMetrics? = null
    )

    @Volatile
    private var isLoaded = false
    private val mutex = Mutex()
    
    // Store load time globally since loading and generation are separate steps
    @Volatile
    private var lastLoadTimeMs = 0L

    @Volatile
    private var enginePermanentlyDead = false // Protects against zombie native threads

    // =========================================================================
    // Native JNI bindings
    // =========================================================================

    private external fun loadModelNative(modelPath: String): Boolean
    private external fun generateTextNative(prompt: String, callback: TokenCallback)
    private external fun unloadModelNative()
    private external fun cancelGenerationNative()
    private external fun isModelLoadedNative(): Boolean

    // =========================================================================
    // Token Callback Interface
    // =========================================================================

    interface TokenCallback {
        fun onToken(token: String)
        fun onComplete()
    }

    // =========================================================================
    // Public API
    // =========================================================================

    // =========================================================================
    // Public API
    // =========================================================================

    suspend fun loadModel(modelPath: String): Boolean = mutex.withLock {
        if (!nativeAvailable) {
            Log.e(TAG, "Cannot load model: native library not available")
            return false
        }

        if (isLoaded) {
            Log.w(TAG, "Model already loaded. Unloading previous model first.")
            unloadInternal()
        }

        Log.i(TAG, "Loading model: $modelPath")
        val startTime = System.currentTimeMillis()
        val success = try {
            loadModelNative(modelPath)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during native load")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Exception during native load: ${e.message}")
            false
        }

        if (success) {
            isLoaded = true
            lastLoadTimeMs = System.currentTimeMillis() - startTime
            Log.i(TAG, "Model loaded successfully in ${lastLoadTimeMs}ms")
        } else {
            Log.e(TAG, "Failed to load model: $modelPath")
            lastLoadTimeMs = 0L
        }

        return success
    }

    /**
     * Generate text and collect the full response as a single string.
     * Uses strict thread-joining to prevent JNI Mutex deadlocks.
     */
    suspend fun generateBlocking(
        prompt: String,
        timeoutSeconds: Long = 30
    ): GenerationResult = mutex.withLock {
        if (enginePermanentlyDead) return GenerationResult("", FailureReason.JNI_FAILURE)

        return withContext(Dispatchers.IO) {
            if (!nativeAvailable || !isLoaded) return@withContext GenerationResult("", FailureReason.LOAD_FAILED)

            Log.i(TAG, "generateBlocking: Lock acquired.")
            
            val builder = StringBuilder()
            val latch = CountDownLatch(1)

            val startTime = System.currentTimeMillis()
            var ttftTime = -1L
            var tokens = 0

            val callback = object : TokenCallback {
                override fun onToken(token: String) {
                    if (tokens == 0) {
                        ttftTime = System.currentTimeMillis()
                    }
                    tokens++
                    builder.append(token)
                }
                override fun onComplete() {
                    Log.i(TAG, "generateBlocking: JNI Complete.")
                    latch.countDown()
                }
            }

            var nativeException: Exception? = null
            var oomError = false

            val generationThread = Thread {
                try {
                    generateTextNative(prompt, callback)
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "Native generate OOM")
                    oomError = true
                    latch.countDown()
                } catch (e: Exception) {
                    Log.e(TAG, "Native generate error: ${e.message}")
                    nativeException = e
                    latch.countDown()
                }
            }
            generationThread.start()

            var timedOut = false
            try {
                // runInterruptible ensures that if the parent coroutine is cancelled,
                // latch.await will throw InterruptedException and we will immediately enter 'finally'.
                val finished = runInterruptible {
                    latch.await(timeoutSeconds, TimeUnit.SECONDS)
                }
                if (!finished) timedOut = true
            } finally {
                // This block runs if we complete, time out, OR are cancelled by the UI.
                if (generationThread.isAlive) {
                    Log.w(TAG, "Cancelling JNI execution...")
                    cancelGenerationNative()
                    // Block the coroutine (and hold the Mutex) until the thread exits or we give up.
                    generationThread.join(2000)
                    
                    if (generationThread.isAlive) {
                        Log.e(TAG, "FATAL: Native thread refused to terminate. Engine permanently locked.")
                        enginePermanentlyDead = true
                    }
                }
            }

            if (enginePermanentlyDead) {
                return@withContext GenerationResult("", FailureReason.JNI_FAILURE)
            }

            if (timedOut) {
                return@withContext GenerationResult("", FailureReason.TIMEOUT)
            }

            if (oomError) {
                return@withContext GenerationResult("", FailureReason.OOM)
            }

            if (nativeException != null) {
                return@withContext GenerationResult("", FailureReason.JNI_FAILURE)
            }

            val endTime = System.currentTimeMillis()
            val totalTime = endTime - startTime
            val ttft = if (ttftTime > 0) ttftTime - startTime else totalTime
            
            // P1 Correct TPS Calculation: GenerationDuration = TotalTime - TTFT
            val genDurationMs = if (ttftTime > 0) endTime - ttftTime else 0L
            val tps = if (genDurationMs > 0 && tokens > 0) {
                tokens / (genDurationMs / 1000f)
            } else 0f

            val metrics = RuntimeMetrics(
                ttftMs = ttft,
                totalTimeMs = totalTime,
                tokensGenerated = tokens,
                tps = tps,
                loadTimeMs = lastLoadTimeMs
            )

            GenerationResult(builder.toString().trim(), FailureReason.NONE, metrics)
        }
    }

    suspend fun unload() = mutex.withLock {
        unloadInternal()
    }

    fun isLoaded(): Boolean = nativeAvailable && isLoaded && isModelLoadedNative()

    fun cancelGeneration() {
        cancelGenerationNative()
    }

    private fun unloadInternal() {
        if (isLoaded) {
            Log.i(TAG, "Unloading model from memory")
            unloadModelNative()
            isLoaded = false
            Log.i(TAG, "Model unloaded. RAM freed.")
        }
    }
}