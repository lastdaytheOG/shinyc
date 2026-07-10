package com.amar.vault.pipeline.providers

import com.amar.vault.pipeline.models.UniversalMetadata
import com.amar.vault.pipeline.models.MetadataField
import java.io.File

interface MetadataProvider {
    /**
     * Determine if this provider can extract data from the given inputs.
     */
    suspend fun canHandle(url: String?, mimeType: String?, text: String?, localFile: File?): Boolean
    
    /**
     * Extracts available metadata. Should never block the UI thread.
     */
    suspend fun extract(url: String?, mimeType: String?, text: String?, localFile: File?): UniversalMetadata
}

// ----------------------------------------------------
// Dummy Implementations (Skeletons for Architecture)
// ----------------------------------------------------

class IntentMetadataProvider : MetadataProvider {
    override suspend fun canHandle(url: String?, mimeType: String?, text: String?, localFile: File?): Boolean {
        return !text.isNullOrBlank() || !url.isNullOrBlank()
    }

    override suspend fun extract(url: String?, mimeType: String?, text: String?, localFile: File?): UniversalMetadata {
        // Simple heuristic for intent text (often just a title or title + URL)
        val lines = text?.lines() ?: emptyList()
        val title = lines.firstOrNull { !it.startsWith("http") }?.takeIf { it.isNotBlank() }
        
        
        return UniversalMetadata(
            title = title?.let { MetadataField(it, "INTENT", 0.4f) }
        )
    }
}

class OpenGraphProvider : MetadataProvider {
    override suspend fun canHandle(url: String?, mimeType: String?, text: String?, localFile: File?): Boolean {
        return !url.isNullOrBlank() && url.startsWith("http")
    }

    override suspend fun extract(url: String?, mimeType: String?, text: String?, localFile: File?): UniversalMetadata {
        // TODO: Perform lightweight network request to fetch <meta property="og:..."> tags
        
        return UniversalMetadata(
            title = MetadataField("Mock OG Title", "OPENGRAPH", 0.8f),
            description = MetadataField("Mock OG Description", "OPENGRAPH", 0.8f)
            // thumbnail = MetadataField("https://...", "OPENGRAPH", 0.8f)
        )
    }
}

class HtmlMetadataProvider : MetadataProvider {
    override suspend fun canHandle(url: String?, mimeType: String?, text: String?, localFile: File?): Boolean {
        return !url.isNullOrBlank() && url.startsWith("http")
    }

    override suspend fun extract(url: String?, mimeType: String?, text: String?, localFile: File?): UniversalMetadata {
        // TODO: Fallback HTML tags parsing (<title>, <meta name="description">)
        return UniversalMetadata()
    }
}

class JsonLdProvider : MetadataProvider {
    override suspend fun canHandle(url: String?, mimeType: String?, text: String?, localFile: File?): Boolean {
        return !url.isNullOrBlank() && url.startsWith("http")
    }

    override suspend fun extract(url: String?, mimeType: String?, text: String?, localFile: File?): UniversalMetadata {
        // TODO: Extract script type="application/ld+json"
        return UniversalMetadata()
    }
}

class SchemaOrgProvider : MetadataProvider {
    override suspend fun canHandle(url: String?, mimeType: String?, text: String?, localFile: File?): Boolean {
        return !url.isNullOrBlank() && url.startsWith("http")
    }

    override suspend fun extract(url: String?, mimeType: String?, text: String?, localFile: File?): UniversalMetadata {
        // TODO: Extract schema.org microdata
        return UniversalMetadata()
    }
}

class ExifMetadataProvider : MetadataProvider {
    override suspend fun canHandle(url: String?, mimeType: String?, text: String?, localFile: File?): Boolean {
        return localFile != null && localFile.exists() && (mimeType?.startsWith("image/") == true || mimeType?.startsWith("video/") == true)
    }

    override suspend fun extract(url: String?, mimeType: String?, text: String?, localFile: File?): UniversalMetadata {
        // TODO: Wrap existing MetadataExtractor.extract (ExifInterface, MediaMetadataRetriever)
        
        return UniversalMetadata(
            // width = MetadataField(1920, "EXIF", 0.9f)
        )
    }
}
