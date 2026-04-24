package com.example.mob_dev_portfolio.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.ui.analysis.AnalysisDetailScreen
import com.example.mob_dev_portfolio.ui.analysis.AnalysisHistoryScreen
import com.example.mob_dev_portfolio.ui.analysis.AnalysisScreen
import com.example.mob_dev_portfolio.ui.detail.LogDetailScreen
import com.example.mob_dev_portfolio.ui.doctor.DoctorListScreen
import com.example.mob_dev_portfolio.ui.doctor.DoctorVisitDetailScreen
import com.example.mob_dev_portfolio.ui.doctor.DoctorVisitEditorScreen
import com.example.mob_dev_portfolio.ui.history.HistoryScreen
import com.example.mob_dev_portfolio.ui.home.HomeScreen
import com.example.mob_dev_portfolio.ui.log.LogSymptomScreen
import com.example.mob_dev_portfolio.ui.log.LogSymptomViewModel
import com.example.mob_dev_portfolio.ui.health.HealthDataSettingsScreen
import com.example.mob_dev_portfolio.ui.health.HealthMetricDetailScreen
import com.example.mob_dev_portfolio.ui.navigation.AnalysisDetailRoute
import com.example.mob_dev_portfolio.ui.navigation.AnalysisRunnerRoute
import com.example.mob_dev_portfolio.ui.navigation.DemographicProfileRoute
import com.example.mob_dev_portfolio.ui.navigation.DetailRoute
import com.example.mob_dev_portfolio.ui.navigation.DoctorVisitDetailRoute
import com.example.mob_dev_portfolio.ui.navigation.DoctorVisitEditorRoute
import com.example.mob_dev_portfolio.ui.navigation.EditLogRoute
import com.example.mob_dev_portfolio.ui.navigation.HealthDataSettingsRoute
import com.example.mob_dev_portfolio.ui.navigation.HealthMetricDetailRoute
import com.example.mob_dev_portfolio.ui.navigation.HealthReportHistoryRoute
import com.example.mob_dev_portfolio.ui.navigation.HealthReportRoute
import com.example.mob_dev_portfolio.ui.navigation.LogSymptomRoute
import com.example.mob_dev_portfolio.ui.navigation.MedicationEditorRoute
import com.example.mob_dev_portfolio.ui.navigation.MedicationListRoute
import com.example.mob_dev_portfolio.ui.medication.MedicationEditorScreen
import com.example.mob_dev_portfolio.ui.medication.MedicationListScreen
import com.example.mob_dev_portfolio.ui.navigation.SettingsRoute
import com.example.mob_dev_portfolio.ui.navigation.TrendVisualisationRoute
import com.example.mob_dev_portfolio.ui.navigation.TopLevelDestinations
import com.example.mob_dev_portfolio.ui.navigation.TopLevelRoute
import com.example.mob_dev_portfolio.ui.profile.DemographicProfileScreen
import com.example.mob_dev_portfolio.ui.report.HealthReportScreen
import com.example.mob_dev_portfolio.ui.report.ReportHistoryScreen
import com.example.mob_dev_portfolio.ui.settings.SettingsScreen
import com.example.mob_dev_portfolio.ui.trends.TrendVisualisationScreen

