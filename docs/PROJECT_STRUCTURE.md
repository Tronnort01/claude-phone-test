# StealthCalc — Project Structure

## Full Directory Layout

```
StealthCalc/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/stealthcalc/
│       │   │   ├── StealthCalcApp.kt              # Application class (Hilt entry)
│       │   │   ├── MainActivity.kt                 # Single activity host
│       │   │   │
│       │   │   ├── calculator/                     # === CALCULATOR MODULE ===
│       │   │   │   ├── ui/
│       │   │   │   │   ├── CalculatorScreen.kt     # Main calculator composable
│       │   │   │   │   ├── CalculatorKeypad.kt     # Keypad grid (digits, ops)
│       │   │   │   │   ├── CalculatorDisplay.kt    # Display with history
│       │   │   │   │   └── ScientificPanel.kt      # Scientific functions panel
│       │   │   │   ├── engine/
│       │   │   │   │   ├── CalcEngine.kt           # Expression parser & evaluator
│       │   │   │   │   ├── CalcTokenizer.kt        # Input tokenizer
│       │   │   │   │   └── MathFunctions.kt        # Trig, log, etc.
│       │   │   │   └── viewmodel/
│       │   │   │       └── CalculatorViewModel.kt   # UI state + secret code detection
│       │   │   │
│       │   │   ├── auth/                           # === AUTH MODULE ===
│       │   │   │   ├── SecretCodeManager.kt        # Hash, validate, change secret code
│       │   │   │   ├── BiometricManager.kt         # Fingerprint/face unlock wrapper
│       │   │   │   ├── AutoLockManager.kt          # Background timer, lifecycle observer
│       │   │   │   ├── PanicHandler.kt             # Shake detection, triple-back handler
│       │   │   │   └── ui/
│       │   │   │       ├── SetupScreen.kt          # First-time secret code setup
│       │   │   │       └── BiometricPrompt.kt      # Biometric re-auth composable
│       │   │   │
│       │   │   ├── core/                           # === CORE MODULE ===
│       │   │   │   ├── data/
│       │   │   │   │   ├── StealthDatabase.kt      # Room database definition
│       │   │   │   │   ├── DatabaseProvider.kt     # SQLCipher encrypted DB factory
│       │   │   │   │   └── Converters.kt           # Room type converters
│       │   │   │   ├── encryption/
│       │   │   │   │   ├── CryptoManager.kt        # AES encryption/decryption utils
│       │   │   │   │   ├── KeyStoreManager.kt      # Android Keystore operations
│       │   │   │   │   └── FileEncryptor.kt        # Encrypt/decrypt file streams
│       │   │   │   ├── di/
│       │   │   │   │   ├── AppModule.kt            # Hilt app-scoped bindings
│       │   │   │   │   ├── DatabaseModule.kt       # DB and DAO providers
│       │   │   │   │   └── RepositoryModule.kt     # Repository bindings
│       │   │   │   ├── notifications/
│       │   │   │   │   ├── CovertNotificationManager.kt  # Disguised notifications
│       │   │   │   │   └── ReminderWorker.kt       # WorkManager for scheduled reminders
│       │   │   │   └── util/
│       │   │   │       ├── DateUtils.kt            # Date formatting helpers
│       │   │   │       └── Extensions.kt           # Common Kotlin extensions
│       │   │   │
│       │   │   ├── stealth/                        # === STEALTH HOME ===
│       │   │   │   ├── ui/
│       │   │   │   │   └── StealthHomeScreen.kt    # Dashboard after unlock
│       │   │   │   └── navigation/
│       │   │   │       └── StealthNavGraph.kt      # NavHost for all stealth screens
│       │   │   │
│       │   │   ├── notes/                          # === NOTES MODULE ===
│       │   │   │   ├── data/
│       │   │   │   │   ├── NoteDao.kt              # Room DAO for notes
│       │   │   │   │   ├── NoteFolderDao.kt        # Room DAO for folders
│       │   │   │   │   ├── NoteTagDao.kt           # Room DAO for tags
│       │   │   │   │   └── NoteRepository.kt       # Repository layer
│       │   │   │   ├── model/
│       │   │   │   │   ├── Note.kt                 # Note entity
│       │   │   │   │   ├── NoteFolder.kt           # Folder entity
│       │   │   │   │   ├── NoteTag.kt              # Tag entity + cross-ref
│       │   │   │   │   └── NoteAttachment.kt       # Attachment entity
│       │   │   │   ├── ui/
│       │   │   │   │   ├── NotesListScreen.kt      # Notes grid/list view
│       │   │   │   │   ├── NoteEditorScreen.kt     # Rich text editor
│       │   │   │   │   ├── NoteEditorToolbar.kt    # Formatting toolbar
│       │   │   │   │   ├── FolderDrawer.kt         # Folder navigation drawer
│       │   │   │   │   └── NoteCard.kt             # Single note card composable
│       │   │   │   └── viewmodel/
│       │   │   │       ├── NotesListViewModel.kt   # List state management
│       │   │   │       └── NoteEditorViewModel.kt  # Editor state management
│       │   │   │
│       │   │   ├── tasks/                          # === TASKS MODULE ===
│       │   │   │   ├── data/
│       │   │   │   │   ├── TaskDao.kt              # Room DAO for tasks
│       │   │   │   │   ├── TaskListDao.kt          # Room DAO for task lists
│       │   │   │   │   ├── HabitDao.kt             # Room DAO for habits
│       │   │   │   │   ├── GoalDao.kt              # Room DAO for goals
│       │   │   │   │   └── TaskRepository.kt       # Repository layer
│       │   │   │   ├── model/
│       │   │   │   │   ├── Task.kt                 # Task entity
│       │   │   │   │   ├── TaskList.kt             # TaskList entity
│       │   │   │   │   ├── Habit.kt                # Habit + HabitEntry entities
│       │   │   │   │   ├── Goal.kt                 # Goal + Milestone entities
│       │   │   │   │   ├── Priority.kt             # Priority enum
│       │   │   │   │   └── Recurrence.kt           # Recurrence data class + type converter
│       │   │   │   ├── ui/
│       │   │   │   │   ├── TaskListScreen.kt       # Task list with filters
│       │   │   │   │   ├── TaskDetailScreen.kt     # Task view/edit
│       │   │   │   │   ├── TaskCard.kt             # Single task card
│       │   │   │   │   ├── CalendarScreen.kt       # Month/week/day calendar
│       │   │   │   │   ├── HabitTrackerScreen.kt   # Habit list + heatmap
│       │   │   │   │   ├── GoalsScreen.kt          # Goals with progress bars
│       │   │   │   │   └── StatsScreen.kt          # Completion statistics
│       │   │   │   └── viewmodel/
│       │   │   │       ├── TaskListViewModel.kt    # Task list state
│       │   │   │       ├── TaskDetailViewModel.kt  # Single task state
│       │   │   │       ├── HabitViewModel.kt       # Habit tracking state
│       │   │   │       └── GoalViewModel.kt        # Goal tracking state
│       │   │   │
│       │   │   ├── recorder/                       # === VOICE RECORDER MODULE ===
│       │   │   │   ├── data/
│       │   │   │   │   ├── RecordingDao.kt          # Room DAO for recordings
│       │   │   │   │   └── RecorderRepository.kt    # Repository layer
│       │   │   │   ├── model/
│       │   │   │   │   └── Recording.kt             # Recording entity
│       │   │   │   ├── service/
│       │   │   │   │   ├── AudioRecorderService.kt  # Foreground service for recording
│       │   │   │   │   └── AudioEncryptor.kt        # Stream encryption for audio files
│       │   │   │   ├── ui/
│       │   │   │   │   ├── RecorderScreen.kt        # Start/stop + status controls
│       │   │   │   │   ├── FakeSignInScreen.kt      # Black-bg fake login cover screen
│       │   │   │   │   ├── RecordingsListScreen.kt  # Saved recordings with playback
│       │   │   │   │   ├── AudioPlayerBar.kt        # Waveform + seek + speed controls
│       │   │   │   │   └── WaveformView.kt          # Audio waveform visualization
│       │   │   │   └── viewmodel/
│       │   │   │       ├── RecorderViewModel.kt     # Recording state management
│       │   │   │       └── PlaybackViewModel.kt     # Playback state management
│       │   │   │
│       │   │   ├── browser/                        # === BROWSER MODULE ===
│       │   │   │   ├── data/
│       │   │   │   │   ├── SavedLinkDao.kt         # Room DAO for links
│       │   │   │   │   ├── LinkCollectionDao.kt    # Room DAO for collections
│       │   │   │   │   └── BrowserRepository.kt    # Repository layer
│       │   │   │   ├── model/
│       │   │   │   │   ├── SavedLink.kt            # Link entity
│       │   │   │   │   ├── LinkCollection.kt       # Collection entity
│       │   │   │   │   └── LinkTag.kt              # Tag entity + cross-ref
│       │   │   │   ├── engine/
│       │   │   │   │   ├── AdBlocker.kt            # URL-based content blocker
│       │   │   │   │   └── ReaderModeParser.kt     # HTML → clean article text
│       │   │   │   ├── ui/
│       │   │   │   │   ├── BrowserScreen.kt        # WebView with nav controls
│       │   │   │   │   ├── BrowserToolbar.kt       # URL bar + actions
│       │   │   │   │   ├── LinkVaultScreen.kt      # Saved links browser
│       │   │   │   │   ├── LinkCard.kt             # Single link card
│       │   │   │   │   └── ReaderModeScreen.kt     # Clean article view
│       │   │   │   └── viewmodel/
│       │   │   │       ├── BrowserViewModel.kt     # WebView state
│       │   │   │       └── LinkVaultViewModel.kt   # Link vault state
│       │   │   │
│       │   │   ├── settings/                       # === SETTINGS ===
│       │   │   │   ├── ui/
│       │   │   │   │   └── SettingsScreen.kt       # All app settings
│       │   │   │   ├── BackupManager.kt            # Encrypted backup/restore
│       │   │   │   └── viewmodel/
│       │   │   │       └── SettingsViewModel.kt    # Settings state
│       │   │   │
│       │   │   └── ui/                             # === SHARED UI ===
│       │   │       ├── theme/
│       │   │       │   ├── Theme.kt                # Material 3 theme (light/dark/black)
│       │   │       │   ├── Color.kt                # Color palette
│       │   │       │   ├── Type.kt                 # Typography
│       │   │       │   └── Shape.kt                # Shape definitions
│       │   │       └── components/
│       │   │           ├── StealthBottomBar.kt     # Bottom navigation bar
│       │   │           ├── SearchBar.kt            # Reusable search bar
│       │   │           ├── ConfirmDialog.kt        # Confirmation dialog
│       │   │           ├── EmptyState.kt           # Empty state placeholder
│       │   │           └── TagChip.kt              # Tag display chip
│       │   │
│       │   └── res/
│       │       ├── drawable/
│       │       │   └── ic_calculator.xml           # Calculator app icon
│       │       ├── mipmap-*/
│       │       │   └── ic_launcher.*               # Launcher icons (calculator)
│       │       ├── values/
│       │       │   ├── strings.xml                 # App name = "Calculator"
│       │       │   ├── colors.xml
│       │       │   └── themes.xml
│       │       └── xml/
│       │           ├── backup_rules.xml            # Disable cloud backup
│       │           └── network_security_config.xml
│       │
│       └── test/                                   # Unit tests
│           └── java/com/stealthcalc/
│               ├── calculator/
│               │   └── CalcEngineTest.kt
│               ├── auth/
│               │   └── SecretCodeManagerTest.kt
│               └── ...
│
├── build.gradle.kts                                # Project-level build config
├── settings.gradle.kts                             # Project settings
├── gradle.properties                               # Gradle properties
├── gradle/
│   └── libs.versions.toml                          # Version catalog
└── local.properties                                # Local SDK path (not committed)
```

