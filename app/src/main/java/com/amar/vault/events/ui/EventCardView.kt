package com.amar.vault.events.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amar.vault.events.model.VirtualEvent

@Composable
fun EventCardView(event: VirtualEvent, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Event: ${event.anchorType}", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "Confidence: ${event.anchorConfidence}", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "${event.evidenceCount} Documents", style = MaterialTheme.typography.bodySmall)
            
            if (event.isEvidenceTruncated) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "Showing top 100 supporting documents", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
