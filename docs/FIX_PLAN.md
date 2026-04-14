# StealthCalc — Fix Plan (next session)

Step-by-step remediation for the issues in `docs/ISSUES_FOUND.md`. **Read that doc first.**

- **Work on branch:** `master` (everything is already merged there).
- **All builds are via GitHub Actions** — do NOT try to build locally in the Claude Code container. See `docs/ANDROID_BUILD_LESSONS.md` for why.
- **Do NOT delete features or files.** Check before creating anything new.
- **Fix in the order below** (dependencies noted where present). Do fix #1 first so subsequent crashes are debuggable.

---

## Status (2026-04-14 post-execution)

All five fixes are **LANDED** on `master`. Each section below retains the original plan text and adds a "Shipped" note describing what actually landed and any deviations.

| Fix | Commit | Deviations from plan |
|---|---|---|
| 1 | `4206bee` | Used `PackageManager.getPackageInfo` instead of `BuildConfig` — AGP 8.5.2 has `buildConfig` off by default, avoided enabling it |
| 2 | `90790de` | Chose Option B (thread `SecretCodeManager` through params) over Option A (new `AuthViewModel`) — simpler, fewer files |
| 3a | `3d6b8fc` | As planned |
| 3b | `5c25c09` | Used framework `VideoView` + `MediaPlayer` instead of Media3 ExoPlayer — zero new deps, simpler build surface |
| 3c | `1f3a351` | Renamed private method to `requestOriginalsDeletion` to avoid shadowing the `deleteOriginals: Boolean` parameter |
| 4 | `583ca17` | No `Recording` table removal — kept dual-ownership per §4.4's "keep Recording, also save a VaultFile" option. Migration 5→6 handled by existing `fallbackToDestructiveMigration()` — no manual `Migration` class needed |
| 5 | `f833ba7` | Only §5.1 + §5.2 shipped — black-screen mode (§5.3) was optional and not implemented |

Known limitations (deferred, not blocking):
- **Fix 3c API 29 multi-URI:** only the first `RecoverableSecurityException` per import is surfaced. If the user imports 10 photos and multiple come from another app, they'll have to retry to clean up the rest. Acceptable because most users are on API 30+.
- **Fix 4 dual-ownership deletion:** deleting a `Recording` via `RecorderRepository.deleteRecording` doesn't cascade to the `VaultFile` row, so an orphan vault entry can appear. Fixing this properly requires either (a) injecting `VaultRepository` into `RecorderRepository` to cascade, or (b) removing the `Recording` table entirely and treating vault files as the source of truth (plan §4.4's bigger refactor).

---

## Fix 1 — Add file-based crash logger and log-export UI

**Goal:** User can tap a button in Settings to share the latest crash log.

### 1.1 Create `app/src/main/java/com/stealthcalc/core/logging/AppLogger.kt`
A singleton-style object (no Hilt needed — called from `Application.onCreate` before Hilt is ready).

```kotlin
object AppLogger {
    private const val FILE = "app.log"
    private const val MAX_BYTES = 1_000_000L   // 1 MB rotation

    fun init(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { writeCrash(context, t, e) }
            previous?.uncaughtException(t, e)
        }
    }

    fun log(context: Context, tag: String, msg: String) { /* append with timestamp */ }

    fun logFile(context: Context): File = File(context.filesDir, "logs/$FILE")

    private fun writeCrash(context: Context, t: Thread, e: Throwable) {
        // Rotate if > MAX_BYTES, append "[FATAL]" block with timestamp + stack
    }
}
```

Notes:
- Log file lives under `context.filesDir/logs/app.log` — app-private, doesn't need storage permission.
- Include build version + device model in the FATAL block header.
- Use `SimpleDateFormat` ISO-8601 for each line.

### 1.2 Install the handler in `StealthCalcApp.kt`
In `StealthCalcApp.onCreate()` (first line after `super.onCreate()`): `AppLogger.init(this)`.

