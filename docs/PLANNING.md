# StealthCalc — Stealth Productivity Suite

## Overview

A personal Android app disguised as a functional calculator. Enter a secret code on the calculator keypad to unlock a hidden productivity suite containing encrypted notes, a task planner, and a private browser/link vault.

**Target:** Personal use only (single user, not for distribution)
**Disguise:** Fully functional calculator — the stealth features are invisible to anyone casually opening the app.

---

## Tech Stack

| Layer | Technology | Why |
|-------|-----------|-----|
| Language | **Kotlin** | Modern, concise, first-class Android support |
| UI | **Jetpack Compose** | Declarative, fast iteration, Material 3 theming |
| Architecture | **MVVM + Clean Architecture** | Separation of concerns, testable |
| Local DB | **Room** (SQLite) | Type-safe, lifecycle-aware, encrypted via SQLCipher |
| Encryption | **SQLCipher + Jetpack Security (EncryptedSharedPreferences)** | AES-256 at rest for all sensitive data |
| DI | **Hilt** | Standard Android DI, minimal boilerplate |
| Navigation | **Compose Navigation** | Single-activity, type-safe routes |
| Browser | **Custom WebView wrapper** | Lightweight, no external dependencies |
| Build | **Gradle KTS** | Type-safe build scripts |
| Min SDK | **26 (Android 8.0)** | Covers 95%+ of devices, enables biometric APIs |
| Target SDK | **35** | Latest stable |

---

## Architecture

```
app/
├── calculator/        # The "front door" — fully functional calculator
├── auth/              # Secret code validation, biometric, auto-lock
├── core/              # Encryption, database, shared models, DI modules
├── notes/             # Secure notes vault
├── tasks/             # Task manager, reminders, habits
├── browser/           # Private browser and link vault
└── ui/theme/          # Material 3 theming, shared composables
```

**Pattern:** Single-Activity architecture. `MainActivity` hosts the calculator Compose UI. Unlocking transitions to a nested NavHost for the stealth features. From the OS perspective, the app is always "just a calculator."

---

## Feature Specifications

### 1. Calculator Disguise (Front Door)

**What the world sees:** A clean, Material 3 calculator with standard and scientific modes.

| Aspect | Detail |
|--------|--------|
| Functionality | Fully working calculator (add, subtract, multiply, divide, %, parentheses, memory) |
| Scientific mode | Trig, log, exponents, factorial — swipe or toggle to access |
| Secret code entry | Type a numeric sequence (e.g. `31415=`) on the calculator keypad, then press `=` |
| Code configuration | Set on first launch; changeable from stealth settings |
| Unlock animation | Subtle crossfade — no dramatic "vault opening" that could be shoulder-surfed |
| Wrong code behavior | Calculator just shows the computation result as normal — no error, no hint |
| App name & icon | "Calculator" with a standard calculator icon |
| Recents screen | Shows "Calculator" — the stealth UI is never visible in the app switcher |

**Security detail:** The secret code is stored as a salted SHA-256 hash in EncryptedSharedPreferences. The app never stores the plaintext code.

### 2. Authentication & Security Layer

| Feature | Detail |
|---------|--------|
| First-time setup | On first secret-code entry, prompt to set: (1) secret code, (2) optional biometric |
| Biometric unlock | After initial code entry, optionally use fingerprint/face to re-enter within a session |
| Auto-lock | Returns to calculator after 30s in background (configurable: 10s / 30s / 1m / 5m) |
| Panic button | Shake device or press back 3x rapidly → instantly returns to calculator view |
| Failed attempts | After 5 wrong codes in a row, add a 30-second cooldown (no visible indicator) |
| Screenshot blocking | `FLAG_SECURE` on all stealth screens — prevents screenshots and screen recording |
| App switcher | Stealth screens show a blank/calculator preview in the recent apps list |
| Encryption | All Room databases encrypted with SQLCipher; key derived from device + user secret |

### 3. Secure Notes Vault

A private, encrypted note-taking system.

