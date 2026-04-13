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

## Repository Layout
- `settings.gradle` - Project settings with Google, Maven Central, and Mozilla Maven repos
- `build.gradle` - Root build config (AGP + Kotlin plugin declarations)
- `app/build.gradle` - App module with GeckoView dependency
- `main.py` - Utility script

## Known Issues / Gotchas
- GeckoView versions must match an exact published build timestamp. Incorrect timestamps will cause `ModuleVersionNotFoundException` at `:app:checkDebugAarMetadata`.
- The Mozilla Maven repository (`maven.mozilla.org/maven2/`) must be configured in `settings.gradle` under `dependencyResolutionManagement.repositories`.
- When upgrading GeckoView past v117, the artifact name must change from `geckoview-arm64-v8a` to `geckoview-omni-arm64-v8a`.

## Development Branch
- Feature branch: `claude/fix-geckoview-dependency-eCOOU`
- Base branch: `master`
