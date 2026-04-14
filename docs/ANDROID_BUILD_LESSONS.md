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