| Feature | Detail |
|---------|--------|
| Note types | Plain text, rich text (bold/italic/lists), checklists |
| Organization | Folders + tags + color labels |
| Search | Full-text search across all notes (decrypted in-memory) |
| Favorites | Pin important notes to top |
| Sort | By date created, date modified, title, or manual order |
| Attachments | Photos from camera/gallery, stored encrypted in app-private storage |
| Trash | Soft-delete with 30-day auto-purge |
| Import/Export | Encrypted backup to local file (AES-256 ZIP); import from backup |
| Editor | Compose-based rich text editor with formatting toolbar |

### 4. Task Manager & Planner

A full personal productivity system.

| Feature | Detail |
|---------|--------|
| Tasks | Title, description, due date, priority (low/med/high/urgent), subtasks |
| Lists | Multiple task lists (e.g. "Work", "Personal", "Projects") |
| Reminders | Local notifications at specific times (notifications appear as "Calculator" to stay covert) |
| Recurring tasks | Daily, weekly, monthly, custom intervals |
| Habits | Daily habit tracker with streaks and a simple calendar heatmap |
| Goals | Long-term goals with milestones and progress percentage |
| Calendar view | Month/week/day views showing tasks and reminders |
| Quick add | Floating action button for rapid task entry |
| Completion stats | Simple dashboard — tasks completed this week/month, habit streaks |

**Covert notifications:** Reminders show as "Calculation complete" or a customizable neutral message. Tapping opens the calculator (user must re-enter code to see the actual task).

### 5. Covert Audio & Video Recorder

A hidden recorder (audio AND video) with a deceptive "sign-in" screen overlay.

| Feature | Detail |
|---------|--------|
| Activation | Trigger from stealth home, or via a quick-action (e.g. long-press calculator `0` key) |
| Recording modes | **Audio only** (microphone) or **Video** (front/back camera + microphone) — user picks before starting |
| Cover screen | While recording, the display shows a **fake sign-in page** — dark/black background with a generic email + password form and a "Sign In" button. Looks like any app's login screen. |
| Cover behavior | The fake sign-in form is interactive but does nothing — tapping "Sign In" shows a fake "Incorrect password" shake animation. Anyone glancing at the phone sees a boring sign-in page. |
| Video + cover | Camera records via a 1x1 pixel transparent preview surface behind the fake sign-in UI — the camera feed is never visible on screen |
| Camera selection | Front or back camera, switchable before recording starts |
| Recording indicator | No visible recording indicator on the cover screen. The status bar dot (required by Android 12+) is unavoidable but blends in. |
| Controls | Tap a hidden region (e.g. top-left corner 3x) or use the secret calculator code to return to the real UI and stop recording. |
| Audio format | AAC/M4A via MediaRecorder — good quality, small file size |
| Video format | H.264/MP4 via MediaRecorder — 720p default, configurable quality |
| Storage | Recordings saved to encrypted app-private storage with timestamps |
| Playback | Built-in media player in the stealth UI — list of recordings with date, duration; video thumbnail preview for video files, waveform for audio |
| File management | Rename, delete, export (decrypted copy to Downloads) |
| Background recording | Audio continues even if screen turns off. Video requires screen on but cover screen hides the real UI. |
| Battery | Show estimated battery drain in the recorder UI; auto-stop option after N hours |
| Max duration | Configurable limit (default: 4 hours audio, 1 hour video) to prevent accidental all-day recording |

**Cover screen design:**
```
┌─────────────────────────┐
│                         │
│       [App Logo]        │   ← Generic/neutral icon
│                         │
│   ┌─────────────────┐   │
│   │  Email           │   │   ← Fake input field
│   └─────────────────┘   │
│                         │
│   ┌─────────────────────┐│
│   │  Password        ●●●││   ← Fake password field
│   └─────────────────────┘│
│                         │
│   ┌─────────────────┐   │
│   │    Sign In       │   │   ← Does nothing real
│   └─────────────────┘   │
│                         │
│   Forgot password?      │   ← Decorative link
│                         │
│                (black)  │
└─────────────────────────┘
```

### 6. Private Browser & Link Vault

A minimal private browser plus bookmark/link management.

