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

### MediaPlayer / VideoView error handling in Compose

| Symptom | Cause | Fix |
|---|---|---|
| App crashes in `remember { MediaPlayer().apply { setDataSource(...); prepare() } }` with `IOException: Prepare failed.: status=0x1` | `MediaPlayer.prepare()` throws on any bad/corrupt/unsupported file. Inside `remember { }` the exception propagates up through Compose recomposition (`RecomposeScopeImpl.compose` on the main thread) and there's no catch — the whole activity dies | Wrap the MediaPlayer construction in `runCatching { ... }.getOrNull()`. Render an error UI when it's null. Log file path / exists / size via `AppLogger` so a user-exported `app.txt` shows exactly what went wrong. Same pattern for `VideoView.setVideoPath` — wrap + add `setOnErrorListener` that calls `true` to suppress the system "Can't play this video" toast and sets a local error state |
| `MediaPlayer.prepare()` blocks the main thread | It's synchronous by design | For a small local audio file it's acceptable (tens of ms). For anything bigger prefer `prepareAsync()` + `OnPreparedListener` + `OnErrorListener`. But **always** catch `IOException` either way — `prepareAsync` throws synchronously on `IllegalStateException` and delivers errors via the listener |
| `MediaPlayer.setDataSource(path)` / `VideoView.setVideoPath(path)` throws `IOException: Prepare failed: status=0x1` for a file that exists on disk and is valid MP4/M4A | Both APIs pass the `String` through `Uri.parse(path)` internally before handing to the native layer. If the path contains **colons, commas, spaces, or non-alphanumeric chars** in the filename segment, `Uri.parse` mangles it and the native opener then can't find/open the file. Smoking gun on this project: `"view_<uuid>_Video Apr 14, 2026 08:30.mp4"` (from `VaultFile.originalName` being a human title) — colons in `HH:mm` broke playback despite the file being perfectly valid. | Don't embed user-facing titles in on-disk filenames for media. Name temp files with a stable `<uuid>.<ext>` pattern. If you need the original extension for MediaPlayer to pick the right codec, derive it from the VaultFile type (or a sanitized extension helper that requires short + alphanumeric) — never copy user strings verbatim |

### Durability of AES-GCM encrypted files written from a foreground service

| Symptom | Cause | Fix |
|---|---|---|
| AES-GCM encrypted files decrypt to garbage / empty plaintext after a mid-write reap of the foreground service | `CipherOutputStream.close()` writes the 16-byte GCM auth tag to the underlying stream during `cipher.doFinal()`. Nested `use {}` blocks correctly sequence close-order. BUT `FileOutputStream.close()` only flushes Java-side buffers — the bytes can still sit in the OS page cache. Android 14+ reaps foreground-service processes aggressively after `stopForeground` on memory pressure; if the OS kills the process between `fos.close()` returning and the page cache hitting disk, the `.enc` file on disk is truncated (missing the trailing auth tag) and decrypt later throws `AEADBadTagException` or produces truncated plaintext | Inside the outer `use {}` block, after the nested `CipherOutputStream.close()` runs, call `fos.flush()` then `runCatching { fos.fd.sync() }`. `fd.sync()` is the Linux `fsync(2)` which blocks until page cache pages for this FD have hit stable storage — so a subsequent process kill can't lose the tag. `runCatching` because some filesystems (external / networked, rare on Android internal storage) don't support `fsync` and you'd rather do best-effort than throw from encrypt |

### CameraX VideoCapture in a Service

