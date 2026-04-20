# StealthCalc — Issues Found (2026-04-14 session)

Detailed findings from code audit in response to user runtime reports. Companion doc: `docs/FIX_PLAN.md` has the remediation plan.

**Repo state when audit ran:** `master` HEAD = `78849f3`.
**Branch worked on:** `master` (all recent work is merged there).
**Build state:** master builds successfully and the APK installs. Everything below is a runtime / UX problem, NOT a build problem.

---

## Status (updated 2026-04-14 post-fix)

All seven issues below are **FIXED**. Commits on `master`:

| # | Commit | Area |
|---|---|---|
| 1 | `4206bee` | File-based crash logger + Settings "Export crash log" |
| 2 | `90790de` | Persist PIN on setup; defensive `NotSetup` guard |
| 3a | `3d6b8fc` | Decrypt vault thumbnails for the grid |
| 3b | `5c25c09` | Vault file viewer (photo/video/audio/open-with) |
| 3c | `1f3a351` | Real gallery deletion via MediaStore PendingIntent |
| 4 | `583ca17` | Recordings encrypt → vault on stop; plaintext deleted |
| 5 | `f833ba7` | Fake lock screen disables Back + keeps screen on |
| J | `ba15641` | In-app MediaStore picker (no Google Photos cloud sign-in) |
| K | `992c4c7` | Video recording via CameraX VideoCapture (LifecycleService) |
| L | `a7f85bc` | WAKE_LOCK + setShowWhenLocked + configChanges for recording resilience |
| M | `a766fd0` | Drop startLockTask/stopLockTask to kill "Screen pinned" toast |
| N1 | `7cb44b6` | Sanitize vault viewer decrypt temp filename (primary media-playback bug) |
| N2 | `c9ac879` | Fsync after AES-GCM tag write so fast service reap can't truncate the file |
| N3 | `0bef40d` | Log decryption failures via AppLogger so `app.txt` captures root cause |
| N4 | `962289d` | Guard zero-byte recordings from reaching the vault |
| A (R4) | `2eb5766` | Battery optimization exemption Settings row |
| D (R4) | `f39a89e` | "Done" action on the recorder foreground notification |
| J (R4) | `3370b4c` | Secure-delete (random overwrite) + recorder → vault cascade on delete |
| C (R4) | `d2e0b82` | Recording marker + on-start/on-boot orphan auto-resume |
| B (R4) | `948a29d` | SYSTEM_ALERT_WINDOW overlay lock service (home-swipe survives) |
| Round 5 | `6eb39dc..33cd2d3` | ExoPlayer video, vault export, photo merge, real-lock UX, AES-CTR+HMAC streaming encryption (OOM hotfix) |

Each fix is its own commit, pushable and bisectable by GitHub Actions. See `docs/FIX_PLAN.md` for what shipped per fix + any deviations from the original plan.

---

## Post-testing iterations — Round 1 (2026-04-14)

User installed the initial 7-fix APK (`f833ba7`) on a Pixel 6 / Android 16 (API 36, targetSdk 35) and reported five new issues. All fixed on `master`; see `docs/FIX_PLAN.md` "Post-testing iterations — Round 1" for details.

| # | User report | Commit | Root cause |
|---|---|---|---|
| A | "the export log should be a txt file. I already told you before I can view .log files" | `5317a38` | Log named `app.log`; stock Android viewers don't recognize the extension. Renamed to `app.txt`. |
| B | "the audio and video recording doesnt actual record anything when I click on tap to record button" | `e3b2c55` | Runtime permissions (`RECORD_AUDIO`/`CAMERA`/`POST_NOTIFICATIONS`) declared in manifest but never requested. `MediaRecorder.start()` threw `SecurityException` which the service silently swallowed. |
| C | "the videos and photos should be a scrollable view so I don't keep having to hit back to see next photo or video" | `1787bbf` | Viewer took a single `fileId` and rendered one file. Rewritten around `HorizontalPager` with on-demand decryption + per-fileId cache trim. |
| D | "if I swipe up on my pixel the fake screen can be discard like any other app" | `a16f3e8` | System bars still visible on the fake lock; Android home-swipe not blockable by normal apps. Added immersive-sticky + best-effort `startLockTask()` (requires the user to enable App Pinning in Settings → Security). |
| E | "the import photos and videos is still leaving a copy in the library" | `a10f5dd` | Photo-picker URIs returned by `GetMultipleContents` aren't MediaStore URIs, so `createDeleteRequest` was a silent no-op. **First attempt** added `resolveToMediaStoreUri` lookup by `(DISPLAY_NAME, SIZE)` + manifest `READ_MEDIA_*` perms. See Round 2 Fix G — this still didn't fully work because the photo picker deliberately strips that mapping. |

---

## Post-testing iterations — Round 5 (2026-04-15)

User reports after Round 4 ship:
1. "the video player is still not working. after I record a video, and stop recording. the video saves to the vault, but when I click on the video it just spins and never loads."
2. "I want an ability to export my vault files, videos, photos to my local library."
3. "I want the ability to edit my photos. specifically the ability to merge two photos together. the ability to remove background of any photo and keep the person."
4. "is there a way to keep record video using my app while the phone is in real lock mode?"

