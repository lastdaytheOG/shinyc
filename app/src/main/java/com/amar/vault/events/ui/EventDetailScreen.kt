package com.amar.vault.events.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amar.vault.events.model.VirtualEvent

@Composable
fun EventDetailScreen(event: VirtualEvent) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Event Details", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(text = "Anchor Type: ${event.anchorType}", style = MaterialTheme.typography.titleMedium)
            Text(text = "Confidence: ${event.anchorConfidence}", style = MaterialTheme.typography.bodyLarge)
            
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "Evidence Count: ${event.evidenceCount}", style = MaterialTheme.typography.bodyLarge)
            Text(text = "Confirmed Edges: ${event.confirmedRelationshipCount}", style = MaterialTheme.typography.bodyMedium)
            Text(text = "Location Matches: ${event.locationEvidenceCount}", style = MaterialTheme.typography.bodyMedium)
            
            Spacer(modifier = Modifier.height(16.dp))
            if (event.isEvidenceTruncated) {
                Text(
                    text = "Showing top 100 supporting documents", 
                    style = MaterialTheme.typography.bodyMedium, 
                    color = MaterialTheme.colorScheme.error
                )
            }
            
            // In a real implementation, a LazyColumn of the evidence documents would go here
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                // items(...)
            }
        }
    }
}