### 1.3 Add "Export Log" button in Settings
In `app/src/main/java/com/stealthcalc/settings/ui/SettingsScreen.kt`:
- Add a row "Export crash log".
- On click, build an `Intent.ACTION_SEND` with `FileProvider.getUriForFile(ctx, "${BuildConfig.APPLICATION_ID}.fileprovider", AppLogger.logFile(ctx))` and `type = "text/plain"`.
- Need a `FileProvider` in manifest:
  ```xml
  <provider
      android:name="androidx.core.content.FileProvider"
      android:authorities="${applicationId}.fileprovider"
      android:exported="false"
      android:grantUriPermissions="true">
      <meta-data
          android:name="android.support.FILE_PROVIDER_PATHS"
          android:resource="@xml/file_provider_paths" />
  </provider>
  ```
- And `res/xml/file_provider_paths.xml`:
  ```xml
  <paths><files-path name="logs" path="logs/" /></paths>
  ```

**Files touched:** `core/logging/AppLogger.kt` (new), `StealthCalcApp.kt`, `settings/ui/SettingsScreen.kt`, `AndroidManifest.xml`, `res/xml/file_provider_paths.xml` (new).

**Shipped (`4206bee`):** As planned, with one deviation — `BuildConfig.VERSION_NAME`/`APPLICATION_ID` are NOT generated in AGP 8.x unless `buildFeatures { buildConfig = true }` is set. Rather than toggle that, `AppLogger.appVersion()` reads via `context.packageManager.getPackageInfo(context.packageName, 0)` at runtime (`longVersionCode` on API 28+, deprecated `versionCode` below).

---

## Fix 2 — Persist the PIN on setup completion

**One-line fix at the root, plus a small plumbing change to get `SecretCodeManager` into `AppRoot`.**

### 2.1 Pass `SecretCodeManager` into `AppRoot`
`AppRoot` is currently parameterless-ish. Two clean options:

**Option A (preferred):** Use a small `AuthViewModel` (Hilt-scoped) that wraps `SecretCodeManager`. Collect/call from `AppRoot` via `hiltViewModel()`.

**Option B (quicker):** Thread the manager from `MainActivity` (it has access via Hilt) down as a parameter.

### 2.2 In the `onSetupComplete` lambda in `stealth/navigation/AppNavigation.kt:102-110`, call `setSecretCode` BEFORE unlocking:
```kotlin
onSetupComplete = { code ->
    secretCodeManager.setSecretCode(code)  // <-- ADD THIS
    activeSecretPin = code
    showSetup = false
    onStealthUnlocked()
}
```

### 2.3 Also guard the re-entry path
In `CalculatorViewModel.evaluate()` (`calculator/viewmodel/CalculatorViewModel.kt:141-146`), the `NotSetup` branch unconditionally returns `NeedsSetup`. After fix 2.2 that's fine, but add a belt-and-braces check: if `secretCodeManager.isSetupComplete` is true, treat `NotSetup` as `Invalid` (defensive).

**Files touched:** `stealth/navigation/AppNavigation.kt`, possibly new `auth/viewmodel/AuthViewModel.kt`, `calculator/viewmodel/CalculatorViewModel.kt` (defensive).

**Shipped (`90790de`):** Took Option B — no new `AuthViewModel`. `MainActivity` `@Inject`s `SecretCodeManager` and passes it as a constructor parameter into `AppRoot`. `onSetupComplete` calls `secretCodeManager.setSecretCode(code)` as the first line. `CalculatorViewModel.evaluate()` NotSetup branch now returns `SecretCodeResult.None` (not `NeedsSetup`) when `secretCodeManager.isSetupComplete` is true, so corrupted prefs can never re-unlock by bouncing through setup.

**Test:** Install, enter a code, press `=`, complete setup. Reinstall-free relaunch: typing a DIFFERENT code should NOT grant access. Only the original code should unlock.

---

## Fix 3a — Decrypt thumbnails for the grid

In `vault/ui/VaultScreen.kt:452-461`, replace the stub with an async loader.

