package com.amar.vault

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvidenceViewer(evidenceIds: List<String>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Text("Evidence Documents", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.height(8.dp))
            Text("Showing ${evidenceIds.size} original documents used to generate this summary.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            
            Spacer(Modifier.height(16.dp))
            
            // Placeholder for thumbnail grid
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().height(200.dp)
            ) {
                Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text("Document Gallery Loading...")
                }
            }
            
            Spacer(Modifier.height(32.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Close")
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