| Feature | Detail |
|---------|--------|
| WebView browser | Basic navigation (back, forward, refresh, URL bar) |
| No history persistence | Browsing history cleared on session end (or optionally kept encrypted) |
| Saved links | Bookmark URLs with title, tags, and notes |
| Collections | Organize links into collections (e.g. "Research", "Read Later") |
| Web clipper | Save page title + URL + optional excerpt |
| Ad blocking | Basic content blocker via WebView URL filtering |
| Downloads | Files saved to encrypted app-private storage |
| Reader mode | Simplified article view (strip ads/nav, just content) |
| Search | Search across saved links by title, URL, tags, or notes |

---

## Screen Map & Navigation

```
[Calculator Screen]
        |
    (secret code)
        |
   [Stealth Home]
   / |    |     \
[Notes][Tasks][Recorder][Browser]
    \   |    |    /
      [Settings]
```

### Screen Inventory

| Screen | Description |
|--------|-------------|
| `CalculatorScreen` | Main calculator UI — always the entry point |
| `SetupScreen` | First-time setup: set secret code, optional biometric |
| `StealthHomeScreen` | Dashboard after unlock — quick access to all modules + recent items |
| `NotesListScreen` | Grid/list of notes with search bar and folder nav |
| `NoteEditorScreen` | Rich text editor for a single note |
| `TaskListScreen` | Task lists with filters (today, upcoming, by list, completed) |
| `TaskDetailScreen` | Single task view/edit with subtasks |
| `HabitTrackerScreen` | Daily habits with streak calendar |
| `GoalsScreen` | Long-term goals with milestone progress |
| `BrowserScreen` | WebView with navigation controls |
| `LinkVaultScreen` | Saved links organized by collection |
| `RecorderScreen` | Start/stop recording with fake sign-in cover screen |
| `FakeSignInScreen` | Black-background fake login page shown during active recording |
| `RecordingsListScreen` | List of saved recordings with playback controls |
| `SettingsScreen` | Change secret code, auto-lock timer, biometric toggle, backup/restore, theme |

---

## Data Models

### Core Entities (Room + SQLCipher)

```kotlin
// --- Notes ---
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String,          // Rich text stored as markdown or HTML
    val folderId: String?,
    val color: Int?,              // Label color (ARGB int)
    val isPinned: Boolean = false,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "note_folders")
data class NoteFolder(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val parentId: String? = null,
    val sortOrder: Int = 0
)

@Entity(tableName = "note_tags")
data class NoteTag(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val color: Int?
)

@Entity(tableName = "note_tag_cross_ref", primaryKeys = ["noteId", "tagId"])
data class NoteTagCrossRef(
    val noteId: String,
    val tagId: String
)

@Entity(tableName = "note_attachments")
data class NoteAttachment(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val noteId: String,
    val fileName: String,
    val encryptedPath: String,   // Path in app-private encrypted storage
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Long = System.currentTimeMillis()
)

// --- Tasks ---
@Entity(tableName = "task_lists")
data class TaskList(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val color: Int?,
    val sortOrder: Int = 0
)

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val listId: String,
    val title: String,
    val description: String? = null,
    val isCompleted: Boolean = false,
    val priority: Priority = Priority.MEDIUM,
    val dueDate: Long? = null,
    val reminderTime: Long? = null,
    val parentTaskId: String? = null,  // For subtasks
    val recurrence: Recurrence? = null,
    val sortOrder: Int = 0,
    val completedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class Priority { LOW, MEDIUM, HIGH, URGENT }

data class Recurrence(
    val type: RecurrenceType,      // DAILY, WEEKLY, MONTHLY, CUSTOM
    val interval: Int = 1,         // Every N days/weeks/months
    val daysOfWeek: Set<Int>? = null  // For weekly: which days
)

// --- Habits ---
@Entity(tableName = "habits")
data class Habit(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String? = null,
    val color: Int?,
    val targetDaysPerWeek: Int = 7,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "habit_entries", primaryKeys = ["habitId", "date"])
data class HabitEntry(
    val habitId: String,
    val date: Long,               // Date as epoch millis (midnight)
    val completed: Boolean = true
)

// --- Goals ---
@Entity(tableName = "goals")
data class Goal(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String? = null,
    val targetDate: Long? = null,
    val progressPercent: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "milestones")
data class Milestone(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val goalId: String,
    val title: String,
    val isCompleted: Boolean = false,
    val sortOrder: Int = 0
)

// --- Browser / Links ---
@Entity(tableName = "link_collections")
data class LinkCollection(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val color: Int?,
    val sortOrder: Int = 0
)

@Entity(tableName = "saved_links")
data class SavedLink(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val collectionId: String?,
    val url: String,
    val title: String,
    val excerpt: String? = null,
    val notes: String? = null,
    val faviconPath: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "link_tags")
data class LinkTag(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String
)

@Entity(tableName = "link_tag_cross_ref", primaryKeys = ["linkId", "tagId"])
data class LinkTagCrossRef(
    val linkId: String,
    val tagId: String
)

// --- Recordings (Audio & Video) ---
@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,                // User-editable name (default: "Recording YYYY-MM-DD HH:mm")
    val encryptedFilePath: String,    // Path in app-private encrypted storage
    val type: RecordingType,          // AUDIO or VIDEO
    val durationMs: Long,             // Recording duration in milliseconds
    val fileSizeBytes: Long,          // File size
    val format: String = "m4a",       // "m4a" for audio, "mp4" for video
    val thumbnailPath: String? = null, // Encrypted thumbnail for video recordings
    val cameraFacing: CameraFacing? = null, // FRONT or BACK (video only)
    val createdAt: Long = System.currentTimeMillis()
)

enum class RecordingType { AUDIO, VIDEO }
enum class CameraFacing { FRONT, BACK }
```

