**Mobile Development 2025/26 Portfolio**
# Retrospective

Student ID: `C24053582`

Core features from symptom to AI to PDFs were shipped plus stretch features not originally scoped including Health Connect, biometric lock, doctor visits, medication reminders, encrypted photos and trend overlays. 

Two debugging problems stand out including the crashing of the PDF generator with SIGSEGV in libhwui after page breaks. I modified drawLogEntry with Log.i and traced it to a stale Canvas reference across closePage(), then resolved currentCanvas again before each direct draw. Migrating Health Connect to 1.1.0 broke seeding because Metadata's constructor went internal, so I used manualEntry instead. However I had to revert when compileSdk forced a downgrade. This taught me to lock the SDK before chasing the latest libraries.

Starting again I would assign MoSCoW priorities to assist with thorough testing of core features, treat AI analysis as background work from day one rather than chasing a 60 second foreground timeout, and configure the lint inspection profile earlier so false positive warnings didn't accumulate.

With more time I would add a Welsh strings-cy.xml, an adaptive launcher icon to replace the placeholder, instrumented Compose tests for photo attachments which have only unit coverage, and a retry and cache layer so failed environmental fetches could be backfilled when network returns.








