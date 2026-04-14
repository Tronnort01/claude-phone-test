# StealthCalc — Project Context

## What This Is
A personal stealth productivity Android app disguised as a calculator. Enter a secret PIN on the calculator keypad + press `=` to unlock a hidden suite of encrypted tools. Built with Kotlin + Jetpack Compose + Hilt + Room/SQLCipher.

## Tech Stack
- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **Architecture:** MVVM + Clean Architecture, single-activity
- **Database:** Room + SQLCipher (AES-256 encrypted)
- **DI:** Hilt
- **Browser:** Mozilla GeckoView (Firefox engine, NOT Chrome)
- **Camera:** CameraX
- **Build:** Gradle KTS, GitHub Actions for APK builds

## Modules (all complete)
1. **Calculator** — Fully working calculator with secret PIN detection (`calculator/`)
2. **Auth** — Secret code (SHA-256 hashed), auto-lock, biometric, panic handler, decoy PIN (`auth/`). PIN is persisted on first setup by the `onSetupComplete` callback in `stealth/navigation/AppNavigation.kt` — threads `SecretCodeManager` from `MainActivity` (see Fix 2).
3. **Notes** — Encrypted notes with folders, tags, rich text editor, secure clipboard copy (`notes/`)
4. **Tasks** — Task lists, priorities, habits with streaks, goals with milestones (`tasks/`)
5. **Recorder** — Audio + video recording with fake phone lock screen cover (`recorder/`). Recordings encrypt into the vault on stop (Fix 4): plaintext MP4/M4A written to `filesDir/recordings/`, then `FileEncryptionService.encryptLocalFile` produces the `VaultFile`, plaintext is deleted, `Recording.vaultFileId` points at the vault row. `FakeLockScreen` now swallows system Back and holds `FLAG_KEEP_SCREEN_ON` (Fix 5).
6. **Browser** — GeckoView (Firefox), Enhanced Tracking Protection, no cookies, private mode (`browser/`)
7. **Vault** — AES-256 encrypted media storage, secure camera, sort by date/size/name (`vault/`). Thumbnails decrypt via `FileEncryptionService.decryptThumbnail` on a background dispatcher (Fix 3a). `VaultFileViewerScreen` renders photo/video/audio/docs with framework-only renderers (Fix 3b). Gallery originals are deleted via `MediaStore.createDeleteRequest` on API 30+ / `RecoverableSecurityException.userAction` on API 29 (Fix 3c).
8. **Settings** — Change PIN, decoy PIN, biometric toggle, auto-lock timer, panic shake/back, screenshot blocking, **Export crash log** (Fix 1) (`settings/`)

## Key Architecture Decisions
- Single Activity (`MainActivity`) — OS only sees "Calculator"
- `AppRoot` composable manages 4 states: Calculator → Setup → Decoy → Stealth
- Secret PIN threaded from calculator unlock → stored in memory → passed to recorder's fake lock screen
- `SecretCodeManager` passed as a parameter from `MainActivity` → `AppRoot` so `onSetupComplete` can call `setSecretCode()` (Fix 2). `@Singleton` Hilt-injected object — stable identity, safe to thread through Compose.
- `FLAG_SECURE` on all stealth screens (no screenshots)
- `EncryptedSharedPreferences` for settings, SQLCipher for database, AES-256-GCM for vault files
- File-based crash logger (`core/logging/AppLogger.kt`) installed in `StealthCalcApp.onCreate` before Hilt — object pattern, no DI. Writes to `filesDir/logs/app.log`, rotates at 1 MB, exportable from Settings via `FileProvider`.
- Recordings flow through the vault: `RecorderService` is `@AndroidEntryPoint`, `@Inject`s `FileEncryptionService` + `VaultRepository`, and encrypts + saves + deletes the plaintext before `stopForeground + stopSelf`. The foreground notification is held until encryption completes so the OS can't reap mid-write.
- MediaStore delete confirmations ride `StateFlow<IntentSender?>` from VM to Compose: `LaunchedEffect` launches `IntentSenderRequest.Builder(sender).build()` via `StartIntentSenderForResult`, then the VM's `onDeleteRequestHandled()` nulls the flow so it doesn't re-fire.
- GeckoView artifact is architecture-specific: `geckoview-omni-arm64-v8a` (the non-omni `geckoview-arm64-v8a` was discontinued after v117)

## Branches
- `master` — main branch, triggers APK build
- `claude/stealth-app-planning-RvjrV` — original development branch, triggers APK build
- `claude/stealth-app-merged` — backup copy of stealth app, triggers APK build
- GitHub Actions builds APK on every push (`.github/workflows/build-apk.yml`)

