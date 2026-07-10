package com.amar.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context

data class CollectionCard(
    val title: String,
    val documentCount: Int,
    val highlightLabel: String,
    val highlightValue: String,
    val evidenceIds: List<String>
)

class CollectionAggregator(private val context: Context) {
    suspend fun buildCard(documentClass: DocumentClass): CollectionCard? = withContext(Dispatchers.IO) {
        val db = VaultDatabase.get(context)
        val metaDao = db.vaultMetadataDao()
        
        // Find all vault items matching this DOCUMENT_CLASS
        val items = metaDao.getByTypeAndValue("DOCUMENT_CLASS", documentClass.name)
        if (items.isEmpty()) return@withContext null
        
        val count = items.size
        val evidenceIds = items.map { it.vaultItemId }.distinct()
        if (evidenceIds.isEmpty()) return@withContext null
        
        when (documentClass) {
            DocumentClass.IDENTITY -> {
                CollectionCard("Identity Documents", count, "Valid IDs", "$count on device", evidenceIds)
            }
            DocumentClass.TICKET -> {
                CollectionCard("Travel Tickets", count, "Status", "Ready", evidenceIds)
            }
            DocumentClass.PAYMENT -> {
                CollectionCard("Payment Receipts", count, "Total Receipts", "$count recorded", evidenceIds)
            }
            DocumentClass.FINANCE -> {
                CollectionCard("Financial Statements", count, "Statements", "$count available", evidenceIds)
            }
            DocumentClass.ORDER -> {
                CollectionCard("Orders & Invoices", count, "Invoices", "$count found", evidenceIds)
            }
            DocumentClass.MEDICAL -> {
                CollectionCard("Medical Records", count, "Records", "$count", evidenceIds)
            }
            else -> null
        }
    }
}
