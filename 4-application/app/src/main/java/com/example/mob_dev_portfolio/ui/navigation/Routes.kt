package com.example.mob_dev_portfolio.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Add
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
}

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
)
