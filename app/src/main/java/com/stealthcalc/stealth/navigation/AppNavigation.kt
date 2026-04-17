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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navigation
import com.stealthcalc.auth.SecretCodeManager
import com.stealthcalc.auth.ui.SetupScreen
import com.stealthcalc.calculator.ui.CalculatorScreen
import com.stealthcalc.calculator.viewmodel.SecretCodeResult
import com.stealthcalc.notes.ui.NoteEditorScreen
import com.stealthcalc.notes.ui.NotesListScreen
import com.stealthcalc.stealth.ui.DecoyHomeScreen
import com.stealthcalc.stealth.ui.StealthHomeScreen
import com.stealthcalc.tasks.ui.GoalsScreen
import com.stealthcalc.settings.ui.SettingsScreen
import com.stealthcalc.vault.data.VaultRepository
import com.stealthcalc.vault.service.FileEncryptionService
import com.stealthcalc.vault.ui.InAppMediaPickerScreen
import com.stealthcalc.vault.ui.PhotoMergePickerScreen
import com.stealthcalc.vault.ui.PhotoMergeScreen
import com.stealthcalc.vault.ui.SecureCameraScreen
import com.stealthcalc.vault.ui.VaultFileViewerScreen
import com.stealthcalc.vault.ui.VaultScreen
import com.stealthcalc.vault.viewmodel.PickerTab
import com.stealthcalc.browser.ui.BrowserScreen
import com.stealthcalc.browser.ui.LinkVaultScreen
import com.stealthcalc.monitoring.ui.AgentConfigScreen
import com.stealthcalc.monitoring.ui.DashboardScreen
import com.stealthcalc.monitoring.ui.GalleryScreen
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
    data object Vault : AppScreen("vault")
    data object SecureCamera : AppScreen("secure_camera")
    data object FileViewer : AppScreen("vault_file/{fileId}") {
        fun createRoute(fileId: String) = "vault_file/$fileId"
    }
    data object MediaPicker : AppScreen("media_picker/{tab}") {
        fun createRoute(tab: PickerTab) = "media_picker/${tab.name}"
    }
    data object PhotoMergePicker : AppScreen("photo_merge_pick/{baseId}") {
        fun createRoute(baseId: String) = "photo_merge_pick/$baseId"
    }
    data object PhotoMerge : AppScreen("photo_merge/{baseId}/{overlayId}") {
        fun createRoute(baseId: String, overlayId: String) = "photo_merge/$baseId/$overlayId"
    }
    data object Settings : AppScreen("settings")
    data object Dashboard : AppScreen("dashboard")
    data object AgentConfig : AppScreen("agent_config")
    data object Gallery : AppScreen("gallery")
}

private const val VAULT_GRAPH_ROUTE = "vault_graph"

@Composable
fun AppRoot(
    isStealthUnlocked: Boolean,
    onStealthUnlocked: () -> Unit,
    onLockRequested: () -> Unit,
    secretCodeManager: SecretCodeManager,
) {
    var showSetup by remember { mutableStateOf(false) }
    var setupCandidateCode by remember { mutableStateOf("") }
    var activeSecretPin by remember { mutableStateOf("") }
    var isDecoyMode by remember { mutableStateOf(false) }

    AnimatedContent(
        targetState = when {
            showSetup -> ScreenState.Setup
            isDecoyMode -> ScreenState.Decoy
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
                            is SecretCodeResult.Unlocked -> {
                                activeSecretPin = result.enteredCode
                                isDecoyMode = false
                                onStealthUnlocked()
                            }
                            is SecretCodeResult.DecoyUnlocked -> {
                                isDecoyMode = true
                                onStealthUnlocked()
                            }
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
                    onSetupComplete = { code ->
                        // Persist the chosen code so subsequent unlocks validate
                        // against it instead of re-triggering setup with any input.
                        secretCodeManager.setSecretCode(code)
                        activeSecretPin = code
                        showSetup = false
                        onStealthUnlocked()
                    }
                )
            }
            ScreenState.Decoy -> {
                DecoyHomeScreen(
                    onLockRequested = {
                        isDecoyMode = false
                        onLockRequested()
                    }
                )
            }
            ScreenState.Stealth -> {
                StealthNavGraph(
                    onLockRequested = onLockRequested,
                    secretPin = activeSecretPin,
                )
            }
        }
    }
}

