package com.amar.vault

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAgentDebug: () -> Unit = {},
    onOpenBrainDebug: () -> Unit = {},
    onOpenDevTools: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Stats from DB
    val allItems by VaultDatabase.get(context).vaultDao().getAllItems()
        .collectAsState(initial = emptyList())

    val totalCount = allItems.size

    val prefs by ScanPreferences.prefsFlow(context).collectAsState(initial = null)
    val developerMode = prefs?.developerMode == true
    // Hidden unlock: tap the build-info line 7× to reveal the Developer section on release
    // builds. Debug builds and an already-enabled toggle reveal it unconditionally.
    var tapCount by remember { mutableStateOf(0) }
    val showDeveloperSection = BuildConfig.DEBUG || developerMode || tapCount >= 7

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Cream)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(54.dp))

        // Back button and title
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onBack,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text("← Home", color = WarmBrown, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "Settings",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = CharcoalSoft,
            letterSpacing = (-0.5).sp
        )

        Spacer(Modifier.height(30.dp))

        // Storage section
        Text(
            text = "STORAGE",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = WarmBrown,
            letterSpacing = 1.5.sp
        )
        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = CreamLight),
            border = BorderStroke(1.dp, CreamDark)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Items in Vault",
                    fontSize = 15.sp,
                    color = WarmBrownDark,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "$totalCount",
                    fontSize = 16.sp,
                    color = CharcoalSoft,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        // About section
        Text(
            text = "ABOUT",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = WarmBrown,
            letterSpacing = 1.5.sp
        )
        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = CreamLight),
            border = BorderStroke(1.dp, CreamDark)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Amar Vault",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = CharcoalSoft
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "On-device AI • Nothing leaves your phone",
                    fontSize = 13.sp,
                    color = WarmBrownDark
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "BGE-M3 · ONNX · HNSW · BM25",
                    fontSize = 11.sp,
                    color = WarmBrown,
                    modifier = Modifier.clickable { if (tapCount < 7) tapCount++ }
                )
            }
        }

        if (showDeveloperSection) {
            Spacer(Modifier.height(32.dp))
            Text(
                text = "DEVELOPER",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = WarmBrown,
                letterSpacing = 1.5.sp
            )
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = CreamLight),
                border = BorderStroke(1.dp, CreamDark)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Developer Mode",
                                fontSize = 15.sp,
                                color = WarmBrownDark,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (BuildConfig.DEBUG) "Always on in debug builds"
                                       else "Unlocks the internal Developer Tools suite",
                                fontSize = 12.sp,
                                color = WarmBrown
                            )
                        }
                        Switch(
                            checked = BuildConfig.DEBUG || developerMode,
                            enabled = !BuildConfig.DEBUG,
                            onCheckedChange = { on ->
                                scope.launch { ScanPreferences.setDeveloperMode(context, on) }
                            }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onOpenDevTools,
                        enabled = BuildConfig.DEBUG || developerMode,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = WarmBrownDark,
                            contentColor = Cream
                        )
                    ) {
                        Text("Open Developer Tools", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}