---

## Security Architecture

```
┌─────────────────────────────────────────────┐
│              Android OS                      │
│  ┌───────────────────────────────────────┐  │
│  │  StealthCalc App Process              │  │
│  │                                       │  │
│  │  ┌─────────────┐  ┌───────────────┐  │  │
│  │  │ Calculator   │  │ Auth Layer    │  │  │
│  │  │ (Public UI)  │→ │ Code + Bio    │  │  │
│  │  └─────────────┘  └───────┬───────┘  │  │
│  │                           │           │  │
│  │                    ┌──────▼──────┐    │  │
│  │                    │ Stealth UI  │    │  │
│  │                    │ (FLAG_SECURE)│   │  │
│  │                    └──────┬──────┘    │  │
│  │                           │           │  │
│  │  ┌────────────────────────▼────────┐  │  │
│  │  │     SQLCipher Encrypted DB      │  │  │
│  │  │  (AES-256, key from Keystore)   │  │  │
│  │  └────────────────────────────────┘  │  │
│  │                                       │  │
│  │  ┌────────────────────────────────┐  │  │
│  │  │  EncryptedSharedPreferences    │  │  │
│  │  │  (settings, hashed secret code)│  │  │
│  │  └────────────────────────────────┘  │  │
│  │                                       │  │
│  │  ┌────────────────────────────────┐  │  │
│  │  │  Encrypted File Storage        │  │  │
│  │  │  (attachments, downloads)      │  │  │
│  │  └────────────────────────────────┘  │  │
│  └───────────────────────────────────────┘  │
└─────────────────────────────────────────────┘
```

**Key derivation:** The SQLCipher database key is derived using PBKDF2 from a secret stored in the Android Keystore (hardware-backed where available). The user's secret code is NOT the database key — the database unlocks automatically when the app starts, but the stealth UI is gated behind the code. This means the encrypted data is protected even if the device storage is dumped.

---

## Implementation Phases

### Phase 1: Foundation (Calculator + Auth)
- [ ] Android project scaffolding (Gradle KTS, Hilt, Compose)
- [ ] Calculator UI with full arithmetic
- [ ] Scientific calculator mode
- [ ] Secret code detection engine
- [ ] First-time setup flow
- [ ] Auth layer (code validation, biometric)
- [ ] Auto-lock on background
- [ ] FLAG_SECURE and app-switcher protection
- [ ] Stealth home dashboard (shell)

### Phase 2: Secure Notes
- [ ] Room + SQLCipher database setup
- [ ] Note CRUD operations
- [ ] Rich text editor (Compose)
- [ ] Folders and tags
- [ ] Full-text search
- [ ] Photo attachments (encrypted storage)
- [ ] Trash with auto-purge

