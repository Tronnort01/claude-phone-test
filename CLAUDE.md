# StealthCalc — Project Context

## New-session checklist (read this first, every time)

Before touching any code on this project:

1. **Read these docs in order, do NOT scan the whole codebase:**
   - This file (CLAUDE.md) — you're in it.
   - `docs/ANDROID_BUILD_LESSONS.md` — gotchas we've already hit (FGS types on API 34+, photo picker vs `ACTION_PICK`, Compose API drift, delegated-property smart cast, etc.). Check the tables before writing anything new in those areas.
   - `docs/ISSUES_FOUND.md` and `docs/FIX_PLAN.md` — the three rounds of already-fixed bugs with commit SHAs. Read the Status tables first; drill into a specific issue only if the user's new task relates to it.
   - **If the task is about phone monitoring / dashboard / agent:** read `docs/MONITORING_DESIGN.md` — in-progress design from the 2026-04-16 session. Captures user decisions (Tailscale + Kotlin/Ktor server + runtime role toggle), codebase reuse surface with line numbers, and open questions. Active branch: `claude/plan-app-monitoring-W0UKj`.

2. **Build pipeline:** **GitHub Actions only.** Never run `./gradlew` locally — the container proxy blocks `dl.google.com` and `maven.mozilla.org`. Every push to `master` triggers `.github/workflows/build-apk.yml`; APK artifact is `StealthCalc-debug`, build log is uploaded on failure.

3. **Branch workflow:** the finished app lives on `master`. For every new feature / bug-fix round, branch off the latest `master` with a descriptive name (e.g. `claude/fix-audio-viewer-crash`), commit per fix, push the branch, then **fast-forward merge back into `master` and push `master`** when the round is done. GitHub Actions builds an APK on every push to any `claude/**` branch AND on every push to `master`. The rule "never push to other branches" from earlier sessions was specifically about me creating side/empty projects — merging a feature branch back to `master` is expected. Don't amend / force-push.

4. **When I report a crash or runtime bug:** ask me to paste the export from **Settings → Diagnostics → Export crash log** (the `app.txt` file) BEFORE diagnosing. `[FATAL]` blocks have device/build/stack; `[recorder]` / `[vault]` lines surface silent failures.

5. **Code reads:** only the specific files the task touches. Use Grep/Glob for call sites. Reserve Explore agents for genuinely uncertain, multi-module tasks.

6. **At end of session:** if I ask you to "update context" (or before I do), extend `docs/ISSUES_FOUND.md` / `docs/FIX_PLAN.md` with a new "Round N" block for whatever shipped, add any new gotchas to `docs/ANDROID_BUILD_LESSONS.md`, merge the feature branch back to `master`, and push `master`. The docs get smarter every session — keep them that way.

My normal starting phrase will be short (e.g. "read context, here's my feedback: …"). Trust this file to have told you what to do; ask me only if the task is ambiguous.

