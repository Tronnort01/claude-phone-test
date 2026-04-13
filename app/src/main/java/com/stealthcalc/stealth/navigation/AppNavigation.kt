package com.stealthcalc.stealth.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.stealthcalc.auth.SecretCodeManager
import com.stealthcalc.auth.ui.SetupScreen
import com.stealthcalc.calculator.ui.CalculatorScreen
import com.stealthcalc.calculator.viewmodel.SecretCodeResult
import com.stealthcalc.notes.ui.NoteEditorScreen
import com.stealthcalc.notes.ui.NotesListScreen
import com.stealthcalc.stealth.ui.StealthHomeScreen
import com.stealthcalc.tasks.ui.GoalsScreen
import com.stealthcalc.browser.ui.BrowserScreen
import com.stealthcalc.browser.ui.LinkVaultScreen
import com.stealthcalc.recorder.ui.RecorderScreen
import com.stealthcalc.recorder.ui.RecordingsListScreen
import com.stealthcalc.tasks.ui.HabitTrackerScreen
import com.stealthcalc.tasks.ui.TaskDetailScreen
import com.stealthcalc.tasks.ui.TaskListScreen

sealed class AppScreen(val route: String) {
    data object Home : AppScreen("stealth_home")
    data object Notes : AppScreen("notes")
    data object NoteEditor : AppScreen("note_editor/{noteId}") {
        fun createRoute(noteId: String?) = "note_editor/${noteId ?: "new"}"
    }
    data object Tasks : AppScreen("tasks")
    data object TaskDetail : AppScreen("task_detail/{taskId}") {
        fun createRoute(taskId: String?) = "task_detail/${taskId ?: "new"}"
    }
    data object Habits : AppScreen("habits")
    data object Goals : AppScreen("goals")
    data object Recorder : AppScreen("recorder")
    data object RecordingsList : AppScreen("recordings_list")
    data object Browser : AppScreen("browser")
    data object LinkVault : AppScreen("link_vault")
    data object Settings : AppScreen("settings")
}

@Composable
fun AppRoot(
    isStealthUnlocked: Boolean,
    onStealthUnlocked: () -> Unit,
    onLockRequested: () -> Unit,
) {
    var showSetup by remember { mutableStateOf(false) }
    var setupCandidateCode by remember { mutableStateOf("") }

    AnimatedContent(
        targetState = when {
            showSetup -> ScreenState.Setup
            isStealthUnlocked -> ScreenState.Stealth
            else -> ScreenState.Calculator
        },
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "root_transition"
    ) { screenState ->
        when (screenState) {
            ScreenState.Calculator -> {
                CalculatorScreen(
                    onSecretCodeResult = { result ->
                        when (result) {
                            is SecretCodeResult.Unlocked -> onStealthUnlocked()
                            is SecretCodeResult.NeedsSetup -> {
                                setupCandidateCode = result.candidateCode
                                showSetup = true
                            }
                            SecretCodeResult.None -> {}
                        }
                    }
                )
            }
            ScreenState.Setup -> {
                SetupScreen(
                    suggestedCode = setupCandidateCode,
                    onSetupComplete = {
                        showSetup = false
                        onStealthUnlocked()
                    }
                )
            }
            ScreenState.Stealth -> {
                StealthNavGraph(
                    onLockRequested = onLockRequested
                )
            }
        }
    }
}

@Composable
fun StealthNavGraph(
    onLockRequested: () -> Unit
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = AppScreen.Home.route,
    ) {
        composable(AppScreen.Home.route) {
            StealthHomeScreen(
                onNavigateToNotes = { navController.navigate(AppScreen.Notes.route) },
                onNavigateToTasks = { navController.navigate(AppScreen.Tasks.route) },
                onNavigateToRecorder = { navController.navigate(AppScreen.Recorder.route) },
                onNavigateToBrowser = { navController.navigate(AppScreen.Browser.route) },
                onNavigateToSettings = { navController.navigate(AppScreen.Settings.route) },
                onLockRequested = onLockRequested,
            )
        }

        composable(AppScreen.Notes.route) {
            NotesListScreen(
                onBack = { navController.popBackStack() },
                onNoteClick = { noteId ->
                    navController.navigate(AppScreen.NoteEditor.createRoute(noteId))
                },
                onNewNote = {
                    navController.navigate(AppScreen.NoteEditor.createRoute(null))
                }
            )
        }

        composable(
            route = AppScreen.NoteEditor.route,
            arguments = listOf(
                androidx.navigation.navArgument("noteId") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = "new"
                }
            )
        ) {
            NoteEditorScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(AppScreen.Tasks.route) {
            TaskListScreen(
                onBack = { navController.popBackStack() },
                onTaskClick = { taskId ->
                    navController.navigate(AppScreen.TaskDetail.createRoute(taskId))
                },
                onNewTask = {
                    navController.navigate(AppScreen.TaskDetail.createRoute(null))
                },
                onNavigateToHabits = { navController.navigate(AppScreen.Habits.route) },
                onNavigateToGoals = { navController.navigate(AppScreen.Goals.route) },
            )
        }

        composable(
            route = AppScreen.TaskDetail.route,
            arguments = listOf(
                androidx.navigation.navArgument("taskId") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = "new"
                }
            )
        ) {
            TaskDetailScreen(onBack = { navController.popBackStack() })
        }

        composable(AppScreen.Habits.route) {
            HabitTrackerScreen(onBack = { navController.popBackStack() })
        }

        composable(AppScreen.Goals.route) {
            GoalsScreen(onBack = { navController.popBackStack() })
        }

        composable(AppScreen.Recorder.route) {
            RecorderScreen(
                onBack = { navController.popBackStack() },
                onNavigateToRecordings = { navController.navigate(AppScreen.RecordingsList.route) }
            )
        }

        composable(AppScreen.RecordingsList.route) {
            RecordingsListScreen(onBack = { navController.popBackStack() })
        }

        composable(AppScreen.Browser.route) {
            BrowserScreen(
                onBack = { navController.popBackStack() },
                onNavigateToVault = { navController.navigate(AppScreen.LinkVault.route) }
            )
        }

        composable(AppScreen.LinkVault.route) {
            LinkVaultScreen(
                onBack = { navController.popBackStack() },
                onOpenLink = { url ->
                    navController.popBackStack()
                    // The browser will load this URL on next compose
                }
            )
        }

        composable(AppScreen.Settings.route) {
            PlaceholderScreen(title = "Settings", onBack = { navController.popBackStack() })
        }
    }
}

private enum class ScreenState { Calculator, Setup, Stealth }
