package com.example.mob_dev_portfolio.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.mob_dev_portfolio.R
import kotlinx.serialization.Serializable

/**
 * Closed set of top-level destinations that appear in the bottom-bar /
 * nav-rail. Modelled as a sealed interface so the navigation layer's
 * `when` blocks are exhaustive — adding a new top-level tab forces the
 * `selected` matcher in [com.example.mob_dev_portfolio.ui.AuraApp] to be
 * updated rather than silently falling through.
 */
sealed interface TopLevelRoute {
    @Serializable
    data object Home : TopLevelRoute
    @Serializable
    data object History : TopLevelRoute
    @Serializable
    data object Analysis : TopLevelRoute

    /**
     * The Doctor Visits tab. Lists every logged visit with a tap-through
     * to the detail (cleared logs + diagnoses) and a FAB to add a new
     * one. Promoted to a top-level destination because the feature
     * feeds the AI pipeline's filtering/annotation behaviour and users
     * should be able to manage it without drilling through Settings.
     */
    @Serializable
    data object Doctor : TopLevelRoute
}

/**
 * Route to the read-only symptom-log detail view. Carries the Room rowId
 * so navigation deep-links and notification taps share the same wiring.
 */
@Serializable
data class DetailRoute(val id: Long)

/**
 * Route to the symptom-log edit form. A separate route from
 * [LogSymptomRoute] (the create form) so the back-stack lands the user on
 * the detail view rather than the list when they finish editing.
 */
@Serializable
data class EditLogRoute(val id: Long)

/**
 * Route for the "add a new symptom" form. Previously a top-level
 * destination in the bottom nav; promoted out of the tab row in favour
 * of a FAB on the Symptoms (nee History) screen so the nav surface
 * stays focused on destinations the user browses rather than actions
 * they perform. Adding happens from Symptoms; the form itself is a
 * transient, one-off destination reached via the FAB and from Home's
 * hero "Log" button.
 */
@Serializable
data object LogSymptomRoute

/**
 * Route for the AI Analysis *run-the-pipeline* screen — the form with
 * profile details, context, and the "Run AI analysis" button. Kept
 * separate from [TopLevelRoute.Analysis] (which hosts the history list)
 * so the Analysis tab can default to "browse past runs" while "start a
 * new one" lives at one level deeper.
 */
@Serializable
data object AnalysisRunnerRoute

/**
 * Route to the detailed view of a single persisted AI analysis run. The
 * [runId] maps to the Room rowId assigned at insert time. Passing the
 * id through the route (rather than an event bus) means the system-bar
 * back-stack and notification deep-links all share the same wiring.
 */
@Serializable
data class AnalysisDetailRoute(val runId: Long)

/**
 * Route for the "Generate a PDF health report" feature. Reached from
 * a Home-screen quick-action card; lives outside the top-level nav so
 * the existing four tabs stay untouched.
 */
@Serializable
data object HealthReportRoute

/**
 * Route for the Trend Visualisation dashboard — a single-page chart
 * surface plotting symptom-severity time-series with an optional
 * environmental overlay (humidity / temperature / pressure / AQI).
 * Reached from a Home quick-action card; one screen-deep from the
 * launch destination keeps the "<= 2 taps from home" acceptance
 * criterion satisfied.
 */
@Serializable
data object TrendVisualisationRoute

/**
 * Route for the list of previously generated PDF reports. Reached from
 * the Health Report screen's action bar; each row opens/shares/deletes
 * the archive backed by a row in `report_archives` + a compressed
 * file under `cacheDir/reports/`.
 */
@Serializable
data object HealthReportHistoryRoute

/**
 * Route for the Health Connect integration settings — per-metric
 * toggles, "Install Health Connect" CTA, "Disconnect" action.
 * Reached from Home (Settings card) and from the
 * Health-Connect permissions-rationale system intent.
 */
@Serializable
data object HealthDataSettingsRoute

