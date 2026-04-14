# StealthCalc — Project Context

## What This Is
A personal stealth productivity Android app disguised as a calculator. Enter a secret PIN on the calculator keypad + press `=` to unlock a hidden suite of encrypted tools. Built with Kotlin + Jetpack Compose + Hilt + Room/SQLCipher.

## Tech Stack
- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **Architecture:** MVVM + Clean Architecture, single-activity
- **Database:** Room + SQLCipher (AES-256 encrypted)
- **DI:** Hilt
- **Browser:** Mozilla GeckoView (Firefox engine, NOT Chrome)
- **Camera:** CameraX
- **Build:** Gradle KTS, GitHub Actions for APK builds

## Modules (all complete)
1. **Calculator** — Fully working calculator with secret PIN detection (`calculator/`)
2. **Auth** — Secret code (SHA-256 hashed), auto-lock, biometric, panic handler, decoy PIN (`auth/`)
3. **Notes** — Encrypted notes with folders, tags, rich text editor, secure clipboard copy (`notes/`)
4. **Tasks** — Task lists, priorities, habits with streaks, goals with milestones (`tasks/`)
5. **Recorder** — Audio + video recording with fake phone lock screen cover (`recorder/`)
6. **Browser** — GeckoView (Firefox), Enhanced Tracking Protection, no cookies, private mode (`browser/`)
7. **Vault** — AES-256 encrypted media storage, secure camera, import from gallery (deletes originals), sort by date/size/name (`vault/`)
8. **Settings** — Change PIN, decoy PIN, biometric toggle, auto-lock timer, panic shake/back, screenshot blocking (`settings/`)

## Key Architecture Decisions
- Single Activity (`MainActivity`) — OS only sees "Calculator"
- `AppRoot` composable manages 4 states: Calculator → Setup → Decoy → Stealth
- Secret PIN threaded from calculator unlock → stored in memory → passed to recorder's fake lock screen
- `FLAG_SECURE` on all stealth screens (no screenshots)
- `EncryptedSharedPreferences` for settings, SQLCipher for database, AES-256-GCM for vault files
- GeckoView artifact is architecture-specific: `geckoview-arm64-v8a`

## Branch
- Development branch: `claude/stealth-app-planning-RvjrV`
- GitHub Actions builds APK on every push (`.github/workflows/build-apk.yml`)

## Current Build Status
- Last push: commit `2ba7c87` — fixed GeckoView artifact name to `geckoview-arm64-v8a`
- Previous build failed because `geckoview` (without arch suffix) doesn't exist on Mozilla Maven
- Build-log artifact is uploaded on failure for debugging
- If build fails again: download `build-log` artifact from Actions, paste errors to fix

## File Structure
```
app/src/main/java/com/stealthcalc/
├── StealthCalcApp.kt          # Hilt Application
├── MainActivity.kt            # Single activity, FLAG_SECURE, auto-lock
├── calculator/                # Calculator UI + engine + secret code detection
├── auth/                      # SecretCodeManager, AutoLockManager, BiometricHelper, PanicHandler
├── core/                      # Database, encryption, DI modules, SecureClipboard
├── stealth/                   # StealthHomeScreen, DecoyHomeScreen, navigation graph
├── notes/                     # Encrypted notes vault
├── tasks/                     # Task manager, habits, goals
├── recorder/                  # Audio/video recorder + FakeLockScreen
├── browser/                   # GeckoView browser + AdBlocker + ReaderMode + LinkVault
├── vault/                     # Encrypted media vault + SecureCamera + FileEncryptionService
├── settings/                  # Settings screen + ViewModel
└── ui/theme/                  # Material 3 theme
```

## Important Notes
- The `settings.gradle.kts` uses `dependencyResolutionManagement` (not `dependencyResolution`)
- Mozilla Maven repo: `https://maven.mozilla.org/maven2`
- GeckoView requires arch suffix: `geckoview-arm64-v8a`, NOT plain `geckoview`
- `combine()` with >5 flows needs the vararg Array form
- Room DB version is currently 5
- Don't use `StorageController.ClearFlags.ALL` for GeckoView — use individual flags ORed together