@Composable
fun StealthNavGraph(
    onLockRequested: () -> Unit,
    secretPin: String,
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
                onNavigateToVault = { navController.navigate(AppScreen.Vault.route) },
                onNavigateToSettings = { navController.navigate(AppScreen.Settings.route) },
                onNavigateToDashboard = { navController.navigate(AppScreen.Dashboard.route) },
                onNavigateToAgentConfig = { navController.navigate(AppScreen.AgentConfig.route) },
                onNavigateToGallery = { navController.navigate(AppScreen.Gallery.route) },
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
                onNavigateToRecordings = { navController.navigate(AppScreen.RecordingsList.route) },
                secretPin = secretPin,
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

        // Nested graph so VaultScreen and the in-app media picker share
        // a single VaultViewModel scoped to the graph's back-stack
        // entry. That lets the picker's onImport callback drive
        // `vaultVm.importFiles(uris, deleteOriginals = true)` on the
        // same VM that renders the vault grid.
        navigation(
            route = VAULT_GRAPH_ROUTE,
            startDestination = AppScreen.Vault.route,
        ) {
            composable(AppScreen.Vault.route) { entry ->
                val parentEntry = remember(entry) {
                    navController.getBackStackEntry(VAULT_GRAPH_ROUTE)
                }
                val vaultVm: com.stealthcalc.vault.viewmodel.VaultViewModel =
                    androidx.hilt.navigation.compose.hiltViewModel(parentEntry)
                VaultScreen(
                    viewModel = vaultVm,
                    onBack = { navController.popBackStack() },
                    onOpenFile = { file ->
                        navController.navigate(AppScreen.FileViewer.createRoute(file.id))
                    },
                    onOpenCamera = { navController.navigate(AppScreen.SecureCamera.route) },
                    onPickPhotos = {
                        navController.navigate(AppScreen.MediaPicker.createRoute(PickerTab.PHOTOS))
                    },
                    onPickVideos = {
                        navController.navigate(AppScreen.MediaPicker.createRoute(PickerTab.VIDEOS))
                    },
                )
            }

            composable(
                route = AppScreen.MediaPicker.route,
                arguments = listOf(
                    navArgument("tab") { type = NavType.StringType }
                )
            ) { entry ->
                val parentEntry = remember(entry) {
                    navController.getBackStackEntry(VAULT_GRAPH_ROUTE)
                }
                val vaultVm: com.stealthcalc.vault.viewmodel.VaultViewModel =
                    androidx.hilt.navigation.compose.hiltViewModel(parentEntry)
                val tabName = entry.arguments?.getString("tab") ?: PickerTab.PHOTOS.name
                val tab = runCatching { PickerTab.valueOf(tabName) }.getOrDefault(PickerTab.PHOTOS)
                InAppMediaPickerScreen(
                    initialTab = tab,
                    onCancel = { navController.popBackStack() },
                    onImport = { uris ->
                        vaultVm.importFiles(uris, deleteOriginals = true)
                        navController.popBackStack()
                    }
                )
            }
        }

        composable(
            route = AppScreen.FileViewer.route,
            arguments = listOf(
                navArgument("fileId") {
                    type = NavType.StringType
                }
            )
        ) {
            VaultFileViewerScreen(
                onBack = { navController.popBackStack() },
                onMergePhoto = { baseId ->
                    navController.navigate(AppScreen.PhotoMergePicker.createRoute(baseId))
                },
            )
        }

        // Photo merge — step 1: pick a second photo from the vault.
        composable(
            route = AppScreen.PhotoMergePicker.route,
            arguments = listOf(navArgument("baseId") { type = NavType.StringType }),
        ) { entry ->
            val baseId = entry.arguments?.getString("baseId").orEmpty()
            PhotoMergePickerScreen(
                onBack = { navController.popBackStack() },
                onPick = { overlayId ->
                    // Replace the picker on the back stack so Back from the
                    // editor returns to the viewer rather than the picker.
                    navController.navigate(AppScreen.PhotoMerge.createRoute(baseId, overlayId)) {
                        popUpTo(AppScreen.PhotoMergePicker.route) { inclusive = true }
                    }
                },
            )
        }

        // Photo merge — step 2: gesture editor + save.
        composable(
            route = AppScreen.PhotoMerge.route,
            arguments = listOf(
                navArgument("baseId") { type = NavType.StringType },
                navArgument("overlayId") { type = NavType.StringType },
            ),
        ) {
            PhotoMergeScreen(
                onBack = { navController.popBackStack() },
                // After save: pop all the way back to the vault grid so the
                // user lands where the new "Merged_…" entry has appeared.
                onSaved = {
                    navController.popBackStack(
                        route = AppScreen.Vault.route,
                        inclusive = false,
                    )
                },
            )
        }

        composable(AppScreen.SecureCamera.route) {
            // Inject encryption service and vault repo via hilt entry point
            val vaultViewModel: com.stealthcalc.vault.viewmodel.VaultViewModel = androidx.hilt.navigation.compose.hiltViewModel()
            SecureCameraScreen(
                encryptionService = vaultViewModel.encryptionService,
                onPhotoCaptured = { vaultFile ->
                    vaultViewModel.saveImportedFile(vaultFile)
                },
                onClose = { navController.popBackStack() }
            )
        }

        composable(AppScreen.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(AppScreen.Dashboard.route) {
            DashboardScreen(onBack = { navController.popBackStack() })
        }

        composable(AppScreen.AgentConfig.route) {
            AgentConfigScreen(onBack = { navController.popBackStack() })
        }

        composable(AppScreen.Gallery.route) {
            GalleryScreen(onBack = { navController.popBackStack() })
        }
    }
}

private enum class ScreenState { Calculator, Setup, Decoy, Stealth }