## Current Build Status
- Fixed GeckoView dependency: version `123.0.20240213221259` (previous `...20240212205514` didn't exist)
- Fixed artifact name: `geckoview-omni-arm64-v8a` (previous `geckoview-arm64-v8a` was discontinued after v117)
- Build-log artifact is uploaded on failure for debugging
- APK artifact name: `StealthCalc-debug`
- If build fails again: download `build-log` artifact from Actions, paste errors to fix
- **2026-04-14 session:** all 7 runtime bugs from `docs/ISSUES_FOUND.md` are fixed across commits `4206bee..f833ba7`. Each fix is its own commit (bisectable). See that doc + `docs/FIX_PLAN.md` for per-fix commit SHAs and on-device verification steps.

## File Structure
```
app/src/main/java/com/stealthcalc/
├── StealthCalcApp.kt          # Hilt Application
├── MainActivity.kt            # Single activity, FLAG_SECURE, auto-lock
├── calculator/                # Calculator UI + engine + secret code detection
├── auth/                      # SecretCodeManager, AutoLockManager, BiometricHelper, PanicHandler
├── core/                      # Database, encryption, DI modules, SecureClipboard
├── stealth/                   # StealthHomeScreen, DecoyHomeScreen, navigation graph
├── notes/                     # Encrypted notes vault
├── tasks/                     # Task manager, habits, goals
├── recorder/                  # Audio/video recorder + FakeLockScreen
├── browser/                   # GeckoView browser + AdBlocker + ReaderMode + LinkVault
├── vault/                     # Encrypted media vault + SecureCamera + FileEncryptionService
├── settings/                  # Settings screen + ViewModel
└── ui/theme/                  # Material 3 theme
```

## Runtime Issues — Historical Context
The initial APK shipped with 7 runtime/UX bugs that were all fixed in the 2026-04-14 session. Before writing any new code:
1. **Read `docs/ISSUES_FOUND.md`** — diagnosis of each bug (file paths + line numbers), now annotated with the commit SHA that resolved it.
2. **Read `docs/FIX_PLAN.md`** — the remediation plan, now annotated per fix with what shipped vs. what was planned.
3. Work on branch `master`. Each fix was its own commit + push so GitHub Actions can bisect regressions.
4. If runtime bugs surface again, Fix 1 (the crash logger) means the user can already export `app.log` via Settings → Diagnostics → Export crash log.

## Important Notes
- **Read `docs/ANDROID_BUILD_LESSONS.md` first** — running log of errors and fixes across Android projects, plus a pre-push checklist. Expanded with a full set of tables from the 2026-04-14 session: AGP 8 BuildConfig gotcha, Compose/Material3 API drift across BOM versions, MediaStore delete-per-API-level, FileProvider paths, Service lifecycle + coroutines, Kotlin name shadowing.
- The `settings.gradle.kts` uses `dependencyResolutionManagement` (not `dependencyResolution`)
- Mozilla Maven repo: `https://maven.mozilla.org/maven2`
- GeckoView requires the omni arch suffix for v118+: `geckoview-omni-arm64-v8a`, NOT plain `geckoview` and NOT the non-omni `geckoview-arm64-v8a`
- GeckoView version format is `MAJOR.MINOR.BUILDTIMESTAMP` — the timestamp must match an actual published build on `maven.mozilla.org`
- `combine()` with >5 flows needs the vararg Array form
- **Room DB version is 6** (bumped 5 → 6 in Fix 4 for `Recording.vaultFileId`). `DatabaseModule` uses `.fallbackToDestructiveMigration()` — fine pre-release, replace with real migrations before shipping.
- Don't use `StorageController.ClearFlags.ALL` for GeckoView — use individual flags ORed together
- AGP 8.5.2 has `buildConfig` **off by default** — `BuildConfig.APPLICATION_ID` / `VERSION_NAME` aren't generated. Either enable in `buildFeatures { buildConfig = true }` or read via `context.packageManager.getPackageInfo(...)` at runtime (see `core/logging/AppLogger.kt`).
- Compose BOM 2024.08.00 → Compose UI 1.6.8, Material3 ~1.2.1. The `LinearProgressIndicator(progress: () -> Float)` lambda form is 1.3+ only — use the scalar `progress: Float` here.
- `androidx.core:core-ktx` already brings `androidx.core.content.FileProvider` — no extra dep needed.
- `FileProvider` meta-data name must be exactly `android.support.FILE_PROVIDER_PATHS`. `res/xml/file_provider_paths.xml` in this project maps `<files-path name="logs" path="logs/">` (for crash-log export) and `<cache-path name="vault_view" path=".">` (for decrypted vault files opened externally).
- Media playback uses framework APIs (`VideoView` + `MediaController`, `MediaPlayer`) — **no Media3/ExoPlayer dep**. Keep it that way unless a specific Media3 feature is needed; new deps add build fragility in this project.
