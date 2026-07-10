package com.amar.vault

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphExplorer(sourceId: String, onDismiss: () -> Unit) {
    var relationships by remember { mutableStateOf<List<VaultRelationship>>(emptyList()) }
    val context = LocalContext.current
    
    LaunchedEffect(sourceId) {
        withContext(Dispatchers.IO) {
            val db = VaultDatabase.get(context)
            val realRels = db.vaultRelationshipDao().getRelationshipsForDocument(sourceId)
            
            // Mocking a sample if none exist for demonstration purposes in Sprint D.2.5
            if (realRels.isEmpty()) {
                relationships = listOf(
                    VaultRelationship(sourceId, "linked_order_092", "PAID_FOR", 0.95f, "LIKELY", "RULE_AMOUNT_TIME_MERCHANT", "v1", "{\"merchant\":\"SWIGGY\",\"amount\":249,\"timeDeltaMinutes\":3}")
                )
            } else {
                relationships = realRels
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Text("Graph Explorer", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.height(8.dp))
            Text("Inspecting trusted edges for this document.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            
            Spacer(Modifier.height(16.dp))
            
            if (relationships.isEmpty()) {
                Text("No relationships found.")
            } else {
                relationships.forEach { rel ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant, 
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("${rel.relationshipType} → ${rel.targetId}", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text("State: ${rel.relationshipState} (${rel.confidence})", fontSize = 12.sp)
                            Text("Created By: ${rel.createdByRule}", fontSize = 12.sp)
                            Spacer(Modifier.height(4.dp))
                            Text("Evidence JSON:\n${rel.relationshipEvidenceJson}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            
            Spacer(Modifier.height(32.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Close Explorer")
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
