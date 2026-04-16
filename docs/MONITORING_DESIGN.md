# Phone-Monitoring Module — Design Notes (in progress)

**Status:** Planning only. No code written. Branch: `claude/plan-app-monitoring-W0UKj`.
**Session:** 2026-04-16 — planning stopped before final plan was written (tool-load timeouts ate the turns). Pick up here next session.

---

## 1. Goal

Add a "phone monitoring" feature to StealthCalc so the user's **primary phone** (dashboard) can see live activity from their **secondary phone** (agent). Both phones are Android; both run a copy of StealthCalc. The user also runs a 24/7 **home server** that brokers traffic so the dashboard works whether the primary phone is at home or on carrier.

Same APK for both phones — a runtime Settings toggle picks the role (`disabled` / `dashboard` / `agent` / `both`). No build flavors.

---

## 2. User decisions already captured

| Question | Answer |
|---|---|
| Secondary phone setup | Stays at home 90% of time, WiFi-only (no SIM), user owns it |
| Primary phone setup | Travels; needs access from anywhere (home WiFi + carrier) |
| Dashboard shape | New module inside StealthCalc (reuse SQLCipher, auth, `FLAG_SECURE`, stealth home grid) |
| Freshness | Near-real-time |
| Privacy posture | Local-only, no cloud — user's data must not leave their control |
| Metric tier for MVP | **"Everything + more"** — max allowable without root |
| Server stack | **Kotlin + Ktor** + SQLite (same language as StealthCalc → shared `kotlinx.serialization` data classes) |
| Tunnel | **Tailscale** on all three devices. Server binds only to tailnet interface. Zero exposed public ports. |
| Fallback if server is down | Agent writes to local encrypted DB first; when both phones on home LAN, dashboard can mDNS-discover agent and pull directly |

---

## 3. Architecture at a glance

```
    [Secondary phone — StealthCalc "agent" role]
          |  (WireGuard tunnel via Tailscale, transparent to app)
          v
    [Home server — Kotlin + Ktor + SQLite, 24/7]
          ^
          |  (WireGuard tunnel via Tailscale)
    [Primary phone — StealthCalc "dashboard" role]
```

- Tailscale is **installed as a separate app** on each phone + the server. StealthCalc does not integrate with Tailscale's SDK — the OS presents the tailnet as just another network. Server reachable at a stable tailnet IP or MagicDNS name (e.g. `home.tailnet-xxxx.ts.net`).
- App-layer auth: per-device bearer token issued at pairing. Defense in depth, not a replacement for the tunnel.
- Transport: **HTTPS batch upload** for periodic events, **WebSocket** for live-stream mode when dashboard is actively watching.
- Durability: agent always writes events to local **SQLCipher** first, then drains to server. Retries with backoff. Never loses an event if server is down.

### Backgrounding strategy (Android 14+ is strict)
- **NotificationListenerService** → its own always-on bound service (not FGS — different lifecycle).
- **UsageStats + battery + WiFi polling** → **WorkManager** periodic job (15-min min interval is fine; UsageStats data itself is OS-maintained, we just drain & ship).
- **Live-stream mode** → when dashboard sends `start-live` command, agent starts a short-lived foreground service (`FOREGROUND_SERVICE_TYPE_SPECIAL_USE` with a declared subtype property, self-stops after N minutes idle) that streams over WebSocket. Re-armable from dashboard.
- **PackageManager broadcasts** (install/uninstall/update) → manifest-registered `BroadcastReceiver`, free.

This plays well with Doze / App Standby and avoids the `ForegroundServiceDidNotStartInTimeException` class of bugs we've already hit in `RecorderService`.

---

## 4. Metric scope for MVP ("Everything + more")

Collectable on stock Android without root:

| Metric | API | Permission | Notes |
|---|---|---|---|
| Foreground app + minutes-per-app + launch count | `UsageStatsManager.queryEvents` | `PACKAGE_USAGE_STATS` (special settings grant) | Core signal |
| Screen on/off, unlock count, time unlocked | `UsageEvents.Event.SCREEN_INTERACTIVE` / `KEYGUARD_HIDDEN` | Same as above | Free once UsageStats granted |
| Battery level, charging state, plug/unplug, temperature, voltage | `BatteryManager` + sticky `ACTION_BATTERY_CHANGED` | None | Zero friction |
| Network state, WiFi SSID, BSSID | `ConnectivityManager` + `WifiManager` | `ACCESS_FINE_LOCATION` (for SSID on API 29+) | SSID needs location grant |
| App installs / uninstalls / updates | `ACTION_PACKAGE_ADDED/REMOVED/REPLACED` broadcast | `QUERY_ALL_PACKAGES` for the full list snapshot | Manifest receiver |
| Incoming notifications (sender, title, text) | `NotificationListenerService` | Separate Settings toggle (not a runtime permission) | Most invasive; own lifecycle |
| Location (coarse, polled) | `FusedLocationProviderClient` | `ACCESS_COARSE_LOCATION` (or `FINE`) | Low-frequency (5–15 min) since phone is stationary |
| Photo/video added to device | `ContentObserver` on `MediaStore` | `READ_MEDIA_IMAGES/VIDEO` | Already declared |

