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
| Round 5 | `6eb39dc..33cd2d3` | ExoPlayer (Item 1), MediaStore export (Item 2), photo merge (Item 3), real-lock UX (Item 4), AES-CTR+HMAC streaming encryption hotfix (O1). Reversed CLAUDE.md's "no Media3 dep" stance — VideoView's lack of observable buffering/error states caused 4+ rounds of "spins forever" bugs. ML background removal explicitly deferred per user. Encryption format changed to v2 (CTR+HMAC) to fix Conscrypt GCM OOM on large files; legacy GCM decrypt preserved for backward compat. |

Known limitations (deferred, not blocking):
- **Fix 3c API 29 multi-URI:** only the first `RecoverableSecurityException` per import is surfaced. If the user imports 10 photos and multiple come from another app, they'll have to retry to clean up the rest. Acceptable because most users are on API 30+.
- **Fix 4 dual-ownership deletion:** deleting a `Recording` via `RecorderRepository.deleteRecording` doesn't cascade to the `VaultFile` row, so an orphan vault entry can appear. Fixing this properly requires either (a) injecting `VaultRepository` into `RecorderRepository` to cascade, or (b) removing the `Recording` table entirely and treating vault files as the source of truth (plan §4.4's bigger refactor).

---

## Post-testing iterations — Round 1 (2026-04-14)

After the user installed the `f833ba7` APK on a Pixel 6 / Android 16 device and tested, five additional issues came up. All fixed on `master`:

| Fix | Commit | Issue → Resolution |
|---|---|---|
| A | `5317a38` | Log file was `app.log` — stock Android text viewers refuse to open it. Renamed to `app.txt` (rotated file `app.txt.1`). Same `logs/` directory in FileProvider so nothing else changed. |
| B | `e3b2c55` | Record button was a no-op — manifest declared `RECORD_AUDIO`/`CAMERA`/`POST_NOTIFICATIONS` but they were never requested at runtime, so `MediaRecorder.start()` threw `SecurityException` which the service silently swallowed. `RecorderScreen` now wraps the record click in `ActivityResultContracts.RequestMultiplePermissions` — `RECORD_AUDIO` always, `CAMERA` for video, `POST_NOTIFICATIONS` on API 33+. The service's silent catch now also calls `AppLogger.log("recorder", ...)` so any remaining failure is exportable. |
| C | `1787bbf` | Viewer showed one file at a time; user had to Back → tap → Back → tap to see next photo. `VaultFileViewerScreen` rewritten around `HorizontalPager`. `VaultFileViewerViewModel` now loads all files of the same type as the tapped one (`repository.getFiles(type = initial.fileType).first()`), finds the initial index, exposes `decrypt(file)` suspending fn with a per-fileId cache + Mutex, and `trimCache(centerIndex, keepAround=2)` to garbage-collect temp files on long scrolls. `onCleared` deletes every remaining plaintext temp. |
| D | `a16f3e8` | Pixel home-swipe dismissed the fake lock. Added `WindowInsetsControllerCompat` immersive-sticky (hides nav bar + status bar) in the `DisposableEffect` that already holds `FLAG_KEEP_SCREEN_ON`. Also added best-effort `Activity.startLockTask() / stopLockTask()` — silent no-op unless the user has enabled "App pinning" in Settings → Security. When enabled, the home gesture is truly blocked until correct-PIN unlock. |
| E | `a10f5dd` | Photo-picker URIs weren't triggering the system delete dialog. **First attempt at the fix** — stayed on `GetMultipleContents` but added `resolveToMediaStoreUri(pickerUri)` that looks up MediaStore rows by `(DISPLAY_NAME, SIZE)`, plus manifest declarations for `READ_MEDIA_IMAGES/VIDEO` (API 33+) and `READ_EXTERNAL_STORAGE` (`maxSdkVersion=32`) and a permission request flow in `VaultScreen`. **This did not fully work** — see Round 2 for why and the real fix. |

---

## Post-testing iterations — Round 5 (2026-04-15)

Branch: `claude/fix-video-player-add-features-Pyk9Z`. Four user-requested items (player still broken, vault export, photo merge, real-lock UX) plus a hotfix for the 446 MB recording OOM that Round 5's diagnostic logging exposed. Five commits: `758d985 (R4 base) → 6eb39dc → 9b5d554 → 107c4dc → 33cd2d3`.