---

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
5. **Recorder** — Audio + video recording with optional fake-lock cover (`recorder/`). **Round 5 default:** when Settings → "Use real device lock while recording" is ON (default), the user power-locks the phone normally and recording continues underneath the real Android keyguard via foreground service + `PARTIAL_WAKE_LOCK` + CameraX bound to `LifecycleService`. Unlocking with the real PIN/biometric returns to the calculator. The legacy fake-cover (`FakeSignInScreen`) and overlay (`OverlayLockService`) paths remain as opt-out. Runtime permissions are requested in `RecorderScreen` before starting (Follow-up Fix B). `RecorderService` promotes to foreground FIRST with a runtime `foregroundServiceType` that matches what's been granted — `MICROPHONE` only for audio, `MICROPHONE|CAMERA` for video (Follow-up Round 2). Recordings encrypt into the vault on stop (Fix 4): plaintext MP4/M4A → `FileEncryptionService.encryptLocalFile` (now Round 5 streaming AES-CTR+HMAC, no OOM on huge recordings) → `VaultFile`, plaintext deleted, `Recording.vaultFileId` points at the vault row. The foreground notification is `VISIBILITY_SECRET` so the lock screen stays clean. `FakeSignInScreen` (when used) swallows system Back, holds `FLAG_KEEP_SCREEN_ON`, hides system bars via `WindowInsetsControllerCompat` immersive-sticky.
6. **Browser** — GeckoView (Firefox), Enhanced Tracking Protection, no cookies, private mode (`browser/`)
7. **Vault** — AES-256 encrypted media storage, secure camera, sort by date/size/name, **multi-select export**, **photo merge** (`vault/`). Thumbnails decrypt via `FileEncryptionService.decryptThumbnail` on a background dispatcher (Fix 3a). `VaultFileViewerScreen` uses `HorizontalPager` to swipe between files of the same type; decryption is per-page on-demand with a per-fileId cache trimmed as the user pages (Follow-up Fix C). Photo renderer is `BitmapFactory`/`Image`; **video renderer is Media3 ExoPlayer** (Round 5 — replaced `VideoView` after 4 rounds of "spins forever" bugs); audio uses framework `MediaPlayer` (Fix 3b). Gallery import uses an in-app MediaStore picker (Round 3 J — no Google Photos). **Export to public library (Round 5 P):** long-press a card → contextual toolbar (count + Export + Delete) → `FileEncryptionService.exportToMediaStore()` streams decrypted bytes through `MediaStore.insert + openOutputStream` into `Pictures/StealthCalc`, `Movies/StealthCalc`, `Music/StealthCalc` — no plaintext temp file ever written. **Photo merge (Round 5 Q):** viewer overflow → in-vault picker → Compose `graphicsLayer` editor (drag/zoom/rotate/opacity) → `Canvas` composite at native resolution → saved as `Merged_<timestamp>.jpg`.
8. **Settings** — Change PIN, decoy PIN, biometric toggle, auto-lock timer, panic shake/back, screenshot blocking, **Export crash log** as `.txt` (Fix 1 + Follow-up Fix A) (`settings/`)
9. **Monitoring** (Round 6) — Phone monitoring agent + dashboard (`monitoring/`). Same APK, runtime role toggle (`disabled`/`agent`/`dashboard`/`both`). **Agent** collects 34 data sources across 28 collectors: app usage (`UsageStatsManager`), screen on/off/unlock, battery (level/charging/temp/voltage), WiFi SSID + network state + WiFi history with signal strength, app installs/uninstalls, incoming notifications (`NotificationListenerService`), location (`FusedLocationProviderClient`), call log, SMS content, media change detection (ContentObserver), device security events (WiFi/BT/airplane/power), keystroke logging (AccessibilityService `TYPE_VIEW_TEXT_CHANGED` with word-level grouping), chat message scraping (AccessibilityService reads WhatsApp/Telegram/Signal/etc), clipboard monitoring, browser history (Chrome content provider), SIM change detection, device info snapshots (storage/RAM/uptime), per-app data usage (`NetworkStatsManager`), calendar events, geofencing with configurable zones (haversine), installed apps inventory, ambient sound-triggered recording (3s probe → 30s record if peak > threshold), contact frequency analysis (7-day call+SMS aggregation), step counter (pedometer sensor), sensor data (proximity/light/accelerometer movement), app permissions audit. **File transfer:** `FileUploader` streams photos/videos/documents/chat media to server via multipart upload; `MediaUploadCollector` + `FileSyncCollector` auto-sync new media + Downloads/Documents + WhatsApp/Telegram/Signal media folders. **Screen capture:** `ScreenshotCollector` (MediaProjection JPEG per cycle) + `ScreenRecordCollector` (MediaCodec H.264 → MP4, configurable duration). **Face capture:** `FaceCaptureCollector` (Camera2 front camera on unlock). **Live streaming:** `ScreenStreamCollector` (half-res JPEG over WebSocket) + `LiveCameraCollector` (front/back camera 320x240 JPEG stream). **Remote commands:** `RemoteCommandHandler` listens on WS `/commands/{deviceId}`, dispatches: `capture_front`, `capture_back`, `record_audio`, `ring`, `send_sms`, `stream_camera_front/back`, `stop_camera_stream`, `screen_record`. **Battery-smart:** collection interval widens 60s → 180s below 20% battery. **Auto-start:** `AgentBootReceiver` starts service + WorkManager on `BOOT_COMPLETED`. Events buffer in local SQLCipher (`MonitoringEvent` entity), upload to server via Ktor HTTP in 120s batches. `AgentService` is a `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` `LifecycleService`; `AgentSyncWorker` is a 15-min `WorkManager` backup. `NotificationMonitorService` is a system-bound NLS. `AccessibilityMonitorService` handles keylogging + chat scraping + clipboard. **Dashboard** polls `GET /state/{deviceId}` every 30s showing device status card (online/offline indicator) + 25 filter tabs + app usage chart + remote control panel (9 buttons) + file gallery with category filters. **Server** (`server/`) is a separate Kotlin+Ktor+Netty+SQLite project: 18 endpoints (REST + 5 WebSocket channels), pairing (OTP → bearer token), batch event upload, file upload/download/list, command relay, live screen + camera stream relay, retention cleanup (30-day rolling, configurable via `RETENTION_DAYS` env). See `docs/MONITORING_DESIGN.md` for full architecture.