### Phase 3: Task Manager
- [ ] Task lists and task CRUD
- [ ] Priority, due dates, subtasks
- [ ] Local notification reminders (covert messages)
- [ ] Recurring tasks
- [ ] Calendar view (month/week)
- [ ] Habit tracker with streaks
- [ ] Goals and milestones
- [ ] Completion statistics dashboard

### Phase 4: Covert Audio & Video Recorder
- [ ] MediaRecorder service for background audio capture
- [ ] Video recording via CameraX with hidden 1x1 preview surface
- [ ] Front/back camera selection for video mode
- [ ] Fake sign-in cover screen (black background, dummy email/password form)
- [ ] Cover screen interactions (fake "Incorrect password" animation)
- [ ] Hidden exit gesture (triple-tap corner) to return to stealth UI
- [ ] Encrypted audio + video file storage
- [ ] Recordings list with playback (waveform for audio, video player for video)
- [ ] Video thumbnail generation (encrypted)
- [ ] Recording management (rename, delete, export)
- [ ] Auto-stop timer and battery awareness
- [ ] Quick-launch from calculator (long-press `0`)

### Phase 5: Private Browser
- [ ] WebView wrapper with navigation controls
- [ ] Link vault with collections and tags
- [ ] Web clipper (save from browser)
- [ ] Basic ad/tracker blocking
- [ ] Encrypted downloads
- [ ] Reader mode
- [ ] Session-only browsing (no persistent history)

### Phase 6: Polish & Hardening
- [ ] Encrypted backup/restore
- [ ] Panic button (shake/triple-back)
- [ ] Theming (dark/light/AMOLED black)
- [ ] Notification customization
- [ ] Performance optimization
- [ ] ProGuard/R8 obfuscation rules
- [ ] Edge case hardening (process death, low memory, etc.)

---

## Build & Deployment

Since this is personal use only:

- **Build:** Local Android Studio builds → generate signed APK
- **Install:** Direct APK sideload or ADB install
- **Updates:** Rebuild and reinstall manually
- **No Play Store** — no review process, no content policies to worry about
- **Signing:** Generate a personal keystore; keep it backed up

---

## Dependencies (build.gradle.kts)

```kotlin
// Core
implementation("androidx.core:core-ktx:1.13.1")
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
implementation("androidx.activity:activity-compose:1.9.1")

// Compose
implementation(platform("androidx.compose:compose-bom:2024.08.00"))
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.ui:ui-graphics")
implementation("androidx.compose.material3:material3")
implementation("androidx.compose.material:material-icons-extended")
implementation("androidx.navigation:navigation-compose:2.7.7")

// Room + SQLCipher
implementation("androidx.room:room-runtime:2.6.1")
implementation("androidx.room:room-ktx:2.6.1")
ksp("androidx.room:room-compiler:2.6.1")
implementation("net.zetetic:android-database-sqlcipher:4.5.4")

// Hilt
implementation("com.google.dagger:hilt-android:2.51.1")
ksp("com.google.dagger:hilt-android-compiler:2.51.1")
implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

// Security
implementation("androidx.security:security-crypto:1.1.0-alpha06")
implementation("androidx.biometric:biometric:1.2.0-alpha05")

// WebView
implementation("androidx.webkit:webkit:1.11.0")

// Notifications
implementation("androidx.work:work-runtime-ktx:2.9.1")
```

---

## Key Design Decisions

1. **Calculator-first UX**: The calculator must feel genuine. No lag, no odd UI elements, no "uncanny valley." Anyone picking up the phone should think it's just a calculator.

2. **No network dependency**: All data is local and encrypted. No cloud sync, no analytics, no telemetry. The app works fully offline.

3. **Single Activity**: The entire app is one Activity with Compose navigation. This means the OS only sees one "calculator" activity — there's no secondary activity that could leak stealth info in process lists.

4. **Covert notifications**: All reminders appear as innocent calculator-related notifications. The actual content is only visible after unlocking.

5. **Defensive lifecycle handling**: The app aggressively returns to calculator mode on any lifecycle disruption (process death, configuration change, etc.). Better to require re-authentication than to accidentally expose content.

6. **No external sharing**: No share-to or share-from intents that could expose the app's true nature. Content stays inside.
