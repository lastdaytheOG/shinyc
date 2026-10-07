package com.amar.vault

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import kotlin.math.sqrt

class EmbeddingEngine(context: Context) {

    companion object {
        // Single source of truth in VaultConfig — the vector store references the same
        // constant, so the embedder and HNSW index can never disagree on dimension.
        const val EMBEDDING_DIM = VaultConfig.Embedding.DIM
        const val MAX_SEQ_LEN   = VaultConfig.Embedding.MAX_SEQ_LEN
        private const val MODEL_FILE  = "bge-m3-ocr-int4.onnx"
        private const val MODEL_CACHE = "bge_m3_cached.onnx"

        fun warmUp(context: Context) {
            copyModelToStorage(context)
        }

        /**
         * Whether a model can be loaded at all: already in app storage, or bundled in the
         * APK's assets. A build may ship without the model (it is ~570 MB); every caller
         * must treat the embedder as optional and leave keyword search working.
         */
        fun isModelPresent(context: Context): Boolean {
            if (File(context.filesDir, MODEL_CACHE).exists()) return true
            // Assets cannot change while the app runs, so the listing is read once.
            return bundledInAssets
                ?: (context.assets.list("")?.contains(MODEL_FILE) == true).also { bundledInAssets = it }
        }

        @Volatile
        private var bundledInAssets: Boolean? = null

        private fun copyModelToStorage(context: Context): File {
            val cacheFile = File(context.filesDir, MODEL_CACHE)
            if (!cacheFile.exists()) {
                // Copy to a temp name and rename: a process killed mid-copy must not leave a
                // truncated file that "exists" and then fails to load on every later launch.
                val partial = File(context.filesDir, "$MODEL_CACHE.part")
                context.assets.open(MODEL_FILE).use { input ->
                    partial.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 8 * 1024 * 1024)
                    }
                }
                if (!partial.renameTo(cacheFile)) {
                    partial.delete()
                    throw java.io.IOException("could not move embedding model into place")
                }
            }
            return cacheFile
        }
    }

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val tokenizer: SimpleTokenizer

    // Whether the exported graph declares a token_type_ids input — decided once at load.
    private val feedsTokenTypeIds: Boolean

    // ELITE FIX: Protects native zero-copy memory from concurrent Search vs Indexing writes
    private val embedMutex = Mutex()

    // Pre-allocated native C++ memory — reused for every inference call.
    // Zero JVM heap allocations per embed() call.
    private val directInputBuffer: LongBuffer = ByteBuffer
        .allocateDirect(MAX_SEQ_LEN * Long.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asLongBuffer()

    private val directMaskBuffer: LongBuffer = ByteBuffer
        .allocateDirect(MAX_SEQ_LEN * Long.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asLongBuffer()

    private val directTokenTypeBuffer: LongBuffer = ByteBuffer
        .allocateDirect(MAX_SEQ_LEN * Long.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asLongBuffer()

    init {
        val modelFile = copyModelToStorage(context)

        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OptLevel.ALL_OPT)

            val chipset = getChipsetFamily()
            android.util.Log.d("EmbeddingEngine", "Detected chipset: $chipset")

            when (chipset) {
                ChipsetFamily.SNAPDRAGON_8_SERIES,
                ChipsetFamily.SNAPDRAGON_7_SERIES,
                ChipsetFamily.DIMENSITY_9000,
                ChipsetFamily.DIMENSITY_8000 -> {
                    try {
                        addNnapi()
                        android.util.Log.d("EmbeddingEngine", "✓ NNAPI enabled (high-end chip)")
                    } catch (e: Exception) {
                        android.util.Log.d("EmbeddingEngine", "NNAPI failed, using XNNPACK: ${e.message}")
                    }
                }
                else -> {
                    android.util.Log.d("EmbeddingEngine", "✓ XNNPACK/CPU selected (mid-range chip)")
                }
            }
        }

        session   = ortEnv.createSession(modelFile.absolutePath, opts)
        tokenizer = SimpleTokenizer(context)
        feedsTokenTypeIds = "token_type_ids" in session.inputNames
    }

    /**
     * @param trueLength run the model at the text's real token count instead of padding to
     *        [MAX_SEQ_LEN]. The graph's sequence axis is dynamic, so a short query costs a
     *        fraction of a padded one; see [VaultConfig.Embedding.QUERY_TRUE_LENGTH] for the
     *        measured speed-up and the (small) effect on the vector.
     */
    suspend fun embed(text: String, isQuery: Boolean = false, trueLength: Boolean = false): FloatArray {
        // Locks the native memory buffers so only one thread can write at a time
        return embedMutex.withLock {
            val encoded = tokenizer.encode(text, MAX_SEQ_LEN)
            // The tokenizer lays out <s> tokens </s> then padding, so the mask's 1s are a prefix.
            val seqLen = if (trueLength) encoded.attentionMask.count { it == 1 }.coerceAtLeast(1) else MAX_SEQ_LEN

            // ── Zero-copy: write directly into pre-allocated native memory ────────
            directInputBuffer.clear()
            directMaskBuffer.clear()
            directTokenTypeBuffer.clear()

            for (i in 0 until seqLen) {
                directInputBuffer.put(encoded.inputIds[i].toLong())
                directMaskBuffer.put(encoded.attentionMask[i].toLong())
                directTokenTypeBuffer.put(0L)  // token_type_ids always 0
            }

            directInputBuffer.flip()
            directMaskBuffer.flip()
            directTokenTypeBuffer.flip()

            val shape = longArrayOf(1, seqLen.toLong())

            // Tensors wrap native memory directly — no data copied
            var inputIdsTensor:    OnnxTensor? = null
            var attentionMaskTensor: OnnxTensor? = null
            var tokenTypeIdsTensor:  OnnxTensor? = null
            var output: OrtSession.Result?       = null

            try {
                inputIdsTensor      = OnnxTensor.createTensor(ortEnv, directInputBuffer,     shape)
                attentionMaskTensor = OnnxTensor.createTensor(ortEnv, directMaskBuffer,      shape)

                val inputs = mutableMapOf(
                    "input_ids"      to inputIdsTensor,
                    "attention_mask" to attentionMaskTensor
                )
                if (feedsTokenTypeIds) {
                    tokenTypeIdsTensor = OnnxTensor.createTensor(ortEnv, directTokenTypeBuffer, shape)
                    inputs["token_type_ids"] = tokenTypeIdsTensor
                }
                output = session.run(inputs)

                val rawOutput = output[0].value
                val embedding = FloatArray(EMBEDDING_DIM)

                if (rawOutput is Array<*> && rawOutput[0] is FloatArray) {
                    val pooled = rawOutput[0] as FloatArray
                    System.arraycopy(pooled, 0, embedding, 0, EMBEDDING_DIM)
                } else if (rawOutput is Array<*> && rawOutput[0] is Array<*>) {
                    val hiddenState = rawOutput[0] as Array<FloatArray>
                    var validTokens = 0
                    for (i in 0 until minOf(seqLen, hiddenState.size)) {
                        if (encoded.attentionMask[i] == 1) {
                            for (j in 0 until EMBEDDING_DIM) {
                                embedding[j] += hiddenState[i][j]
                            }
                            validTokens++
                        }
                    }
                    if (validTokens > 0) {
                        for (j in 0 until EMBEDDING_DIM) {
                            embedding[j] /= validTokens
                        }
                    }
                }

                return@withLock embedding.normalize()

            } finally {
                inputIdsTensor?.close()
                attentionMaskTensor?.close()
                tokenTypeIdsTensor?.close()
                output?.close()
            }
        }
    }

    private enum class ChipsetFamily {
        SNAPDRAGON_8_SERIES, SNAPDRAGON_7_SERIES,
        SNAPDRAGON_6_SERIES, SNAPDRAGON_4_SERIES,
        DIMENSITY_9000, DIMENSITY_8000,
        DIMENSITY_7000, DIMENSITY_6000, UNKNOWN
    }

    private fun getChipsetFamily(): ChipsetFamily {
        val hardware = android.os.Build.HARDWARE.lowercase()
        val board    = android.os.Build.BOARD.lowercase()
        val soc      = try {
            android.os.Build::class.java.getField("SOC_MODEL")
                .get(null).toString().lowercase()
        } catch (e: Exception) { "" }

        val combined = "$hardware $board $soc"
        android.util.Log.d("EmbeddingEngine", "Hardware: $combined")

        return when {
            combined.contains("sm8")  || combined.contains("8gen") || combined.contains("8 gen")  -> ChipsetFamily.SNAPDRAGON_8_SERIES
            combined.contains("sm7")  || combined.contains("7gen") || combined.contains("7 gen") || combined.contains("sm7s") -> ChipsetFamily.SNAPDRAGON_7_SERIES
            combined.contains("sm6")  || combined.contains("6gen") || combined.contains("695")   || combined.contains("690") || combined.contains("680") -> ChipsetFamily.SNAPDRAGON_6_SERIES
            combined.contains("sm4")  || combined.contains("4gen") || combined.contains("480")   || combined.contains("460") -> ChipsetFamily.SNAPDRAGON_4_SERIES
            combined.contains("mt6989") || combined.contains("mt6985") || combined.contains("mt6983") || combined.contains("dimensity 9") -> ChipsetFamily.DIMENSITY_9000
            combined.contains("mt6895") || combined.contains("mt6891") || combined.contains("dimensity 8") -> ChipsetFamily.DIMENSITY_8000
            combined.contains("mt6886") || combined.contains("mt6855") || combined.contains("dimensity 7") -> ChipsetFamily.DIMENSITY_7000
            combined.contains("mt6835") || combined.contains("mt6833") || combined.contains("dimensity 6") -> ChipsetFamily.DIMENSITY_6000
            else -> ChipsetFamily.UNKNOWN
        }
    }

    private fun FloatArray.normalize(): FloatArray {
        val norm = sqrt(this.sumOf { (it * it).toDouble() }).toFloat()
        return if (norm > 0f) FloatArray(size) { this[it] / norm } else this
    }

    fun close() {
        session.close()
        ortEnv.close()
    }
}