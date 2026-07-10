package com.amar.vault

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amar.vault.ui.theme.Cream
import com.amar.vault.ui.theme.CreamDark
import com.amar.vault.ui.theme.CreamLight
import com.amar.vault.ui.theme.CharcoalSoft
import com.amar.vault.ui.theme.WarmBrown
import com.amar.vault.ui.theme.WarmBrownDark

@Composable
fun AiOnboardingScreen(
    viewModel: AiOnboardingViewModel = hiltViewModel(),
    onComplete: () -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(uiState.navigateToAskVault) {
        if (uiState.navigateToAskVault) {
            viewModel.onNavigated()
            onComplete()
        }
    }

    if (uiState.isLoadingProfile) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Cream),
            contentAlignment = Alignment.Center
        ) {
            Text("Loading...", color = WarmBrown, fontSize = 16.sp)
        }
        return
    }

    val profile = uiState.profile
    if (profile == null || !profile.supported) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Cream),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "This device is not supported for on-device AI.",
                fontSize = 18.sp,
                color = CharcoalSoft,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(containerColor = CharcoalSoft)
            ) {
                Text("Go Back")
            }
        }
        return
    }

    val recommended = uiState.recommendedModel ?: return

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

        Spacer(Modifier.height(32.dp))

        Text(
            text = "Recommended AI",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = CharcoalSoft,
            letterSpacing = (-0.5).sp
        )
        Text(
            text = "Private. Offline. Optimized for your device.",
            fontSize = 16.sp,
            color = WarmBrown,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(Modifier.height(48.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CreamLight),
            border = BorderStroke(1.dp, CreamDark)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = recommended.displayName,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = CharcoalSoft
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = recommended.downloadSize,
                    fontSize = 14.sp,
                    color = WarmBrownDark
                )

                Spacer(Modifier.height(32.dp))

                when (uiState.downloadState) {
                    AiOnboardingViewModel.DownloadState.DOWNLOADING -> {
                        Text(
                            text = "Downloading...",
                            fontSize = 15.sp,
                            color = CharcoalSoft,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { uiState.downloadProgress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = CharcoalSoft,
                            trackColor = CreamDark
                        )
                    }
                    AiOnboardingViewModel.DownloadState.VERIFYING -> {
                        Text(
                            text = "Verifying...",
                            fontSize = 15.sp,
                            color = CharcoalSoft,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = CharcoalSoft,
                            trackColor = CreamDark
                        )
                    }
                    AiOnboardingViewModel.DownloadState.PREPARING -> {
                        Text(
                            text = "Preparing AI...",
                            fontSize = 15.sp,
                            color = CharcoalSoft,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    AiOnboardingViewModel.DownloadState.READY -> {
                        Text(
                            text = "Almost Ready...",
                            fontSize = 15.sp,
                            color = CharcoalSoft,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    AiOnboardingViewModel.DownloadState.IDLE, AiOnboardingViewModel.DownloadState.FAILED -> {
                        if (uiState.downloadState == AiOnboardingViewModel.DownloadState.FAILED) {
                            Text(
                                text = "Couldn't prepare AI. Please try again.",
                                fontSize = 14.sp,
                                color = Color(0xFFCC3333),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                        }
                        
                        Button(
                            onClick = { viewModel.onDownloadClicked() },
                            colors = ButtonDefaults.buttonColors(containerColor = CharcoalSoft),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Download", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (uiState.showStorageWarning) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissStorageWarning() },
            title = {
                Text(
                    text = "Not enough storage",
                    fontWeight = FontWeight.Bold,
                    color = CharcoalSoft
                )
            },
            text = {
                val availableFormatted = String.format("%.1f GB", profile.freeStorageGb)
                val requiredFormatted = String.format("%.1f GB", recommended.requiredStorageGb)
                Text(
                    text = "Need: $requiredFormatted\nAvailable: $availableFormatted",
                    color = WarmBrownDark
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissStorageWarning()
                        val intent = Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
                        context.startActivity(intent)
                    }
                ) {
                    Text("Manage Storage", color = CharcoalSoft, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissStorageWarning() }) {
                    Text("Cancel", color = WarmBrown)
                }
            },
            containerColor = CreamLight
        )
    }
}
