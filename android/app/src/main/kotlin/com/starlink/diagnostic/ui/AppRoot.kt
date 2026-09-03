package com.starlink.diagnostic.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Troubleshoot
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.SatelliteAlt
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.starlink.diagnostic.ui.components.OnboardingDialog
import com.starlink.diagnostic.ui.screens.ControlScreen
import com.starlink.diagnostic.ui.screens.DashboardScreen
import com.starlink.diagnostic.ui.screens.DiagnosticsScreen
import com.starlink.diagnostic.ui.screens.GpsScreen
import com.starlink.diagnostic.ui.screens.HardwareScreen
import com.starlink.diagnostic.ui.screens.HistoryScreen
import com.starlink.diagnostic.ui.screens.LiveMonitorScreen
import com.starlink.diagnostic.ui.screens.MoreScreen
import com.starlink.diagnostic.ui.screens.NetworkScreen
import com.starlink.diagnostic.ui.screens.ObstructionMapScreen
import com.starlink.diagnostic.ui.screens.RawScreen
import com.starlink.diagnostic.ui.screens.SettingsScreen

private data class BottomItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val nav = rememberNavController()
    // V2.2 first-run onboarding (persisted in the shared prefs)
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(AppViewModel.PREFS, Context.MODE_PRIVATE) }
    var showOnboarding by remember { mutableStateOf(!prefs.getBoolean("onboarded", false)) }
    if (showOnboarding) {
        OnboardingDialog(onDone = {
            prefs.edit().putBoolean("onboarded", true).apply()
            showOnboarding = false
        })
    }
    val items = listOf(
        BottomItem("dashboard", "اللوحة", Icons.Rounded.SatelliteAlt),
        BottomItem("live", "مراقبة", Icons.Rounded.MonitorHeart),
        BottomItem("history", "السجل", Icons.Rounded.History),
        BottomItem("diagnostics", "تشخيص", Icons.Rounded.Troubleshoot),
        BottomItem("more", "المزيد", Icons.Rounded.Widgets),
    )
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = currentRoute in items.map { it.route }

    Box(
        Modifier
            .fillMaxSize()
            .background(Navy),
    ) {
        NavHost(navController = nav, startDestination = "dashboard") {
            composable("dashboard") { DashboardScreen(vm, nav) }
            composable("live") { LiveMonitorScreen(vm) }
            composable("history") { HistoryScreen(vm) }
            composable("diagnostics") { DiagnosticsScreen(vm) }
            composable("more") { MoreScreen(vm, nav) }
            composable("hardware") { HardwareScreen(vm) }
            composable("gps") { GpsScreen(vm) }
            composable("raw") { RawScreen(vm) }
            composable("network") { NetworkScreen(vm) }
            composable("obstruction") { ObstructionMapScreen(vm) }
            composable("control") { ControlScreen(vm) }
            composable("settings") { SettingsScreen(vm) }
        }
        if (showBar) {
            Box(Modifier.align(Alignment.BottomCenter)) {
                BottomBar(nav, items, currentRoute)
            }
        }
    }
}

@Composable
private fun BottomBar(
    nav: NavHostController,
    items: List<BottomItem>,
    currentRoute: String?,
) {
    NavigationBar(containerColor = Color(0xE60D1430)) {
        items.forEach { item ->
            NavigationBarItem(
                selected = currentRoute == item.route,
                onClick = {
                    nav.navigate(item.route) {
                        popUpTo("dashboard") { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label, textAlign = TextAlign.Center) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = SkySoft,
                    selectedTextColor = SkySoft,
                    unselectedIconColor = MutedText,
                    unselectedTextColor = MutedText,
                    indicatorColor = SkyBlue.copy(alpha = 0.18f),
                ),
            )
        }
    }
}