Plus user re-test after the initial Round 5 ExoPlayer ship: "looks like everything works except the video media player. I'm still not able to play videos that were recorded by the app. if I import a video from library gallery, it works fine."

All five items addressed across `6eb39dc..33cd2d3` on branch `claude/fix-video-player-add-features-Pyk9Z`. See `docs/FIX_PLAN.md` "Post-testing iterations — Round 5" for what shipped per fix.

| # | Issue → Resolution | Commit | Root cause |
|---|---|---|---|
| O1 | Recorded videos still won't play | `33cd2d3` | The smoking gun. Sanity-log added in commit `107c4dc` shipped a build that captured `recording sanity ... size=467799153 ftypOk=true retrieverOk=true durMs=309000 wxh=1280x720` followed by `OutOfMemoryError: Failed to allocate a 268435472 byte allocation ... at OpenSSLAeadCipher.appendToBuf(...) at javax.crypto.Cipher.update(...) at FileEncryptionService.encryptStream`. Conscrypt (Android's default JCE provider) implements AES/GCM by buffering the entire ciphertext in an internal `ByteArrayOutputStream` until `doFinal()` — a 446 MB / 5min09s recording requires ~446 MB heap, OOMs at the ~268 MB doubling step. Both this round's manual `cipher.update()` rewrite AND the original `CipherOutputStream(fos, cipher)` path have the same problem because both feed Conscrypt's GCM, which is fundamentally non-streaming. Switched encryption to **AES-256-CTR + HMAC-SHA256**, the standard streaming encrypt-then-MAC AEAD construction. CTR is a true stream cipher (each `update()` returns exactly the bytes you fed in, no buffering); HMAC is computed in the same pass. Constant memory at any file size. Backward-compat decrypt detects format by 4-byte magic ("SC2v") and falls through to the legacy GCM path for old files. |
| O2 | ExoPlayer "spins forever" intermittently | `107c4dc` | Within the same hotfix commit. Round 5's initial ExoPlayer integration built the player + called `prepare()` inside `remember{}`, then attached the `Player.Listener` in a separate `DisposableEffect`. `DisposableEffect` runs in the post-composition effect phase, AFTER the body — any error / state change fired synchronously by `prepare()` was lost, leaving the UI stuck on `isBuffering=true`. Reordered: build player in `remember{}`, attach listener AS THE FIRST THING inside `DisposableEffect`, THEN `setMediaItem` + `prepare()`. Plus a 12-second no-`STATE_READY` timeout that surfaces an explicit "Timed out preparing video" error so an infinite spinner can never recur. |
| O3 | encryptStream() fsync was a silent no-op | `107c4dc` | Pre-existing, surfaced during the OOM debug. `CipherOutputStream.close()` flushes + closes the WRAPPED `FileOutputStream` as part of its contract. The Round 4 N2 code's `fos.flush() + fos.fd.sync()` after the inner `use{}` block ran on an ALREADY-CLOSED fd: `flush` was a no-op and `fd.sync()` threw `IOException` that `runCatching` swallowed. Plausible cause of legacy GCM tag truncation despite the N2 fix. Round 5's V2 (CTR+HMAC) rewrite drives the cipher manually, so we keep `fos` open through the explicit `flush + fd.sync()` and close it ourselves. |
| O4 | Recording sanity diagnostics in app.txt | `107c4dc` | Without observability, diagnosing "video saved but won't play" was guesswork (Round 4 N1-N4 each chased a different symptom). New `validateAndLogRecording()` runs on the plaintext recording before encryption: checks the first 12 bytes for the MP4 `ftyp` box, runs MediaMetadataRetriever for duration / w x h / mime, logs everything to `app.txt` under tag "recorder". This is what made O1 a 5-minute root cause instead of another guessing round. |
| P | Vault → device library export | `6eb39dc` | New "select vault files, push to public media library" flow. Long-press a card in `VaultScreen` enters selection mode; toolbar swaps to count + Export + Delete + Cancel; tap Export streams decrypted bytes into `MediaStore.Images/Video/Audio.Media.EXTERNAL_CONTENT_URI` via `ContentResolver.insert` + `openOutputStream` (`Pictures/StealthCalc`, `Movies/StealthCalc`, `Music/StealthCalc`). NO plaintext temp file ever written. New `FileEncryptionService.exportToMediaStore()` + `_selectedFileIds: StateFlow<Set<String>>` + `exportSelected()`/`deleteSelected()` on `VaultViewModel` + `combinedClickable` on the file card. Manifest gained `WRITE_EXTERNAL_STORAGE` capped at API 28 (legacy storage); API 29+ uses scoped-storage `MediaStore.insert` which grants implicitly via the returned URI. |
| Q | Photo merge editor | `6eb39dc` | New 2-step flow accessible from the file viewer's overflow menu when a PHOTO is open: (1) `PhotoMergePickerScreen` shows in-vault photos minus the base, (2) `PhotoMergeScreen` overlays the second photo with drag / pinch-zoom / twist-rotate / opacity slider via Compose `graphicsLayer` (no per-frame Bitmap alloc). Save composes via `android.graphics.Canvas` at the base image's native resolution, encrypts via the existing `encryptBitmap`, persists as `Merged_<timestamp>.jpg`. Three new files: `PhotoMergePickerScreen.kt`, `PhotoMergeScreen.kt`, `PhotoMergeViewModel.kt` (+ `PhotoMergePickerViewModel.kt`). Two new routes in `AppNavigation.kt`. ML background removal explicitly deferred per user — adds a 10 MB ML Kit dep + sizable UI module — but the Compose canvas + Bitmap pipeline is in place so adding `com.google.mlkit:segmentation-subject` later is a one-screen change. |
| R | Real-lock-during-recording (design change) | `6eb39dc` | User wanted "recording continues while phone is in real lock mode" instead of the fake-cover UX. Already technically possible (foreground service + `PARTIAL_WAKE_LOCK` + CameraX on `LifecycleService` keep the pipeline alive across activity backgrounding); the BLOCKER was `MainActivity.setShowWhenLocked(true)` actively forcing the activity OVER the keyguard. New Settings toggle "Use real device lock while recording" (default ON) drives: `MainActivity` skips `setShowWhenLocked` while pref is true; `RecorderViewModel.enterCoverScreen` no-ops; `startRecording` doesn't auto-pop the cover; `RecorderService` notification gets `setVisibility(VISIBILITY_SECRET)` + channel `lockscreenVisibility=SECRET` so the lock screen stays clean; `RecorderScreen` UI replaces the "Show lock screen" button with a hint card "Press the power button — recording continues". Legacy fake-cover + overlay paths preserved as opt-out. |
| S | Build break: delegated property smart cast | `9b5d554` | First Round 5 build failed at `compileDebugKotlin`: `Smart cast to 'VaultFile' is impossible, because 'currentFile' is a delegated property.` Captured `currentFile` (a `mutableStateOf`-backed delegate) into a local `val cf` before the null check + property access — the same pattern documented in `docs/ANDROID_BUILD_LESSONS.md` for delegated properties. |

