# Android Build — Lessons Learned

Running log of errors encountered and how to prevent/fix them. Check this before starting new Android projects.

---

## Pre-push Checklist

Before pushing any Android project, verify:

- [ ] All classes referenced in `AndroidManifest.xml` exist as `.kt` files (Application, Activity, Service, BroadcastReceiver)
- [ ] All resources referenced by the manifest exist: `@mipmap/ic_launcher`, `@mipmap/ic_launcher_round`, `@style/Theme.*`
- [ ] Every `R.string/color/drawable/style/xml` reference in code has a matching entry in `res/`
- [ ] Every Kotlin file's `import com.yourapp.*` resolves to an actual file in the project
- [ ] Every `@HiltViewModel` constructor param is either `@Inject`-able or has a provider
- [ ] Every `@Entity` used by a DAO is declared in the `@Database(entities = [...])` list
- [ ] `gradle.properties` has `android.useAndroidX=true` if any AndroidX dep is used (GeckoView brings them transitively)
- [ ] Workflow uploads **both** the APK (on success) **and** the build log `.txt` (always or on failure)
- [ ] Upload step uses `if-no-files-found: error` so the build fails loud if the APK path is wrong

---

## Errors Encountered & Fixes

### GeckoView

| Error | Cause | Fix |
|---|---|---|
| `Could not find org.mozilla.geckoview:geckoview-arm64-v8a:XXX` | Artifact `geckoview-arm64-v8a` was discontinued after v117 | Use `geckoview-omni-arm64-v8a` for v118+ |
| `Could not find org.mozilla.geckoview:...:123.0.20240212205514` | Wrong build timestamp — version must match an actually-published build on `maven.mozilla.org` | Correct version for 123.0 is `123.0.20240213221259`. Check `https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview-omni-arm64-v8a/` |
| `Unresolved reference 'cookieBannerMode'` | `cookieBannerMode()` is NOT on `ContentBlocking.Settings.Builder` in GeckoView 123.0. In later versions it moved to `GeckoRuntimeSettings.Builder` | Remove the call, or move to `GeckoRuntimeSettings.Builder` if using a newer GeckoView |
| `Null cannot be a value of a non-null type 'RuntimeTelemetry.Delegate'` | `.telemetryDelegate(null)` — parameter is non-null in GeckoView 123.0 | Remove the `.telemetryDelegate(null)` line |
| `StorageController.ClearFlags.ALL` compile error | `ClearFlags.ALL` doesn't exist | OR individual flags: `COOKIES or NETWORK_CACHE or IMAGE_CACHE or AUTH_SESSIONS or PERMISSIONS or SITE_DATA` |
| AndroidX errors with GeckoView | GeckoView depends on AndroidX transitively | Add `android.useAndroidX=true` and `android.nonTransitiveRClass=true` to `gradle.properties` |
| Mozilla Maven not in repos | `google()` and `mavenCentral()` don't host GeckoView | Add `maven { url 'https://maven.mozilla.org/maven2/' }` to `dependencyResolutionManagement.repositories` in `settings.gradle.kts` |

### AAPT / Resources

| Error | Cause | Fix |
|---|---|---|
| `AAPT: error: resource mipmap/ic_launcher not found` | Manifest references launcher icons but no `mipmap/` directory has them | For `minSdk 26+`: create `res/mipmap-anydpi-v26/ic_launcher.xml` (adaptive-icon) + `res/drawable/ic_launcher_foreground.xml` + `res/drawable/ic_launcher_background.xml`. For lower minSdk: add PNG fallbacks in `mipmap-hdpi`, `-xhdpi`, `-xxhdpi`, `-xxxhdpi` |
| `AAPT: error: resource style/Theme.X not found` | Theme declared in manifest not defined in `themes.xml` | Add `<style name="Theme.X" parent="...">` to `res/values/themes.xml` |

### Manifest

| Error | Cause | Fix |
|---|---|---|
| Build passes but app crashes at boot or on broadcast | `<receiver>`, `<service>`, or `<activity>` declared in manifest references a class that doesn't exist | Either create the class or remove the manifest declaration. Manifest merger won't catch missing classes — only runtime/lint does |
| `Class not found` at install/runtime | Class name in manifest doesn't match package + name of actual `.kt` file | Verify `android:name=".foo.Bar"` matches `com.yourapp.foo.Bar` exactly |

### Kotlin Compile

