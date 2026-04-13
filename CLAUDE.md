# Project Context

## Goal
Build and deliver the debug APK file for this Android project. Every session should push code and confirm the GitHub Actions build produces the APK artifact.

## What This Is
Android project using Mozilla GeckoView as the browser engine.

## Build Process

Builds run via GitHub Actions (`.github/workflows/build.yml`). Push to any `claude/**` or `master` branch triggers a build automatically. The workflow also supports `workflow_dispatch` for manual triggers.

The APK is uploaded as a GitHub Actions artifact named `app-debug`. On failure, build logs are uploaded as `error-log` artifact.

Do not build locally - use GitHub Actions.

## Build System

- Gradle 8.7 (wrapper in repo)
- Android Gradle Plugin 8.3.0
- Kotlin 1.9.22, Java 17
- compileSdk 34, minSdk 24, targetSdk 34

## Dependencies

- **GeckoView**: `org.mozilla.geckoview:geckoview-omni-arm64-v8a:123.0.20240213221259`
  - Repository: `https://maven.mozilla.org/maven2/`
  - `geckoview-arm64-v8a` (non-omni) was discontinued after v117. Use `geckoview-omni-arm64-v8a` for v118+.

## Repository Layout

- `.github/workflows/build.yml` - CI workflow
- `settings.gradle` - Repos: google(), mavenCentral(), maven.mozilla.org
- `build.gradle` - Root build config
- `app/build.gradle` - App module with GeckoView dependency
- `gradlew` + `gradle/wrapper/` - Gradle wrapper
- `app/src/main/` - Android manifest and Kotlin sources

## Branch

- Feature: `claude/fix-geckoview-dependency-eCOOU`
- Base: `master`
