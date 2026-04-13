# Project Context

## Overview
Android project using Mozilla GeckoView as the browser engine.

## Build System
- Gradle 8.7 with Android Gradle Plugin 8.3.0
- Kotlin 1.9.22, Java 17
- `compileSdk 34`, `minSdk 24`, `targetSdk 34`

## Key Dependencies
- **GeckoView**: `org.mozilla.geckoview:geckoview-omni-arm64-v8a:123.0.20240213221259`
  - Hosted on Mozilla Maven: `https://maven.mozilla.org/maven2/`
  - The `geckoview-arm64-v8a` (non-omni) artifact was discontinued after v117. For v118+, use `geckoview-omni-arm64-v8a` instead.
  - Version format: `MAJOR.MINOR.BUILDTIMESTAMP` (follows Firefox release versioning)

## Building the APK

**Builds run via GitHub Actions, NOT locally.** The CI environment has full access to Google Maven, Mozilla Maven, and the Android SDK.

### How to build:
1. Push changes to the branch (any `claude/**` or `master` branch)
2. GitHub Actions workflow (`.github/workflows/build.yml`) triggers automatically
3. The workflow builds the debug APK using `./gradlew assembleDebug`
4. The APK is uploaded as a GitHub Actions artifact named `app-debug`
5. Download the APK from the Actions run artifacts tab

### Why not build locally:
This remote environment does not have direct access to `dl.google.com` (Android SDK/plugins) or `maven.mozilla.org` (GeckoView). All builds must go through GitHub Actions which has unrestricted network access.

## Repository Layout
- `.github/workflows/build.yml` - GitHub Actions CI workflow for building APK
- `settings.gradle` - Project settings with Google, Maven Central, and Mozilla Maven repos
- `build.gradle` - Root build config (AGP + Kotlin plugin declarations)
- `app/build.gradle` - App module with GeckoView dependency
- `gradlew` + `gradle/wrapper/` - Gradle wrapper (v8.7)
- `app/src/main/AndroidManifest.xml` - App manifest
- `app/src/main/java/` - Kotlin source files
- `main.py` - Utility script

## Known Issues / Gotchas
- GeckoView versions must match an exact published build timestamp. Incorrect timestamps will cause `ModuleVersionNotFoundException` at `:app:checkDebugAarMetadata`.
- The Mozilla Maven repository (`maven.mozilla.org/maven2/`) must be configured in `settings.gradle` under `dependencyResolutionManagement.repositories`.
- When upgrading GeckoView past v117, the artifact name must change from `geckoview-arm64-v8a` to `geckoview-omni-arm64-v8a`.

## Development Branch
- Feature branch: `claude/fix-geckoview-dependency-eCOOU`
- Base branch: `master`