| Error | Cause | Fix |
|---|---|---|
| `Unresolved reference 'Foo'` but `Foo` exists in the codebase | Missing `import` statement in the file using `Foo` | Add `import com.yourapp.path.to.Foo` |
| `Redeclaration: class Foo` | Same class declared in two files (e.g., one was copy-pasted) | Delete the duplicate file. Grep with `grep -rn "class Foo\|enum class Foo\|data class Foo\|sealed class Foo"` to find all declarations |
| `'when' expression must be exhaustive` | Missing case in `when` over an enum or sealed class | Add the missing branch OR add `else -> ...` |

### Room + Hilt

| Warning / Error | Cause | Fix |
|---|---|---|
| `The column X in the junction entity Y is not covered by any index` | Room warning about unindexed foreign key in cross-ref entity | Add `indices = [Index(value = ["tagId"])]` to the `@Entity` annotation |
| `Cannot find implementation for Foo_Impl` | Room's KSP processor didn't run | Ensure `ksp(libs.androidx.room.compiler)` is in `app/build.gradle.kts` AND the KSP plugin is applied |
| `Error:... cannot be provided without an @Inject constructor or an @Provides-annotated method` | Hilt can't resolve a dependency | Either add `@Inject` to the constructor or add a `@Provides` method in a `@Module` |
| `@HiltAndroidApp not found` | Application class missing annotation | Ensure `android:name=".YourApp"` in manifest and the class has `@HiltAndroidApp` |
| Service needs field injection | Hilt supports `@AndroidEntryPoint` on Services — fields can be `@Inject lateinit var` just like Activities | Annotate the class, declare `@Inject lateinit var foo: Foo` |
| DB schema change on pre-release | Writing migrations is overhead for an unshipped app | `.fallbackToDestructiveMigration()` in `Room.databaseBuilder` means version bumps silently drop/recreate tables. Fine for dev, unsafe for prod |

### AGP 8 BuildConfig

| Error | Cause | Fix |
|---|---|---|
| `Unresolved reference 'BuildConfig'` in AGP 8.x | Since AGP 8.0, `buildConfig` is **off by default**. `BuildConfig.VERSION_NAME`, `BuildConfig.APPLICATION_ID` etc. aren't generated | Either (a) enable in `android { buildFeatures { buildConfig = true } }`, OR (b) read at runtime via `context.packageManager.getPackageInfo(context.packageName, 0)` — use `info.longVersionCode` on API 28+, deprecated `info.versionCode` below |

### Compose / Material3 API drift

| Symptom | Cause | Fix |
|---|---|---|
| `LinearProgressIndicator(progress = { ... }, ...)` won't compile | The `progress: () -> Float` lambda overload was added in Material3 1.3.0. Compose BOM 2024.08.00 ships Material3 1.2.1 which only has `progress: Float` | Use the scalar form: `progress = progress.coerceIn(0f, 1f)` |
| `LocalLifecycleOwner` deprecation warning | Compose UI 1.7+ deprecated `androidx.compose.ui.platform.LocalLifecycleOwner` in favor of `androidx.lifecycle.compose.LocalLifecycleOwner` | BOM 2024.08.00 is Compose UI 1.6.8 where the original import still works; either is accepted. Use `androidx.lifecycle.compose` going forward if lifecycle 2.8+ is on the classpath |
| `combine()` call with 6+ flows | `combine` has concrete overloads only up to 5 flows; 6+ needs the vararg form with `Array<Any?>` | Use `combine(flow1, flow2, ...flowN) { values -> ... }` where the lambda takes `Array<Any?>` and you index + cast each slot |

### MediaStore delete on modern Android

| API level | Direct `contentResolver.delete(uri, null, null)` behavior | Correct approach |
|---|---|---|
| API ≤28 | Works; deletes immediately | Direct call is fine |
| API 29 | Throws `RecoverableSecurityException` for files owned by other apps (Camera, Photos) | Catch it, launch `rse.userAction.actionIntent.intentSender` via `StartIntentSenderForResult` |
| API 30+ | Silently returns 0 rows (no grant, no exception) | Bulk: `MediaStore.createDeleteRequest(contentResolver, uris)` → one `PendingIntent.intentSender` that approves all at once |

Bridge from ViewModel to Compose launcher: expose `StateFlow<IntentSender?>` from the VM, collect it with `collectAsStateWithLifecycle`, `LaunchedEffect(sender)` launches via `IntentSenderRequest.Builder(sender).build()`, then the VM's `onHandled()` sets the StateFlow back to `null` so it doesn't re-fire on recomposition.