## AndroidManifest.xml — Key Points

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Permissions -->
    <uses-permission android:name="android.permission.INTERNET" />           <!-- Browser -->
    <uses-permission android:name="android.permission.CAMERA" />             <!-- Note attachments -->
    <uses-permission android:name="android.permission.VIBRATE" />            <!-- Haptic feedback -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" /> <!-- Reminders (API 33+) -->
    <uses-permission android:name="android.permission.USE_BIOMETRIC" />      <!-- Biometric auth -->
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" /> <!-- Reschedule reminders -->
    <uses-permission android:name="android.permission.RECORD_AUDIO" />       <!-- Voice recorder -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" /> <!-- Background recording -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" /> <!-- API 34+ -->

    <application
        android:name=".StealthCalcApp"
        android:label="Calculator"                    <!-- Disguised name -->
        android:icon="@mipmap/ic_launcher"            <!-- Calculator icon -->
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:allowBackup="false"                   <!-- No cloud backup! -->
        android:fullBackupContent="false"
        android:theme="@style/Theme.StealthCalc">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize"
            android:screenOrientation="portrait">     <!-- Lock portrait for calculator feel -->
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Voice recorder foreground service -->
        <service
            android:name=".recorder.service.AudioRecorderService"
            android:exported="false"
            android:foregroundServiceType="microphone" />

        <!-- Boot receiver to reschedule reminders -->
        <receiver
            android:name=".core.notifications.BootReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>

    </application>