### 3a.1 Add a decrypt-to-bitmap helper in `FileEncryptionService`
```kotlin
fun decryptThumbnail(vaultFile: VaultFile): Bitmap? {
    val path = vaultFile.thumbnailPath ?: return null
    val f = File(path); if (!f.exists()) return null
    val fis = FileInputStream(f)
    val iv = ByteArray(IV_SIZE).also { fis.read(it) }
    val cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(TAG_SIZE, iv)) }
    return BitmapFactory.decodeStream(CipherInputStream(fis, cipher))
}
```

### 3a.2 Replace `VaultFileCard` thumbnail block
Use `produceState` or `LaunchedEffect` + local `MutableState<Bitmap?>`:
```kotlin
val thumb by produceState<Bitmap?>(null, file.id, file.thumbnailPath) {
    value = withContext(Dispatchers.IO) {
        runCatching { viewModel.encryptionService.decryptThumbnail(file) }.getOrNull()
    }
}
```
Pass `encryptionService` down or expose it on the ViewModel (it already is — `val encryptionService` is public on `VaultViewModel:49`).

**Files touched:** `vault/service/FileEncryptionService.kt`, `vault/ui/VaultScreen.kt`.

**Shipped (`3d6b8fc`):** As planned. `FileEncryptionService.decryptThumbnail(VaultFile)` mirrors `saveThumbnail` — reads 12-byte IV, AES-GCM init, decodes through `CipherInputStream` → `BitmapFactory.decodeStream`. Returns null on any failure so the icon fallback still renders. `VaultFileCard` takes `encryptionService` as a new parameter, passed from the call site via `viewModel.encryptionService` (already a public val). Thumbnail load uses `produceState<Bitmap?>` keyed on `(file.id, file.thumbnailPath)` so list scroll doesn't re-decrypt the same file.

---

## Fix 3b — Build the file viewer screen

### 3b.1 Create `app/src/main/java/com/stealthcalc/vault/ui/VaultFileViewerScreen.kt`
Responsibilities:
- Receive a `fileId: String` argument.
- Fetch `VaultFile` from repo (via a small `VaultFileViewerViewModel`).
- Decrypt to a temp file via `encryptionService.decryptToTempFile(file)`.
- Photo: `Image(bitmap = BitmapFactory.decodeFile(temp.path).asImageBitmap())`.
- Video: use Media3 ExoPlayer (`androidx.media3:media3-exoplayer` + `media3-ui`) OR `VideoView` via `AndroidView`. Media3 is cleaner — add to `libs.versions.toml` under `camerax` section.
- Audio: Media3 as well (AudioAttributes media session).
- Document: try `Intent.ACTION_VIEW` on a `FileProvider` URI of the decrypted copy.
- On dispose: delete the decrypted temp file.

### 3b.2 Wire the route
`stealth/navigation/AppNavigation.kt`:
- Add `data object FileViewer : AppScreen("vault_file/{fileId}") { fun createRoute(id: String) = "vault_file/$id" }`
- Add `composable(AppScreen.FileViewer.route, arguments = listOf(navArgument("fileId") { type = NavType.StringType })) { VaultFileViewerScreen(onBack = { navController.popBackStack() }) }`
- Change line 245 to `onOpenFile = { file -> navController.navigate(AppScreen.FileViewer.createRoute(file.id)) }`

**Files touched:** `vault/ui/VaultFileViewerScreen.kt` (new), `vault/viewmodel/VaultFileViewerViewModel.kt` (new), `stealth/navigation/AppNavigation.kt`, `gradle/libs.versions.toml` (media3 deps), `app/build.gradle.kts`.