### FileProvider

| Symptom | Cause | Fix |
|---|---|---|
| `IllegalArgumentException: Failed to find configured root` | `file_provider_paths.xml` doesn't have an entry whose `path` covers the file you're trying to serve | Add the right path type: `<files-path>` for `filesDir/`, `<cache-path>` for `cacheDir/`, `<external-files-path>` for `getExternalFilesDir()`. Use `path="."` to include the whole root |
| Meta-data reference doesn't resolve | Meta-data name must be exactly `android.support.FILE_PROVIDER_PATHS` (legacy support namespace, still correct) | Use that exact string regardless of AndroidX migration |
| `androidx.core.content.FileProvider` class missing | Forgot the dep | Already included by `androidx.core:core-ktx` — no extra dep needed |

### Service lifecycle + coroutines

| Symptom | Cause | Fix |
|---|---|---|
| Background work started in a Service doesn't finish before process is killed | `stopForeground(STOP_FOREGROUND_REMOVE)` + `stopSelf()` allow the OS to reap the process at any time; coroutines started before stop can be cancelled mid-flight | Do the work on the service scope, THEN call stopForeground/stopSelf inside the coroutine's completion block. Keep the foreground notification alive until background work is done |
| Coroutine cancelled when service destroyed | Default `serviceScope` uses a `Job()` that isn't tied to service lifecycle — fine if you WANT it to survive; bad if you need clean cancellation | Decide explicitly: cancel the scope in `onDestroy()` for clean shutdown, or let coroutines outlive the service if they need to finish durable work |

### Kotlin name shadowing

| Symptom | Cause | Fix |
|---|---|---|
| `fun importFiles(deleteOriginals: Boolean) { ... deleteOriginals(uris) ... }` — the inner call resolves to the Boolean param, not the private method | Kotlin's function/property namespace is shared within a scope. A Boolean parameter shadows a same-named function and makes `name(args)` fail because Boolean isn't invokable | Rename the function (`requestOriginalsDeletion`) or the parameter to make the call site unambiguous |

### Kotlin delegated properties + smart cast

| Symptom | Cause | Fix |
|---|---|---|
| `Smart cast to 'Bitmap' is impossible, because 'thumbBitmap' is a delegated property` | `val x by produceState<T?>(...)` and `var y by remember { mutableStateOf<T?>(null) }` are delegated properties (read via `getValue()`). Kotlin can't prove the getter is stable between a null-check and a later read, so `if (x != null) { x.foo() }` won't smart-cast | Capture into a plain local val: `val captured = x; if (captured != null) captured.foo()`. Or use safe-call + let: `x?.let { it.foo() }` |

### Foreground services on Android 14+ (API 34+)

| Symptom | Cause | Fix |
|---|---|---|
| `SecurityException: Starting FGS with type camera ... requires permissions: all of [FOREGROUND_SERVICE_CAMERA] and any of [CAMERA, SYSTEM_CAMERA]` | Manifest declares `foregroundServiceType="microphone\|camera"` (the MAXIMUM possible types). `startForeground()` without an explicit type promotes with the union — but on Android 14+ the runtime type must be a SUBSET of manifest AND all types' permissions must be granted. For audio-only sessions CAMERA isn't granted, so the promotion fails | At runtime call `startForeground(id, notification, fgsType)` with only the types actually needed. Use `ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE` for audio, `MICROPHONE or CAMERA` for video (API 29+ only — below that use the 2-arg overload) |
| `ForegroundServiceDidNotStartInTimeException: Context.startForegroundService() did not then call Service.startForeground()` crashes the whole app | `startForegroundService()` has a ~5–10 second deadline for the service to call `startForeground()`. If your service does blocking setup work (MediaRecorder.prepare/start, CameraManager.openCamera, etc.) FIRST and it throws, the catch block logs the error but the deadline still elapses with no `startForeground()` call — system kills the process | Call `startForeground()` as the FIRST thing in `onStartCommand` (before any MediaRecorder/Camera work). Do the failable setup afterwards; if it throws, catch and call `stopForeground(STOP_FOREGROUND_REMOVE) + stopSelf()` to unwind cleanly |

### MediaStore deletion + picker URIs

