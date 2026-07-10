package com.amar.vault.action.engine

import com.amar.vault.VaultDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ActionExpirationWorker(private val db: VaultDatabase) {

    suspend fun processExpirations(currentTimeMs: Long) = withContext(Dispatchers.IO) {
        
        val dao = db.agentActionSnapshotDao()
        val expirableActions = dao.getExpirableActions()

        val toArchive = mutableListOf<String>()
        val toUpdate = mutableListOf<String>()

        for (action in expirableActions) {
            val expires = action.expiresAt
            if (expires != null) {
                if (currentTimeMs >= expires) {
                    // Action lifespan has passed. Eradicate from active feed.
                    toArchive.add(action.actionId)
                } else {
                    // Still valid. Update Audit Trail.
                    toUpdate.add(action.actionId)
                }
            }
        }

        // Apply strict state transitions
        if (toArchive.isNotEmpty()) {
            dao.archiveActions(toArchive)
        }

        if (toUpdate.isNotEmpty()) {
            dao.updateLastEvaluated(toUpdate, currentTimeMs)
        }
    }
}
