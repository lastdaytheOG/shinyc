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

        private fun copyModelToStorage(context: Context): File {
            val cacheFile = File(context.filesDir, MODEL_CACHE)
            if (!cacheFile.exists()) {
                context.assets.open(MODEL_FILE).use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 8 * 1024 * 1024)
                    }
                }
            }
            return cacheFile
        }
    }

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val tokenizer: SimpleTokenizer

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
    }

    suspend fun embed(text: String, isQuery: Boolean = false): FloatArray {
        // Locks the native memory buffers so only one thread can write at a time
        return embedMutex.withLock {
            val encoded = tokenizer.encode(text, MAX_SEQ_LEN)

            // ── Zero-copy: write directly into pre-allocated native memory ────────
            directInputBuffer.clear()
            directMaskBuffer.clear()
            directTokenTypeBuffer.clear()

            for (i in 0 until MAX_SEQ_LEN) {
                directInputBuffer.put(encoded.inputIds[i].toLong())
                directMaskBuffer.put(encoded.attentionMask[i].toLong())
                directTokenTypeBuffer.put(0L)  // token_type_ids always 0
            }

            directInputBuffer.flip()
            directMaskBuffer.flip()
            directTokenTypeBuffer.flip()

            val shape = longArrayOf(1, MAX_SEQ_LEN.toLong())

            // Tensors wrap native memory directly — no data copied
            var inputIdsTensor:    OnnxTensor? = null
            var attentionMaskTensor: OnnxTensor? = null
            var tokenTypeIdsTensor:  OnnxTensor? = null
            var output: OrtSession.Result?       = null

            try {
                inputIdsTensor      = OnnxTensor.createTensor(ortEnv, directInputBuffer,     shape)
                attentionMaskTensor = OnnxTensor.createTensor(ortEnv, directMaskBuffer,      shape)
                tokenTypeIdsTensor  = OnnxTensor.createTensor(ortEnv, directTokenTypeBuffer, shape)

                output = try {
                    session.run(mapOf(
                        "input_ids"      to inputIdsTensor,
                        "attention_mask" to attentionMaskTensor,
                        "token_type_ids" to tokenTypeIdsTensor
                    ))
                } catch (e: Exception) {
                    session.run(mapOf(
                        "input_ids"      to inputIdsTensor,
                        "attention_mask" to attentionMaskTensor
                    ))
                }

                val rawOutput = output[0].value
                val embedding = FloatArray(EMBEDDING_DIM)

                if (rawOutput is Array<*> && rawOutput[0] is FloatArray) {
                    val pooled = rawOutput[0] as FloatArray
                    System.arraycopy(pooled, 0, embedding, 0, EMBEDDING_DIM)
                } else if (rawOutput is Array<*> && rawOutput[0] is Array<*>) {
                    val hiddenState = rawOutput[0] as Array<FloatArray>
                    var validTokens = 0
                    for (i in 0 until MAX_SEQ_LEN) {
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