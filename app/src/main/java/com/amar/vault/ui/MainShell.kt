package com.amar.vault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.amar.vault.DocumentViewerScreen
import com.amar.vault.OnboardingScreen
import com.amar.vault.ScanPreferences
import com.amar.vault.SearchViewModel
import com.amar.vault.ui.screens.AppLockScreen
import com.amar.vault.ui.screens.DocumentsScreen
import com.amar.vault.ui.screens.SearchScreen
import com.amar.vault.ui.screens.SettingsScreen
import com.amar.vault.ui.security.AppLockManager
import com.amar.vault.ui.theme.AmarTheme

enum class MainTab {
    SEARCH,
    DOCUMENTS,
    SETTINGS
}

enum class ShellScreen {
    MAIN_TABS,
    DOCUMENT_VIEWER,
    DEV_TOOLS,
    ONBOARDING
}

@Composable
fun MainShell() {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val prefsState by ScanPreferences.prefsFlow(context).collectAsState(initial = null)
    val prefs = prefsState ?: return

    val activeLanguage = remember(prefs.language) {
        when (prefs.language) {
            "hi" -> AppLanguage.HINDI
            "en" -> AppLanguage.ENGLISH
            else -> {
                val locale = context.resources.configuration.locales[0]
                if (locale.language.startsWith("hi")) AppLanguage.HINDI else AppLanguage.ENGLISH
            }
        }
    }

    var isUnlocked by remember { mutableStateOf(!prefs.appLockEnabled) }

    LaunchedEffect(prefs.appLockEnabled) {
        if (!prefs.appLockEnabled) {
            isUnlocked = true
        } else if (!isUnlocked && activity != null) {
            AppLockManager.promptUnlock(
                activity = activity,
                onSuccess = { isUnlocked = true }
            )
        }
    }

    CompositionLocalProvider(LocalAppLanguage provides activeLanguage) {
        AmarTheme(largerText = prefs.largerText) {
            if (prefs.appLockEnabled && !isUnlocked) {
                AppLockScreen(
                    onUnlockClick = {
                        if (activity != null) {
                            AppLockManager.promptUnlock(
                                activity = activity,
                                onSuccess = { isUnlocked = true }
                            )
                        }
                    }
                )
            } else if (!prefs.onboardingDone) {
                OnboardingScreen(
                    onDocumentsChosen = {},
                    onComplete = {}
                )
            } else {
                ShellContent(prefs = prefs)
            }
        }
    }
}

@Composable
private fun ShellContent(prefs: ScanPreferences.Prefs) {
    val searchViewModel: SearchViewModel = hiltViewModel()
    val lang = LocalAppLanguage.current

    var currentTab by remember { mutableStateOf(MainTab.SEARCH) }
    var currentScreen by remember { mutableStateOf(ShellScreen.MAIN_TABS) }
    var selectedDocumentId by remember { mutableStateOf("") }

    when (currentScreen) {
        ShellScreen.DOCUMENT_VIEWER -> {
            DocumentViewerScreen(
                itemId = selectedDocumentId,
                onBack = { currentScreen = ShellScreen.MAIN_TABS }
            )
        }

        ShellScreen.DEV_TOOLS -> {
            if (com.amar.vault.dev.DeveloperMode.isEnabled(prefs)) {
                com.amar.vault.dev.DevToolsRoot(onExit = { currentScreen = ShellScreen.MAIN_TABS })
            } else {
                currentScreen = ShellScreen.MAIN_TABS
            }
        }

        ShellScreen.MAIN_TABS, ShellScreen.ONBOARDING -> {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .navigationBarsPadding()
                            .height(58.dp)
                    ) {
                        TabItem(
                            icon = if (currentTab == MainTab.SEARCH) Icons.Rounded.Search else Icons.Outlined.Search,
                            label = VaultStrings.searchNav(lang),
                            selected = currentTab == MainTab.SEARCH,
                            onClick = { currentTab = MainTab.SEARCH }
                        )
                        TabItem(
                            icon = if (currentTab == MainTab.DOCUMENTS) Icons.Rounded.Folder else Icons.Outlined.Folder,
                            label = VaultStrings.docsNav(lang),
                            selected = currentTab == MainTab.DOCUMENTS,
                            onClick = { currentTab = MainTab.DOCUMENTS }
                        )
                        TabItem(
                            icon = if (currentTab == MainTab.SETTINGS) Icons.Rounded.Settings else Icons.Outlined.Settings,
                            label = VaultStrings.settingsNav(lang),
                            selected = currentTab == MainTab.SETTINGS,
                            onClick = { currentTab = MainTab.SETTINGS }
                        )
                    }
                }
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    when (currentTab) {
                        MainTab.SEARCH -> {
                            SearchScreen(
                                viewModel = searchViewModel,
                                onNavigateToDocuments = { currentTab = MainTab.DOCUMENTS },
                                onOpenItemDetail = { id ->
                                    selectedDocumentId = id
                                    currentScreen = ShellScreen.DOCUMENT_VIEWER
                                }
                            )
                        }

                        MainTab.DOCUMENTS -> {
                            DocumentsScreen(
                                onOpenDocument = { id ->
                                    selectedDocumentId = id
                                    currentScreen = ShellScreen.DOCUMENT_VIEWER
                                }
                            )
                        }

                        MainTab.SETTINGS -> {
                            SettingsScreen(
                                onOpenDevTools = { currentScreen = ShellScreen.DEV_TOOLS }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.TabItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = color)
    }
}
