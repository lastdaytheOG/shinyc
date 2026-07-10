package com.amar.vault

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.ui.theme.Cream
import com.amar.vault.ui.theme.WarmBrown

enum class Screen {
    ONBOARDING,
    HOME,
    SAVED,
    AGENTIC,
    PHOTOS,
    DOCUMENTS,
    TIMELINE,
    SETTINGS,
    AGENT_DEBUG,
    BRAIN_DEBUG,
    SEARCH_OVERLAY,
    DOCUMENT_VIEWER,
    DOCUMENT_CHAT,
    ENTITY,
    EVENTS,
    AI_MODELS,
    AI_ONBOARDING,
    DEV_TOOLS
}

@Composable
fun VaultApp() {
    val context = LocalContext.current

    val prefsState by ScanPreferences.prefsFlow(context).collectAsState(initial = null)
    val prefs = prefsState ?: return

    var currentScreen by remember(prefs.onboardingDone) {
        mutableStateOf(if (prefs.onboardingDone) Screen.HOME else Screen.ONBOARDING)
    }

    val searchViewModel: SearchViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    var selectedDocumentId by remember { mutableStateOf("") }
    var selectedEntityName by remember { mutableStateOf("") }
    var previousScreen by remember { mutableStateOf(Screen.HOME) }

    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = {
            (fadeIn(animationSpec = tween(220)) + scaleIn(initialScale = 0.96f, animationSpec = tween(220))) togetherWith
            (fadeOut(animationSpec = tween(220)) + scaleOut(targetScale = 1.04f, animationSpec = tween(220)))
        },
        label = "VaultScreenTransition"
    ) { screen ->
        when (screen) {
            Screen.ONBOARDING -> {
                OnboardingScreen(onComplete = { currentScreen = Screen.HOME })
            }

            Screen.HOME -> {
                val stashItems by searchViewModel.stashItems.collectAsState(initial = emptyList())
                val workInfos by androidx.work.WorkManager.getInstance(context)
                    .getWorkInfosByTagFlow("bulk_scan")
                    .collectAsState(initial = emptyList())
                val isSyncing = workInfos.any { it.state == androidx.work.WorkInfo.State.RUNNING }

                HomeScreen(
                    stashItems = stashItems,
                    isSyncing = isSyncing,
                    onSearchTriggerClick = { currentScreen = Screen.SEARCH_OVERLAY },
                    onSavedClick = { currentScreen = Screen.SAVED },
                    onRecentItemClick = { item -> ContentOpenManager.open(context, item) },
                    onAgenticClick = { currentScreen = Screen.AGENTIC },
                    onPhotosClick = { currentScreen = Screen.PHOTOS },
                    onDocumentsClick = { currentScreen = Screen.DOCUMENTS },
                    onSettingsClick = { currentScreen = Screen.SETTINGS },
                    onTimelineClick = { currentScreen = Screen.TIMELINE }
                )
            }

            Screen.SAVED -> {
                SavedScreen(
                    viewModel = searchViewModel,
                    onBack = { currentScreen = Screen.HOME },
                    onItemClick = { id ->
                        selectedDocumentId = id
                        previousScreen = Screen.SAVED
                        currentScreen = Screen.DOCUMENT_VIEWER
                    }
                )
            }

            Screen.AGENTIC -> {
                AgenticScreen(
                    onBack = { currentScreen = Screen.HOME },
                    onOpenItem = { id ->
                        selectedDocumentId = id
                        previousScreen = Screen.AGENTIC
                        currentScreen = Screen.DOCUMENT_VIEWER
                    },
                    onModelManagementClick = { currentScreen = Screen.AI_MODELS },
                    onOnboardingClick = { currentScreen = Screen.AI_ONBOARDING }
                )
            }

            Screen.PHOTOS -> {
                PhotosScreen(onBack = { currentScreen = Screen.HOME })
            }

            Screen.DOCUMENTS -> {
                ScreenWithBack(onBack = { currentScreen = Screen.HOME }) {
                    DocumentPickerScreen()
                }
            }

            Screen.TIMELINE -> {
                ScreenWithBack(onBack = { currentScreen = Screen.HOME }) {
                    com.amar.vault.timeline.ui.TimelineScreen()
                }
            }

            Screen.SETTINGS -> {
                SettingsScreen(
                    onBack = { currentScreen = Screen.HOME },
                    onOpenAgentDebug = { currentScreen = Screen.AGENT_DEBUG },
                    onOpenBrainDebug = { currentScreen = Screen.BRAIN_DEBUG },
                    onOpenDevTools = { currentScreen = Screen.DEV_TOOLS }
                )
            }

            Screen.DEV_TOOLS -> {
                // Developer Tools are gated: reachable only in debug builds or when the user has
                // explicitly enabled Developer Mode. Defence-in-depth — the Settings entry that
                // routes here is itself gated, and this re-checks before rendering anything.
                if (com.amar.vault.dev.DeveloperMode.isEnabled(prefs)) {
                    com.amar.vault.dev.DevToolsRoot(onExit = { currentScreen = Screen.SETTINGS })
                } else {
                    currentScreen = Screen.SETTINGS
                }
            }

            Screen.AGENT_DEBUG -> {
                if (BuildConfig.DEBUG) {
                    ScreenWithBack(onBack = { currentScreen = Screen.HOME }) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Agent Runtime Deferred", fontSize = 18.sp, color = WarmBrown)
                        }
                    }
                } else {
                    currentScreen = Screen.HOME
                }
            }

            Screen.BRAIN_DEBUG -> {
                if (BuildConfig.DEBUG) {
                    ScreenWithBack(onBack = { currentScreen = Screen.HOME }) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Brain Runtime Deferred", fontSize = 18.sp, color = WarmBrown)
                        }
                    }
                } else {
                    currentScreen = Screen.HOME
                }
            }

            Screen.SEARCH_OVERLAY -> {
                SearchOverlayScreen(
                    viewModel = searchViewModel,
                    onBack = { currentScreen = Screen.HOME },
                    onResultClick = { id ->
                        selectedDocumentId = id
                        previousScreen = Screen.SEARCH_OVERLAY
                        currentScreen = Screen.DOCUMENT_VIEWER
                    },
                    onEntityClick = { name ->
                        selectedEntityName = name
                        previousScreen = Screen.SEARCH_OVERLAY
                        currentScreen = Screen.ENTITY
                    }
                )
            }

            Screen.DOCUMENT_VIEWER -> {
                DocumentViewerScreen(
                    itemId = selectedDocumentId,
                    onBack = { currentScreen = previousScreen },
                    summarize = { item -> searchViewModel.summarizeDocument(item) },
                    onChatClick = { currentScreen = Screen.DOCUMENT_CHAT }
                )
            }

            Screen.DOCUMENT_CHAT -> {
                DocumentChatScreen(
                    itemId = selectedDocumentId,
                    viewModel = searchViewModel,
                    onBack = { currentScreen = Screen.DOCUMENT_VIEWER }
                )
            }

            Screen.ENTITY -> {
                ScreenWithBack(onBack = { currentScreen = previousScreen }) {
                    EntityScreen(
                        viewModel = searchViewModel,
                        entityName = selectedEntityName,
                        onOpen = { item -> ContentOpenManager.open(context, item) },
                        onEventsClick = { name ->
                            selectedEntityName = name
                            previousScreen = Screen.ENTITY
                            currentScreen = Screen.EVENTS
                        }
                    )
                }
            }

            Screen.EVENTS -> {
                ScreenWithBack(onBack = { currentScreen = previousScreen }) {
                    EventsScreen(
                        viewModel = searchViewModel,
                        topic = selectedEntityName,
                        onOpen = { item -> ContentOpenManager.open(context, item) }
                    )
                }
            }

            Screen.AI_MODELS -> {
                ModelManagementScreen(
                    onBack = { currentScreen = Screen.AGENTIC }
                )
            }

            Screen.AI_ONBOARDING -> {
                AiOnboardingScreen(
                    onComplete = { currentScreen = Screen.AGENTIC },
                    onBack = { currentScreen = Screen.AGENTIC }
                )
            }
        }
    }
}

@Composable
private fun ScreenWithBack(onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(Cream)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← Back", color = WarmBrown, fontSize = 16.sp) }
        }
        content()
    }
}
