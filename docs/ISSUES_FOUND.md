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
- `MediaRecorder` in `RecorderService` does successfully write output files — they just land in app-private storage unencrypted, not in the vault.