| Symptom | Cause | Fix |
|---|---|---|
| Video recording via `MediaRecorder` with `setVideoSource(MediaRecorder.VideoSource.CAMERA)` silently produces an empty/truncated MP4 | `VideoSource.CAMERA` is the LEGACY Camera1 path. It requires `Camera.open()` + `Camera.unlock()` + `MediaRecorder.setCamera(camera)` before `prepare()`. If you don't hold a Camera instance, `start()` throws `RuntimeException` on modern Android — which is easy to catch-and-log and thereby "disappear" | Use CameraX `Recorder` + `VideoCapture` instead: `val videoCapture = VideoCapture.withOutput(Recorder.Builder().setQualitySelector(...).build())`, `bindToLifecycle(lifecycleOwner, selector, videoCapture)`, then `videoCapture.output.prepareRecording(ctx, FileOutputOptions.Builder(file).build()).withAudioEnabled().start(executor, listener)`. The listener delivers `VideoRecordEvent.Start` and `.Finalize` — mirror your state machine against those |
| `ProcessCameraProvider.bindToLifecycle(this, ...)` throws "LifecycleOwner is destroyed" from inside a `Service` | A plain `Service` is not a `LifecycleOwner`, and CameraX needs one to bind camera use cases | Extend `androidx.lifecycle.LifecycleService` instead of `Service`. Add `androidx.lifecycle:lifecycle-service` dep. Override `onBind(intent: Intent): IBinder?` with non-null Intent and `super.onBind(intent)` first. Override `onStartCommand` with `super.onStartCommand(intent, flags, startId)` first. Otherwise the lifecycle state machine drifts and CameraX's bind silently sees STATE_INITIALIZED → fails |
| Video recording starts but when `stopRecording()` is called nothing saves | `Recording.stop()` does NOT block and does NOT deliver the finalized file synchronously. The file isn't finalized until `VideoRecordEvent.Finalize` fires on the event listener | Drive the vault-persist chain from the `VideoRecordEvent.Finalize` case of your listener, not from the `stop()` call site. `Finalize` carries `event.hasError()`, `event.error`, `event.cause` so error handling is explicit. `stopRecording()` should only call `videoRecording?.stop()` and let Finalize drive the rest |
| FGS deadline crash even with CameraX because `ProcessCameraProvider.getInstance().addListener(...)` is async | `getInstance()` returns a `ListenableFuture` — the `addListener` callback where you bind the camera can fire AFTER the ~5-10s `startForegroundService` deadline if something else blocks the main thread | Call `startForeground()` as the FIRST thing in `onStartCommand` (before the `ProcessCameraProvider` handshake). Do the CameraX setup inside the `addListener` callback. On any exception there, call `stopForeground(STOP_FOREGROUND_REMOVE) + stopSelf()` to unwind cleanly |

### PARTIAL_WAKE_LOCK for service-driven recordings

| Symptom | Cause | Fix |
|---|---|---|
| Long audio/video recording silently truncates when the user presses the power button or the device auto-locks | `FLAG_KEEP_SCREEN_ON` only prevents auto-sleep while the activity is in the resumed state. Once the screen locks (power press, OS policy), the activity pauses, the flag is lifted, and Android aggressively suspends the app process after ~30s unless something is actively holding the CPU awake. MediaRecorder / CameraX Recorder then falls over mid-write | Acquire a `PowerManager.PARTIAL_WAKE_LOCK` in the recording service. Tag it `"YourApp:RecorderWakeLock"` (visible in `adb shell dumpsys power`). Call `setReferenceCounted(false)`. Always pass a timeout to `acquire(ms)` — Lint complains and Doze gets hostile if you don't. Release on every stop path (success, failure, no-file, `onDestroy`) |
| System `setShowWhenLocked(true)` does nothing even though it was called | The flag only takes effect if the activity was in the `STARTED` state at the moment the lock happens. Calling it in `onCreate` with the activity backgrounded is a no-op | Tie the toggle to live recording state: observe `RecorderService.isRecording.collect` inside a `repeatOnLifecycle(Lifecycle.State.STARTED)` block and call `setShowWhenLocked` / `setTurnScreenOn` from there. Clear the flags when recording stops so normal lock behaviour resumes |
| Activity recreates on rotation / touch / font-scale change and tears down a Compose cover screen | By default Android restarts activities on a config change. Even a sensor blip (accidental orientation probe from the OS) can pull the rug out from under a long-running cover | Add `android:configChanges="orientation\|screenSize\|screenLayout\|keyboardHidden\|uiMode\|smallestScreenSize\|navigation\|keyboard\|density\|fontScale"` to the Activity in `AndroidManifest.xml`. Compose handles the remaining size updates automatically; no manual `onConfigurationChanged` wiring needed |

### App pinning / LockTask toast is un-suppressible

| Symptom | Cause | Fix |
|---|---|---|
| `startLockTask()` triggers a system toast "Screen pinned — touch and hold Back and Overview to unpin" every time | That toast is emitted by the framework itself whenever a non-DeviceOwner / non-system-signed app calls `startLockTask()` on a device where the user has enabled "App pinning" under Settings → Security. There is NO documented API to suppress it from a regular app | Don't call `startLockTask()` for a stealth/cover screen. Use `BackHandler` + `WindowInsetsControllerCompat` immersive-sticky (hide system bars) + `FLAG_KEEP_SCREEN_ON` as the baseline. Accept that bottom-home-swipe may dismiss the cover on Pixel — the recording itself keeps running via the foreground service |

### System photo picker and ACTION_PICK routing

