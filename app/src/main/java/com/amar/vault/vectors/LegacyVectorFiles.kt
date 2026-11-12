package com.amar.vault.vectors

import android.content.Context
import com.amar.vault.VaultLog
import java.io.File

object LegacyVectorFiles {

    val NAMES = listOf("hnsw_index.bin", "vector_mappings.json", "embedding_manifest.json", "bge_m3_cached.onnx",
        "bge_m3_cached.onnx.part", "vector_mappings.json.tmp", "hnsw_index.bin.tmp")

    fun clean(context: Context): Long {
        var freed = 0L
        for (name in NAMES) {
            val f = File(context.filesDir, name)
            if (f.isFile) {
                val size = f.length()
                if (f.delete()) freed += size
            }
        }
        if (freed > 0) VaultLog.i("LegacyVectorFiles", "removed the old vector index and model: ${freed / 1_048_576} MB")
        return freed
    }
}
