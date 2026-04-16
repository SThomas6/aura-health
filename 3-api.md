**Mobile Development 2025/26 Portfolio**
# API Description

Student ID: `C24053582`

Data persistence: Room stores relational symptom data where each entry links to environmental metrics, AI analyses, and report aggregations (FR-RP-02). Unlike DataStore, which forces full dataset deserialization per update, Room emits flows to Compose’s reactive UI, keeping lists updated on data change. DataStore replaces the deprecated EncryptedSharedPreferences for user preferences, using coroutine native asynchronous I/O to prevent main thread blocking during preference writes on the Pixel 3a emulator. 

Location Capture: FusedLocationProviderClient’s getCurrentLocation retrieves a fresh location when symptoms are saved. This avoids both the stale cache of getLastLocation and continuous battery drain with requestLocationUpdates. This avoids background activity (NFR-RE-04), building trust with the user. Before saving, coordinates are rounded to maximise privacy (NFR-SE-02).

Background processing:  Two mechanisms handle timing needs including coroutines withTimeout(5000), handling the 5 second environmental fetches (NFR-PE-01) without adding overhead. WorkManager ensures that AI analysis (NFR-PE-02) finishes even if the process is killed, common when under memory pressure. However, ViewModel-scoped coroutines would die silently.  

Notifications: NotificationCompat with a declared NotificationChannel and FLAG_IMMUTABLE PendingIntent meets API 35’s mutability requirements. This prevents intent hijacking by deep linking to the analysis result (FR-NO-02) using a frozen payload. The notification gives clinically relevant information, telling users whether to seek medical attention. 

UI and navigation: Navigation 3 makes the back stack observable, so the UI reacts automatically instead of having to manually update it, reducing the complexity of the fragment lifecycle between symptom results and logger. NavigationSuiteScaffold adapts navigation between bottom bar and rail by window size. It also handles the edge-to-edge insets that API 35 requires. MaterialTheme with dynamicColorScheme handles light/dark themes from one definition. Navigation graph makes the 3 tap path to symptom logging (NFR-UA-03) testable at design time. 

PDF Generation: PdfDocument with canvas avoids licensing constraints and APK bloat while keeping offline report generation (NFR-RE-02). 

