package com.opencode.ai.ui.screens

import androidx.compose.material3.MaterialTheme

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.using
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.opencode.ai.ui.components.AppDrawer
import com.opencode.ai.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

enum class Screen {
    CHAT, PROVIDERS, SKILLS_MCP, SETTINGS
}

@Composable
fun MainScreen(viewModel: ChatViewModel, onSignOut: (() -> Unit)? = null) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf(Screen.CHAT) }
    var showModelPicker by remember { mutableStateOf(false) }

    // Predictive back (Android 14+): экран уменьшается и скругляется под жестом «назад».
    var backProgress by remember { mutableFloatStateOf(0f) }
    val animatedBack by animateFloatAsState(
        targetValue = backProgress,
        animationSpec = androidx.compose.animation.core.spring(stiffness = Spring.StiffnessMedium),
        label = "predictive_back"
    )
    PredictiveBackHandler(enabled = showModelPicker || currentScreen != Screen.CHAT) { progress ->
        try {
            progress.collect { event -> backProgress = event.progress }
            if (showModelPicker) showModelPicker = false else currentScreen = Screen.CHAT
            backProgress = 0f
        } catch (e: CancellationException) {
            backProgress = 0f
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                sessions = viewModel.sessions,
                onNewChat = {
                    viewModel.newChat()
                    scope.launch { drawerState.close() }
                    currentScreen = Screen.CHAT
                },
                onSessionClick = { session ->
                    viewModel.loadSession(session)
                    scope.launch { drawerState.close() }
                    currentScreen = Screen.CHAT
                },
                onProvidersClick = {
                    scope.launch { drawerState.close() }
                    currentScreen = Screen.PROVIDERS
                },
                onSettingsClick = {
                    scope.launch { drawerState.close() }
                    currentScreen = Screen.SETTINGS
                },
                onModelsClick = {
                    scope.launch { drawerState.close() }
                    viewModel.refreshOpenCodeModels()
                    showModelPicker = true
                },
                onSkillsClick = {
                    scope.launch { drawerState.close() }
                    currentScreen = Screen.SKILLS_MCP
                },
            )
        },
        gesturesEnabled = true
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = 1f - 0.09f * animatedBack
                    scaleX = scale
                    scaleY = scale
                    shape = RoundedCornerShape((32f * animatedBack).dp)
                    clip = animatedBack > 0.001f
                }
                .background(MaterialTheme.colorScheme.background)
        ) {
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = {
                    // К чату — слева, от чата — справа: ощущение «shared axis».
                    val dir = if (targetState == Screen.CHAT) -1 else 1
                    (slideInHorizontally(
                        animationSpec = spring(dampingRatio = 0.84f, stiffness = 380f)
                    ) { dir * it / 5 } +
                        fadeIn(spring(stiffness = 520f)) +
                        scaleIn(spring(dampingRatio = 0.84f, stiffness = 380f), initialScale = 0.93f)) togetherWith
                        (slideOutHorizontally(
                            animationSpec = spring(dampingRatio = 0.9f, stiffness = 480f)
                        ) { -dir * it / 9 } +
                            fadeOut(spring(stiffness = 700f)) +
                            scaleOut(spring(stiffness = 500f), targetScale = 0.96f)) using SizeTransform(clip = false)
                },
                label = "screen_transition"
            ) { screen ->
                when (screen) {
                    Screen.CHAT -> ChatScreen(
                        viewModel = viewModel,
                        onMenuClick = { scope.launch { drawerState.open() } },
                        onModelClick = { viewModel.refreshOpenCodeModels(); showModelPicker = true }
                    )
                    Screen.PROVIDERS -> ProvidersScreen(
                        onBack = { currentScreen = Screen.CHAT },
                        onSettingsClick = { currentScreen = Screen.SETTINGS },
                        viewModel = viewModel
                    )
                    Screen.SKILLS_MCP -> SkillsMcpScreen(onBack = { currentScreen = Screen.CHAT })
                    Screen.SETTINGS -> SettingsScreen(onBack = { currentScreen = Screen.CHAT }, viewModel = viewModel, onSignOut = onSignOut)
                }
            }

            AnimatedVisibility(
                visible = showModelPicker,
                enter = fadeIn() + scaleIn(
                    spring(dampingRatio = 0.8f, stiffness = 360f), initialScale = 0.92f
                ) + slideInHorizontally(
                    animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f)
                ) { it / 3 },
                exit = fadeOut() + slideOutHorizontally(
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 480f)
                ) { it / 4 }
            ) {
                ModelPickerScreen(
                    viewModel = viewModel,
                    onBack = { showModelPicker = false },
                    onModelSelected = {
                        viewModel.selectModel(it)
                        showModelPicker = false
                    }
                )
            }
        }
    }
}