/**
 * Route for the demographic profile editor (date of birth + biological
 * sex). Reached from Settings. Name editing still lives on the Analysis
 * screen for now — this screen covers the two fields the Gemini prompt
 * reads outside of symptom logs.
 */
@Serializable
data object DemographicProfileRoute

/**
 * Route for the Settings landing page — a single entry point that
 * consolidates "Appearance", "Demographic profile" and "Health data
 * integration" so Home can focus on the daily-check-in flow. Added
 * after user feedback that those cards felt out of place cluttering
 * the Home screen.
 */
@Serializable
data object SettingsRoute

/**
 * Route for the user-declared health conditions screen. Lists conditions
 * (e.g. "Type 2 Diabetes"), lets the user add/edit/delete, and feeds the
 * AI's already-explained context bundle. Reachable from Settings and
 * from the onboarding flow.
 */
@Serializable
data object HealthConditionsRoute

/**
 * Route for the fullscreen Health Connect metric detail screen. The
 * [metricStorageKey] is the stable
 * [com.example.mob_dev_portfolio.data.health.HealthConnectMetric.storageKey]
 * — not the enum name — so a later rename doesn't invalidate back-stack
 * state or deep links.
 */
@Serializable
data class HealthMetricDetailRoute(val metricStorageKey: String)

/**
 * Route for the medication-reminders list (FR-MR-02) — every active
 * reminder ordered by next-fire time, with a 30-day dose-history feed
 * underneath (FR-MR-06). Reached from a Home quick-action card.
 */
@Serializable
data object MedicationListRoute

/**
 * Route for the medication-reminder create/edit form (FR-MR-01, FR-MR-04).
 * A null [id] means "create"; any non-null id loads the existing
 * reminder for editing.
 */
@Serializable
data class MedicationEditorRoute(val id: Long? = null)

/**
 * Route for the read-only "visit detail" view — shows the doctor's
 * summary, any logs the user has marked as reviewed, and any
 * diagnoses (each with its linked symptoms). Reached from the
 * Doctor-Visits list.
 */
@Serializable
data class DoctorVisitDetailRoute(val id: Long)

/**
 * Route for the doctor-visit create/edit form. A null [id] means
 * "create"; any non-null id loads the existing visit (summary,
 * covered-log selection, diagnoses + linked logs) for editing.
 */
@Serializable
data class DoctorVisitEditorRoute(val id: Long? = null)

/**
 * One bottom-bar / nav-rail destination. The label is held as a
 * [StringRes] (not a literal) so the user-visible text is centralised
 * in `strings.xml` — easier to translate, easier for the marker to
 * audit i18n compliance. The composable resolves the resource via
 * `stringResource(dest.labelRes)` inside the navigation suite.
 */
data class TopLevelDestination(
    val route: TopLevelRoute,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val routeQualifiedName: String,
    /**
     * Stable, locale-independent identifier — used for `testTag` so
     * Compose UI tests don't break when the label translates.
     */
    val testTagId: String,
)

val TopLevelDestinations: List<TopLevelDestination> = listOf(
    TopLevelDestination(TopLevelRoute.Home, R.string.nav_home, Icons.Filled.Home, TopLevelRoute.Home::class.qualifiedName.orEmpty(), "home"),
    TopLevelDestination(TopLevelRoute.History, R.string.nav_symptoms, Icons.AutoMirrored.Filled.ListAlt, TopLevelRoute.History::class.qualifiedName.orEmpty(), "symptoms"),
    TopLevelDestination(TopLevelRoute.Doctor, R.string.nav_doctor, Icons.Filled.MedicalServices, TopLevelRoute.Doctor::class.qualifiedName.orEmpty(), "doctor"),
    TopLevelDestination(TopLevelRoute.Analysis, R.string.nav_analyse, Icons.Filled.AutoAwesome, TopLevelRoute.Analysis::class.qualifiedName.orEmpty(), "analyse"),
)