## Key Architecture Decisions
- Single Activity (`MainActivity`) — OS only sees "Calculator"
- `AppRoot` composable manages 4 states: Calculator → Setup → Decoy → Stealth
- Secret PIN threaded from calculator unlock → stored in memory → passed to recorder's fake lock screen
- `SecretCodeManager` passed as a parameter from `MainActivity` → `AppRoot` so `onSetupComplete` can call `setSecretCode()` (Fix 2). `@Singleton` Hilt-injected object — stable identity, safe to thread through Compose.
- `FLAG_SECURE` on all stealth screens (no screenshots)
- `EncryptedSharedPreferences` for settings, SQLCipher for database. **Vault file encryption (Round 5):** AES-256-CTR + HMAC-SHA256 streaming AEAD. Format v2: `[4 bytes magic "SC2v"][16 bytes IV][N ciphertext][32 bytes HMAC tag]`. Constant-memory encrypt + decrypt regardless of file size (was previously AES-GCM via Conscrypt, which buffers the entire ciphertext in heap during `doFinal()` — OOMed on 446 MB recordings). Decrypt is format-aware: peeks magic, dispatches to v2 streaming or legacy GCM. `FileEncryptionService.exportToMediaStore` and `decryptToStream` (PipedInputStream + worker thread) stream too.
- File-based crash logger (`core/logging/AppLogger.kt`) installed in `StealthCalcApp.onCreate` before Hilt — object pattern, no DI. Writes to `filesDir/logs/app.txt` (renamed from `app.log` in Follow-up Fix A so default Android viewers open it), rotates at 1 MB, exportable from Settings via `FileProvider`. `[recorder]`/`[vault]` tagged lines from service code surface via `AppLogger.log()` for silent-failure diagnostics.
- Recordings flow through the vault: `RecorderService` is `@AndroidEntryPoint`, `@Inject`s `FileEncryptionService` + `VaultRepository`, and encrypts + saves + deletes the plaintext before `stopForeground + stopSelf`. The foreground notification is held until encryption completes so the OS can't reap mid-write.
- **Foreground service order matters on Android 14+ (API 34+):** `RecorderService.promoteToForeground()` calls `startForeground(id, notification, fgsType)` as the FIRST thing in `onStartCommand` with the right runtime `ServiceInfo.FOREGROUND_SERVICE_TYPE_*` (MICROPHONE for audio, MICROPHONE|CAMERA for video). If the promote succeeds we then set up MediaRecorder; if either promote OR MediaRecorder throws, we `stopForeground(STOP_FOREGROUND_REMOVE) + stopSelf()`. Previously MediaRecorder ran first and any exception missed the 5-10s `startForegroundService` deadline, crashing the process with `ForegroundServiceDidNotStartInTimeException` (Follow-up Round 2).
- Gallery import uses `Intent.ACTION_PICK` with `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` (or Video) as the intent data — this opens the legacy gallery picker which returns real MediaStore URIs. Previously the code used `ActivityResultContracts.GetMultipleContents()` which on Android 13+ returns ephemeral `content://media/picker/...` URIs that `MediaStore.createDeleteRequest` silently refuses to delete (Follow-up Round 2).
- MediaStore delete confirmations ride `StateFlow<IntentSender?>` from VM to Compose: `LaunchedEffect` launches `IntentSenderRequest.Builder(sender).build()` via `StartIntentSenderForResult`, then the VM's `onDeleteRequestHandled()` nulls the flow so it doesn't re-fire.
- `RecorderViewModel.startRecording()` has a 4-second safety timer: if `RecorderService.isRecording` hasn't flipped to true by then, it dismisses the cover screen so the user isn't stuck on the fake lock (Follow-up Round 2).
- **Round 3 recording pipeline:** `RecorderService` is a `LifecycleService` (not plain `Service`) so CameraX `bindToLifecycle` works. Audio still uses `MediaRecorder` with `AudioSource.MIC`. Video uses CameraX `Recorder` + `VideoCapture`; `VideoRecordEvent.Finalize` drives the vault persist chain, not the `stopRecording()` call site. A `PARTIAL_WAKE_LOCK` tagged `"StealthCalc:RecorderWakeLock"` is acquired in `promoteToForeground()` and released on every stop path (success, failure, `onDestroy`) — keeps the CPU alive through real-phone lock. `MainActivity` observes `RecorderService.isRecording` and toggles `setShowWhenLocked`/`setTurnScreenOn` so a real lock wakes into the fake cover, not the OS keyguard. `MainActivity` also has a wide `android:configChanges` set so rotation / touch / font-scale don't recreate the activity and tear down FakeLockScreen.
- **Round 3 gallery import:** `Intent.ACTION_PICK` is dead — it routed to Google Photos on Pixel and forced cloud sign-in. Now an in-app Compose picker (`vault/ui/InAppMediaPickerScreen.kt` + `vault/viewmodel/InAppMediaPickerViewModel.kt`) queries MediaStore directly and returns real `content://media/external/...` URIs. A nested `navigation("vault_graph") { composable(Vault); composable(MediaPicker) }` in `AppNavigation.kt` scopes a single `VaultViewModel` across both destinations via `hiltViewModel(parentEntry)` so the picker's `onImport` lands on the same VM that renders the vault grid. `VaultScreen` accepts `onPickPhotos`/`onPickVideos` callbacks.
- **Round 5 video player:** `VaultFileViewerScreen` uses Media3 `ExoPlayer.Builder(ctx).build()` with the listener attached BEFORE `prepare()` (synchronous prepare-time errors are otherwise lost). 12-second `STATE_READY` watchdog + `onPlayerError` log to `app.txt` so an infinite spinner can never recur. Deps: `androidx.media3:media3-exoplayer/ui/common:1.4.1`. Reverses the earlier "no Media3 dep" stance — `VideoView`'s lack of observable buffering / error states caused 4+ rounds of bugs.
- **Round 5 real-lock UX:** `MainActivity` reads `KEY_USE_REAL_LOCK_DURING_RECORDING` (default true) from `EncryptedSharedPreferences` on every `RecorderService.isRecording` emission and conditionally calls `setShowWhenLocked` — when the pref is true, NEVER force the activity over the keyguard, so the user can power-lock normally and the foreground service keeps recording. `RecorderViewModel.enterCoverScreen` and `startRecording` short-circuit similarly. The notification is `VISIBILITY_SECRET` so the lock screen stays clean.
- **Round 5 photo merge:** new routes `photo_merge_pick/{baseId}` → `photo_merge/{baseId}/{overlayId}` in `AppNavigation.kt`. `PhotoMergeScreen` uses Compose `graphicsLayer { translationX/Y, scaleX/Y, rotationZ }` + `alpha()` + `detectTransformGestures` for a zero-alloc-per-frame preview. Save runs `composeMerged` on `Dispatchers.Default` via `android.graphics.Canvas` + `Matrix` at the base image's native resolution → `encryptionService.encryptBitmap` → `repository.saveFile`.
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
- **2026-04-14 session (original 7 bugs):** fixed across commits `4206bee..f833ba7` (`docs/ISSUES_FOUND.md`).
- **2026-04-14 on-device testing — Round 1:** user-reported regressions fixed across `5317a38..a10f5dd` — log as `.txt`, runtime permissions for recording, immersive fake lock + opt-in screen pinning, `HorizontalPager` viewer, photo-picker → MediaStore URI resolution. See `docs/FIX_PLAN.md` "Post-testing iterations — Round 1".
- **2026-04-14 on-device testing — Round 2:** Pixel 6 / Android 16 (API 36) surfaced harder bugs fixed across `d9e0d53..5617f60` — FGS runtime type must match granted permissions, `startForeground()` before MediaRecorder setup (deadline crash), `Intent.ACTION_PICK` with MediaStore URI instead of system photo picker, cover-screen auto-dismiss if recording never starts. See `docs/FIX_PLAN.md` "Post-testing iterations — Round 2".
- **2026-04-14 on-device testing — Round 3:** four more Pixel 6 / Android 16 bugs fixed on branch `claude/fix-cloud-signin-video-bugs-cGptT` (`a766fd0..ba15641`) — (M) drop `startLockTask()` to kill the unsuppressible "Screen pinned" toast; (L) `PARTIAL_WAKE_LOCK` in `RecorderService` + `setShowWhenLocked` on `MainActivity` + `android:configChanges` so rotation/touch/real-phone-lock don't interrupt recording; (K) video recording moved to CameraX `VideoCapture` (`RecorderService` is now a `LifecycleService`) — the old `MediaRecorder.VideoSource.CAMERA` path was silently broken because it's the legacy Camera1 API; (J) in-app MediaStore gallery picker (`InAppMediaPickerScreen` + `InAppMediaPickerViewModel`) replaces `Intent.ACTION_PICK` so Google Photos can never intercept and force cloud sign-in — nested `navigation("vault_graph")` shares a `VaultViewModel` between Vault and picker. See `docs/FIX_PLAN.md` "Post-testing iterations — Round 3".
- **2026-04-15 Round 5 (`6eb39dc..33cd2d3`, branch `claude/fix-video-player-add-features-Pyk9Z`):** four user-requested items + a hotfix. (1) Media3 ExoPlayer replaces VideoView in the vault viewer, with the listener attached BEFORE `prepare()` and a 12s watchdog (commit `6eb39dc` + `107c4dc`). (2) Vault → public library export via long-press selection mode + `MediaStore.insert + openOutputStream` streaming (commit `6eb39dc`). (3) Photo merge editor: viewer overflow → in-vault picker → Compose `graphicsLayer` editor → `Canvas` composite (commit `6eb39dc`). (4) "Use real device lock while recording" Settings toggle (default on) — `MainActivity` stops fighting the real keyguard, recording continues underneath via FGS + wake lock, notification is `VISIBILITY_SECRET` (commit `6eb39dc`). HOTFIX (`33cd2d3`): switched encryption from AES-GCM to AES-CTR + HMAC-SHA256 streaming AEAD because Conscrypt buffers the entire ciphertext during GCM `doFinal()` — OOMed on a 446 MB recording. New file format v2 with magic-byte detection for backward compat with legacy GCM files. See `docs/FIX_PLAN.md` "Post-testing iterations — Round 5".
- HEAD on `master` after Round 5 merge: see `git log --oneline -1 master`.

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
├── monitoring/                # Phone monitoring agent/dashboard (Round 6)
│   ├── model/                 # MonitoringEvent entity + 20+ serializable DTOs
│   ├── data/                  # MonitoringDao + MonitoringRepository (34 metric IDs)
│   ├── collector/             # 28 data collectors (app usage, battery, screen, network,
│   │                          #   WiFi history, call log, SMS, media detect + upload,
│   │                          #   file sync, security events, browser history, SIM change,
│   │                          #   device info, data usage, calendar, geofence, installed apps,
│   │                          #   ambient sound, contact frequency, step counter, sensors,
│   │                          #   app permissions, screenshot, screen record, face capture,
│   │                          #   live camera stream, screen stream)
│   ├── service/               # AgentService (FGS) + NotificationMonitorService (NLS)
│   │                          #   + AccessibilityMonitorService (keylogger/chat/clipboard)
│   │                          #   + AgentSyncWorker + AgentBootReceiver
│   │                          #   + RemoteCameraService + RemoteCommandHandler
│   ├── network/               # AgentApiClient (Ktor HTTP) + FileUploader (multipart)
│   └── ui/                    # AgentConfigScreen + DashboardScreen + GalleryScreen
│                              #   + LiveScreenScreen + PermissionHelper + ViewModels
├── settings/                  # Settings screen + ViewModel
└── ui/theme/                  # Material 3 theme
```

### Lightweight Agent (secondary phone) — `agent/`
Separate Android project, independent Gradle build. ~5MB APK disguised as "Calculator".
```
agent/app/src/main/java/com/stealthagent/
├── AgentApp.kt                # Hilt Application + WorkManager init
├── collector/                 # AllCollectors (consolidated), AppInstallReceiver
├── data/                      # AgentDao, AgentDatabase (Room+SQLCipher), AgentRepository, DiModule
├── model/                     # MonitoringEvent entity, API DTOs
├── network/                   # AgentClient (Ktor, direct WiFi + server fallback)
├── service/                   # AgentForegroundService, BootReceiver,
│                              #   AgentNotificationListener, AgentAccessibilityService
└── ui/                        # MainActivity, CalculatorScreen (disguise),
                               #   SetupScreen (one-time config, hidden behind secret code)
