# StealthCalc — Round 8 Features (2026-04-20)

**Branch:** `claude/round8-features-bYw2Y`
**Commit:** `b19bcb0`
**Base:** Round 7 (`claude/research-new-features-bYw2Y`)

---

## Features Shipped

### Tier 1 — Appearance & Settings

#### 1. AMOLED Dark Theme
- True-black (`#000000`) background variant for OLED power savings
- Toggle in **Settings → Appearance**
- Key: `SettingsViewModel.KEY_AMOLED_ENABLED = "amoled_theme_enabled"`
- `StealthCalcTheme(useAmoled: Boolean)` picks `AmoledColorScheme` vs `StealthDarkColorScheme`
- Read once at `MainActivity.setContent`; takes effect on next cold start
- Files: `ui/theme/Color.kt`, `ui/theme/Theme.kt`, `settings/viewmodel/SettingsViewModel.kt`, `settings/ui/SettingsScreen.kt`, `MainActivity.kt`

#### 2. App Icon Switcher
- 3 launcher identities: **Calculator** (default, dark), **Clock** (blue), **Notes** (green)
- Each alias has its own `<activity-alias>` in `AndroidManifest.xml` with separate icon and label
- `SettingsViewModel.switchAppIcon(alias)` calls `PackageManager.setComponentEnabledSetting` to enable one alias and disable the rest
- Picker dialog in **Settings → Appearance → App Icon / Disguise**
- Files: `AndroidManifest.xml`, `res/mipmap-anydpi-v26/ic_launcher_{clock,notes}.xml`, `res/drawable/ic_launcher_bg_{blue,green}.xml`, `res/values/strings.xml`, `SettingsViewModel.kt`, `SettingsScreen.kt`

#### 3. Biometric-Only Unlock (Long-press `=`)
- Long-pressing the `=` key triggers `BiometricHelper.showBiometricPrompt()` directly
- Skips PIN entry; only works if the user already authenticated via PIN once in this session (`BiometricHelper.canUseBiometric()`)
- `BiometricHelper` updated from `FragmentActivity` → `ComponentActivity` to match `MainActivity`'s base class (uses `androidx.biometric:biometric:1.2.0-alpha05` `BiometricPrompt(ComponentActivity, callback)` constructor)
- Files: `auth/BiometricHelper.kt`, `calculator/ui/CalculatorKeypad.kt`, `calculator/ui/CalculatorScreen.kt`, `stealth/navigation/AppNavigation.kt`, `MainActivity.kt`

#### 4. Shake Sensitivity Tuning
- Slider in **Settings → Security → Shake to Lock** shows Low (15 m/s²) / Medium (25 m/s²) / High (35 m/s²)
- `PanicHandler.shakeThreshold` is a computed property reading from `EncryptedSharedPreferences` on every sensor event check — changes take effect immediately without restarting
- Key: `PanicHandler.KEY_SHAKE_THRESHOLD = "panic_shake_threshold"`
- Files: `auth/PanicHandler.kt`, `settings/viewmodel/SettingsViewModel.kt`, `settings/ui/SettingsScreen.kt`

#### 5. Clipboard Timeout Config
- Picker in **Settings → Privacy → Clipboard Auto-Clear**: 15s / 30s / 1m / 5m / Never
- `SecureClipboard.scheduleAutoClear()` reads `KEY_CLIPBOARD_TIMEOUT_MS` from prefs on each `copy()` call; `-1L` = never
- Both `copy()` and `copyWithLabel()` schedule auto-clear
- Files: `core/util/SecureClipboard.kt`, `settings/viewmodel/SettingsViewModel.kt`, `settings/ui/SettingsScreen.kt`

---

### Tier 1 — Vault

#### 6. Recording Cascade Delete
Already implemented in Round 4. `RecorderRepository.deleteRecording()` checks `vaultFileId` and deletes the linked `VaultFile`. No changes needed.

#### 7. Thumbnail Regeneration
- Broken-image (`BrokenImage`) icon button in the Vault top bar
- Calls `VaultViewModel.regenerateThumbnails()` — queries all files, filters those with `thumbnailPath == null` or file missing, calls `FileEncryptionService.regenerateThumbnail(vf)`, updates the DB via new `VaultDao.updateThumbnailPath()`
- `regenerateThumbnail()` decrypts to temp file, decodes bitmap, calls existing `generateThumbnailFromBitmap()`, deletes temp
- Currently handles PHOTO type; VIDEO regeneration is a future enhancement
- Files: `vault/ui/VaultScreen.kt`, `vault/viewmodel/VaultViewModel.kt`, `vault/data/VaultRepository.kt`, `vault/data/VaultDao.kt`, `vault/service/FileEncryptionService.kt`