</manifest>
```

## Package Responsibilities

| Package | Responsibility | Key Classes |
|---------|---------------|-------------|
| `calculator` | Public-facing calculator UI and math engine | `CalculatorScreen`, `CalcEngine`, `CalculatorViewModel` |
| `auth` | Secret code, biometric, auto-lock, panic | `SecretCodeManager`, `BiometricManager`, `AutoLockManager` |
| `core.data` | Room DB with SQLCipher encryption | `StealthDatabase`, `DatabaseProvider` |
| `core.encryption` | Encryption primitives and key management | `CryptoManager`, `KeyStoreManager`, `FileEncryptor` |
| `core.di` | Hilt dependency injection modules | `AppModule`, `DatabaseModule` |
| `core.notifications` | Covert notification system | `CovertNotificationManager`, `ReminderWorker` |
| `stealth` | Dashboard and navigation graph | `StealthHomeScreen`, `StealthNavGraph` |
| `notes` | Encrypted notes with rich text | `NoteDao`, `NoteRepository`, `NoteEditorScreen` |
| `tasks` | Tasks, habits, goals, calendar | `TaskDao`, `HabitDao`, `GoalDao`, `CalendarScreen` |
| `recorder` | Covert voice recorder with fake sign-in cover | `AudioRecorderService`, `FakeSignInScreen`, `RecorderViewModel` |
| `browser` | WebView browser and link vault | `BrowserScreen`, `AdBlocker`, `LinkVaultScreen` |
| `settings` | App configuration and backup | `SettingsScreen`, `BackupManager` |
| `ui.theme` | Material 3 theme definitions | `Theme.kt`, `Color.kt` |
| `ui.components` | Shared reusable composables | `StealthBottomBar`, `SearchBar`, `TagChip` |