```
Build: `cd agent && gradle assembleDebug`. CI: `.github/workflows/build-agent.yml`, artifact: `StealthAgent-debug`.

## Runtime Issues — Historical Context
Original APK shipped with 7 runtime/UX bugs. On-device testing on a Pixel 6 / Android 16 (API 36, targetSdk 35) then surfaced four more rounds of follow-up bugs / feature requests. All five rounds are now fixed on `master`. Before writing any new code:
1. **Read `docs/ISSUES_FOUND.md`** — diagnosis of each original bug (file paths + line numbers), annotated with the commit SHA that resolved it, plus a new "Post-testing iterations" section summarizing the Round 1 + Round 2 follow-ups.
2. **Read `docs/FIX_PLAN.md`** — the remediation plan, annotated per fix with what actually shipped, deviations from plan, and two new "Post-testing iterations" blocks.
3. Work on branch `master`. Each fix was its own commit + push so GitHub Actions can bisect regressions.
4. If runtime bugs surface again, the crash logger means the user can export `app.txt` via Settings → Diagnostics → Export crash log. Emphasize: ask for that log first — it's named every time and is the fastest way to root-cause.

## Important Notes
- **Read `docs/ANDROID_BUILD_LESSONS.md` first** — running log of errors and fixes across Android projects, plus a pre-push checklist. Expanded with a full set of tables from the 2026-04-14 session: AGP 8 BuildConfig gotcha, Compose/Material3 API drift across BOM versions, MediaStore delete-per-API-level, FileProvider paths, Service lifecycle + coroutines, Kotlin name shadowing.
- The `settings.gradle.kts` uses `dependencyResolutionManagement` (not `dependencyResolution`)
- Mozilla Maven repo: `https://maven.mozilla.org/maven2`
- GeckoView requires the omni arch suffix for v118+: `geckoview-omni-arm64-v8a`, NOT plain `geckoview` and NOT the non-omni `geckoview-arm64-v8a`
- GeckoView version format is `MAJOR.MINOR.BUILDTIMESTAMP` — the timestamp must match an actual published build on `maven.mozilla.org`
- `combine()` with >5 flows needs the vararg Array form
- **Room DB version is 7** (bumped 6 → 7 in Round 6 for `MonitoringEvent` entity). `DatabaseModule` uses `.fallbackToDestructiveMigration()` — fine pre-release, replace with real migrations before shipping.
- Don't use `StorageController.ClearFlags.ALL` for GeckoView — use individual flags ORed together
- AGP 8.5.2 has `buildConfig` **off by default** — `BuildConfig.APPLICATION_ID` / `VERSION_NAME` aren't generated. Either enable in `buildFeatures { buildConfig = true }` or read via `context.packageManager.getPackageInfo(...)` at runtime (see `core/logging/AppLogger.kt`).
- Compose BOM 2024.08.00 → Compose UI 1.6.8, Material3 ~1.2.1. The `LinearProgressIndicator(progress: () -> Float)` lambda form is 1.3+ only — use the scalar `progress: Float` here.
- `androidx.core:core-ktx` already brings `androidx.core.content.FileProvider` — no extra dep needed.
- `FileProvider` meta-data name must be exactly `android.support.FILE_PROVIDER_PATHS`. `res/xml/file_provider_paths.xml` in this project maps `<files-path name="logs" path="logs/">` (for crash-log export) and `<cache-path name="vault_view" path=".">` (for decrypted vault files opened externally).
- Media playback: AUDIO uses framework `MediaPlayer`. VIDEO uses **Media3 ExoPlayer 1.4.1** (`androidx.media3:media3-exoplayer/ui/common`) — added in Round 5 after `VideoView` caused 4+ rounds of "spins forever" bugs. ExoPlayer's `Player.STATE_BUFFERING/READY/ENDED` + `onPlayerError(PlaybackException)` give us the observable state VideoView lacks. Listener MUST be attached BEFORE `prepare()` (DisposableEffect runs in a separate Compose phase, can miss synchronous events) — see `VaultFileViewerScreen.VideoPlayer`.
- **Vault encryption format v2 (Round 5):** AES-256-CTR + HMAC-SHA256 streaming AEAD. `[4 bytes "SC2v"][16 bytes IV][N ciphertext][32 bytes HMAC tag]`. Reason for the change: Conscrypt's AES-GCM buffers the ENTIRE ciphertext in heap during `doFinal()` — OOMs on >~150 MB files (capture-from-recording was the trigger). CTR is a true stream cipher, HMAC over `magic ‖ iv ‖ ciphertext` is computed in the same pass. Decrypt is format-aware: peeks first 4 bytes, dispatches to v2 streaming or legacy GCM. New writes are always v2; legacy small files still readable. HMAC key derived as `SHA-256(aesKey || "stealthcalc-hmac-v2")`.
- **Android 14+ foreground services (API 34+):** manifest `foregroundServiceType` is the MAXIMUM permitted set, not the default. At runtime `startForeground(id, notification, fgsType)` must pass a SUBSET and every included type must have its permission granted. For this project, `RecorderService.promoteToForeground()` picks `ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE` for audio and `MICROPHONE or CAMERA` for video. Wrong type or missing permission → `SecurityException`.
- **`startForegroundService()` deadline:** the service must call `startForeground()` within ~5–10 seconds or the system kills the process with `ForegroundServiceDidNotStartInTimeException`. Do it FIRST in `onStartCommand`, before any failable setup (MediaRecorder, CameraManager, network I/O). On setup failure: `stopForeground(STOP_FOREGROUND_REMOVE) + stopSelf()` to unwind cleanly.
- **Photo picker vs `ACTION_PICK`:** On Android 13+, `ActivityResultContracts.GetMultipleContents` funnels through `ACTION_GET_CONTENT` and on image/video mime types redirects to the system photo picker, which returns ephemeral `content://media/picker/...` URIs. Those URIs **cannot** be mapped back to MediaStore rows (picker privacy-strips the mapping) so `MediaStore.createDeleteRequest` silently fails. Use `Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)` (or Video) — `ACTION_PICK` does NOT redirect to the photo picker; it opens the legacy gallery picker which returns real `content://media/external/...` URIs that can actually be deleted.
- **Runtime permissions:** manifest declaration is necessary but NOT sufficient on API 23+. `RECORD_AUDIO`, `CAMERA`, `POST_NOTIFICATIONS` (API 33+), `READ_MEDIA_IMAGES/VIDEO` (API 33+) all need `ActivityResultContracts.RequestMultiplePermissions` at UI time before starting the dependent feature. Check `ContextCompat.checkSelfPermission` first so you don't re-prompt when already granted.
- **App pinning (Activity.startLockTask):** from a regular app, `startLockTask()` only takes effect if the user has enabled "App pinning" in device Settings → Security. Wrap in `runCatching` — on devices where pinning is disabled it's a silent no-op, not an error. Pair with `stopLockTask()` in `DisposableEffect.onDispose` so unlock returns the device to normal navigation.
