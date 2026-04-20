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
import com.example.mob_dev_portfolio.ui.history.HistoryScreen
import com.example.mob_dev_portfolio.ui.home.HomeScreen
import com.example.mob_dev_portfolio.ui.log.LogSymptomScreen
import com.example.mob_dev_portfolio.ui.log.LogSymptomViewModel
import com.example.mob_dev_portfolio.ui.navigation.AnalysisDetailRoute
import com.example.mob_dev_portfolio.ui.navigation.AnalysisRunnerRoute
import com.example.mob_dev_portfolio.ui.navigation.DetailRoute
import com.example.mob_dev_portfolio.ui.navigation.EditLogRoute
import com.example.mob_dev_portfolio.ui.navigation.TopLevelDestinations
import com.example.mob_dev_portfolio.ui.navigation.TopLevelRoute

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
            null -> Unit
        }
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TopLevelDestinations.forEach { dest ->
                val selected = when (dest.route) {
                    TopLevelRoute.Home -> hierarchy?.any { it.hasRoute<TopLevelRoute.Home>() } == true
                    TopLevelRoute.Log -> hierarchy?.any { it.hasRoute<TopLevelRoute.Log>() } == true
                    TopLevelRoute.History -> hierarchy?.any {
                        it.hasRoute<TopLevelRoute.History>() ||
                            it.hasRoute<DetailRoute>() ||
                            it.hasRoute<EditLogRoute>()
                    } == true
                    TopLevelRoute.Analysis -> hierarchy?.any {
                        it.hasRoute<TopLevelRoute.Analysis>() ||
                            it.hasRoute<AnalysisRunnerRoute>() ||
                            it.hasRoute<AnalysisDetailRoute>()
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
                    onLogSymptomClick = { navigateToTopLevel(navController, TopLevelRoute.Log) },
                    onViewHistoryClick = { navigateToTopLevel(navController, TopLevelRoute.History) },
                    onOpenLog = { id -> navController.navigate(DetailRoute(id)) },
                )
            }
            composable<TopLevelRoute.Log> {
                LogSymptomScreen(
                    onBack = {
                        if (!navController.popBackStack()) {
                            navigateToTopLevel(navController, TopLevelRoute.Home)
                        }
                    },
                    onSaved = { navigateToTopLevel(navController, TopLevelRoute.History) },
                )
            }
            composable<TopLevelRoute.History> {
                HistoryScreen(
                    onOpenLog = { id -> navController.navigate(DetailRoute(id)) },
                )
            }
            composable<TopLevelRoute.Analysis> {
                AnalysisHistoryScreen(
                    onOpenRun = { id -> navController.navigate(AnalysisDetailRoute(id)) },
                    onRunNewAnalysis = { navController.navigate(AnalysisRunnerRoute) },
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
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