### Deferred to later rounds (documented limits of stock Android)
- **Clipboard monitoring** — background reads blocked on API 29+. Needs an accessibility-service hack or being the foreground app.
- **SMS / call-log content** — Play Store restrictions; needs to be default SMS app. Possible on sideloaded build but out of scope.
- **Typed text / on-screen content** — would need an `AccessibilityService`. Viable on sideloaded build but flagged by Play Protect. Separate round with its own tradeoff discussion.
- **Other apps' internal state** — not possible without root.

---

## 5. Codebase reuse surface (from Explore agent, 2026-04-16)

Surveyed and confirmed — these are the exact integration points for the new module.

### Stealth home grid + navigation
- `app/src/main/java/com/stealthcalc/stealth/navigation/AppNavigation.kt:45-76` — `sealed class AppScreen`; add `data object Dashboard : AppScreen("dashboard")` and `data object AgentConfig : AppScreen("agent_config")`.
- `AppNavigation.kt:157-394` — `StealthNavGraph()`; add two new `composable(...)` entries.
- `AppNavigation.kt:40-48` — `StealthHomeScreen` parameter list; add `onNavigateToDashboard` / `onNavigateToAgentConfig`.
- `AppNavigation.kt:94-148` — tile grid; add two `ModuleCard` entries.

### Hilt DI
- `app/src/main/java/com/stealthcalc/core/di/AppModule.kt:19-38` — provides `@EncryptedPrefs SharedPreferences` (namespace `stealth_prefs`).
- `app/src/main/java/com/stealthcalc/core/di/DatabaseModule.kt:26-81` — `StealthDatabase` + DAO bindings.
- **Add:** `app/src/main/java/com/stealthcalc/monitoring/di/MonitoringModule.kt` with repository + network-client bindings.

### Room / SQLCipher
- `app/src/main/java/com/stealthcalc/core/data/StealthDatabase.kt:36-73` — `@Database`, version **6**. Bump to **7** when adding monitoring entities. `.fallbackToDestructiveMigration()` remains acceptable pre-release.
- `app/src/main/java/com/stealthcalc/core/encryption/KeyStoreManager.kt:39-46` — `getDatabasePassphrase()`; random 32-byte key, Keystore-wrapped, stored in `EncryptedSharedPreferences`. Reuse as-is.
- DAO pattern reference: `app/src/main/java/com/stealthcalc/recorder/data/RecordingDao.kt` (Flow queries + suspend mutations).
- **Add entities:** `AppUsageEvent`, `DeviceSnapshot`, `NotificationRecord`, `NetworkEvent`, `LocationPoint`, `InstallEvent`, `PendingUpload`, `PairingState`, plus DAOs.

### Settings
- `app/src/main/java/com/stealthcalc/settings/viewmodel/SettingsViewModel.kt:16-36` — `SettingsState` data class; extend with `monitoringRole`, `tailscaleServerUrl`, `pairingCode`, `monitoringEnabledMetrics: Set<String>`.
- `SettingsViewModel.kt:46-57` — key constants; add `KEY_MONITORING_ROLE`, `KEY_TAILSCALE_URL`, `KEY_PAIRING_CODE`, `KEY_MONITORING_METRICS`.
- `app/src/main/java/com/stealthcalc/settings/ui/SettingsScreen.kt:58-150+` — add a "Monitoring" section with `SectionHeader` + rows.

### Logging
- `app/src/main/java/com/stealthcalc/core/logging/AppLogger.kt:54-67` — `AppLogger.log(context, tag, message)`. New module tags its events `[agent]` / `[dashboard]` following the `[recorder]` / `[vault]` convention.