| Symptom | Cause | Fix |
|---|---|---|
| `Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)` opens Google Photos and demands a cloud sign-in on Pixel | `ACTION_PICK` routes to whatever app registered as the default handler for the image/video MIME type. On Pixel that's Google Photos, which refuses to return URIs until the user authenticates against a Google account — even when all the user's photos are local | If you only need local media, query MediaStore yourself. `contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, ...)` gives you `_ID` + `DISPLAY_NAME` + `SIZE` + `MIME_TYPE` + `DURATION` directly. `ContentUris.withAppendedId(collection, id)` turns a row into a real `content://media/external/...` URI — which is exactly what `MediaStore.createDeleteRequest` needs to actually delete originals. Thumbnails: `ContentResolver.loadThumbnail(uri, Size, null)` on API 29+, deprecated `MediaStore.Images/Video.Thumbnails.getThumbnail` below. Build a Compose `LazyVerticalGrid` around this and you never touch Google Photos |

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

### 2026-04-15 session — Round 4 (commits `7cb44b6`..`948a29d`, branch `claude/fix-media-playback-bug-XM1sr`)

Root-caused the media-playback bug from `0f9037b` and landed the user's Round 4 stability+security bundle.

- **N1 filename sanitize (`7cb44b6`):** primary bug — `VaultFile.originalName` ("Video Apr 14, 2026 08:30.mp4") was being embedded into the decrypt cache path. `Uri.parse` choked on the colons/commas. New `extensionFor()` helper + `view_<uuid>.<ext>` pattern. See the new "filename-parsing in setDataSource" lesson above.
- **N2 fsync (`c9ac879`):** `fos.flush() + fos.fd.sync()` inside `encryptStream`'s outer `use {}` so a post-stopForeground reap can't lose the GCM auth tag. See the new "Durability of AES-GCM" lesson above.
- **N3 log decrypt failures (`0bef40d`):** replaced `runCatching.getOrNull()` in `VaultFileViewerViewModel.decrypt` with a try/catch that calls `AppLogger.log`. `@ApplicationContext` injection pattern: `@Inject @ApplicationContext appContext: Context` in a HiltViewModel is the canonical way.
- **N4 zero-byte guard (`962289d`):** `MIN_VALID_RECORDING_BYTES = 1024L` in `RecorderService.persistRecordingToVault`. Below threshold → log + delete plaintext + placeholder Recording, no vault row.
- **Feature A battery exemption (`2eb5766`):** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in manifest + Settings row that branches on `PowerManager.isIgnoringBatteryOptimizations`. **Watch out:** duplicate `val context = LocalContext.current` declarations in the same Compose Column scope = compile error. Moved the single declaration up so both sections share it.
- **Feature B overlay lock (`948a29d`):** `SYSTEM_ALERT_WINDOW` + new `OverlayLockService` using plain Android Views. **Decision to document:** ComposeView in a Service requires ViewTreeLifecycleOwner / ViewTreeViewModelStoreOwner / ViewTreeSavedStateRegistryOwner to be set on the host view. For a 2-screen UI (clock + PIN pad) that's ~100 lines of plumbing. LinearLayout + GridLayout + Button is boring and reliable — take the plain-view path for service-hosted UIs unless you already have rich Compose reuse. The overlay is attached with `TYPE_APPLICATION_OVERLAY` (API 26+; pre-26 falls back to deprecated `TYPE_SYSTEM_ALERT`). Android 10+ system gestures STILL take priority over overlays — our overlay follows the user around the OS rather than actively blocking the gesture.
- **Feature C auto-resume (`d2e0b82`):** marker file pattern `.in_progress_<id>` (tmp + rename for atomicity) written before any failable setup in `RecorderService.startRecording`. Scan on `StealthCalcApp.onCreate` + `BootReceiver.onReceive`. **Hilt trap:** `Application` and `BroadcastReceiver` don't support `@Inject` field injection. Use `dagger.hilt.EntryPoints.get(context, Accessor::class.java)` with an `@EntryPoint @InstallIn(SingletonComponent::class) interface Accessor { fun recordingRecovery(): RecordingRecovery }` defined on the target class.
- **Feature D notification action (`f39a89e`):** `NotificationCompat.Builder.addAction` with `PendingIntent.getService(..., ACTION_STOP, FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT)`. `requestCode=1` to distinguish from `requestCode=0` on the tap-to-open intent — otherwise they collapse and both actions launch the same thing.
- **Feature J secure-delete (`3370b4c`):** `RandomAccessFile("rw")` + `SecureRandom` 8KB chunks + `fd.sync()` + `delete()`. Wired into VaultRepository + VaultFileViewerViewModel + RecorderRepository. Recorder → Vault cascade fixes the Round 1 orphan-VaultFile limitation.