#### 8. OCR on Photos (Extract Text)
- "Extract Text (OCR)" `OutlinedButton` in `PhotoEditorScreen` below the background removal button
- Uses ML Kit `TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)` on `workingBitmap`
- Result shown in an `AlertDialog` with a **Copy** button that writes to system clipboard
- Loading state via `isExtractingText: Boolean` in `PhotoEditorState`
- New dep: `com.google.mlkit:text-recognition:16.0.1`
- Files: `vault/viewmodel/PhotoEditorViewModel.kt`, `vault/ui/PhotoEditorScreen.kt`, `app/build.gradle.kts`, `gradle/libs.versions.toml`

---

### Tier 1 — Monitoring

#### 9. Remote Lock Agent
- `RemoteCommandHandler` handles new command `lock_device`
- Calls `DevicePolicyManager.lockNow()` if there are active device admins; otherwise logs a fallback warning
- Existing Lock button in `DashboardScreen` remote control panel triggers this
- Files: `monitoring/service/RemoteCommandHandler.kt`, `monitoring/ui/DashboardScreen.kt`

#### 10. Scheduled Silent Windows
- `AgentService` reads schedule prefs directly via `@EncryptedPrefs SharedPreferences` (ViewModel cannot be injected into a Service)
- New `isWithinSchedule()` method checks `schedule_enabled`, `schedule_start_hour`, `schedule_end_hour`, `schedule_days`
- Collection loop skips the cycle (with `delay + continue`) when outside the window
- Files: `monitoring/service/AgentService.kt`

---

### Tier 1 — Browser

#### 11. Custom Browser User Agent
- UA overridden to `Mozilla/5.0 (Linux; Android 14; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36`
- Applied via `GeckoRuntimeSettings.Builder().userAgentOverride(...)` in `BrowserScreen`
- Files: `browser/ui/BrowserScreen.kt`

---

### Tier 2 — Security

#### 12. Duress/Decoy PIN with Wipe
- New toggle in **Settings → Decoy PIN → "Wipe vault on decoy unlock"**
- When enabled, `CalculatorViewModel` calls `WipeManager.wipeAll()` immediately upon decoy PIN validation before returning `SecretCodeResult.DecoyUnlocked`
- Key: `SettingsViewModel.KEY_DECOY_WIPE_ENABLED = "decoy_wipe_enabled"`
- Files: `calculator/viewmodel/CalculatorViewModel.kt`, `settings/viewmodel/SettingsViewModel.kt`, `settings/ui/SettingsScreen.kt`

---

### Tier 2 — Browser / Vault

#### 13 & 14. Browser Save Page to Vault
- Save-page (`SaveAlt`) icon button in the browser bottom bar
- `BrowserViewModel.savePageToVault()` writes a text file with the page title, URL, and timestamp, encrypts it via `FileEncryptionService.importFile()`, and saves to the vault
- Not a full MHTML capture — saves a reference stub (GeckoView download delegate API was too version-specific to use safely)
- Files: `browser/ui/BrowserScreen.kt`, `browser/viewmodel/BrowserViewModel.kt`

---

### Tier 2 — Monitoring

#### 15. Remote Wipe
- `RemoteCommandHandler` handles new command `wipe_vault`
- Calls `WipeManager.wipeAll()` on the agent side
- Wipe button (with confirmation dialog) in `DashboardScreen` remote control panel
- Files: `monitoring/service/RemoteCommandHandler.kt`, `monitoring/ui/DashboardScreen.kt`

#### 16. Timeline View
- New `TimelineScreen` + `TimelineViewModel`
- Loads last 500 events from `MonitoringRepository.getRecent(500)`, groups by hour (`MMM d, HH:00`)
- Scrollable `LazyColumn` with hour-header rows + per-event `Card`s (kind, timestamp, payload preview)
- Refresh button reloads data
- Accessible from **Dashboard → Timeline** button (next to Map)
- Files: `monitoring/ui/TimelineScreen.kt` (new), `monitoring/ui/TimelineViewModel.kt` (new), `monitoring/ui/DashboardScreen.kt`, `stealth/navigation/AppNavigation.kt`

---

## Architecture Notes

- `BiometricHelper` migrated from `FragmentActivity` to `ComponentActivity` — required because `MainActivity` extends `ComponentActivity`. Uses `BiometricPrompt(ComponentActivity, callback)` constructor added in `androidx.biometric:biometric:1.2.0-alpha05`.
- `SettingsViewModel` now injects `@ApplicationContext Context` (needed for `PackageManager` in `switchAppIcon()`).
- Schedule check in `AgentService` reads prefs directly rather than going through a ViewModel — Services cannot use `hiltViewModel()`; prefs keys are duplicated from `ScheduleConfigViewModel`.
- App icon aliases use `android:enabled="false"` initially. `PackageManager.DONT_KILL_APP` flag ensures the switch is gradual (no force-stop).

---

## Deferred to Round 9

- Vault folder/album organization
- Video trimming
- mDNS/NSD auto-discovery
- Browser saved credentials
- Home screen widget
- Tor proxy (Orbot)
- Panic wipe trigger (3× shake + vol-down → `WipeManager.wipeAll()`)
- Geofence push alerts (geofence collector fires events already; ntfy/WebSocket notification not wired)
