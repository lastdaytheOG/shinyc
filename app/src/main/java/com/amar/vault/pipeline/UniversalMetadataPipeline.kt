package com.amar.vault.pipeline

import com.amar.vault.pipeline.models.UniversalMetadata
import com.amar.vault.pipeline.providers.*
import com.amar.vault.pipeline.stages.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object OpenGraphCache {
    private val cache = mutableMapOf<String, UniversalMetadata>()
    
    fun get(url: String): UniversalMetadata? = cache[url]
    fun put(url: String, metadata: UniversalMetadata) {
        cache[url] = metadata
    }
}

class UniversalMetadataPipeline {

    private val providers = listOf(
        IntentMetadataProvider(),
        OpenGraphProvider(),
        HtmlMetadataProvider(),
        JsonLdProvider(),
        SchemaOrgProvider(),
        ExifMetadataProvider()
    )

    /**
     * The master entry point for the Universal Metadata Extraction Pipeline.
     * Guaranteed to never crash or fail the save process.
     */
    suspend fun process(
        rawUrl: String?,
        rawMimeType: String?,
        rawText: String?,
        localFile: File?
    ): UniversalMetadata = withContext(Dispatchers.IO) {
        try {
            // Stage 1: Detect Content
            val contentType = ContentDetector.detectType(rawUrl, rawMimeType)

            // Stage 2: Normalize URL
            val normalizedUrl = UrlNormalizer.normalize(rawUrl)

            // Stage 3: Detect Platform
            val platform = PlatformDetector.detectPlatform(normalizedUrl)

            // Optional: Check Cache before running heavy extractors
            if (normalizedUrl != null) {
                val cached = OpenGraphCache.get(normalizedUrl)
                if (cached != null) {
                    // For a real implementation, we'd still merge intent extras and cached data.
                    // return cached
                }
            }

            // Stage 4: Execute all capable providers asynchronously and concurrently
            val extractionResults = mutableListOf<UniversalMetadata>()
            
            // Note: In production, use async/awaitAll to run these in parallel.
            for (provider in providers) {
                if (provider.canHandle(normalizedUrl, rawMimeType, rawText, localFile)) {
                    try {
                        val result = provider.extract(normalizedUrl, rawMimeType, rawText, localFile)
                        extractionResults.add(result)
                    } catch (e: Exception) {
                        // Fault tolerance: One provider failing does not fail the pipeline
                        android.util.Log.e("MetadataPipeline", "Provider failed", e)
                    }
                }
            }

            // Stage 5: Merge Results intelligently based on Quality Scores
            var finalMetadata = MetadataMerger.merge(extractionResults)

            // Inject raw fields that must be preserved
            finalMetadata = finalMetadata.copy(
                rawUri = normalizedUrl ?: rawUrl,
                mimeType = rawMimeType,
                localFile = localFile?.absolutePath
            )

            // Cache the result if applicable
            if (normalizedUrl != null) {
                OpenGraphCache.put(normalizedUrl, finalMetadata)
            }

            return@withContext finalMetadata

        } catch (e: Exception) {
            android.util.Log.e("MetadataPipeline", "Catastrophic pipeline failure, returning basic fallback", e)
            return@withContext UniversalMetadata(
                rawUri = rawUrl,
                mimeType = rawMimeType,
                localFile = localFile?.absolutePath
            )
        }
    }
}