Ordering paid off again: N1 first (the user's blocking bug), then N2-N4 as defensive companions, then D/A/J/C/B smallest-to-biggest so any GitHub Actions regression is trivially bisectable. Each commit is exportable + testable in isolation.

### 2026-04-15 session — Round 5 (commits `6eb39dc..33cd2d3`, branch `claude/fix-video-player-add-features-Pyk9Z`)

The "video doesn't play" symptom that survived four prior rounds (N1 temp-filename, N2 fsync, N3 logging, N4 zero-byte guard) finally root-caused. The lesson tables below distill it into rules so the same trap doesn't get re-set.

#### Conscrypt's AES-GCM is NOT streamable (single biggest surprise of the session)

Android's default JCE provider is Conscrypt. Conscrypt's `AES/GCM/NoPadding` implementation buffers the ENTIRE ciphertext in an internal `ByteArrayOutputStream` until `cipher.doFinal()` is called — because GCM authentication needs to process all data before producing the 16-byte tag. This means **peak heap during encrypt and decrypt equals the file size**. A 446 MB recording crashes the process with `OutOfMemoryError` at the `ByteArrayOutputStream.grow()` doubling step, well before reaching the actual file size.

Both `CipherOutputStream(fos, cipher)` (writes go into `cipher.update()` which buffers) and the manual `cipher.update(buffer)` pattern hit this — the entry point doesn't matter, the cipher buffers regardless. There is no flag to disable this buffering.

**Stack trace for next-time pattern matching:**
```
java.lang.OutOfMemoryError: Failed to allocate a 268435472 byte allocation...
  at java.io.ByteArrayOutputStream.grow(ByteArrayOutputStream.java:120)
  at com.android.org.conscrypt.OpenSSLAeadCipher.appendToBuf(OpenSSLAeadCipher.java:313)
  at com.android.org.conscrypt.OpenSSLAeadCipher.updateInternal(OpenSSLAeadCipher.java:321)
  at javax.crypto.Cipher.update(Cipher.java:1741)
```
Anything matching this stack means "you tried to GCM-encrypt/decrypt a file too big to fit in heap."

**Fix patterns** (pick one):
| Approach | Trade-off |
|---|---|
| **AES-CTR + HMAC-SHA256 (encrypt-then-MAC)** | What we used. Standard AEAD construction. CTR is a true stream cipher — `update()` returns exactly the bytes you fed it. HMAC is computed in the same pass. Constant memory at any size. Format change required + backward-compat path for old GCM files. |
| BouncyCastle's GCM | Streams correctly, but adds `org.bouncycastle:bcprov-jdk15to18` (~5 MB) and you must explicitly install the provider — Conscrypt is preferred by default. |
| Tink's `StreamingAead` (`AesGcmHkdfStreaming`) | Streams in chunks transparently. Adds Tink (~2 MB) and a key-handle migration. Overkill if you don't already use Tink. |
| Chunked GCM by hand (per-chunk IV+tag) | Streams, stays on Conscrypt, no new deps. But you invent a custom file format and each chunk pays a 16-byte tag overhead. |

**File format we shipped** (`SC2v` magic so legacy GCM files are still readable):
```
[4 bytes magic "SC2v"]   ← detect at decrypt; if missing, run legacy GCM path
[16 bytes random AES-CTR IV]
[N bytes ciphertext = AES-256-CTR(plaintext)]
[32 bytes HMAC-SHA256(magic || iv || ciphertext)]
```
Decrypt flow: open file, peek 4 bytes, dispatch to `decryptV2()` or `decryptLegacyGcm()`. Verify HMAC with `MessageDigest.isEqual(expected, computed)` (constant-time).

**HMAC key**: don't reuse the AES key for HMAC. Derive a separate one. We used `SHA-256(aesKey || "stealthcalc-hmac-v2")` which is an adequate KDF when the AES key already has full Keystore entropy. Use HKDF if you don't have that guarantee.

#### `CipherOutputStream.close()` closes the wrapped `FileOutputStream` (silent fsync no-op)

Documented contract: "Closing this output stream causes this filter's output stream and the cipher to be closed." So this pattern is broken:
```kotlin
FileOutputStream(outputFile).use { fos ->
    fos.write(iv)
    CipherOutputStream(fos, cipher).use { cos ->
        // ... write ciphertext ...
    }  // ← this closes fos
    fos.flush()       // ← no-op, fos is closed (Java FileOutputStream.flush doesn't throw, just does nothing)
    fos.fd.sync()     // ← throws IOException("File is closed"), swallowed by runCatching
}
```
Round 4 N2 added the flush+fsync to durably write the GCM tag before service reap. Because of this issue, the fsync NEVER happened. To fix, drive the cipher manually OR open `fos` separately and don't `use{}` it through the inner block. Round 5's V2 (CTR+HMAC) rewrite handles its own buffering and explicitly does `fos.flush() + fos.fd.sync()` before closing.

#### Compose: ExoPlayer listener MUST be attached before `prepare()`

Anti-pattern that wasted a debug round:
```kotlin
val exoPlayer = remember(file) {
    ExoPlayer.Builder(ctx).build().apply {
        setMediaItem(MediaItem.fromUri(uri))
        prepare()  // ← starts; can fire onPlayerError or STATE_READY immediately
    }
}
DisposableEffect(exoPlayer) {       // ← runs in post-composition effect phase, AFTER body
    val l = object : Player.Listener { ... }
    exoPlayer.addListener(l)         // ← too late; missed any synchronous event
    onDispose { exoPlayer.removeListener(l); exoPlayer.release() }
}
```
The DisposableEffect body runs as part of Compose's effect phase, AFTER the composition body. Anything `prepare()` fires synchronously (an immediate format error, or a fast STATE_READY for a small cached file) is gone before the listener attaches → UI stuck on initial state forever (`isBuffering = true` in our case).

Correct pattern: build the bare player in `remember{}`, attach the listener in `DisposableEffect` AS THE FIRST THING, THEN call `setMediaItem` + `prepare()`:
```kotlin
val exoPlayer = remember(file) { ExoPlayer.Builder(ctx).build() }
DisposableEffect(exoPlayer, file) {
    val l = object : Player.Listener { ... }
    exoPlayer.addListener(l)
    exoPlayer.setMediaItem(MediaItem.fromUri(uri))
    exoPlayer.prepare()
    onDispose { exoPlayer.removeListener(l); exoPlayer.release() }
}
```
Pair with a watchdog `LaunchedEffect` that times out after N seconds if `STATE_READY` never arrives — covers the case where the player silently stalls without ever firing `onPlayerError`. We used 12s.

#### Diagnostic-first when chasing media bugs

After three rounds of guessing at "video saved but won't play", the actual root cause was nailed in 5 minutes once we logged the recording's first 12 bytes (verify ASCII `ftyp` MP4 box) + `MediaMetadataRetriever` outputs (duration / w x h / mime) BEFORE encryption:
```
[recorder] recording sanity id=... type=VIDEO size=467799153
  ftyp='ftyp' ftypOk=true retrieverOk=true durMs=309000
  wxh=1280x720 mime=video/mp4 path=...
```
This single log line proved the MP4 was valid → the bug was downstream → the OOM stack trace pinpointed it within the encryption call. Always add this kind of "the file was X bytes, the system saw Y" log right at the boundary you're suspicious of.

#### Compose `mutableStateOf` delegated property + smart cast

Already in this doc but bit us again on a fresh path:
```kotlin
var currentFile by remember { mutableStateOf<VaultFile?>(null) }
// ...
if (currentFile?.fileType == VaultFileType.PHOTO) {
    onMergePhoto(currentFile.id)   // ← Smart cast to 'VaultFile' is impossible, because 'currentFile' is a delegated property
}
```
Always capture into a local `val` immediately after the null check or at the start of the conditional:
```kotlin
val cf = currentFile
if (cf != null && cf.fileType == VaultFileType.PHOTO) {
    onMergePhoto(cf.id)
}
```

#### MediaStore export (vault → public library)

For writing into Pictures/Movies/Music on API 29+:
```kotlin
val values = ContentValues().apply {
    put(MediaStore.MediaColumns.DISPLAY_NAME, sanitizedName)
    put(MediaStore.MediaColumns.MIME_TYPE, mime)
    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/StealthCalc")
    put(MediaStore.MediaColumns.IS_PENDING, 1)  // hide while we write
}
val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
resolver.openOutputStream(uri)!!.use { out -> /* write bytes */ }
resolver.update(uri, ContentValues().apply { put(IS_PENDING, 0) }, null, null)
```
Sanitize `DISPLAY_NAME` — strip `\\/:*?"<>|` and runs of whitespace. Recorder titles like `"Video Apr 14, 2026 08:30.mp4"` (colons + commas) crash some gallery apps if you don't.

API 26-28 still need `<uses-permission android:name="WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />` in the manifest. API 29+ inherits write access from the URI returned by `insert()` — no permission required.

#### CameraX video FILES ARE FINE — don't blame the codec without proof

Half this round's time was wasted suspecting CameraX's `Recorder` was producing malformed MP4s (fMP4 vs standard, missing moov atom, codec issues). It wasn't. The sanity-log proved `MediaMetadataRetriever` happily extracted duration / w x h / mime from a 446 MB file — the container was valid. The bug was in the encryption pipeline. Lesson: when a media file "won't play", verify the container BEFORE blaming the producer.

---

### 2026-04-14 session — Round 3 (commits `a766fd0`..`ba15641`, branch `claude/fix-cloud-signin-video-bugs-cGptT`)

- **Fix M — pinning toast (`a766fd0`):** removed `startLockTask`/`stopLockTask` from FakeLockScreen. System toast was un-suppressible; home-swipe dismiss is the accepted trade-off.
- **Fix L — lock resilience (`a7f85bc`):** `PARTIAL_WAKE_LOCK` in RecorderService + `setShowWhenLocked` / `setTurnScreenOn` on MainActivity while recording + `android:configChanges` on MainActivity so rotation/touch/font-scale don't recreate.
- **Fix K — CameraX video (`992c4c7`):** swapped `MediaRecorder.VideoSource.CAMERA` (legacy Camera1, silently broken) for CameraX `Recorder` + `VideoCapture`. `RecorderService` now extends `LifecycleService`. Added `androidx.camera:camera-video:1.3.4` and `androidx.lifecycle:lifecycle-service:2.8.4`.
- **Fix J — in-app gallery picker (`ba15641`):** new `InAppMediaPickerScreen` + `InAppMediaPickerViewModel` query MediaStore directly and return real `content://media/external/...` URIs — no Google Photos, no cloud sign-in. Nested `navigation("vault_graph")` shares `VaultViewModel` between Vault and picker.

Session notes that paid off:
- **Round-3 ordering:** landed smallest isolated fix first (pinning toast deletion), then manifest + WakeLock, then CameraX, then the gallery picker. Each commit pushed on its own so GitHub Actions could bisect any regression.
- **Nested navigation graph for shared VM:** `navigation("vault_graph") { composable(Vault.route); composable(MediaPicker.route) }` + `hiltViewModel(parentEntry)` is the cleanest way to share a Hilt VM between a parent screen and a child flow without passing callbacks through Activity/Application scope.
- **CameraX Recorder.stop() is async:** drive the vault persist off `VideoRecordEvent.Finalize`, NOT off the `stopRecording()` call site. Finalize carries `hasError()` + `error` + `cause` — use them.

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

---

## Round 6: Phone Monitoring Module (2026-04-16)

### New dependency patterns

| Topic | Gotcha | Fix |
|---|---|---|
| `kotlinx.serialization` plugin | Must be `org.jetbrains.kotlin.plugin.serialization` with the **same version as Kotlin** (2.0.10). Different from the runtime dep version (1.7.1). | Version catalog: `kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }` |
| Ktor client on Android | Use `ktor-client-okhttp` engine (not CIO or Android — CIO has issues with TLS on some API levels, Android engine is experimental). | `implementation(libs.ktor.client.okhttp)` |
| `@HiltWorker` | Requires both `androidx.hilt:hilt-work` AND `ksp(androidx.hilt:hilt-compiler)` (this is separate from dagger `hilt-android-compiler`). Also requires custom `WorkManager` init via `Configuration.Provider`. | See `StealthCalcApp.kt` + manifest `<provider>` that disables `WorkManagerInitializer`. |
| `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` | New in API 34. Must declare a `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE">` child inside the `<service>` tag explaining the use case. Without it, Play Store review rejects. | Sideloaded app — not a concern for us, but declared anyway for completeness. |
| `NotificationListenerService` | NOT a permission grant — user must toggle it in Settings → Notification access. `@AndroidEntryPoint` works because it extends `Service`. System manages binding lifecycle. | Must be `android:exported="true"` with `android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"`. |
| `PACKAGE_USAGE_STATS` | Special permission — not grantable at runtime via `requestPermissions()`. User must go to Settings → Usage access. Test with `UsageStatsManager.queryUsageStats()` — empty list means not granted. | Use `tools:ignore="ProtectedPermissions"` in manifest to suppress lint. Guide user to Settings from Agent Config UI. |
| `play-services-location` | Transitive dep on `play-services-base`. On devices without Play Services, `FusedLocationProviderClient` throws. | Pixel 6 has Play Services. `LocationCollector.collect()` wraps in `runCatching`. |
| Exposed ORM SQLite (server) | `upsert` with composite primary key needs both columns passed to `upsert()` call. SQLite doesn't support `ON CONFLICT` on multi-column PKs in all modes — test. | `RecentState.upsert(RecentState.deviceId, RecentState.field) { ... }` |

### Round 6 extended (2026-04-17 session 2)

| Topic | Gotcha | Fix |
|---|---|---|
| `AccessibilityService` + `@AndroidEntryPoint` | Works because `AccessibilityService` extends `Service`. Hilt injects in `onCreate()` before the system calls `onServiceConnected()`. Must be `exported=true` with `BIND_ACCESSIBILITY_SERVICE` permission. | Manifest: `<service android:exported="true" android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">` + `<meta-data android:name="android.accessibilityservice" android:resource="@xml/accessibility_config" />` |
| `TYPE_VIEW_TEXT_CHANGED` for keylogging | Fires per-character. Buffering with a 2-second flush delay avoids flooding the DB. Compare new text to last value — if longer and starts with it, diff is appended chars; if shorter, record `[DEL]`. | `keystrokeFlushJob?.cancel(); keystrokeFlushJob = scope.launch { delay(2000); flush() }` |
| `MediaProjection` requires Activity consent | `MediaProjectionManager.createScreenCaptureIntent()` must be launched via `startActivityForResult` from an Activity. The resultCode + data Intent must be passed to `getMediaProjection()`. **Currently a gap** — `setMediaProjection()` exists but no UI calls it. | Next session: add a consent launcher in AgentConfigScreen or a transparent Activity. |
| Camera2 in a Service (no preview Surface) | `CameraDevice.TEMPLATE_STILL_CAPTURE` works without a preview surface. For video/repeating capture, use an `ImageReader` surface. The Camera2 callback model is async — use `CountDownLatch` with a timeout to block the coroutine. | `FaceCaptureCollector` + `RemoteCameraService` both use `CountDownLatch(1).await(5, SECONDS)`. |
| `MediaCodec` encoder for screen recording | Surface input via `createInputSurface()` feeds frames from MediaProjection's VirtualDisplay. Must call `signalEndOfInputStream()` + drain remaining buffers before stopping the muxer. Width/height must be even numbers. | `ScreenRecordCollector`: `((width * scale).toInt() / 2) * 2` for even alignment. |
| `SmsManager.getDefault()` deprecated | On API 31+ use `context.getSystemService(SmsManager::class.java)`. On API < 31 use the static `getDefault()`. Our `minSdk = 26` so we use the deprecated version with `@Suppress`. | Works fine on sideloaded builds. Play Store would reject `SEND_SMS` without being the default SMS app. |
| `NetworkStatsManager.querySummary` | Needs `PACKAGE_USAGE_STATS` permission (same one as `UsageStatsManager`). Returns per-UID stats; map UID → package via `PackageManager.getPackagesForUid()`. | `DataUsageCollector` filters to top 20 apps by total bytes > 100KB. |
| `ACTIVITY_RECOGNITION` permission | Runtime permission on API 29+. Needed for `TYPE_STEP_COUNTER` sensor. If not granted, `SensorManager.registerListener` silently fails. | Check permission before registering. Pedometer returns cumulative steps since boot — compute deltas. |
| Sensor listener battery drain | Registering `SENSOR_DELAY_NORMAL` for accelerometer/proximity/light burns battery if left running continuously. | `SensorCollector` records only every 5 minutes (debounced in `maybeRecord()`). Consider unregistering between samples in a future optimization. |
| `BroadcastReceiver` with `@AndroidEntryPoint` | Hilt injects via `applicationContext`. The receiver must have a no-arg constructor. `goAsync()` + `CoroutineScope` + `pending.finish()` allows suspend work within the 10-second ANR deadline. | `AppInstallReceiver` and `AgentBootReceiver` both use this pattern. |
| Server `RETENTION_DAYS` cleanup | Coroutine runs every 6 hours. Deletes events by `capturedAt` and files by `lastModified`. Must not delete the `.index.json` file. | `Cleanup.kt`: `if (file.name.startsWith(".")) return@forEach` |

---

## Round 8 (2026-04-20)

| Topic | Gotcha | Fix |
|---|---|---|
| `BiometricPrompt` with `ComponentActivity` | `MainActivity` extends `ComponentActivity`, NOT `FragmentActivity`. The old `BiometricPrompt(FragmentActivity, Executor, AuthenticationCallback)` constructor won't accept it. | Use `BiometricPrompt(ComponentActivity, AuthenticationCallback)` (no Executor param) — added in `androidx.biometric:biometric:1.2.0-alpha05`. Remove the `ContextCompat.getMainExecutor()` call. |
| `activity-alias` + `PackageManager.setComponentEnabledSetting` | When switching icons, you must disable the current launcher component AND enable the new one. If you only enable the new alias without disabling the main activity or the other aliases, all enabled ones appear simultaneously in the launcher. | Iterate all known component names; set `COMPONENT_ENABLED_STATE_ENABLED` for the chosen alias and `COMPONENT_ENABLED_STATE_DISABLED` for every other. Main activity launcher entry must also be disabled when an alias is active. Use `DONT_KILL_APP` flag — `KILL_APP` would force-stop the running process. |
| `SettingsViewModel` needs `Context` | `PackageManager` calls require a `Context`. `SettingsViewModel` was Context-free. | Add `@ApplicationContext private val context: Context` to the constructor — Hilt provides `@ApplicationContext` for `@HiltViewModel`. |
| `GeckoView` download delegate API | Tried to add `downloadDelegate` to intercept browser downloads and save to vault. The method signatures in GeckoView 123 don't match older documentation examples — `onDownloadRequest` takes `GeckoSession.WebResponse`, not `GeckoSessionFetch`. | Remove the delegate entirely; implement "save page" as a stub text file containing URL + title. Full MHTML capture would need a working GeckoSession download delegate — defer until API is confirmed. |
| ML Kit `TextRecognition` dep | `com.google.mlkit:text-recognition` requires `com.google.mlkit:text-recognition-common` and the script-specific dep (`text-recognition` defaults to Latin). | Use `implementation("com.google.mlkit:text-recognition:16.0.1")` — it bundles Latin script. For Chinese/Japanese/Korean, add separate deps. `TextRecognizerOptions.DEFAULT_OPTIONS` picks the right model automatically. |
| `ScheduleConfigViewModel` keys in `AgentService` | `ScheduleConfigViewModel` is a `ViewModel` — cannot be injected into a `Service` via `@Inject`. Tried injecting the VM class directly; Hilt rejects it at compile time. | Read prefs directly in `AgentService` using `@EncryptedPrefs SharedPreferences`. Duplicate the key constant strings — a small maintenance cost but the only option without a shared prefs-key module. |
| `activity-alias` icon resources must be in `mipmap`, not `drawable` | Adaptive icon XML files in `mipmap-anydpi-v26/` that reference `@drawable/` background shapes work fine. The alias's `android:icon` attribute must point to `@mipmap/ic_launcher_xxx` (where the adaptive-icon XML lives), not the background drawable directly. | Create `mipmap-anydpi-v26/ic_launcher_clock.xml` and `ic_launcher_notes.xml` as adaptive icons, then reference them with `android:icon="@mipmap/ic_launcher_clock"` in the alias. |

---

## Agent App — Command Implementation (2026-04-20)

| Topic | Gotcha | Fix |
|---|---|---|
| Ktor WebSocket client — `install(WebSockets)` on shared client | The existing `AgentClient` HTTP client used `OkHttp` engine with only `ContentNegotiation` installed. Calling `.webSocket(...)` on it throws because the WebSockets plugin is not installed. | Add `install(WebSockets)` to the same `HttpClient` lazy initializer. Ktor supports both HTTP and WebSocket plugins on the same client instance. |
| `NotificationListenerService` `instance` singleton | `replyToNotification` needs access to the live NLS instance to call `activeNotifications` and `sendReply`. The service is bound by the system — you can't inject or resolve it via Hilt. | Store `instance` in a companion object, set in `onListenerConnected()`, nulled in `onListenerDisconnected()`. Check for null before calling. |
| `Notification.Action.remoteInputs` for inline reply | `Notification.Action` can have zero or more `RemoteInput` objects. Only actions with at least one `RemoteInput` support inline reply. Need to find the right action and the right result key. | Filter actions where `action.remoteInputs?.isNotEmpty() == true`, take the first match. Use `RemoteInput.addResultsToIntent()` to bundle the reply text, then `action.actionIntent.send(context, 0, fillIn)`. |
| `MediaProjection` + FGS type on API 34+ | Calling `MediaProjectionManager.getMediaProjection(resultCode, data)` on Android 14+ throws `SecurityException` if the calling app does not have a foreground service with type `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` running. | Add `mediaProjection` to `android:foregroundServiceType` in the manifest (`specialUse\|mediaProjection`) and update `startForeground()` call. Note: `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` must be OR'd into the bitmask. |
| Screen recording requires user consent every session | `MediaProjectionManager.createScreenCaptureIntent()` must be launched from an Activity via `startActivityForResult`. The resultCode+data cannot be persisted across reboots. | Implement `AgentScreenRecordService.setMediaProjection(resultCode, data)` to be called from the agent's setup/config UI after user consent. `recordScreen()` returns `false` silently if not set — no crash. |
| Camera2 in a foreground service — thread required | `CameraManager.openCamera()` requires a `Handler` for callbacks. Creating a `Handler` on the calling thread (usually `Dispatchers.IO` coroutine) throws because that thread has no `Looper`. | Spin up a `HandlerThread` (`"AgentCamera"`, `"AgentLiveCamera"`) and pass its `Handler`. Call `quitSafely()` in `release()` / `stopStreaming()`. |
| `AgentClient.uploadFile()` takes `ByteArray`, not `File` | The main app's `FileUploader` takes a `File`. The agent's `AgentClient.uploadFile()` takes `ByteArray`. | Read file bytes with `file.readBytes()` before calling `uploadFile()`, then `file.delete()`. For large captures this is acceptable (JPEG / short audio). |
