package com.example.mob_dev_portfolio.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.mob_dev_portfolio.ui.history.HistoryScreen
import com.example.mob_dev_portfolio.ui.home.HomeScreen
import com.example.mob_dev_portfolio.ui.log.LogSymptomScreen
import com.example.mob_dev_portfolio.ui.navigation.TopLevelDestinations
import com.example.mob_dev_portfolio.ui.navigation.TopLevelRoute

@Composable
fun AuraApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val hierarchy = backStackEntry?.destination?.hierarchy

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TopLevelDestinations.forEach { dest ->
                val selected = when (dest.route) {
                    TopLevelRoute.Home -> hierarchy?.any { it.hasRoute<TopLevelRoute.Home>() } == true
                    TopLevelRoute.Log -> hierarchy?.any { it.hasRoute<TopLevelRoute.Log>() } == true
                    TopLevelRoute.History -> hierarchy?.any { it.hasRoute<TopLevelRoute.History>() } == true
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
                HistoryScreen()
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
