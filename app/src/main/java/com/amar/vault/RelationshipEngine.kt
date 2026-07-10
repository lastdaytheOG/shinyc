package com.amar.vault

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs

object RelationshipEngine {

    val VERSION = "v1_relationship_engine"

    suspend fun executePipeline(context: Context) = withContext(Dispatchers.IO) {
        val db = VaultDatabase.get(context)
        val relDao = db.vaultRelationshipDao()
        
        // Clear graph for this version (rebuilds edges)
        relDao.deleteByVersion(VERSION)
        
        // Phase 2: Pipeline execution order MUST be strict
        
        // Step 1: SAME_TRANSACTION pass
        runSameTransactionPass(db, relDao)
        
        // Step 2: Deduplication Pass (Creates Canonical Clusters for relations)
        // (In a real scenario, this builds an in-memory map of canonical node representations)
        
        // Step 3: PAID_FOR
        runPaidForPass(db, relDao)
        
        // Step 4: ATTACHMENT_OF
        runAttachmentOfPass(db, relDao)
    }
    
    private suspend fun runSameTransactionPass(db: VaultDatabase, relDao: VaultRelationshipDao) {
        val metaDao = db.vaultMetadataDao()
        // Example: Finding identical screenshots via exact PNR match
        // Dummy implementation for structural pipeline completeness
        val pnrs = metaDao.getByTypeAndValue("DOCUMENT_TYPE", "TRAIN_TICKET")
        // ... Merge duplicate pnrs ...
    }
    
    private suspend fun runAttachmentOfPass(db: VaultDatabase, relDao: VaultRelationshipDao) {
        // Find PDFs referencing order screenshots
    }

    private suspend fun runPaidForPass(db: VaultDatabase, relDao: VaultRelationshipDao) {
        val metaDao = db.vaultMetadataDao()
        val payments = metaDao.getByTypeAndValue("DOCUMENT_CLASS", "PAYMENT")
        val orders = metaDao.getByTypeAndValue("DOCUMENT_CLASS", "ORDER")
        
        val newRelationships = mutableListOf<VaultRelationship>()
        
        for (order in orders) {
            val orderId = order.vaultItemId
            val orderAmount = metaDao.getByItemIdAndType(orderId, "AMOUNT").firstOrNull()?.numericValue ?: continue
            val orderOrg = metaDao.getByItemIdAndType(orderId, "ORGANIZATION").firstOrNull()?.value
            val orderDate = metaDao.getByItemIdAndType(orderId, "DATE").firstOrNull()?.timestampValue ?: 0L
            
            val candidatePayments = mutableListOf<VaultRelationship>()
            
            for (payment in payments) {
                val payId = payment.vaultItemId
                val payAmount = metaDao.getByItemIdAndType(payId, "AMOUNT").firstOrNull()?.numericValue ?: continue
                val payOrg = metaDao.getByItemIdAndType(payId, "PAYMENT_APP").firstOrNull()?.value
                val payDate = metaDao.getByItemIdAndType(payId, "DATE").firstOrNull()?.timestampValue ?: 0L
                
                if (abs(orderAmount - payAmount) < 0.01) {
                    val timeDeltaMins = abs(orderDate - payDate) / (1000 * 60)
                    if (timeDeltaMins <= 10) {
                        
                        val json = JSONObject().apply {
                            put("amount", orderAmount)
                            put("timeDeltaMinutes", timeDeltaMins)
                            if (orderOrg != null) put("merchant", orderOrg)
                        }.toString()
                        
                        candidatePayments.add(VaultRelationship(
                            sourceId = payId,
                            targetId = orderId,
                            relationshipType = "PAID_FOR",
                            confidence = 0.90f,
                            relationshipState = "LIKELY",
                            createdByRule = "RULE_AMOUNT_TIME_MERCHANT",
                            relationshipVersion = VERSION,
                            relationshipEvidenceJson = json
                        ))
                    }
                }
            }
            
            // THE HIGHLANDER RULE
            // If N > 1 valid LIKELY matches exist, we abort relationship creation.
            // Ambiguity means the system does not know the truth. No relationship is better than a wrong relationship.
            if (candidatePayments.size == 1) {
                newRelationships.addAll(candidatePayments)
            }
        }
        
        if (newRelationships.isNotEmpty()) {
            relDao.insertAll(newRelationships)
        }
    }
}