Round 5 known limitations (carried into next round):
- ML background removal not yet implemented. The plumbing is in place — `PhotoMergeScreen` already does Compose canvas compositing with `graphicsLayer`, so adding a third operation that runs `com.google.mlkit:segmentation-subject` on the base bitmap and applies the binary mask is a single new screen + ~10 MB APK growth. User chose merge-only this round.
- Legacy GCM-encrypted vault files larger than ~150 MB will OOM on decrypt because Conscrypt's GCM buffers the whole plaintext during `doFinal()`. In practice no such file exists — they would have OOMed during encrypt under the old code — but if one is ever discovered (e.g. transferred from another device), it'll need a manual one-shot heap bump or a re-encrypt-via-SAF tool. New writes are always v2 streaming.
- The user's already-failed 446 MB MP4 sitting in `filesDir/recordings/` from Round 4 should be picked up by `RecordingRecovery.scanAndRecover()` on the next app start (file is >1 KB and the marker may have been cleaned, but Feature C's markerless path catches it). Re-encryption with v2 should succeed and the file lands in the vault. If recovery doesn't catch it (e.g. the file sat there >7 days and was reaped), the user can just record again.

---

## Post-testing iterations — Round 4 (2026-04-15)

User report: "I'm not able to playback the video or voice recordings I just saved in the app. I think the media player is broken or the files are being corrupted after recording." Plus a user-prioritized wishlist for stability + security: A (battery exemption), B (SYSTEM_ALERT_WINDOW overlay), C (auto-resume), D (notification stop), J (secure-delete).

All on branch `claude/fix-media-playback-bug-XM1sr` (commits `7cb44b6..948a29d`). Doc update is the final commit of the round; when merging to `master`, keep the branch name in the history.

