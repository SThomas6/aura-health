package com.example.mob_dev_portfolio.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

sealed interface TopLevelRoute {
    @Serializable
    data object Home : TopLevelRoute
    @Serializable
    data object Log : TopLevelRoute
    @Serializable
    data object History : TopLevelRoute
    @Serializable
    data object Analysis : TopLevelRoute
}

@Serializable
data class DetailRoute(val id: Long)

@Serializable
data class EditLogRoute(val id: Long)

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

data class TopLevelDestination(
    val route: TopLevelRoute,
    val label: String,
    val icon: ImageVector,
    val routeQualifiedName: String,
)

val TopLevelDestinations: List<TopLevelDestination> = listOf(
    TopLevelDestination(TopLevelRoute.Home, "Home", Icons.Filled.Home, TopLevelRoute.Home::class.qualifiedName.orEmpty()),
    TopLevelDestination(TopLevelRoute.Log, "Log", Icons.Filled.Add, TopLevelRoute.Log::class.qualifiedName.orEmpty()),
    TopLevelDestination(TopLevelRoute.History, "History", Icons.AutoMirrored.Filled.ListAlt, TopLevelRoute.History::class.qualifiedName.orEmpty()),
    TopLevelDestination(TopLevelRoute.Analysis, "Analyse", Icons.Filled.AutoAwesome, TopLevelRoute.Analysis::class.qualifiedName.orEmpty()),
)