| Symptom | Cause | Fix |
|---|---|---|
| `MediaStore.createDeleteRequest(uris)` creates a `PendingIntent`, user approves, **nothing is deleted** | On Android 13+ the system photo picker (`ActivityResultContracts.GetMultipleContents` → `ACTION_GET_CONTENT` with image/* mime type) returns ephemeral `content://media/picker/...` URIs that are read-only grants — NOT MediaStore rows. `createDeleteRequest` silently succeeds against these with 0 rows affected | Use `Intent.ACTION_PICK` with data URI = `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` (or Video/Audio). This opens the **legacy gallery picker** (stock Gallery / Photos) which returns real `content://media/external/...` URIs that `createDeleteRequest` can delete. `ACTION_PICK` does NOT redirect to the photo picker the way `ACTION_GET_CONTENT` does |
| Even with `READ_MEDIA_IMAGES` granted, querying MediaStore for a picker URI's `DISPLAY_NAME` + `SIZE` returns zero matches | Photo-picker URIs are intentionally unmappable to MediaStore rows; the picker privacy-strips the mapping. No amount of permission grants lets you look them up | Don't rely on resolving picker URIs to MediaStore. Use `ACTION_PICK` with MediaStore URI up-front so the URI IS a MediaStore URI |
| `Intent.ACTION_PICK` throws `ActivityNotFoundException` on a device with no gallery app | Rare, but possible (stripped ROM) | Wrap the `launcher.launch(intent)` call in try/catch; Toast the user that no gallery app is available. The import flow can still accept file-provider URIs via the separate OpenMultipleDocuments fallback |

### Build / Workflow

| Error | Cause | Fix |
|---|---|---|
| Build succeeds but no APK artifact appears | `upload-artifact@v4` default `if-no-files-found: warn` — silently succeeds when no file matches | Set `if-no-files-found: error` on the APK upload step |
| Build log stored as `.log` — not openable on mobile | File extension affects default handler on phones | Use `build.txt` (or `build-output.txt`) as the log file name |
| Workflow only runs on feature branch, not master | `on.push.branches` list too narrow | Include `master` (and any backup branches) in the trigger |

---

## Required Workflow Template (`.github/workflows/build-apk.yml`)

```yaml
name: Build APK
on:
  push:
    branches: [ 'master', 'claude/**' ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - uses: android-actions/setup-android@v3
      - run: |
          gradle wrapper --gradle-version 8.7
          chmod +x gradlew
      - run: ./gradlew assembleDebug --stacktrace 2>&1 | tee build-output.txt
        continue-on-error: true
      - name: Upload build log
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: build-log
          path: build-output.txt
          retention-days: 7
      - name: Upload APK
        if: success()
        uses: actions/upload-artifact@v4
        with:
          name: app-debug
          path: app/build/outputs/apk/debug/app-debug.apk
          if-no-files-found: error
          retention-days: 30
      - name: Fail if build failed
        run: |
          if grep -q "BUILD FAILED" build-output.txt; then
            echo "Build failed — check build-log artifact for errors"
            exit 1
          fi
```

---

## Required `gradle.properties` (for any AndroidX / GeckoView project)

```
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

---

## Required `settings.gradle.kts` snippet (GeckoView projects)

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.mozilla.org/maven2") }  // GeckoView
    }
}
```

---

## Audit Commands (run before pushing)

```bash
# 1. Every class referenced in manifest must exist
grep -oE 'android:name="\.[^"]+"' app/src/main/AndroidManifest.xml | sed 's/android:name="\.//;s/"//' | while read cls; do
  path="app/src/main/java/$(grep -oE 'package="[^"]+"' app/src/main/AndroidManifest.xml | sed 's|package="||;s|"||;s|\.|/|g')/${cls//./\/}.kt"
  [ -f "$path" ] || echo "MISSING: $cls"
done

# 2. Look for declared classes that have duplicates
grep -rh "^(enum class\|class\|data class\|sealed class\|object) " app/src --include="*.kt" \
  | awk '{print $3}' | sed 's/[:(].*//' | sort | uniq -d

# 3. Every R.string/color/drawable/mipmap/style ref in code
grep -rhoE "R\.(string|color|drawable|mipmap|style|xml|layout)\.[a-z_0-9]+" app/src/main/java \
  | sort -u
# Cross-check each against res/ files

# 4. Every import com.yourapp.* resolves
grep -rh "^import com\.yourapp\." app/src/main/java --include="*.kt" | sort -u \
  | sed 's|import ||;s|\.|/|g;s|$|.kt|' \
  | while read p; do [ -f "app/src/main/java/$p" ] || echo "UNRESOLVED: $p"; done
```

---

## Session Process Lessons

- **Always check all branches before starting work.** `git branch -a` and `git fetch --all`. Don't assume the branch named in a task description is the one with real code — check other branches.
- **Never recreate files from scratch when the project already has them.** Check `git ls-tree -r HEAD` and `git log --all --oneline` first.
- **Grep for ALL declaration types**, not just `class`. Use `grep -rn "enum class\|sealed class\|object\|data class\|class\|interface "` when looking for types.
- **Don't build Android locally in restricted network environments** — the Claude Code remote container's proxy blocks `dl.google.com` and `maven.mozilla.org`. GitHub Actions runners have full network access.
- **Update CLAUDE.md every session with: what you changed, what errors you hit, what the fix was.** This document is the primary carryover between sessions.

---

## Index of Historical Issues (this project)

- GeckoView artifact discontinued — fixed in `e93d2c2` and predecessors
- Missing launcher icons — fixed in `f7e0b53`
- Missing `BootReceiver` class — fixed in `87a5009`
- Missing `VaultSortOrder` import (enum existed, import was missing) — fixed in `e93d2c2`
- Wrong GeckoView APIs (`cookieBannerMode`, nullable `telemetryDelegate`) — fixed in `ccdf5d0`

### 2026-04-14 session — 7 runtime bugs closed (commits `4206bee`..`f833ba7`)

- **Fix 1 (`4206bee`)** — file-based crash logger + Settings "Export crash log". Uses `FileProvider` under `files-path name="logs"`, reads build/version via `PackageManager.getPackageInfo()` (BuildConfig not enabled in AGP 8.5.2).
- **Fix 2 (`90790de`)** — persist the PIN in `onSetupComplete`. Threaded `SecretCodeManager` as a parameter from `MainActivity` through `AppRoot`. Added defensive `NotSetup → None` guard in `CalculatorViewModel` for corrupted-prefs edge case.
- **Fix 3a (`3d6b8fc`)** — decrypt vault thumbnails with `FileEncryptionService.decryptThumbnail()` (inverse of existing `saveThumbnail`). `produceState<Bitmap?>` keyed on `(file.id, file.thumbnailPath)` so list scroll doesn't re-decrypt.
- **Fix 3b (`5c25c09`)** — vault file viewer using framework-only renderers: `BitmapFactory.decodeFile` for photos, `VideoView + MediaController` for video, `MediaPlayer` + Compose for audio, `Intent.ACTION_VIEW` + `FileProvider` for docs. `DisposableEffect` releases `MediaPlayer`; `Lifecycle.Event.ON_STOP` auto-pauses. `cache-path` added to `file_provider_paths.xml` for external open-with intents.
- **Fix 3c (`1f3a351`)** — `MediaStore.createDeleteRequest` on API 30+, `RecoverableSecurityException.userAction.actionIntent.intentSender` on API 29, direct delete on ≤28. Bridged via `StateFlow<IntentSender?>` + `StartIntentSenderForResult`.
- **Fix 4 (`583ca17`)** — `RecorderService` @Injects `FileEncryptionService` and `VaultRepository`, chains encrypt → `vaultRepository.saveFile` → post `Recording` → `source.delete()` → `stopForeground + stopSelf` inside the coroutine so the process survives mid-encryption. `Recording.vaultFileId` added; DB 5 → 6.
- **Fix 5 (`f833ba7`)** — `BackHandler(enabled = true) { /* no-op */ }` + `DisposableEffect` toggling `FLAG_KEEP_SCREEN_ON` on the host Activity window. Foreground service type `microphone|camera` was already correct; no WakeLock needed.

Session-wide process notes that paid off:
- **Commit-per-fix** meant any regression could be isolated to one commit when Actions turned red.
- **Fix 1 first** was the single best ordering decision — every subsequent on-device failure is exportable from Settings → Diagnostics without ADB.
- **Framework over libraries** for media playback avoided touching `libs.versions.toml` / `app/build.gradle.kts` at all. Build surface was tiny (0 new deps across 7 fixes).
- **Double-check API availability vs. BOM version** — Material3 `LinearProgressIndicator(progress: () -> Float)` looked obvious but is 1.3+, not in BOM 2024.08.00 (1.2.1). Generally assume the lower end of whatever the BOM pins.
