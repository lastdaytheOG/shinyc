package com.amar.vault

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.ui.theme.Cream
import com.amar.vault.ui.theme.CreamDark
import com.amar.vault.ui.theme.CreamLight
import com.amar.vault.ui.theme.CharcoalSoft
import com.amar.vault.ui.theme.WarmBrown
import com.amar.vault.ui.theme.WarmBrownDark
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ModelManagementScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelManager = remember { ModelManager.getInstance(context) }
    
    val modelsList by modelManager.getAllModelsFlow().collectAsState(initial = emptyList())
    val profile = remember { DeviceCapability.getDeviceProfile(context) }
    val recommended = remember { ModelRecommendationEngine.recommend(profile) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Cream)
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(54.dp))

        // Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onBack,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text("← Back", color = WarmBrown, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "AI Models",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = CharcoalSoft,
            letterSpacing = (-0.5).sp
        )
        Text(
            text = "Manage models for secure, on-device chat.",
            fontSize = 14.sp,
            color = WarmBrown,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(28.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(modelsList) { model ->
                val isRec = model.modelId == recommended.modelId
                ModelItemCard(
                    model = model,
                    isRecommended = isRec,
                    onDownload = {
                        scope.launch { modelManager.downloadModel(model.modelId) }
                    },
                    onDelete = {
                        scope.launch { modelManager.deleteModel(model.modelId) }
                    },
                    onToggleActive = { active ->
                        scope.launch {
                            if (active) {
                                modelManager.enableModel(model.modelId)
                            } else {
                                modelManager.disableModel(model.modelId)
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ModelItemCard(
    model: LocalModel,
    isRecommended: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onToggleActive: (Boolean) -> Unit
) {
    // Hide technical quantization naming for presentation:
    val simpleName = when (model.modelId) {
        "qwen-3b" -> "Qwen 2.5 3B"
        "qwen-1.5b" -> "Qwen 2.5 1.5B"
        "gemma-2b" -> "Gemma 2 2B"
        else -> model.displayName
    }

    val simpleDescription = when (model.modelId) {
        "qwen-3b" -> "Highest quality details and reasoning."
        "qwen-1.5b" -> "Very fast performance, saves battery life."
        "gemma-2b" -> "Google edge-optimized response model."
        else -> "On-device language learning model."
    }

    val sizeText = when (model.modelId) {
        "qwen-3b" -> "2.2 GB"
        "qwen-1.5b" -> "950 MB"
        "gemma-2b" -> "1.6 GB"
        else -> "${model.sizeBytes / (1024 * 1024)} MB"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CreamLight),
        border = BorderStroke(1.dp, CreamDark)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Title & Recommendation Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = simpleName,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = CharcoalSoft
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = sizeText,
                        fontSize = 12.sp,
                        color = WarmBrown
                    )
                }

                if (isRecommended) {
                    Box(
                        modifier = Modifier
                            .background(CharcoalSoft, RoundedCornerShape(20.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Recommended",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = simpleDescription,
                fontSize = 14.sp,
                color = WarmBrownDark,
                lineHeight = 18.sp
            )

            Spacer(Modifier.height(16.dp))

            // Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status / Toggle state
                when (model.status) {
                    "READY" -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (model.isEnabled) "Active" else "Inactive",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (model.isEnabled) CharcoalSoft else WarmBrown
                            )
                            Spacer(Modifier.width(10.dp))
                            Switch(
                                checked = model.isEnabled,
                                onCheckedChange = onToggleActive,
                                colors = SwitchDefaults.colors(checkedTrackColor = CharcoalSoft)
                            )
                        }

                        TextButton(onClick = onDelete) {
                            Text(
                                text = "Delete",
                                color = Color(0xFFCC3333),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    "DOWNLOADING" -> {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Downloading… ${model.downloadProgress}%",
                                fontSize = 13.sp,
                                color = WarmBrown,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { model.downloadProgress / 100f },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = CharcoalSoft,
                                trackColor = CreamDark
                            )
                        }
                    }
                    "VERIFYING" -> {
                        Text(
                            text = "Verifying installation…",
                            fontSize = 13.sp,
                            color = WarmBrown,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    "PENDING" -> {
                        Text(
                            text = "Pending download…",
                            fontSize = 13.sp,
                            color = WarmBrown,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    else -> {
                        // Not downloaded / Failed
                        Button(
                            onClick = onDownload,
                            colors = ButtonDefaults.buttonColors(containerColor = CharcoalSoft),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("Download", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
