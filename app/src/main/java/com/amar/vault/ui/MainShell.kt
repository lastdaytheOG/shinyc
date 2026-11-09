package com.amar.vault.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.amar.vault.*
import com.amar.vault.ui.screens.*
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

    // App Lock State
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
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = NavigationBarDefaults.Elevation
                    ) {
                        NavigationTabItem(
                            icon = Icons.Default.Search,
                            label = VaultStrings.searchNav(lang),
                            selected = currentTab == MainTab.SEARCH,
                            onClick = { currentTab = MainTab.SEARCH }
                        )

                        NavigationTabItem(
                            icon = DocumentNavIcon,
                            label = VaultStrings.docsNav(lang),
                            selected = currentTab == MainTab.DOCUMENTS,
                            onClick = { currentTab = MainTab.DOCUMENTS }
                        )

                        NavigationTabItem(
                            icon = Icons.Default.Settings,
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
private fun RowScope.NavigationTabItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(imageVector = icon, contentDescription = label) },
        label = { Text(text = label, style = MaterialTheme.typography.labelMedium) },
        colors = NavigationBarItemDefaults.colors(
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}