| Fix | Commit | Issue → Resolution |
|---|---|---|
| Item 1 — ExoPlayer video | `6eb39dc` | Replaced `VideoView` (no observable buffering / error states, contributed to the Round-4 N1-N4 "spins forever" loop) with **Media3 ExoPlayer**. `androidx.media3:media3-exoplayer/ui/common:1.4.1` added to `libs.versions.toml` + `app/build.gradle.kts`. New `VideoPlayer` composable in `VaultFileViewerScreen.kt` builds an `ExoPlayer.Builder(context).build()` per-tempFile, drives `Player.STATE_BUFFERING/READY/ENDED` into a real spinner, surfaces `onPlayerError(PlaybackException)` with `errorCode + errorCodeName + cause` to `app.txt`. `PlayerView` from `media3-ui` is the AndroidView. Audio path stays on `MediaPlayer` (works fine, no need to touch). |
| Item 2 — Vault export to library | `6eb39dc` | New flow. Long-press a card in `VaultScreen.kt` toggles selection mode; the `TopAppBar` swaps from the normal toolbar to a contextual one (count + Export icon + Delete icon + Cancel). New `combinedClickable` on `VaultFileCard` with `onClick` (toggle if mode active, open otherwise) and `onLongClick` (always toggle). Visual: 3 dp primary border + 25 % primary tint overlay + check-circle badge in TopStart. New `_selectedFileIds: MutableStateFlow<Set<String>>` + `selectedFileIds: StateFlow` + `toggleSelection(id)`/`clearSelection()` + `exportSelected()`/`deleteSelected()` + `ExportEvent(success, total)` snackbar event on `VaultViewModel`. New `FileEncryptionService.exportToMediaStore(vaultFile): Uri?` builds `ContentValues(DISPLAY_NAME, MIME_TYPE, RELATIVE_PATH=Pictures/StealthCalc \| Movies/StealthCalc \| Music/StealthCalc, IS_PENDING=1)`, `resolver.insert(EXTERNAL_CONTENT_URI by type)`, streams plaintext into `resolver.openOutputStream(uri)` via `decryptToOutputStream` (no plaintext temp file ever materialized), clears `IS_PENDING`. Failure path deletes the half-written pending row. Added `<uses-permission android:name="WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />` for legacy-storage devices. New `sanitizeExportName()` helper strips `\\/:*?"<>|` from recorder titles like `"Video Apr 14, 2026 08:30.mp4"`. |
| Item 3 — Photo merge | `6eb39dc` | New 2-step flow. From `VaultFileViewerScreen.kt`, when the current page is a PHOTO, the toolbar shows a `MoreVert` overflow with "Merge with another photo". Tap → navigates to `photo_merge_pick/{baseId}`. New **`PhotoMergePickerScreen.kt`** (3-col `LazyVerticalGrid` of in-vault photos minus base; reuses `FileEncryptionService.decryptThumbnail`) + **`PhotoMergePickerViewModel.kt`** (`@HiltViewModel`, `SavedStateHandle["baseId"]`, `repository.getFiles(type=PHOTO).map { it.filterNot { ... == baseId }}`). Pick → navigates to `photo_merge/{baseId}/{overlayId}` with `popUpTo(picker, inclusive=true)`. New **`PhotoMergeScreen.kt`** uses Compose `graphicsLayer { translationX/Y, scaleX/Y, rotationZ }` + `alpha()` for live preview at zero per-frame alloc; `detectTransformGestures { _, pan, zoom, rotation -> ... }` drives the transform. New **`PhotoMergeViewModel.kt`** decrypts both bitmaps off the main thread, `mergeAndSave(transform: OverlayTransform)` runs `composeMerged(base, overlay, transform)` on `Dispatchers.Default` via `android.graphics.Canvas` + `Matrix` (postTranslate centered → postScale → postRotate → postTranslate user offset → postScale into result-pixel coords) at the base image's NATIVE resolution; Saved as `Merged_<yyyyMMdd_HHmmss>.jpg` via the existing `encryptionService.encryptBitmap` + `repository.saveFile`. `onSaved` pops back to `Vault` route inclusive=false. ML background removal **deferred** per user — adds ~10 MB ML Kit dep + new screen, scoped for a future round. |
| Item 4 — Real-lock during recording | `6eb39dc` | New default UX. Settings: `KEY_USE_REAL_LOCK_DURING_RECORDING` pref (default `true`), exposed via `SettingsState.useRealLockDuringRecording` + `setUseRealLockDuringRecording()`. New `SettingsToggle` row "Use real device lock while recording" with subtitle that flips on toggle. `MainActivity` injects `@EncryptedPrefs SharedPreferences encryptedPrefs` and the `RecorderService.isRecording` observer now reads the pref each emission: if real-lock pref is true, ALWAYS calls `setShowWhenLocked(false)` (stops fighting the real keyguard); otherwise old behavior. `RecorderViewModel.enterCoverScreen(secretPin)` short-circuits when pref is true (no overlay, no in-activity cover). `RecorderViewModel.startRecording()` skips the auto-`showCoverScreen=true` when pref is true. `RecorderScreenState` gained `useRealLockDuringRecording: Boolean` (default `true`) re-read from prefs on every `RecorderService.isRecording` emission so a Settings toggle takes effect on the next recording. `RecorderService.createNotification()` adds `setVisibility(NotificationCompat.VISIBILITY_SECRET)` + the channel adds `lockscreenVisibility = Notification.VISIBILITY_SECRET` so the lock screen stays clean while recording. `RecorderScreen.kt` replaces the "Show lock screen" button with a hint card "Press the power button — recording continues" + "Unlock with your phone PIN to return here. Tap Done in the notification or back here to stop." when pref is on. Legacy `FakeSignInScreen` + `OverlayLockService` paths preserved as opt-out (toggle the pref off → old fake-cover UX comes back). |
| S — Build fix: delegated property smart cast | `9b5d554` | First Round 5 push failed at `compileDebugKotlin`: the `actions` lambda inside `VaultFileViewerScreen.kt`'s `TopAppBar` had `if (currentFile?.fileType == VaultFileType.PHOTO) { ... onMergePhoto(currentFile.id) ... }` — `currentFile` is `var by remember { mutableStateOf<VaultFile?>(null) }` (a delegated property), so Kotlin can't smart-cast across the null check. Captured `val cf = currentFile` immediately; `if (cf != null && cf.fileType == VaultFileType.PHOTO) { ... onMergePhoto(cf.id) }`. Same pattern as Round 1's `mediaRecorder` smart-cast issue documented in `ANDROID_BUILD_LESSONS.md`. |
| O2/O3/O4 — ExoPlayer listener + fsync + recording sanity log | `107c4dc` | Three changes after the user reported "imports play, recordings don't". (a) ExoPlayer listener was attached AFTER `prepare()` in a `DisposableEffect` — any synchronous error fired by `prepare()` was lost, leaving `isBuffering=true` forever. Reordered: build player in `remember{}`, attach listener as the FIRST thing inside `DisposableEffect`, THEN `setMediaItem` + `prepare()`. Plus a 12s no-`STATE_READY` timeout that surfaces "Timed out preparing video" + logs to `app.txt`. (b) `encryptStream`'s `fos.flush() + fos.fd.sync()` after the inner `CipherOutputStream.use{}` was a silent no-op — `CipherOutputStream.close()` closes the underlying `FileOutputStream` per its contract, so `flush` did nothing and `fd.sync()` threw `IOException` swallowed by `runCatching`. Rewrote to drive the cipher with `update()` / `doFinal()` so `fos` stays open through the explicit fsync. (c) New `validateAndLogRecording()` in `RecorderService` peeks the recording's first 12 bytes (verify ASCII `ftyp` MP4 box at offset 4..7) + runs `MediaMetadataRetriever` for duration / w x h / mime, logs everything to `app.txt` BEFORE encryption. This is the diagnostic that exposed O1. |
| O1 — HOTFIX streaming AES-CTR + HMAC | `33cd2d3` | The Round 5 diagnostic captured this stack: `OutOfMemoryError: Failed to allocate a 268435472 byte allocation ... at OpenSSLAeadCipher.appendToBuf(OpenSSLAeadCipher.java:313) ... at javax.crypto.Cipher.update(Cipher.java:1741) at FileEncryptionService.encryptStream`. Conscrypt (Android's default JCE provider) implements AES/GCM by buffering the ENTIRE ciphertext in an internal `ByteArrayOutputStream` until `doFinal()` is called — GCM authentication needs to process all data before producing the tag. A 446 MB recording needs ~446 MB of heap (allocator dies at the ~268 MB doubling step). Both the manual `cipher.update()` rewrite from O3 AND the original `CipherOutputStream(fos, cipher)` wrapping suffer from this — both feed Conscrypt's GCM, which is fundamentally non-streaming. Fix: switch to **AES-256-CTR + HMAC-SHA256**, the standard streaming encrypt-then-MAC AEAD construction. CTR is a true stream cipher (each `update()` returns exactly the bytes you fed it, no buffering); HMAC is computed in the same pass over `magic ‖ iv ‖ ciphertext`. Constant memory at any file size. New file format v2: `[4 bytes magic "SC2v"][16 bytes random CTR IV][N bytes ciphertext][32 bytes HMAC-SHA256 tag]`. Backward compat: `decryptFileTo()` peeks first 4 bytes; if `V2_MAGIC` matches → `decryptV2(fis, totalLen, out)`; else → `decryptLegacyGcm(fis, out)`. New writes always v2. Touched: `encryptStream` (rewrite), `decryptFile` (now wraps `decryptFileTo`), new `decryptV2` + `decryptLegacyGcm` + `deriveHmacKey(SecretKey): SecretKeySpec` (HKDF replaced with `SHA-256(key ‖ "stealthcalc-hmac-v2")` — adequate when master key already has full Keystore entropy), `decryptToOutputStream` (now wraps `decryptFileTo`), `decryptToStream` (now uses `PipedInputStream` filled by a daemon thread running `decryptFileTo` so external consumers stream too), `decryptThumbnail` (decrypt to `ByteArrayOutputStream(<256 KB)` then `BitmapFactory.decodeByteArray`). The user's 446 MB MP4 sitting in `filesDir/recordings/` from the failed encrypt is picked up by `RecordingRecovery.scanAndRecover()` on next app start (markerless ≥1 KB / >60s old path) and successfully re-encrypted with v2. |

Round 5 known limitations (deferred):
- ML-based background removal not implemented this round (user-deferred). The new `PhotoMergeScreen` already does Compose canvas + Bitmap compositing; adding `com.google.mlkit:segmentation-subject:16.0.0-beta1` (~10 MB APK) and a third "Remove background" operation that runs the segmentation model on the base bitmap is a one-screen change.
- Legacy GCM-encrypted vault files larger than ~150 MB will OOM on decrypt because Conscrypt's GCM buffers the whole plaintext during `doFinal()`. In practice no such file exists (the old encrypt would have OOMed at the same point). If one is ever discovered (e.g. transferred from another device install), it needs either a one-shot heap bump (`largeHeap="true"` in manifest, ugly) or a re-encrypt-via-SAF tool. New writes are always v2 so this can only worsen if a legacy build re-appears.
- The 12s ExoPlayer prepare timeout is conservative — extremely large videos on slow storage may legitimately take longer to prepare. So far the data-point we have is 446 MB / 5 min played in well under 12s after the v2 fix; bump if it ever bites.

---

## Post-testing iterations — Round 4 (2026-04-15)

User reported the Round 3 APK (`0f9037b`) no longer crashed when opening a saved recording, but the files still didn't play. Root-caused + four media fixes, plus the user's requested Round 4 stability/security bundle (A, B, C, D, J). All on branch `claude/fix-media-playback-bug-XM1sr`.

| Fix | Commit | Issue → Resolution |
|---|---|---|
| N1 — Decrypt temp filename | `7cb44b6` | `FileEncryptionService.decryptToTempFile` was composing `view_<uuid>_<originalName>` as the cache filename. For recorder files, `originalName` = `"Video Apr 14, 2026 08:30.mp4"` (colons, commas, spaces). `VideoView.setVideoPath` → `Uri.parse` → `MediaPlayer.setDataSource` mangles such strings; prepare fails with `status=0x1` (exact stack from the 0f9037b crash). Rewrote to `view_<uuid>.<ext>` + `extensionFor()` helper that prefers the extension from `originalName` only if short + fully alphanumeric, else falls back to `.mp4`/`.m4a`/`.jpg` by `VaultFileType`. |
| N2 — Fsync after GCM tag | `c9ac879` | `encryptStream`'s nested `use {}` blocks correctly sequence `CipherOutputStream.close()` (writes auth tag) → `FileOutputStream.close()`. But `close()` only flushes Java buffers, not the OS page cache. Android 14+ reaps FGS processes fast after `stopForeground`; the GCM tag can be lost to truncation. Added `fos.flush() + fos.fd.sync()` inside the outer `use`. `runCatching` on `fd.sync()` for filesystems that don't support it. |
| N3 — Log decrypt failures | `0bef40d` | `VaultFileViewerViewModel.decrypt` was `runCatching.getOrNull()` — silently swallowed every exception, so `app.txt` never showed the real cause (bad tag, missing file, etc.). Replaced with explicit try/catch that calls `AppLogger.log` with id + originalName + type + encrypted-file size + exception class/message. `@ApplicationContext` injected for the logger call. |
| N4 — Zero-byte recording guard | `962289d` | `MediaRecorder.start()` / CameraX occasionally silently produce a 0-byte file. `persistRecordingToVault` was encrypting it into an `.enc` of pure `[IV][GCM tag]`, which decrypted to empty plaintext → `MediaPlayer.prepare()` failure. Added `MIN_VALID_RECORDING_BYTES=1024` guard; below threshold we log, delete the plaintext, return a placeholder Recording with " (empty)" suffix, and write no vault row. |
| A — Battery exemption | `2eb5766` | New Settings row "Disable Battery Optimization". `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` added to manifest. Not-exempt tap → `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with package data (one-tap system dialog). Exempt tap → `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` so revoke stays user-initiated. `PowerManager.isIgnoringBatteryOptimizations` drives the row subtitle. Side note: removed a duplicate `val context = LocalContext.current` from the Diagnostics section that the new row's higher-scope declaration shadowed. |
| D — Notification stop | `f39a89e` | `NotificationCompat.Builder.addAction("Done", pendingIntent)` on the recorder foreground notification. `PendingIntent.getService` targets `RecorderService` with `ACTION_STOP` (already wired in `onStartCommand`). `FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT`; `requestCode=1` to distinguish from the tap-to-open PI on `requestCode=0`. Label is "Done" (not "Stop recording") — stays covert with the "Calculator / Calculation in progress..." text. |
| J — Secure-delete | `3370b4c` | New `FileEncryptionService.secureDelete(file)`: `RandomAccessFile` rewrite with `SecureRandom` 8KB chunks + `fd.sync()` + `delete()`. Best-effort — documented limits (flash wear leveling, SQLCipher WAL). Wired into `VaultRepository.deleteFile` (encrypted payload + thumbnail), `VaultFileViewerViewModel.trimCache` + `onCleared` (plaintext temp cache — biggest leak vector), and `RecorderRepository.deleteRecording`. `RecorderRepository` now also cascades into `VaultRepository.deleteFile` when the Recording has a `vaultFileId`, closing the Round 1 known limitation about orphan vault files after deleting a recording. |
| C — Auto-resume | `d2e0b82` | New `recorder/service/RecordingRecovery.kt` (@Singleton). `RecorderService.startRecording` atomically writes `.in_progress_<id>` (tmp + rename) with id/type/facing/startTime/outputPath BEFORE any MediaRecorder/CameraX setup. Clean exit paths (`persistRecordingToVault` success + N4 zero-byte) delete the marker. `scanAndRecover()` (on `Dispatchers.IO`) walks `filesDir/recordings/`: markers → finalize via existing `encryptLocalFile` → `vaultRepository.saveFile` → `recorderRepository.saveRecording` (title gets " (recovered)" suffix); markerless `.m4a`/`.mp4` ≥1KB + older than 60s → also recovered (inferred type from extension, startTime from `file.lastModified()`). Skipped if `RecorderService.isRecording` is true. Hooks: `StealthCalcApp.onCreate` via `EntryPoints.get(RecordingRecovery.Accessor)` (Hilt doesn't field-inject Application); `BootReceiver.onReceive` same pattern for post-reboot. |
| B — Overlay lock | `948a29d` | New `OverlayLockService` + `OverlayLockBus` (@Singleton). Service uses `WindowManager.addView` with `TYPE_APPLICATION_OVERLAY`, intentionally with plain Android Views (LinearLayout + GridLayout of Buttons) to avoid ComposeView ViewTree owner plumbing. Clock updates every second via Handler. Correct PIN sends self-addressed `ACTION_DISMISS` which removes the view + `bus.markDismissed()` + `stopSelf()`. Manifest: `SYSTEM_ALERT_WINDOW` permission + service declaration. `SettingsViewModel.KEY_OVERLAY_LOCK_ENABLED` pref + `setOverlayLockEnabled` setter. New Settings row "Secure Overlay Lock" toggles the pref + launches `ACTION_MANAGE_OVERLAY_PERMISSION` when enabling without grant. `RecorderViewModel.enterCoverScreen(secretPin)` checks pref + `canDrawOverlays` and picks overlay or in-activity path. Plain pin is threaded through from `AppNavigation.activeSecretPin` → `RecorderScreen` → `viewModel.enterCoverScreen(pin)` → `bus.configure(pin)`. |

Round 4 known limitations (deferred):
- Feature B: Android 10+ system gesture bars take touch priority over overlays — the user CAN still home-swipe, but the overlay remains visible atop the landing app. Acceptable for the stealth threat model (the cover follows the user around the OS) but not a true gesture-block.
- Feature C: a recording interrupted exactly while writing the MP4 `moov` atom can leave a partially-corrupt plaintext. Caught by our `runCatching` + log; the file is left in `recordings/` for manual triage rather than being deleted.
- Feature J: flash wear leveling / bad-block remapping; one random overwrite is the practical ceiling from userspace. Hardware SECURE_ERASE not accessible.
- Feature A: battery-opt exemption is user-opt-in. Can't force the grant without breaching Play policy for non-alarm apps.

---

## Post-testing iterations — Round 3 (2026-04-14)

Round 2 APK shipped with the CameraX video path still missing and the `Intent.ACTION_PICK` handoff still routing to Google Photos (which forces cloud sign-in on Pixel). The fake lock was also sensitive to real-phone lock (rotation / touch triggered recreation) and `startLockTask()` was firing the unsuppressible "Screen pinned" toast. Four commits on branch `claude/fix-cloud-signin-video-bugs-cGptT`:

| Fix | Commit | Issue → Resolution |
|---|---|---|
| J — In-app gallery picker | `ba15641` | Built `InAppMediaPickerViewModel` + `InAppMediaPickerScreen` that query `MediaStore.Images/Video.EXTERNAL_CONTENT_URI` directly via `ContentResolver`. Thumbnails via `ContentResolver.loadThumbnail(uri, Size(256, 256), null)` on API 29+ with a bounded LRU cache (~120 entries). `LazyVerticalGrid` with tab switcher, multi-select, duration badge on videos. Nested `navigation("vault_graph")` wraps `Vault` + `MediaPicker` so both resolve to the SAME `VaultViewModel` via `hiltViewModel(parentEntry)` — the picker's `onImport` calls `vaultVm.importFiles(uris, deleteOriginals = true)` on the same VM that renders the grid, keeping the MediaStore delete-confirm pipeline intact. `VaultScreen` lost its `Intent.ACTION_PICK` launchers + permission flow entirely; gained `onPickPhotos`/`onPickVideos` callbacks. Google Photos / system photo picker is never invoked. URIs returned are real `content://media/external/...` rows so `createDeleteRequest` still deletes the originals on approval. |
| K — Video via CameraX | `992c4c7` | Converted `RecorderService: Service` → `RecorderService: LifecycleService` (chained `super.onBind` + `super.onStartCommand` as required). Split `startRecording(type, facing)` into `startAudioRecording()` (MediaRecorder unchanged) and `startVideoRecording(facing)` (new). Video path: `ProcessCameraProvider.getInstance(this)` + `Recorder.Builder().setQualitySelector(QualitySelector.fromOrderedList(HD, SD, LOWEST), FallbackStrategy.lowerQualityOrHigherThan(LOWEST))` + `VideoCapture.withOutput(recorder)` + `bindToLifecycle(this, selector, capture)` + `prepareRecording(this, FileOutputOptions.Builder(outputFile).build()).withAudioEnabled().start(mainExecutor, listener)`. `handleVideoRecordEvent` mirrors the audio path's state machine — Start fires `_isRecording=true` + elapsed timer; Finalize runs the existing `persistRecordingToVault` chain on `Dispatchers.IO` inside `serviceScope.launch`, then `releaseWakeLock` + `stopForeground` + `stopSelf`. `stopRecording()` short-circuits for video with `videoRecording.stop()` — the Finalize event drives the rest. `cleanupVideo()` on every stop path. Added `androidx.camera:camera-video:1.3.4` and `androidx.lifecycle:lifecycle-service:2.8.4` to `libs.versions.toml` + `app/build.gradle.kts`. FGS type stays MICROPHONE|CAMERA for video — `promoteToForeground()` still runs first in `onStartCommand` so the 5–10s deadline is safe. |
| L — Lock-resilient recording | `a7f85bc` | Three-part fix. Manifest: `MainActivity` gained `android:configChanges="orientation\|screenSize\|screenLayout\|keyboardHidden\|uiMode\|smallestScreenSize\|navigation\|keyboard\|density\|fontScale"` so config changes don't recreate. Added `android.permission.WAKE_LOCK`. `MainActivity` collects `RecorderService.isRecording` and calls `updateShowWhenLocked(isRecording)` which toggles `setShowWhenLocked` + `setTurnScreenOn` on API 27+ (flag fallback on 26). `RecorderService.promoteToForeground()` now also calls `acquireWakeLock()` after `startForeground()` succeeds — lazy-creates a `PARTIAL_WAKE_LOCK` tagged `"StealthCalc:RecorderWakeLock"` with `setReferenceCounted(false)` and a `maxDurationMs + 60_000L` timeout (Lint / Doze requirement). `releaseWakeLock()` is called in the `startRecording` failure catch, `stopRecording()` success + no-file paths, the video Finalize success + no-file paths, and `onDestroy()` as a safety net. |
| M — No pinning toast | `a766fd0` | Removed `runCatching { activity?.startLockTask() }` (line 105) and `runCatching { activity?.stopLockTask() }` (line 110) from `FakeSignInScreen.kt` along with the paragraph-comment block explaining them. Updated the surviving `DisposableEffect` comment to note why — the toast is system-managed and un-suppressible from a regular app. `FLAG_KEEP_SCREEN_ON` + `BackHandler(enabled=true)` + `WindowInsetsControllerCompat` immersive-sticky remain. |

Round 3 known limitations:

- **Home-gesture dismiss:** without `startLockTask()`, the Pixel bottom home-swipe can drop out of the fake lock. The recording itself doesn't stop — the foreground service + wake lock keep the capture pipeline alive independently of the activity. User unlocks via calculator PIN → returns to recorder → taps stop.
- **Battery-Restricted cohort:** users who set the app to `Battery → Restricted` can still get the foreground service killed. Out of scope for this round; doze exemption needs a user-visible prompt.
- **CameraX quality fallback:** `QualitySelector.fromOrderedList(HD, SD, LOWEST)` — Pixel handles HD trivially; the fallback just protects against low-end devices missing HD encoder profiles. If users report grainy video, raise to `FHD` first and keep SD as backstop.
- **Partial photo grant:** the picker honors the grant level as-is; "Selected photos only" (API 34+) means MediaStore returns the filtered subset. A `READ_MEDIA_VISUAL_USER_SELECTED` + photo-scope-change re-prompt UI is deferred.

---

## Post-testing iterations — Round 2 (2026-04-14)

User installed the Round 1 APK and tested on the same Pixel 6 / Android 16. The exported `app.txt` showed three distinct errors + three `[FATAL]` blocks, all from the recording path, plus three `Import delete skipped` lines proving the Round 1 picker-URI resolution never actually found MediaStore matches. These fixes landed:

| Fix | Commit | Root cause → Resolution |
|---|---|---|
| F — Recording FGS type | `d9e0d53` | Three cascading service bugs all with one root: (a) `SecurityException: Starting FGS with type camera ... requires CAMERA` — on Android 14+ the runtime `foregroundServiceType` must be a subset of the manifest declaration AND each type's permission must be granted. Manifest declares `microphone\|camera` (max); default promotion uses the union, which fails for audio because CAMERA isn't requested. (b) `ForegroundServiceDidNotStartInTimeException` — `startForeground()` was the last line of `startRecording`, after `MediaRecorder.prepare()/start()`, which throws cheerfully. When it did, the catch block logged but never promoted — system killed the process for missing the ~5–10s deadline. (c) `RuntimeException: start failed` — follow-on of (a). Fix: new `promoteToForeground(type)` called FIRST in `onStartCommand` with a runtime type of `FOREGROUND_SERVICE_TYPE_MICROPHONE` for audio and `MICROPHONE\|CAMERA` for video. MediaRecorder setup runs after we're safely foreground; any exception there now stops the FGS cleanly with `stopForeground(STOP_FOREGROUND_REMOVE) + stopSelf()`. |
| G — Gallery picker | `9ca8d73` | Round 1's `resolveToMediaStoreUri` couldn't find matches even with `READ_MEDIA_*` granted because the system photo picker privacy-strips the picker-URI-to-MediaStore mapping intentionally. Fix: switch from `ActivityResultContracts.GetMultipleContents` (which funnels through `ACTION_GET_CONTENT` → photo picker on API 33+) to a raw `Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)` with `EXTRA_ALLOW_MULTIPLE=true`, launched via `StartActivityForResult`. `ACTION_PICK` opens the legacy gallery picker (stock Gallery / Photos) and returns real `content://media/external/images/media/N` URIs that `createDeleteRequest` can actually delete. The `resolveToMediaStoreUri` code stays as a fallback for gallery apps that return non-MediaStore URIs (cloud, Drive). |
| H — Cover auto-dismiss | `7adfa5c` | `RecorderViewModel.startRecording()` set `showCoverScreen = true` preemptively. If the service then failed to record (Round 2 Fix F wasn't in), the fake lock stayed up forever with nothing captured. Added a `viewModelScope.launch { delay(4_000); if (!RecorderService.isRecording.value && state.showCoverScreen) ... }` safety net. |
| I — Docs | `5617f60` | Added two new tables to `docs/ANDROID_BUILD_LESSONS.md` — "Foreground services on Android 14+ (API 34+)" and "MediaStore deletion + picker URIs" — so the next project hits neither trap. |

Round 2 known limitations:
- **`ACTION_PICK` UI differs:** the legacy gallery picker looks older than Google's photo picker and may default to Google Photos' old picker interface. Functionality is identical; just uglier. If this matters, the real fix is a custom in-app gallery using `MediaStore.Images.Media.query()` — not worth it unless the picker UX becomes a ship-blocker.
- **Cloud-only Google Photos items** returned by `ACTION_PICK` aren't MediaStore-backed, so can't be deleted. `resolveToMediaStoreUri` still falls back to name+size query; fails gracefully by logging.

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

---

## Round 8 — Feature Plan & Execution (2026-04-20)

**Branch:** `claude/round8-features-bYw2Y` → merged to `master`
**Scope:** 16 new features across Tier 1 (10 items) and Tier 2 (6 items). No Notes/Tasks changes.

| # | Feature | Approach | Deviation / Notes |
|---|---------|----------|-------------------|
| R8-1 | AMOLED theme | `darkColorScheme()` with `Color(0xFF000000)` background; toggled in Settings | Reads pref once at `setContent`; live update deferred to next cold start (standard Android pattern) |
| R8-2 | App icon switcher | `<activity-alias>` in manifest + `PackageManager.setComponentEnabledSetting` | 3 aliases: Calculator (default), Clock (blue `#1565C0`), Notes (green `#2E7D32`). `@ApplicationContext` added to `SettingsViewModel` |
| R8-3 | Biometric long-press `=` | `combinedClickable` on `=` key; `AppRoot` passes `biometricHelper` callback | `BiometricHelper` updated from `FragmentActivity` → `ComponentActivity` for `biometric:1.2.0-alpha05` |
| R8-4 | Shake sensitivity | Prefs-backed computed property on `PanicHandler.shakeThreshold` | Reads prefs on every sensor check; no service restart needed |
| R8-5 | Clipboard timeout | `SecureClipboard.scheduleAutoClear()` reads pref; `-1L` = never | `copy()` and `copyWithLabel()` both trigger auto-clear |
| R8-6 | Recording cascade delete | — | Already implemented Round 4; no work needed |
| R8-7 | Thumbnail regen | New `FileEncryptionService.regenerateThumbnail()` + `VaultDao.updateThumbnailPath()` | Currently PHOTO type only; VIDEO regen deferred |
| R8-8 | OCR on photos | ML Kit `TextRecognition` on `workingBitmap`; result dialog with Copy | New dep `com.google.mlkit:text-recognition:16.0.1` |
| R8-9 | Remote lock | `RemoteCommandHandler`: `lock_device` → `DevicePolicyManager.lockNow()` | Falls back to log if no active admin |
| R8-10 | Scheduled windows | `AgentService.isWithinSchedule()` reads prefs directly | `ScheduleConfigViewModel` keys duplicated; can't inject ViewModel into Service |
| R8-11 | Browser UA | `GeckoRuntimeSettings.Builder.userAgentOverride(...)` in `BrowserScreen` | Single-line change |
| R8-12 | Decoy wipe | `CalculatorViewModel` calls `WipeManager.wipeAll()` on `DecoyValid` result if pref set | Wipe runs before `DecoyUnlocked` return; user sees empty vault |
| R8-13/14 | Save page to vault | `BrowserViewModel.savePageToVault()` writes URL+title stub as encrypted text | Full MHTML download deferred — GeckoView 123 download delegate API too version-specific |
| R8-15 | Remote wipe | `RemoteCommandHandler`: `wipe_vault` → `WipeManager.wipeAll()` | Dashboard confirmation dialog guards accidental wipe |
| R8-16 | Timeline view | New `TimelineScreen` + `TimelineViewModel`; last 500 events grouped by hour | Wired from Dashboard alongside existing Map button |
