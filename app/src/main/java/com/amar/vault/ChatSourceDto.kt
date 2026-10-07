package com.amar.vault

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persistence DTO for chat message sources.
 *
 * Why: chat history previously serialized the Room entity [VaultItem] directly into
 * chat_history.sourcesJson. Any future change to the VaultItem schema (a new non-null
 * column, a rename, a type change) would silently break deserialization of ALL stored
 * chat history — the sources would vanish. This DTO decouples the stored JSON from the
 * live entity: the entity may evolve freely; this frozen shape is what's on disk.
 *
 * Field names mirror the VaultItem JSON keys exactly, so legacy rows (which stored raw
 * VaultItem arrays) deserialize into this DTO with no dependency on the entity class.
 *
 * Behaviour preservation: every field VaultItem serialized is carried here, so values
 * round-trip losslessly. The UI still receives List<VaultItem> — nothing downstream changes.
 */
data class ChatSourceDto(
    val id: String = "",
    val uri: String = "",
    val ocrText: String = "",
    val lang: String = "en",
    val itemType: String = "",
    val pageNum: Int = 0,
    val sourceFile: String = "",
    val timestamp: Long = 0L,
    val pHash: Long = 0L,
    val tags: String = "",
    val contentHash: String = "",
    val sourceApp: String? = null,
    val sharedAt: Long? = null,
    val originalUri: String? = null,
    val title: String? = null,
    val mimeType: String? = null
) {
    fun toVaultItem(): VaultItem {
        // A source saved before database version 14 has its tags glued onto its text and its
        // type under a former name; the stored JSON is left as it is and read as what it means.
        val parts = FormerStoredText.split(ocrText)
        return VaultItem(
            id = id,
            uri = uri,
            ocrText = parts.page,
            lang = lang,
            itemType = ItemType.ofStored(itemType) ?: ItemType.fromFormer(itemType, mimeType, uri, sourceFile),
            pageNum = pageNum,
            sourceFile = sourceFile,
            timestamp = timestamp,
            pHash = pHash,
            tags = listOf(tags, parts.tags).filter { it.isNotBlank() }.joinToString(" "),
            contentHash = contentHash,
            sourceApp = sourceApp,
            sharedAt = sharedAt,
            originalUri = originalUri,
            title = title,
            mimeType = mimeType,
            qrPayload = QrPayloads.join(parts.qrPayloads),
        )
    }

    companion object {
        fun from(item: VaultItem): ChatSourceDto = ChatSourceDto(
            id = item.id,
            uri = item.uri,
            ocrText = item.ocrText,
            lang = item.lang,
            itemType = item.itemType.stored,
            pageNum = item.pageNum,
            sourceFile = item.sourceFile,
            timestamp = item.timestamp,
            pHash = item.pHash,
            tags = item.tags,
            contentHash = item.contentHash,
            sourceApp = item.sourceApp,
            sharedAt = item.sharedAt,
            originalUri = item.originalUri,
            title = item.title,
            mimeType = item.mimeType
        )
    }
}

/** Versioned envelope so the on-disk format can evolve explicitly in the future. */
private data class ChatSourcesEnvelope(
    val v: Int = 1,
    val sources: List<ChatSourceDto> = emptyList()
)

/**
 * Encodes/decodes chat sources JSON, decoupled from the VaultItem entity.
 *
 * - encode: always writes the current versioned envelope.
 * - decode: reads the versioned envelope, and TOLERANTLY falls back to the legacy
 *   format (a bare JSON array of VaultItem-shaped objects) so existing chat history
 *   keeps its sources. Never throws — returns empty on unrecoverable input, exactly
 *   matching the previous catch→emptyList behaviour.
 */
object ChatSourceCodec {

    private val gson = Gson()
    private val dtoListType = object : TypeToken<List<ChatSourceDto>>() {}.type

    fun encode(sources: List<VaultItem>): String {
        val envelope = ChatSourcesEnvelope(v = 1, sources = sources.map { ChatSourceDto.from(it) })
        return gson.toJson(envelope)
    }

    fun decode(json: String?): List<VaultItem> {
        if (json.isNullOrBlank()) return emptyList()
        val trimmed = json.trimStart()
        return try {
            if (trimmed.startsWith("{")) {
                // Current versioned format.
                val envelope = gson.fromJson(json, ChatSourcesEnvelope::class.java)
                envelope?.sources?.map { it.toVaultItem() } ?: emptyList()
            } else {
                // Legacy format: bare array of VaultItem-shaped objects. Parse straight
                // into DTOs (field names align) — no dependency on the entity schema.
                val dtos: List<ChatSourceDto> = gson.fromJson(json, dtoListType) ?: emptyList()
                dtos.map { it.toVaultItem() }
            }
        } catch (e: Exception) {
            VaultLog.w("ChatSourceCodec", "Failed to decode sources (${VaultLog.len(json)})", e)
            emptyList()
        }
    }
}
