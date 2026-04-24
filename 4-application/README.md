# Aura Health

> Mobile Development 2025/26 Portfolio — Student ID `C24053582`

Aura Health is a preventative-healthcare journaling app for Android. It pairs a rich symptom logger with real-time environmental data (weather, air quality), wearable metrics (Health Connect), medication reminders, and on-device AI analysis powered by Google Gemini. Users can generate PDF health reports, link symptoms to doctor-diagnosed conditions, and visualise symptom trends against environmental triggers.

The problem it solves: people with environmentally sensitive conditions (migraines, asthma, joint pain, fatigue) rarely connect their symptoms with triggers like barometric pressure swings, humidity spikes, or air-quality dips. Aura Health logs the symptom *and* the environment in a single tap, then lets an LLM spot the correlations a human would miss.

---

## Table of Contents

1. [Features](#features)
2. [Tech Stack](#tech-stack)
3. [Architecture](#architecture)
4. [Setup & Running the App](#setup--running-the-app)
5. [Configuration Reference](#configuration-reference)
6. [Testing](#testing)
7. [Project Structure](#project-structure)
8. [Permissions & Privacy](#permissions--privacy)
9. [Troubleshooting](#troubleshooting)

---

## Features

### Symptom logging
- Catalog-driven symptom picker + free-text custom entries
- Severity 1–10 scale, optional notes, medication-at-time-of-entry
- Automatic environmental capture on save: coarse location, reverse-geocoded place name, weather (temperature, humidity, pressure, WMO code), and air quality index — pulled from the [Open-Meteo](https://open-meteo.com/) public API
- Optional photo attachments (camera or gallery). EXIF metadata is stripped on re-encode, including GPS tags, before the image hits disk
- Offline-first: logs save locally immediately, environmental enrichment happens asynchronously

### AI analysis (Gemini)
- On-demand or weekly scheduled analysis via WorkManager
- Structured prompt includes symptoms, trends, environmental context, Health Connect metrics, and doctor-visit context
- PII stripping before the request leaves the device: ages are sent as bands (not DOB), full names removed, coordinates rounded
- Analysis history stored in an encrypted Room database with deep-linkable detail pages
- Notification on completion with tap-to-open deep link

### Doctor visits
- Record clinic visits: date, notes, attending doctor
- Mark specific symptom logs as "reviewed and cleared" so the AI stops flagging them
- Attach diagnoses (e.g. "Chronic migraine") to existing logs; the AI receives these as *already-explained* context rather than new concerns
- Detail page shows the full audit trail per visit

### PDF health report
- Multi-page PDF generated on-device using `android.graphics.pdf.PdfDocument` — no third-party PDF library
- Aggregates symptom logs, severity trends, environmental context, AI analysis history, and doctor-visit summary
- Date-range selectable, archived in-app, sharable via the system share sheet (FileProvider)

### Trends visualisation
- Per-symptom line chart with environmental overlays (temperature, humidity, pressure, AQI)
- Tap a data point for the underlying log entry
- Historical weather pulled from Open-Meteo's archive endpoint

### Medication reminders
- Schedule doses by frequency (daily / specific weekdays / one-off) and time of day
- AlarmManager-backed, with exact-alarm permission handling and graceful fallback to inexact alarms on OEMs that revoke it
- Notification with "Taken" / "Snooze" action buttons; dose history retained for 30 days
- Reminders re-armed on boot and on every cold start (survives force-stop)

### Health Connect integration
- Read-only integration with the system Health Connect store
- Metrics consumed: steps, heart rate, resting HR, sleep, SpO2, respiratory rate, height, weight, body fat, active calories, exercise sessions
- Per-metric opt-in toggles so users grant exactly what they want to share with the AI
- "Seed sample data" button writes 10–14 days of plausible demo records so charts render on fresh installs (the only reason WRITE permissions are declared)

### Home dashboard
- Greeting + severity ring summarising the last 7 days
- Health-metric cards (when Health Connect is connected)
- Trend preview with weather overlay
- Medication reminder preview (next 3 doses)
- Quick-action CTAs: log symptom, generate report

### Settings
- Light / dark / system theme, applied via `AppCompatDelegate.setDefaultNightMode` so the launcher splash respects the user's choice
- Biometric app lock (class-3 biometrics with device-credential fallback); locks on background, unlocks on return
- User profile (name, DOB) — DOB is used only to compute an age band for AI prompts

---

## Tech Stack

| Area | Choice |
| --- | --- |
| Language | Kotlin 2.0.21, JVM target 17 |
| UI | Jetpack Compose (BOM `2024.12.01`), Material 3, adaptive-navigation-suite |
| Navigation | `navigation-compose` 2.8.5 with type-safe `@Serializable` routes |
| Persistence | Room 2.7.1 + SQLCipher 4.6.1 (full-database encryption) |
| Preferences | AndroidX DataStore (preferences) 1.1.1 |
| Background work | WorkManager 2.9.1 (AI analysis), AlarmManager (medication reminders) |
| Networking | OkHttp 4.12.0 + kotlinx-serialization-json 1.7.3 |
| Location | Play Services Fused Location Provider 21.3.0 + `android.location.Geocoder` |
| Wearables | Health Connect client `1.1.0-alpha11` |
| AI | Google Gemini REST API (`gemini-3.1-flash-lite-preview` by default) |
| Auth | AndroidX Biometric 1.1.0 |
| Splash | `androidx.core:core-splashscreen` 1.0.1 |
| Typography | Downloadable Google Fonts (Plus Jakarta Sans, JetBrains Mono) |
| Testing | JUnit 4, kotlinx-coroutines-test, MockWebServer, Compose UI Test, Room testing |

**Target configuration** — `minSdk 31`, `targetSdk 35`, `compileSdk 35`.

---

## Architecture

### High level

```
  ┌─────────────────────────────┐
  │   Compose UI (ui/**)        │  ViewModels per screen
  └───────────────┬─────────────┘
                  │ StateFlow
  ┌───────────────┴─────────────┐
  │   AppContainer (DI)         │  Constructed in AuraApplication.onCreate()
  │   - Repositories            │  Lazy-initialised, single graph
  │   - Services                │
  └───────────────┬─────────────┘
                  │
  ┌───────────────┴─────────────┐
  │   Data layer (data/**)      │
  │   - Room DAOs (SQLCipher)   │
  │   - Gemini HTTP client      │
  │   - Health Connect client   │
  │   - Open-Meteo client       │
  │   - Fused location provider │
  │   - Android Keystore        │
  └─────────────────────────────┘
```

### Dependency injection

There is no Hilt / Dagger. The app uses a hand-rolled `AppContainer` interface with a `DefaultAppContainer` implementation that lazy-initialises every repository and service. ViewModels obtain the container via `Application.container` through a `CreationExtras`-based factory pattern, which keeps ViewModels testable without a framework.

### Database

- Single Room database: `AuraDatabase` (v10, SQLCipher-encrypted)
- The encryption passphrase is generated on first launch with `SecureRandom` and stored in the Android Keystore (AES-256-GCM)
- Ten forward migrations — no destructive schema drops
- Main tables: `symptom_logs`, `symptom_photos`, `analysis_runs`, `analysis_run_logs`, `report_archives`, `medication_reminders`, `medication_dose_events`, `doctor_visits`, `doctor_visit_covered_logs`, `doctor_diagnoses`, `doctor_diagnosis_logs`

### Background work

- **AnalysisWorker** — a `CoroutineWorker` that runs the full prompt-build → Gemini-call → persist-result pipeline with a 1-minute timeout and exponential backoff. Survives process death via WorkManager.
- **AnalysisScheduler** — periodic job (weekly, Monday 9 AM) plus one-off triggers from the UI.
- **MedicationReminderScheduler** — AlarmManager one-shots, re-armed on boot and cold start.

### Privacy pipeline

The path from a symptom log to the Gemini request is the most security-sensitive surface. It runs through `AnalysisService.buildRequest(...)` which:
1. Drops any log marked "cleared" by a doctor visit
2. Annotates diagnosed logs with the diagnosis label (with the patient's name stripped from the label)
3. Converts DOB to an age band
4. Rounds coordinates (~100 m precision)
5. Emits an `AnalysisRequest` — the contract surface verified by `AnalysisServiceDoctorContextTest` and `AnalysisServicePayloadTest`

---

## Setup & Running the App

### Prerequisites

- **Android Studio Ladybug (or newer)** — Hedgehog will not recognise the Kotlin 2.0.21 toolchain
- **JDK 17** (Android Studio ships one; otherwise install via `brew install openjdk@17` on macOS)
- **Android SDK Platform 35** and **Build Tools 35.0.0** installed via the SDK Manager
- **A Gemini API key** — get one free at [aistudio.google.com/app/apikey](https://aistudio.google.com/app/apikey)
- A physical device or emulator running **Android 12 (API 31) or newer**
- *(Optional for Health Connect features)* Google Play Services + the Health Connect app on the device (pre-installed on Android 14+, installable from Play on 12/13)

### Step 1 — Clone and open

```bash
git clone <repo-url>
cd "<repo>/4-application"
open -a "Android Studio" .
```

Let Android Studio finish its first-sync Gradle download (first run will pull ~1 GB of dependencies).

### Step 2 — Create `local.properties`

Android Studio populates `sdk.dir` automatically on first sync. You then need to add the Gemini key to the same file:

```properties
# /4-application/local.properties
sdk.dir=/Users/<you>/Library/Android/sdk   # auto-populated by Android Studio
GEMINI_API_KEY=your_gemini_api_key_here
```

> **Note**: `local.properties` is in `.gitignore` — the key never hits source control. The build tolerates a missing key; the AI screen will surface a `"Gemini key not configured"` error at runtime rather than fail the build. Every other feature of the app works without a key.

### Step 3 — Build and install

From Android Studio: pick a device or emulator and press **Run**.

From the CLI:

```bash
./gradlew assembleDebug              # build the debug APK
./gradlew installDebug               # install on the connected device
./gradlew :app:connectedDebugAndroidTest   # run instrumentation tests
./gradlew :app:testDebugUnitTest     # run unit tests
```

### Step 4 — First launch

On cold-start the app will:
1. Show a short onboarding flow (name + DOB)
2. Offer to connect Health Connect (optional — skip if you don't have it)
3. Seed ~20 demo symptom logs so the UI isn't empty
4. Land on the Home dashboard

Open **Settings → Health data** and tap **Seed sample data** to populate 10–14 days of demo wearable metrics for the charts to render. (If Health Connect isn't installed this button is hidden.)

### Step 5 — Generate an AI analysis

1. Go to the **Analysis** tab
2. (First time only) grant the notification permission when prompted
3. Tap **Run analysis** — it kicks off a `WorkManager` job that typically takes 10–30 seconds
4. When finished you'll get a notification that deep-links to the detail screen

---

## Configuration Reference

### `local.properties` keys

| Key | Required | Description |
| --- | --- | --- |
| `sdk.dir` | Yes | Android SDK install path. Android Studio writes this automatically. |
| `GEMINI_API_KEY` | No (but AI features disabled without it) | Google Gemini API key. Read at build time and exposed as `BuildConfig.GEMINI_API_KEY`. |

### Gemini model selection

The model name lives in [`app/build.gradle:36`](app/build.gradle) as a `buildConfigField`:

```groovy
buildConfigField "String", "GEMINI_MODEL", "\"gemini-3.1-flash-lite-preview\""
```

Swap in any model the API supports (e.g. `gemini-2.5-flash`, `gemini-2.5-pro`) and rebuild.

### Database migrations

Schema changes require a new `Migration(n, n+1)` object added to `AuraDatabase.MIGRATIONS` and the version bumped. Destructive migrations (`fallbackToDestructiveMigration`) are intentionally *not* configured — a failed migration is a build-time error, not a silent data wipe.

---

## Testing

The project has two test suites. Both are runnable from the IDE or CLI.

### Unit tests — `app/src/test`

Fast, no emulator needed. Covers:

| Test class | What it pins |
| --- | --- |
| `AnalysisServicePayloadTest` | Gemini request never contains DOB, full name, or raw coordinates |
| `AnalysisServiceDoctorContextTest` | Cleared logs are excluded; diagnosed logs carry their label; PII stripped from diagnosis annotations |
| `AnalysisGuidanceTest` | Structured guidance (recommendations + confidence) parses cleanly |
| `AnalysisResultStoreTest` | Latest-result DataStore round-trips |
| `OpenMeteoEnvironmentalServiceTest` | Weather + AQI response parsing |
| `WeatherCodeDescriptionTest` | WMO codes map to human-readable labels |
| `ReverseGeocoderFormatTest` | Place-name formatting across locales |
| `CoordinateRoundingTest` | Coarse-coordinate privacy (~100 m) |
| `HistoryViewModelTest`, `HistoryFilterTest` | Filter + sort logic on the symptoms tab |
| `LogSymptomEnvironmentalFetchTest`, `LogSymptomEditModeTest` | Log editor state machine |
| `HomeInsightsTest` | Home dashboard severity aggregation |
| `PlaintextDatabaseMigratorTest` | Legacy plaintext-to-SQLCipher one-shot migration |
| `FilesystemQuarantineTest` | Corrupted-database recovery path |

Run with:

```bash
./gradlew :app:testDebugUnitTest
```

### Instrumentation tests — `app/src/androidTest`

Require a running device/emulator. Covers:

| Test class | What it pins |
| --- | --- |
| `HistoryScreenTest` | List rendering, filtering, navigation to detail, delete flow |
| `LogSymptomScreenTest` | Form validation, photo picker, location opt-in |
| `LogDetailScreenTest` | Read-only rendering including new doctor-visit annotation badges |
| `TrendVisualisationScreenTest` | Chart renders, overlays toggle, tooltips appear |
| `HomeScreenInsightsTest` | Dashboard cards, severity ring, CTAs |

Run with:

```bash
./gradlew :app:connectedDebugAndroidTest
```

---

## Project Structure

```
4-application/
├── app/
│   ├── build.gradle                 # Module config, BuildConfig fields, dependencies
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml  # Permissions, receivers, activity, deep links
│   │   │   ├── java/com/example/mob_dev_portfolio/
│   │   │   │   ├── AuraApplication.kt       # Application class, DI bootstrap
│   │   │   │   ├── MainActivity.kt          # Single FragmentActivity entry point
│   │   │   │   ├── AppContainer.kt          # DI interface + default impl
│   │   │   │   │
│   │   │   │   ├── data/                    # All persistence + I/O
│   │   │   │   │   ├── AuraDatabase.kt      # Room DB + migrations
│   │   │   │   │   ├── SymptomLogRepository.kt
│   │   │   │   │   ├── ai/                  # Gemini client, analysis service, history store
│   │   │   │   │   ├── doctor/              # Visits, diagnoses, snapshot builder
│   │   │   │   │   ├── environment/         # Open-Meteo clients
│   │   │   │   │   ├── health/              # Health Connect service, snapshots, seeder
│   │   │   │   │   ├── location/            # Fused provider + geocoder
│   │   │   │   │   ├── medication/          # Reminders + dose events
│   │   │   │   │   ├── photo/               # EXIF strip + encrypted disk store
│   │   │   │   │   ├── preferences/         # Theme, profile, per-metric toggles
│   │   │   │   │   ├── report/              # PDF generator + archive
│   │   │   │   │   └── security/            # Keystore-backed passphrase provider
│   │   │   │   │
│   │   │   │   ├── reminders/               # AlarmManager receivers + scheduler
│   │   │   │   ├── security/                # AppLockController (biometric gate)
│   │   │   │   ├── work/                    # WorkManager workers + schedulers
│   │   │   │   │
│   │   │   │   └── ui/                      # Every screen, one subpackage per feature
│   │   │   │       ├── AuraApp.kt           # Nav graph, root scaffold
│   │   │   │       ├── analysis/            # Run, history, detail screens
│   │   │   │       ├── detail/              # Symptom log detail
│   │   │   │       ├── doctor/              # Doctor visit list, editor, detail
│   │   │   │       ├── health/              # Health Connect settings
│   │   │   │       ├── history/             # Symptom list + filters
│   │   │   │       ├── home/                # Dashboard
│   │   │   │       ├── log/                 # New/edit symptom log
│   │   │   │       ├── medication/          # Reminder list + editor
│   │   │   │       ├── onboarding/          # First-run flow
│   │   │   │       ├── report/              # PDF generate + history
│   │   │   │       ├── settings/            # Theme, lock, profile
│   │   │   │       ├── theme/               # AuraTheme, colours, typography
│   │   │   │       └── trends/              # Trend chart with weather overlay
│   │   │   └── res/                         # Layouts, drawables, themes, string resources
│   │   │
│   │   ├── test/                            # Unit tests
│   │   └── androidTest/                     # Instrumentation tests
│   │
│   └── proguard-rules.pro
│
├── build.gradle                             # Root build (plugin versions)
├── settings.gradle
├── gradle.properties
├── local.properties                         # Gitignored — SDK + Gemini key
└── README.md                                # This file
```

---

## Permissions & Privacy

### Runtime permissions the user may see

| Permission | When it's requested | Behaviour if denied |
| --- | --- | --- |
| `CAMERA` | Tapping "Take Photo" on a log | Gallery picker still works |
| `POST_NOTIFICATIONS` | First visit to the Analysis screen (API 33+) | AI still runs; no completion notification |
| `ACCESS_COARSE_LOCATION` | Tapping "Add location" on a log | Log saves without environmental context |
| `USE_EXACT_ALARM` / `SCHEDULE_EXACT_ALARM` | Creating the first medication reminder | Falls back to inexact alarms |
| Health Connect per-metric grants | Health Data Settings → master toggle | Other metrics unaffected |
| `USE_BIOMETRIC` | Enabling app lock in Settings | Feature simply stays off |

### Data handled on-device only

- Raw coordinates (the rounded value is what reaches Gemini)
- Full name, DOB, photos, medication names
- Every row of every table (entire DB is SQLCipher-encrypted)

### Data sent to external services

- **Open-Meteo** (weather, AQI) — rounded coordinates only, no user identifier
- **Google Gemini** (AI analysis) — age band, symptoms, trends, environmental snapshot, Health Connect summary, doctor-visit context. Name, DOB, exact coordinates, photos, and cleared logs are never sent.

Tests `AnalysisServicePayloadTest` and `AnalysisServiceDoctorContextTest` lock this contract in place so a future change can't quietly leak PII.

---

## Troubleshooting

**"Gemini key not configured" error on the Analysis screen**
Add `GEMINI_API_KEY=...` to `local.properties` and rebuild. Gradle re-reads the file on every `assemble*` task, but Android Studio's instant-run sometimes keeps the old `BuildConfig` — do a full **Build → Rebuild Project** if the error persists after adding the key.

**Health Connect section missing from Settings**
Health Connect is not installed on the device. On Android 12/13 install it from the Play Store; on Android 14+ it's a system component (make sure it's enabled in Settings → Apps → Health Connect).

**Medication reminders don't fire on Xiaomi / Oppo / Huawei devices**
These OEMs kill background alarms aggressively. The app uses exact alarms + boot-completed re-arming, but you may also need to enable "autostart" for Aura Health in the device's battery settings. A `SecurityException` on exact-alarm scheduling is caught and logged; reminders still fire, just with coarser timing.

**"SQLCipher native library not found" crash on launch**
Clean + rebuild (`./gradlew clean assembleDebug`). The native library is bundled via the `net.zetetic:sqlcipher-android` artifact; a stale incremental build can leave the `.so` out of the APK.

**First cold launch is slow**
Expected on a clean install — the app schedules the weekly WorkManager job, creates notification channels, generates the SQLCipher passphrase, and seeds demo data. Subsequent launches are near-instant.

---

## License

This project is a college portfolio submission. Not licensed for redistribution.
