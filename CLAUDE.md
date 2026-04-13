# Project Context

## Overview
Android project using Mozilla GeckoView as the browser engine.

## CRITICAL: How to Build the APK

**DO NOT attempt to build locally. DO NOT install Android SDK. DO NOT try apt-get or curl for SDK components.**

This remote environment blocks `dl.google.com` and `maven.mozilla.org` via network proxy. There is NO workaround. Builds MUST go through GitHub Actions.

### Build steps:
1. Make code changes and push to the branch
2. GitHub Actions workflow (`.github/workflows/build.yml`) triggers automatically on push to any `claude/**` or `master` branch
3. The workflow runs `./gradlew assembleDebug` on an `ubuntu-latest` runner (which has full network access + Android SDK)
4. The APK is uploaded as a GitHub Actions artifact named `app-debug`
5. User downloads the APK from the GitHub Actions run artifacts tab

### To manually trigger a build:
The workflow also supports `workflow_dispatch` - can be triggered from the GitHub Actions UI.

## Build System
- Gradle 8.7 (wrapper included in repo at `gradle/wrapper/`)
- Android Gradle Plugin 8.3.0
- Kotlin 1.9.22, Java 17
- `compileSdk 34`, `minSdk 24`, `targetSdk 34`

## Key Dependencies
- **GeckoView**: `org.mozilla.geckoview:geckoview-omni-arm64-v8a:123.0.20240213221259`
  - Hosted on Mozilla Maven: `https://maven.mozilla.org/maven2/`
  - The `geckoview-arm64-v8a` (non-omni) artifact was DISCONTINUED after v117. For v118+, MUST use `geckoview-omni-arm64-v8a`
  - Version format: `MAJOR.MINOR.BUILDTIMESTAMP` (follows Firefox release versioning)
  - The original broken version was `123.0.20240212205514` - this does NOT exist. Correct version is `123.0.20240213221259`

## Repository Layout
```
.github/workflows/build.yml  - GitHub Actions CI (builds APK)
settings.gradle               - Repos: google(), mavenCentral(), maven.mozilla.org
build.gradle                  - Root: AGP 8.3.0 + Kotlin 1.9.22 plugins
app/build.gradle              - App module with GeckoView dependency
gradlew + gradle/wrapper/     - Gradle wrapper v8.7
app/src/main/AndroidManifest.xml
app/src/main/java/com/example/claudephonetest/MainActivity.kt
main.py                       - Utility script
CLAUDE.md                     - THIS FILE - project memory
.gitignore                    - Ignores .gradle/, build/, local.properties
```

## Known Issues / Gotchas
- **Network proxy blocks Android builds locally** - `dl.google.com` and `maven.mozilla.org` are blocked in this environment. Never waste time trying to install SDK or build locally.
- GeckoView versions must match an exact published build timestamp. Wrong timestamps cause `ModuleVersionNotFoundException` at `:app:checkDebugAarMetadata`.
- When upgrading GeckoView past v117, artifact name MUST change from `geckoview-arm64-v8a` to `geckoview-omni-arm64-v8a`.
- Mozilla Maven repo (`maven.mozilla.org/maven2/`) must be in `settings.gradle` under `dependencyResolutionManagement.repositories`.

## Development Branch
- Feature branch: `claude/fix-geckoview-dependency-eCOOU`
- Base branch: `master`