@Composable
fun AuraApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val hierarchy = backStackEntry?.destination?.hierarchy

    // Subscribe to notification-tap deep links. MainActivity publishes the
    // pending target in `onCreate` (cold start) or `onNewIntent` (warm start).
    // Using a StateFlow rather than a SharedFlow is deliberate: on cold start
    // the emit happens *before* setContent has run, and a replay=0 SharedFlow
    // would drop the event because the collector hasn't subscribed yet. A
    // nullable StateFlow lets the late subscriber still observe the target,
    // and calling consume() after navigating ensures a subsequent config
    // change doesn't re-fire the deep-link.
    val context = LocalContext.current
    val deepLinkEvents = remember(context) {
        (context.applicationContext as AuraApplication).container.deepLinkEvents
    }
    val pendingDeepLink by deepLinkEvents.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pendingDeepLink) {
        when (val target = pendingDeepLink) {
            DeepLinkTarget.AnalysisResult -> {
                navigateToTopLevel(navController, TopLevelRoute.Analysis)
                deepLinkEvents.consume(target)
            }
            is DeepLinkTarget.AnalysisRun -> {
                // Reset to the Analysis tab root first so "back" from
                // the detail view lands on the history list (rather
                // than, say, the Log form the user was previously on).
                navigateToTopLevel(navController, TopLevelRoute.Analysis)
                navController.navigate(AnalysisDetailRoute(target.runId))
                deepLinkEvents.consume(target)
            }
            DeepLinkTarget.HealthDataSettings -> {
                // Reset to Home first so the system-intent path doesn't
                // stack the settings screen on top of whatever random
                // state the user happened to be in when the system
                // resumed the process.
                navigateToTopLevel(navController, TopLevelRoute.Home)
                navController.navigate(HealthDataSettingsRoute)
                deepLinkEvents.consume(target)
            }
            null -> Unit
        }
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TopLevelDestinations.forEach { dest ->
                val selected = when (dest.route) {
                    TopLevelRoute.Home -> hierarchy?.any { it.hasRoute<TopLevelRoute.Home>() } == true
                    // The Symptoms tab owns the list, the per-log detail,
                    // the edit form, AND the "log a new symptom" form —
                    // any of those should keep the tab highlighted so
                    // the user doesn't lose context while adding from
                    // the FAB or editing an existing entry.
                    TopLevelRoute.History -> hierarchy?.any {
                        it.hasRoute<TopLevelRoute.History>() ||
                            it.hasRoute<DetailRoute>() ||
                            it.hasRoute<EditLogRoute>() ||
                            it.hasRoute<LogSymptomRoute>()
                    } == true
                    TopLevelRoute.Analysis -> hierarchy?.any {
                        it.hasRoute<TopLevelRoute.Analysis>() ||
                            it.hasRoute<AnalysisRunnerRoute>() ||
                            it.hasRoute<AnalysisDetailRoute>()
                    } == true
                    // The Doctor tab owns the list + add/edit form + detail
                    // view, so any of those should keep the tab highlighted.
                    TopLevelRoute.Doctor -> hierarchy?.any {
                        it.hasRoute<TopLevelRoute.Doctor>() ||
                            it.hasRoute<DoctorVisitDetailRoute>() ||
                            it.hasRoute<DoctorVisitEditorRoute>()
                    } == true
                }
                item(
                    selected = selected,
                    onClick = { navigateToTopLevel(navController, dest.route) },
                    icon = { Icon(dest.icon, contentDescription = dest.label) },
                    label = { Text(dest.label) },
                    modifier = Modifier.testTag("nav_${dest.label.lowercase()}"),
                )
            }
        },
    ) {
        NavHost(
            navController = navController,
            startDestination = TopLevelRoute.Home,
        ) {
            composable<TopLevelRoute.Home> {
                HomeScreen(
                    onLogSymptomClick = { navController.navigate(LogSymptomRoute) },
                    onViewHistoryClick = { navigateToTopLevel(navController, TopLevelRoute.History) },
                    onOpenLog = { id -> navController.navigate(DetailRoute(id)) },
                    onGenerateReport = { navController.navigate(HealthReportRoute) },
                    onOpenTrends = { navController.navigate(TrendVisualisationRoute) },
                    onOpenSettings = { navController.navigate(SettingsRoute) },
                    onOpenMedications = { navController.navigate(MedicationListRoute) },
                    onOpenHealthMetric = { metric ->
                        navController.navigate(HealthMetricDetailRoute(metric.storageKey))
                    },
                )
            }
            composable<MedicationListRoute> {
                MedicationListScreen(
                    onBack = {
                        if (!navController.popBackStack()) {
                            navigateToTopLevel(navController, TopLevelRoute.Home)
                        }
                    },
                    onAddReminder = { navController.navigate(MedicationEditorRoute(null)) },
                    onEditReminder = { id -> navController.navigate(MedicationEditorRoute(id)) },
                )
            }
            composable<MedicationEditorRoute> { entry ->
                val route = entry.toRoute<MedicationEditorRoute>()
                MedicationEditorScreen(
                    reminderId = route.id,
                    onBack = {
                        if (!navController.popBackStack()) {
                            navigateToTopLevel(navController, TopLevelRoute.Home)
                        }
                    },
                    onSaved = {
                        if (!navController.popBackStack()) {
                            navController.navigate(MedicationListRoute)
                        }
                    },
                    onDeleted = {
                        if (!navController.popBackStack()) {
                            navController.navigate(MedicationListRoute)
                        }
                    },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(
                    onBack = {
                        if (!navController.popBackStack()) {
                            navigateToTopLevel(navController, TopLevelRoute.Home)
                        }
                    },
                    onOpenDemographicProfile = { navController.navigate(DemographicProfileRoute) },
                    onOpenHealthDataSettings = { navController.navigate(HealthDataSettingsRoute) },
                )
            }
            composable<HealthMetricDetailRoute> { entry ->
                val route = entry.toRoute<HealthMetricDetailRoute>()
                HealthMetricDetailScreen(
                    metricStorageKey = route.metricStorageKey,
                    onBack = {
                        if (!navController.popBackStack()) {
                            navigateToTopLevel(navController, TopLevelRoute.Home)
                        }
                    },
                )
            }
            composable<HealthReportRoute> {
                HealthReportScreen(
                    onBack = {
                        if (!navController.popBackStack()) {
                            navigateToTopLevel(navController, TopLevelRoute.Home)
                        }
                    },
                    onOpenHistory = { navController.navigate(HealthReportHistoryRoute) },
                )
            }
            composable<TrendVisualisationRoute> {
                // The redesigned Trends page no longer surfaces per-log
                // tap targets (the line graph is bucket-aggregated so
                // there isn't a single log to route to). Back is the
                // only navigation affordance we need here.
                TrendVisualisationScreen(
                    onBack = {
                        if (!navController.popBackStack()) {
                            navigateToTopLevel(navController, TopLevelRoute.Home)
                        }
                    },
                )
            }
            composable<HealthReportHistoryRoute> {
                ReportHistoryScreen(
                    onBack = {
                        if (!navController.popBackStack()) {
                            navigateToTopLevel(navController, TopLevelRoute.Home)
                        }
                    },
                )
            }
            composable<HealthDataSettingsRoute> {
                HealthDataSettingsScreen(
                    onBack = {
                        if (!navController.popBackStack()) {
                            navigateToTopLevel(navController, TopLevelRoute.Home)
                        }
                    },
                )
            }
            composable<DemographicProfileRoute> {
                DemographicProfileScreen(
                    onBack = {
                        if (!navController.popBackStack()) {
                            navigateToTopLevel(navController, TopLevelRoute.Home)
                        }
                    },
                )
            }
            composable<LogSymptomRoute> {
                LogSymptomScreen(
                    onBack = {
                        if (!navController.popBackStack()) {
                            navigateToTopLevel(navController, TopLevelRoute.History)
                        }
                    },
                    // Pop rather than navigateToTopLevel so we land
                    // exactly on whichever Symptoms-tab screen launched
                    // us (the list from the FAB, or Home if the user
                    // came via the hero button — in which case the
                    // Symptoms tab is the natural place to see the
                    // entry they just saved).
                    onSaved = {
                        if (!navController.popBackStack()) {
                            navigateToTopLevel(navController, TopLevelRoute.History)
                        }
                    },
                )
            }
            composable<TopLevelRoute.History> {
                HistoryScreen(
                    onOpenLog = { id -> navController.navigate(DetailRoute(id)) },
                    onAddSymptom = { navController.navigate(LogSymptomRoute) },
                )
            }
            composable<TopLevelRoute.Analysis> {
                AnalysisHistoryScreen(
                    onOpenRun = { id -> navController.navigate(AnalysisDetailRoute(id)) },
                    onRunNewAnalysis = { navController.navigate(AnalysisRunnerRoute) },
                )
            }
            composable<TopLevelRoute.Doctor> {
                DoctorListScreen(
                    onAddVisit = { navController.navigate(DoctorVisitEditorRoute(null)) },
                    onOpenVisit = { id -> navController.navigate(DoctorVisitDetailRoute(id)) },
                )
            }
            composable<DoctorVisitEditorRoute> { entry ->
                val route = entry.toRoute<DoctorVisitEditorRoute>()
                DoctorVisitEditorScreen(
                    visitId = route.id,
                    onBack = {
                        if (!navController.popBackStack()) {
                            navigateToTopLevel(navController, TopLevelRoute.Doctor)
                        }
                    },
                    onSaved = {
                        // Prefer popping back into wherever we came from
                        // (the list, or the detail view for an edit). If
                        // the stack is empty we land on the Doctor tab
                        // root — never lose the user in an edit flow.
                        if (!navController.popBackStack()) {
                            navigateToTopLevel(navController, TopLevelRoute.Doctor)
                        }
                    },
                )
            }
            composable<DoctorVisitDetailRoute> { entry ->
                val route = entry.toRoute<DoctorVisitDetailRoute>()
                DoctorVisitDetailScreen(
                    visitId = route.id,
                    onBack = {
                        if (!navController.popBackStack()) {
                            navigateToTopLevel(navController, TopLevelRoute.Doctor)
                        }
                    },
                    onEdit = { id -> navController.navigate(DoctorVisitEditorRoute(id)) },
                    onOpenLog = { id -> navController.navigate(DetailRoute(id)) },
                    onDeleted = {
                        if (!navController.popBackStack()) {
                            navigateToTopLevel(navController, TopLevelRoute.Doctor)
                        }
                    },
                )
            }
            composable<AnalysisRunnerRoute> {
                // The "run a new analysis" form — a one-off destination
                // reached from the history list's FAB. Popping back
                // lands on the history list, which will refresh via its
                // Flow-backed state once the worker persists the run.
                AnalysisScreen()
            }
            composable<AnalysisDetailRoute> { entry ->
                val route = entry.toRoute<AnalysisDetailRoute>()
                AnalysisDetailScreen(
                    runId = route.runId,
                    onBack = {
                        if (!navController.popBackStack()) {
                            navigateToTopLevel(navController, TopLevelRoute.Analysis)
                        }
                    },
                    onGenerateReport = { navController.navigate(HealthReportRoute) },
                )
            }
            composable<DetailRoute> { entry ->
                val route = entry.toRoute<DetailRoute>()
                LogDetailScreen(
                    id = route.id,
                    onBack = {
                        if (!navController.popBackStack()) {
                            navigateToTopLevel(navController, TopLevelRoute.History)
                        }
                    },
                    onEdit = { id -> navController.navigate(EditLogRoute(id)) },
                    onDeleted = {
                        if (!navController.popBackStack()) {
                            navigateToTopLevel(navController, TopLevelRoute.History)
                        }
                    },
                    onOpenVisit = { id -> navController.navigate(DoctorVisitDetailRoute(id)) },
                )
            }
            composable<EditLogRoute> { entry ->
                val route = entry.toRoute<EditLogRoute>()
                LogSymptomScreen(
                    onBack = {
                        if (!navController.popBackStack()) {
                            navigateToTopLevel(navController, TopLevelRoute.History)
                        }
                    },
                    onSaved = {
                        navController.popBackStack(DetailRoute(route.id), inclusive = false)
                    },
                    viewModel = viewModel(factory = LogSymptomViewModel.editFactory(route.id)),
                )
            }
        }
    }
}

private fun navigateToTopLevel(navController: NavController, route: TopLevelRoute) {
    // When the user taps a top-level tab from *inside* a nested
    // destination (e.g. Home → Health data → tap Home in the nav bar)
    // we want the nested screens to get popped. The previous
    // implementation saved/restored state on every pop, which caused the
    // nav bar to visually "not respond" because the destination was
    // restored back to the nested screen on the way in. Dropping the
    // save/restore pair makes the tab always land on its root — that's
    // the behaviour users expect from a bottom nav bar.
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) {
            saveState = false
            inclusive = false
        }
        launchSingleTop = true
        restoreState = false
    }
}