**Shipped (`5c25c09`):** Same module split. **No Media3 / ExoPlayer dep was added.** Renderers use only the Android framework:
- PHOTO → `BitmapFactory.decodeFile` + `Image(ContentScale.Fit)`
- VIDEO → `AndroidView { VideoView }` + `MediaController`
- AUDIO → `MediaPlayer` + Compose `LinearProgressIndicator` (scalar form — BOM 2024.08.00 ships Material3 1.2.1, which doesn't have the `() -> Float` lambda overload). Auto-pauses on `Lifecycle.Event.ON_STOP`, releases on `DisposableEffect.onDispose`.
- DOCUMENT/OTHER → `Intent.ACTION_VIEW` through `FileProvider` chooser.

`file_provider_paths.xml` gained `<cache-path name="vault_view" path="." />` so FileProvider can serve the decrypted temp to external apps. `libs.versions.toml` and `app/build.gradle.kts` were NOT touched.

---

## Fix 3c — Really delete the gallery original

### 3c.1 Stop doing `contentResolver.delete()` directly for imported URIs
`VaultViewModel.importFiles` currently has `contentResolver.delete(uri, null, null)`. Replace with a queued list of URIs to delete.

### 3c.2 Implement a delete request flow
- On API 30+: `MediaStore.createDeleteRequest(contentResolver, uris)` returns a `PendingIntent`. The Activity launches it via `rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult())`.
- On API 29: catch `RecoverableSecurityException`, grab `.userAction.actionIntent.intentSender`, launch same way.
- On API 28 and below: direct delete works.

### 3c.3 Plumb the launcher
`VaultViewModel` should expose a `StateFlow<IntentSender?>` for "deletion pending". `VaultScreen` collects it and launches via `rememberLauncherForActivityResult`. After the launcher returns `RESULT_OK`, emit back to the ViewModel "confirmed" so it can mark the import finished.

**Files touched:** `vault/viewmodel/VaultViewModel.kt`, `vault/ui/VaultScreen.kt`.

**Shipped (`1f3a351`):** As planned. The private delete method was named `requestOriginalsDeletion` (not `deleteOriginals`) because a `deleteOriginals: Boolean` parameter on `importFiles` would otherwise shadow the function — Kotlin's function/property namespace is shared, and a Boolean isn't invokable. `publishBulkDeleteRequest` is gated behind `@RequiresApi(Build.VERSION_CODES.R)`. Known limitation: on API 29 with multiple problematic URIs, only the first `RecoverableSecurityException` surfaces a prompt; remaining problematic URIs require a re-import.

---

## Fix 4 — Encrypt recordings into the vault on stop

### 4.1 Inject vault deps into `RecorderService`
Hilt can inject into services with `@AndroidEntryPoint`.
- Add `@AndroidEntryPoint` to `RecorderService`.
- `@Inject lateinit var encryptionService: FileEncryptionService`
- `@Inject lateinit var vaultRepository: VaultRepository`

### 4.2 In `stopRecording()` after `mediaRecorder?.stop()`
Do the encryption + vault save off the main thread (use a background scope):
```kotlin
CoroutineScope(Dispatchers.IO).launch {
    try {
        val vaultFile = encryptionService.encryptLocalFile(
            source = file,
            originalName = "$typeLabel $timestamp.${if (type == RecordingType.VIDEO) "mp4" else "m4a"}",
            fileType = if (type == RecordingType.VIDEO) VaultFileType.VIDEO else VaultFileType.AUDIO,
            mimeType = if (type == RecordingType.VIDEO) "video/mp4" else "audio/mp4"
        )
        vaultRepository.saveFile(vaultFile)
        file.delete()    // remove plaintext
    } catch (e: Exception) { AppLogger.log(this@RecorderService, "recorder", "vault save failed: ${e.message}") }
}
```

### 4.3 Add `encryptLocalFile` to `FileEncryptionService`
Like `importFile` but takes a `File` instead of a URI. Re-use `encryptStream(FileInputStream(source), encFile)` + thumbnail gen for videos.

### 4.4 Decide fate of `Recording` table
The existing `RecordingDao`/`Recording` entity tracks metadata already. Two options:
- **Keep it** for the "Recordings" list UI, but have it point to the `VaultFile.id` (add `vaultFileId: String?` column → migration).
- **Remove it** entirely and show recordings as a filter in the vault (FilterChip for AUDIO/VIDEO already exists in VaultScreen).

Pick one. The cleaner long-term choice is removing `Recording` and treating vault files as the source of truth — but that's a bigger refactor. For this session, KEEP `Recording` but also save a `VaultFile`.

**Files touched:** `recorder/service/RecorderService.kt`, `vault/service/FileEncryptionService.kt`, `recorder/model/Recording.kt` (add vaultFileId), `core/data/StealthDatabase.kt` (version bump + migration).

**Shipped (`583ca17`):** Per §4.4, kept `Recording` (dual-ownership with `VaultFile`) — smaller change. `FileEncryptionService.encryptLocalFile(File, name, fileType, mime)` is the File-sibling of `importFile(Uri, ...)` and reuses `encryptStream` + `saveThumbnail` + `MediaMetadataRetriever.setDataSource(File.absolutePath)`. `RecorderService.stopRecording` now launches on `serviceScope`, does encryption + `vaultRepository.saveFile` + plaintext `source.delete()` inside a `withContext(Dispatchers.IO)` block, and **only then** calls `stopForeground(STOP_FOREGROUND_REMOVE) + stopSelf()` — so the foreground notification keeps the process alive through the whole encrypt/save/delete chain. On any exception we fall back to the pre-fix behavior (plaintext path + no `vaultFileId`) so the user never loses a recording, and log via `AppLogger.log(...)` so Fix 1's Export button can surface it. DB 5 → 6 relied on existing `fallbackToDestructiveMigration()` — no manual `Migration` class. Known limitation: `RecorderRepository.deleteRecording` doesn't cascade into the vault (orphan `VaultFile` rows possible).

---

## Fix 5 — Make the fake lock screen actually lock

In `recorder/ui/FakeSignInScreen.kt` (the file that defines the `FakeLockScreen` composable — consider renaming the file to `FakeLockScreen.kt` while you're there):

### 5.1 Disable back button
At top of `FakeLockScreen` body:
```kotlin
BackHandler(enabled = true) { /* swallow — do nothing */ }
```
Import `androidx.activity.compose.BackHandler`.

### 5.2 Keep the screen on + hold CPU so recording continues
Because Compose doesn't have a direct "keep screen on" modifier, set it via the host activity's window while this composable is active:
```kotlin
val view = LocalView.current
DisposableEffect(Unit) {
    val window = (view.context as Activity).window
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
}
```
Additionally, the `RecorderService` foreground service already holds the process alive. Keep it `foregroundServiceType="microphone|camera"` (already set in manifest).

### 5.3 Option: fake black screen
Add a UI toggle on `RecorderScreen` ("Black screen mode") that launches `FakeLockScreen` with a pure-black background AND disables the clock display. Tap anywhere to bring up the PIN pad for a few seconds then fade back to black. Correct PIN exits.

### 5.4 The only exit is the correct PIN
Already the case for the `onUnlock()` path. Post-fix 5.1, Back no longer sneaks through.

**Files touched:** `recorder/ui/FakeSignInScreen.kt` (rename optional), `recorder/ui/RecorderScreen.kt`.

**Shipped (`f833ba7`):** Only §5.1 + §5.2. `BackHandler(enabled = true) { /* no-op */ }` and a `DisposableEffect` toggling `FLAG_KEEP_SCREEN_ON` on the host Activity window. §5.3 (black-screen mode, with tap-to-reveal PIN pad) was marked optional and not implemented — current fake lock is a standard-looking lock screen; if a true-black cover is wanted later, it's purely additive work on top. No file rename; `FakeSignInScreen.kt` still defines `FakeLockScreen`. `RecorderScreen.kt` was not modified — only `FakeSignInScreen.kt` was.

---

## Build & verify

Each fix is push-and-test via GitHub Actions (master builds automatically per `.github/workflows/build-apk.yml`). Do not batch all fixes in a single push — push after each numbered fix and wait for the green build before continuing so a regression is easy to bisect.

APK artifact name: `StealthCalc-debug`.

---

## Out of scope for this plan

- Decoy PIN setup UI (exists in settings? verify before touching).
- Panic-handler tuning.
- Biometric unlock (`BiometricHelper.kt` exists; not reported broken).
- Browser fixes.
