**Mobile Development 2025/26 Portfolio**
# API Description

Student ID: C24053582

**Data persistence.** Room stores relational symptom data with foreign keys to environmental metrics, photo attachments, AI runs and doctor-cleared logs, and cascade-delete keeps the graph consistent (FR-RP-02, NFR-SE-05). Room emits Flows that Compose collects through collectAsStateWithLifecycle so lists update on save without refresh. SQLCipher's SupportOpenHelperFactory encrypts the database at rest with a Keystore passphrase (NFR-SE-01). DataStore Preferences handles scalars like theme mode. A Proto store would have suited the demographic profile, however the field count didn't justify it.

**Location capture.** FusedLocationProviderClient.getCurrentLocation fetches a fresh location upon save, avoiding any potential stale cache from getLastLocation and the background drain of requestLocationUpdates (NFR-RE-04). If the coroutine is cancelled the location gets cancelled too using CancellationTokenSource. 

**Background work.** withTimeout(5_000) cancels the OkHttp environmental call at the deadline (NFR-PE-01). WorkManager owns AI analysis because the worker survives process death, whereas a ViewModel-scoped coroutine would die silently (NFR-PE-02). Personal information is stripped using AnalysisSanitizer before being sent to the gemini API for analysis. Results are then written to Room. AlarmManager's setExactAndAllowWhileIdle (FR-MR-01) is used to schedule reminders as PeriodicWorkRequest only has a 15 minute minimum interval time which isn't accurate enough for medication dosage times.

**System integrations.** BiometricPrompt and device credential fallback gates app entry on resume (NFR-SE-04). The Health Connect SDK reads only opted-in metrics, and aggregates them client side (FR-HK-02). PdfDocument renders the report with a paginator (FR-RP-01). A third party PDF library would balloon the APK and add licensing risk.

**UI and navigation.** Navigation Compose with an @Serializable TopLevelRoute makes the back stack observable. NavigationSuiteScaffold adapts between bottom bar and rail by window size. The graph makes the 3 tap log path (NFR-UA-03) testable at design time. NotificationCompat with FLAG_IMMUTABLE PendingIntents (required since API 31) deep links to results without intent hijacking (FR-NO-02).