### Foreground service reference
- `app/src/main/java/com/stealthcalc/recorder/service/RecorderService.kt` — `@AndroidEntryPoint class RecorderService : LifecycleService()`; pattern for `@Inject` into a service.
- `app/src/main/AndroidManifest.xml:76-79` — existing `RecorderService` declaration. Add `<service android:name=".monitoring.service.AgentLiveService" android:exported="false" android:foregroundServiceType="specialUse">` plus the `<property>` child for `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE`.

### Networking deps (NOT on classpath yet — must add)
- `gradle/libs.versions.toml` + `app/build.gradle.kts` — no OkHttp / Ktor client / Retrofit / `kotlinx.serialization` currently.
- **Add:** `ktor-client-core`, `ktor-client-okhttp`, `ktor-client-content-negotiation`, `ktor-client-websockets`, `ktor-serialization-kotlinx-json`, `kotlinx-serialization-json`. One ecosystem across phone app + server.

### Build config
- `compileSdk = 35`, `minSdk = 26`, `targetSdk = 35` (`app/build.gradle.kts:11-16`).
- No product flavors. Role switch is a runtime Settings toggle.

### New permissions needed in manifest
- `PACKAGE_USAGE_STATS` (special; user grants via Settings → Usage access)
- `QUERY_ALL_PACKAGES`
- `ACCESS_FINE_LOCATION` (for WiFi SSID + location)
- `FOREGROUND_SERVICE_SPECIAL_USE`

`NotificationListenerService` binding is not a `<uses-permission>` — it's enabled via Settings → Notification access.

---

## 6. Server component (separate mini-project)

**Not** part of this Android repo. Suggested layout for next session:

```
stealthcalc-server/
├── build.gradle.kts          # Ktor + Exposed (or raw JDBC) + SQLite
├── src/main/kotlin/
│   ├── Main.kt               # engine(Netty) on tailnet interface only
│   ├── routes/
│   │   ├── Pairing.kt        # POST /pair  (OTP → token)
│   │   ├── Events.kt         # POST /events/batch, GET /events/{deviceId}
│   │   ├── State.kt          # GET /state/{deviceId}
│   │   └── Live.kt           # WS /live/{deviceId}
│   ├── db/Schema.kt          # devices, events, recent_state
│   └── auth/TokenAuth.kt     # bearer-token plugin
└── shared/ (optional gradle module shared with Android app for DTOs)
```

Shared DTOs via a `shared/` Kotlin Multiplatform module (or just copy-pasted data classes; KMP is nicer but adds build complexity).

---

## 7. Pairing flow

1. User opens dashboard → Settings → "Add device" → dashboard calls server `POST /pair/request` → server returns 6-digit OTP valid 10 minutes.
2. Dashboard shows OTP.
3. User opens agent phone → Agent Config → enters OTP + server URL (`https://home.tailnet-xxxx.ts.net`) → agent calls `POST /pair/redeem` with OTP → server returns long-lived bearer token + device ID.
4. Agent stores token in `EncryptedSharedPreferences`. Pairing done.
5. Dashboard similarly gets its own token.

---

## 8. Open questions for next session

- **Tailscale subnet routing vs full mesh?** — decision: full mesh (all three devices on tailnet). Simpler, no subnet-router config. Confirm with user.
- **Shared DTO module** — KMP `shared/` vs copy-paste. KMP is the "right" answer but adds Gradle config; defer if we want to ship fast.
- **Accessibility-service round 2** — user said "Everything + more"; need to confirm whether they want the accessibility path (typed text, on-screen content) as a follow-up round or not at all.
- **Dashboard UI wireframes** — live status card + timeline + app-usage chart + notification list + optional map. Draft layouts before implementation.
- **Retention policy** — how long does the server keep events? Default proposal: 30 days rolling, configurable via a server env var.

---

## 9. Non-goals

- Remote control of secondary phone (no "wipe", no "lock", no "take screenshot on demand"). Trust is one-directional: agent reports, dashboard observes. Keeps the attack surface minimal and the code simple.
- Play Store compliance. This is a sideloaded personal app; some permissions used here (`QUERY_ALL_PACKAGES`, `NotificationListenerService` for monitoring) would trigger Play review. Not a concern for this project.
- Multi-tenant / multi-user. One user, known set of devices.

---

## 10. Next-session starter prompt

> "Read context, here's my feedback on `docs/MONITORING_DESIGN.md`: &lt;changes&gt;. Proceed to implementation on branch `claude/plan-app-monitoring-W0UKj`. Start with the Android agent module (manifest permissions, Hilt wiring, Room entities v7) before touching the server."

Everything needed to start coding is above. Re-read §5 (reuse surface) before writing any new file so we don't reinvent patterns.