| # | Issue → Resolution | Commit | Root cause |
|---|---|---|---|
| N1 | Recordings won't play back | `7cb44b6` | `FileEncryptionService.decryptToTempFile` used `view_<uuid>_<originalName>` as the cache filename. For recorder-produced files, `originalName` is `"Video Apr 14, 2026 08:30.mp4"` — colons + commas + spaces. `VideoView.setVideoPath(String)` → `Uri.parse(path)` → `MediaPlayer.setDataSource` mangles the string; `prepare()` throws `IOException: Prepare failed.: status=0x1` (exact stack from `0f9037b`'s crash log). Rewritten to name the temp file `view_<uuid>.<ext>` with a defensive `extensionFor()` helper that falls back to `.mp4`/`.m4a`/`.jpg` by `VaultFileType` when `originalName` doesn't have a clean short ext. |
| N2 | AES-GCM auth tag truncation on fast service reap | `c9ac879` | `encryptStream` used nested `use {}` blocks which correctly order `CipherOutputStream.close()` (writes tag) before `FileOutputStream.close()` — but `close()` only flushes Java buffers, not the OS page cache. Android 14+ reaps foreground-service processes aggressively after `stopForeground`; between the Java close and the page cache hitting disk, the `.enc` can be truncated and the trailing 16-byte GCM tag lost. Added explicit `fos.flush() + fos.fd.sync()` inside the outer `use` block. Best-effort via `runCatching` on `fd.sync()` because some filesystems don't support it. |
| N3 | Decrypt failures silently swallowed | `0bef40d` | `VaultFileViewerViewModel.decrypt` wrapped `decryptToTempFile` in `runCatching.getOrNull()`; the UI then rendered "Failed to decrypt" with zero visibility into the actual cause (bad GCM tag, missing `.enc`, etc.). Replaced with an explicit try/catch that calls `AppLogger.log("vault", ...)` with id + originalName + type + encrypted file size + exception class + message. `app.txt` now shows exactly why a given file fails. |
| N4 | 0-byte recordings reached the vault as unplayable files | `962289d` | `MediaRecorder.start()` and CameraX `VideoCapture` occasionally hand back a file that's been opened but never written (silent start failure or process kill in the ~100ms before first frame). `persistRecordingToVault` happily encrypted a 0-byte plaintext into an `.enc` containing only `[IV][GCM tag]`, which decrypted to empty plaintext, which `MediaPlayer.prepare()` then rejected. Added a `MIN_VALID_RECORDING_BYTES=1024` guard: anything smaller is logged, the plaintext is deleted, a placeholder `Recording` with a " (empty)" suffix is returned, and no vault row is written. |
| A | Doze kills long recordings | `2eb5766` | Foreground service + `PARTIAL_WAKE_LOCK` is not enough — Doze still kills apps not exempt from battery optimization after ~15min screen-off. Manifest gained `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. New "Disable Battery Optimization" Settings row launches `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (if not exempt) or `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (if exempt, so revoke remains user-initiated). `rememberLauncherForActivityResult` re-queries on return so the subtitle flips live. |
| B | Home-swipe dismissed the in-Activity fake lock | `948a29d` | Round 3's trade-off (dropping `startLockTask` to kill the "Screen pinned" toast) left a Pixel bottom home-swipe able to drop out of the cover. New `OverlayLockService` draws the fake lock via `WindowManager.TYPE_APPLICATION_OVERLAY` — the overlay follows the user around the OS, remaining visible even after they swipe to the launcher or another app. Plain Android Views (LinearLayout + GridLayout of Buttons) instead of ComposeView to avoid ViewTree owner plumbing in a Service. `OverlayLockBus` (@Singleton) shares the secretPin + `isShowing` StateFlow with `RecorderViewModel`. New Settings "Secure Overlay Lock" toggle drives the permission flow via `ACTION_MANAGE_OVERLAY_PERMISSION`. Falls back to the in-Activity `FakeLockScreen` when the user hasn't opted in or grant is missing. |
| C | Service reap mid-recording lost data | `d2e0b82` | No sidecar state meant that if the foreground service was OOM-killed, the partial MP4/M4A sat in `filesDir/recordings/` forever. `RecorderService.startRecording` now atomically writes `.in_progress_<id>` with `{id, type, facing, startTime, outputPath}` BEFORE any MediaRecorder/CameraX setup. Clean exit paths delete the marker. New `RecordingRecovery` (@Singleton, @Inject'd) scans `recordings/` on every app start (`StealthCalcApp.onCreate` via `EntryPoints.get`) AND on `BOOT_COMPLETED` (`BootReceiver`): markers → finalize via the existing `encryptLocalFile` chain; markerless `.m4a`/`.mp4` older than 60s and ≥1KB → also finalized (defends against `persistRecordingToVault` failing after marker delete, and pre-Feature-C leftovers). Recovery is skipped entirely if a recording is in progress. |
| D | No on-screen stop when cover dismissed | `f39a89e` | Residual trade-off from Round 3 + Feature B's known limit: once the user has home-swiped away from the cover, they had no Stop affordance until reopening the app. Added a `NotificationCompat.addAction("Done", ...)` to the foreground notification; the action's `PendingIntent.getService` targets `RecorderService` with `ACTION_STOP` which is already wired. Label is "Done" (not "Stop recording") to stay covert with the "Calculator / Calculation in progress..." text. `FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT`; distinct `requestCode=1` from the tap-to-open activity intent. |
| J | Deleted files recoverable from disk | `3370b4c` | Plaintext temp cache files in `cacheDir` (decrypted vault media) and deleted `.enc` payloads in `filesDir/vault/` were unlinked with a plain `File.delete()`. A userspace forensic recovery tool scanning unlinked-but-not-yet-GC'd sectors could pull the bytes back. New `FileEncryptionService.secureDelete(file)` opens the file with `RandomAccessFile` "rw", overwrites every byte with `SecureRandom` in 8KB chunks, `fd.sync()`s, then `delete()`s. Wired into `VaultRepository.deleteFile` (payload + thumbnail), `VaultFileViewerViewModel.trimCache` + `onCleared` (decrypted temp cache), and `RecorderRepository.deleteRecording`. The recorder delete now ALSO cascades to `VaultRepository.deleteFile` when the Recording has a `vaultFileId` — closes the Round 1 known limitation where deleting a Recording left the linked VaultFile orphaned. Best-effort: flash-storage wear leveling means NAND-level cells may retain data; one random-pass is the practical ceiling without OEM SECURE_ERASE access. |

Round 4 known limitations (carried into next round):
- Feature B overlay can be covered by system gestures on Android 10+; the user CAN still home-swipe, but our overlay sits on top of the landing app rather than being blocked entirely. Functionally the cover still follows the user around, so this is acceptable for the threat model.
- Feature C: a recording interrupted *while* writing the MP4 `moov` atom at the very end can leave a partially-corrupt file. `MediaExtractor` tolerates this for fragmented MP4s but a worst-case corrupt file logs `"recovery: encrypt/save failed"` and is left alone in `recordings/` for later manual triage.
- Feature J: flash storage wear leveling / bad-block remapping. True forensic erase requires hardware support (eMMC/UFS SECURE_ERASE) we can't trigger from userspace. Document honestly as "best-effort".
- Feature A: battery-opt exemption is user-opt-in. If the user declines, Doze can still kill long recordings — we can't force the grant, and Play would reject the Whitelisted variant of the permission for a non-alarm app.

---

## Post-testing iterations — Round 3 (2026-04-14)

User installed the Round 2 APK on Pixel 6 / Android 16 (API 36, targetSdk 35) and reported four new issues. All fixed on branch `claude/fix-cloud-signin-video-bugs-cGptT`:

| # | User report | Commit | Root cause |
|---|---|---|---|
| J | "when I import photos and videos. it asks asks me to sign in to cloud Google account. I shouldn't have to. I don't have cloud photos. all my photos are local." | `ba15641` | `Intent.ACTION_PICK` hands off to whatever app registered for the image/video MIME type. On Pixel that's Google Photos, which refuses to return URIs until the user signs in. Fixed by bypassing the system picker entirely — new in-app `InAppMediaPickerScreen` + `InAppMediaPickerViewModel` that queries `MediaStore.Images/Video.EXTERNAL_CONTENT_URI` directly via `ContentResolver`, shows thumbnails via `ContentResolver.loadThumbnail` (API 29+), and returns real MediaStore URIs so `createDeleteRequest` still works. Nested `navigation("vault_graph")` shares a `VaultViewModel` between `VaultScreen` and the picker. |
| K | "when I start video recording. it still very buggy. I don't see my recording getting saved anywhere." | `992c4c7` | Video path used `MediaRecorder.VideoSource.CAMERA`, which is the legacy Camera1 pipeline and requires an explicit `Camera.open()` + `setCamera()` — we never held a Camera instance, so `MediaRecorder.start()` threw RuntimeException, the service caught it, the empty MP4 was cleaned up, and nothing reached the vault chain. Fixed by switching video to CameraX `Recorder` + `VideoCapture` bound to the service's lifecycle (`RecorderService` now extends `LifecycleService`). `VideoRecordEvent.Finalize` drives the vault persist. Added `androidx.camera:camera-video:1.3.4` + `androidx.lifecycle:lifecycle-service:2.8.4`. |
| L | "my phone keeps actually locking, how do I stop that, I think it's interrupting my recording... when I rotate my screen or touch my phone the app seems to get real phone locked. when I unlock my phone my recording is gone or stopped." | `a7f85bc` | Three-part fix. (1) `MainActivity` gained `android:configChanges` covering orientation/screenSize/screenLayout/keyboardHidden/uiMode/smallestScreenSize/navigation/keyboard/density/fontScale so config changes don't recreate the activity and tear down FakeLockScreen. (2) `MainActivity` observes `RecorderService.isRecording` and toggles `setShowWhenLocked(true)` + `setTurnScreenOn(true)` while recording — if the device does lock, waking returns to the fake lock cover instead of the Pixel keyguard. (3) `RecorderService` holds a `PARTIAL_WAKE_LOCK` "StealthCalc:RecorderWakeLock" acquired in `promoteToForeground()` and released on every stop path — keeps the CPU + MediaRecorder/CameraX Recorder alive even when the screen is off. Added `WAKE_LOCK` permission. |
| M | "the fake lock screen shouldnt have notifications about pin app" | `a766fd0` | Android shows an unsuppressible system toast ("Screen pinned — touch and hold Back and Overview to unpin") every time `Activity.startLockTask()` is called on a device where the user has enabled App Pinning in Settings → Security. Regular apps can't suppress it. Fixed by removing the `runCatching { startLockTask() }` / `stopLockTask()` calls from `FakeSignInScreen.kt`. `FLAG_KEEP_SCREEN_ON` + `BackHandler` + immersive-sticky remain. Trade-off: home-gesture swipe can now dismiss the fake lock on some devices. The user explicitly accepted this. |

Round 3 known limitations:

- **Battery optimization "Restricted":** if the user has the Calculator set to `Battery → Restricted`, the foreground service can still be killed. Doze exemption is out of scope (would require a user-visible `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` prompt).
- **Background lock UX:** `setShowWhenLocked(true)` only works if the activity was foregrounded when the lock happened. If the user backgrounded the calculator before locking, they'll see the real Pixel keyguard first and have to unlock normally to return to our fake cover — this is intentional so the stealth cover doesn't pop up unexpectedly when unlocking the device after unrelated use.
- **Partial photo grant (API 34+ "Selected photos only"):** the in-app picker uses the permission at a grant-binary level. If the user chose "Selected photos only", MediaStore returns the filtered subset. Reasonable first-cut behaviour — full `READ_MEDIA_VISUAL_USER_SELECTED` + re-picker flow is a future enhancement.
- **Home-gesture dismiss:** without `startLockTask()`, the Pixel bottom home-swipe can drop out of the fake lock. Recording keeps going (the foreground service + wake lock are independent of the activity), but the cover is momentarily lost. User accepted this to kill the pin-app toast.

---

## Post-testing iterations — Round 2 (2026-04-14)

User installed the Round 1 APK (`a10f5dd`) and tested. The exported `app.txt` log showed three service errors + three `[FATAL]` blocks + three `Import delete skipped` lines — all from two unresolved issues. Fixed on `master`; see `docs/FIX_PLAN.md` "Post-testing iterations — Round 2" for details.

| # | User report | Commit | Root cause |
|---|---|---|---|
| F | "the recording is still not appearing saved anywhere. when I click on record button it goes to fake lock screen, but then shortly goes to real locked screen and recording is nowhere to be found" | `d9e0d53` | Three interlocking Android-14+ bugs: (1) manifest `foregroundServiceType="microphone\|camera"` causes the default FGS promotion to require both permissions even for audio; (2) `startForeground()` was called AFTER MediaRecorder setup, missing the 5–10s deadline when setup threw — process crashed with `ForegroundServiceDidNotStartInTimeException`; (3) cascading `RuntimeException: start failed`. Fix: new `promoteToForeground(type)` called FIRST in `onStartCommand` with the specific runtime type. |
| G | "the video and photo import is still not working completely. a copy of the selected photos are still appearing on the local library gallery" | `9ca8d73` | Round 1's fallback lookup by name+size still didn't resolve the photo picker's ephemeral URIs because the picker privacy-strips the MediaStore mapping by design. Fix: switch to `Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)` which opens the legacy gallery picker and returns real MediaStore URIs directly. |
| H | — (UX hardening tied to Issue F) | `7adfa5c` | `RecorderViewModel.startRecording()` set `showCoverScreen = true` preemptively. If the service failed to record (before Fix F landed), the fake lock stayed up forever. Added a 4-second safety timer — if `RecorderService.isRecording` hasn't turned true by then, auto-dismiss cover. |
| I | — (docs) | `5617f60` | Two new lesson tables in `docs/ANDROID_BUILD_LESSONS.md`: "Foreground services on Android 14+ (API 34+)" and "MediaStore deletion + picker URIs", so the next project / AI agent avoids the same traps. |

---

## Issue 1 — App crashes with no way to extract logs

**Status:** FIXED in `4206bee`. See `docs/FIX_PLAN.md` §Fix 1 for what shipped.

**User report:** "the app keeps crashing. you should add an app log that I can extract while running app."

**Root cause:** No crash handler or file-based logger anywhere. `StealthCalcApp.kt` never installs `Thread.setDefaultUncaughtExceptionHandler`. Nothing writes to disk that the user could extract.

**Severity:** Blocking — without this we can't debug anything else.

---

## Issue 2 — Secret-code setup accepts any new code (PIN not persisted)

**Status:** FIXED in `90790de`. See `docs/FIX_PLAN.md` §Fix 2 for what shipped.

**User report:** "the secret code setup signin keeps accepting a new code and allows anyone to access. it should only allow once and then keep it as memory like other pin logins."

**Root cause:** `SetupScreen`'s completion callback never calls `SecretCodeManager.setSecretCode()`.

- `app/src/main/java/com/stealthcalc/stealth/navigation/AppNavigation.kt:102-110` — the `onSetupComplete` lambda only sets local state and calls `onStealthUnlocked()`. It does NOT call `secretCodeManager.setSecretCode(code)`.
- `app/src/main/java/com/stealthcalc/auth/SecretCodeManager.kt:30-39` — `setSecretCode()` is the only method that writes the hash. It is currently only called from `changeCode()` (line 84), never from the setup flow.

**Effect:** Because nothing is persisted, `validateCode(anything)` always returns `NotSetup`, which re-triggers setup, which grants access without persisting. Every code works.

**Severity:** Critical security bug. The entire auth system is a no-op in practice.

---

## Issue 3 — Vault thumbnails, viewer, and gallery deletion all broken

**Status:** FIXED across `3d6b8fc` (3a), `5c25c09` (3b), `1f3a351` (3c). See `docs/FIX_PLAN.md` §Fix 3a/3b/3c.

### 3a. Thumbnails never display

**User report:** "I can't see the thumbnails."

**Root cause:** `VaultScreen.kt:452-461` has placeholder code that always returns `null`:
```kotlin
val thumbBitmap = remember(file.thumbnailPath) {
    file.thumbnailPath?.let { path ->
        try {
            // Thumbnails are encrypted — need to decrypt
            // For now show type icon; full decryption in viewer
            null                            // <-- always null
        } catch (_: Exception) { null }
    }
}
```
Thumbnails ARE being generated and encrypted correctly by `FileEncryptionService.saveThumbnail()` (`vault/service/FileEncryptionService.kt:273-292`). They exist on disk at `file.thumbnailPath`. The UI just never decrypts them.

### 3b. Tapping a file does nothing (no viewer)

**User report:** "when I click on photo, the image doesn't appear."

**Root cause:** No viewer screen exists.
- `app/src/main/java/com/stealthcalc/stealth/navigation/AppNavigation.kt:242-248` passes `onOpenFile = { /* TODO: open encrypted file viewer */ }` — a literal no-op.
- `AppScreen` sealed class has no `Viewer` route.
- No viewer composable exists anywhere under `app/src/main/java/com/stealthcalc/vault/ui/`.

### 3c. Originals not deleted from device gallery

**User report:** "it's supposed to remove image from local library gallery, but the photo remains after import."

**Root cause:** `VaultViewModel.kt:167-173` uses plain `contentResolver.delete(uri, null, null)`. On API 29+ (Android 10+), deleting MediaStore entries owned by another app (e.g. the Camera app or `Photos`) throws `RecoverableSecurityException`; on API 30+ you must use `MediaStore.createDeleteRequest()` to get a `PendingIntent` and let the user confirm.

The current `catch (_: Exception) {}` silently swallows the failure.

**Severity:** Critical for user. Whole point of "secure vault" is that the original is gone from the gallery.

---

## Issue 4 — Voice/video recordings never reach the secure vault

**Status:** FIXED in `583ca17`. See `docs/FIX_PLAN.md` §Fix 4 for what shipped.

**User report:** "when recording voice, video, I don't see files being saved. they should save automatically to my secure vault."

**Root cause:** Recordings are saved to plaintext files under the app's `filesDir/recordings/`, tracked only in the Recording table — they are NEVER encrypted and NEVER inserted into the vault DB.

- `app/src/main/java/com/stealthcalc/recorder/service/RecorderService.kt:111` creates the output file at `filesDir/recordings/$fileName` — plain MP4/M4A.
- `RecorderService.kt:198-209` stores that plaintext path in `Recording.encryptedFilePath` (misleading field name — it is not encrypted).
- `RecorderService.kt` has no reference to `FileEncryptionService`, `VaultRepository`, or `VaultFile`.
- `recorder/viewmodel/RecorderViewModel.kt` only inserts into `RecordingDao`, not the vault.

**Severity:** High — a core feature (Covert Recorder → Secure Vault) is not wired up.

---

## Issue 5 — Fake lock screen doesn't actually lock the device

**Status:** FIXED in `f833ba7`. See `docs/FIX_PLAN.md` §Fix 5 for what shipped.

**User report:** "when making a recording, I like mimic home screen. however the back button should be disabled because it immediately takes me back to the app which defeats the purpose. the only way out of stealth recording should be by correct pin value. also the phone should not turn off, it should keep recording until someone enters correct pin. it can go to a fake black screen if necessary."

**Root cause:** `FakeLockScreen` has no back-button interception, no wake-lock, and nothing keeping the screen on.

- `app/src/main/java/com/stealthcalc/recorder/ui/FakeSignInScreen.kt` (the file that defines the `FakeLockScreen` composable) contains:
  - NO `BackHandler { }` — pressing Back exits to the recorder screen (breaks the illusion).
  - NO `FLAG_KEEP_SCREEN_ON`, `WakeLock`, or `View.keepScreenOn = true` — the device screen turns off on the system timeout and recording may pause.
  - Wrong-PIN handler resets the field but nothing else — good, but back-button is the unpatched hole.
- Only the correct-PIN path calls `onUnlock()`. Good. But that path isn't the only way out because Back dismisses the composable.

**Severity:** High — feature described to user as a cover is trivially bypassed.

---

## Summary table

| # | Area | Severity | Files primarily affected |
|---|---|---|---|
| 1 | Crash logging | Blocking | `StealthCalcApp.kt`, new `core/logging/AppLogger.kt`, `settings/ui/SettingsScreen.kt` |
| 2 | Auth / PIN setup | Critical | `stealth/navigation/AppNavigation.kt` |
| 3a | Vault thumbnails | High | `vault/ui/VaultScreen.kt`, `vault/service/FileEncryptionService.kt` |
| 3b | Vault viewer | High | new `vault/ui/VaultFileViewerScreen.kt`, `stealth/navigation/AppNavigation.kt` |
| 3c | Gallery delete | High | `vault/viewmodel/VaultViewModel.kt`, `MainActivity.kt` (for PendingIntent launch) |
| 4 | Recording → vault | High | `recorder/service/RecorderService.kt`, `recorder/viewmodel/RecorderViewModel.kt`, DI module |
| 5 | Fake lock screen | High | `recorder/ui/FakeSignInScreen.kt` (rename advisable), `recorder/ui/RecorderScreen.kt` |

---

## What was verified / ruled out

- Build compiles and APK installs successfully (user confirmed installing latest APK).
- GeckoView dependency is correct (`geckoview-omni-arm64-v8a:123.0.20240213221259`).
- All Hilt injections resolve at compile time; the `@HiltAndroidApp` on `StealthCalcApp` is present.
- `FileEncryptionService.saveThumbnail()` DOES generate and save encrypted thumbnails at import — the problem is only the UI-side decryption.
- `EncryptedSharedPreferences` works (it's what `SecretCodeManager` reads) — the bug is that `setSecretCode()` is never called, not that the store is broken.

---

## Round 8 — Feature additions (2026-04-20)

**Branch:** `claude/round8-features-bYw2Y` → merged to `master` (commit `b19bcb0`)
**No new bugs found.** All items below are new features, not regressions.

| # | Feature | Status |
|---|---------|--------|
| R8-1 | AMOLED dark theme (true-black Settings toggle) | ✅ Shipped |
| R8-2 | App icon switcher (Calculator / Clock / Notes via `activity-alias`) | ✅ Shipped |
| R8-3 | Biometric-only unlock (long-press `=`) | ✅ Shipped |
| R8-4 | Shake sensitivity tuning (15/25/35 m/s² picker in Settings) | ✅ Shipped |
| R8-5 | Clipboard timeout config (15s/30s/1m/5m/Never) | ✅ Shipped |
| R8-6 | Recording cascade delete | ✅ Already done (Round 4) |
| R8-7 | Thumbnail regeneration (broken-image toolbar button) | ✅ Shipped |
| R8-8 | OCR on photos (ML Kit text recognition + copy dialog) | ✅ Shipped |
| R8-9 | Remote lock agent (`lock_device` command → `DevicePolicyManager.lockNow()`) | ✅ Shipped |
| R8-10 | Scheduled silent windows (agent collection gated by schedule prefs) | ✅ Shipped |
| R8-11 | Custom browser user agent (GeckoView UA override) | ✅ Shipped |
| R8-12 | Decoy PIN with vault wipe | ✅ Shipped |
| R8-13/14 | Browser save page to vault (URL stub encryption) | ✅ Shipped |
| R8-15 | Remote wipe (`wipe_vault` command → `WipeManager.wipeAll()`) | ✅ Shipped |
| R8-16 | Timeline view (hourly event grouping, `TimelineScreen`) | ✅ Shipped |

See `docs/ROUND8_FEATURES.md` for full implementation detail.

---

## Agent + Server Compatibility Audit (2026-04-20)

**Trigger:** Round 8 added `lock_device`, `wipe_vault`, and other commands to the main app's `RemoteCommandHandler`. The lightweight agent (`agent/`) and server (`server/`) were audited for compatibility gaps.

### Findings

| Service | Gap Found | Status |
|---------|-----------|--------|
| **Server** (`server/`) | No gap — `Commands.kt` relays any `type: String` generically; no whitelist or enum | ✅ No changes needed |
| **Agent** (`agent/`) — initial state | Had **zero** command handling. No WebSocket listener, no dispatch, no camera, no audio, no SMS. Data-collector only. | ❌ Missing |
| Agent — after Round 8 initial fix | Added WebSocket listener + `lock_device`, `wipe_vault`, `ring` (3 of 13 commands) | ⚠️ Partial |
| Agent — after full implementation | All 13 commands implemented (commits `a61361c`, `affb23c`) | ✅ Full parity |

### Agent commands now implemented

| Command | Implementation |
|---------|---------------|
| `capture_front` / `capture_back` | `AgentCameraService` — Camera2 still capture, upload via `AgentClient.uploadFile()` |
| `record_audio` | `MediaRecorder` inline in `AgentForegroundService`, upload bytes |
| `ring` | `RingtoneManager.TYPE_RINGTONE`, auto-stop after 10s |
| `send_sms` | `SmsManager.getDefault().sendTextMessage()` |
| `stream_camera_front` / `stream_camera_back` | `AgentLiveCameraService` — Camera2 320×240 JPEG stream over WebSocket to `/camera/{side}/{deviceId}` |
| `stop_camera_stream` | `AgentLiveCameraService.stopStreaming()` |
| `screen_record` | `AgentScreenRecordService` — MediaCodec H.264 + MediaMuxer, requires `setMediaProjection()` called first |
| `reply_notification` | `AgentNotificationListener.replyToNotification()` via `RemoteInput` |
| `launch_app` | `PackageManager.getLaunchIntentForPackage()` |
| `lock_device` | `DevicePolicyManager.lockNow()` if admin active |
| `wipe_vault` | `AgentRepository.wipe()` (clears DB + prefs) → `stopSelf()` |

- `MediaRecorder` in `RecorderService` does successfully write output files — they just land in app-private storage unencrypted, not in the vault.