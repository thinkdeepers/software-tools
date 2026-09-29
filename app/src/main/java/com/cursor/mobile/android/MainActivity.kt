package com.cursor.mobile.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.compose.rememberNavController
import com.cursor.mobile.android.data.FakeRepository
import com.cursor.mobile.android.ui.navigation.Routes
import com.cursor.mobile.android.ui.screens.ChatScreen
import com.cursor.mobile.android.ui.screens.InboxScreen
import com.cursor.mobile.android.ui.screens.NewAgentScreen
import com.cursor.mobile.android.ui.screens.ReviewScreen
import com.cursor.mobile.android.ui.screens.SettingsScreen
import com.cursor.mobile.android.ui.theme.CursorMobileTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CursorMobileApp() }
    }
}

@Composable
fun CursorMobileApp() {
    var darkTheme by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf(true) }

    CursorMobileTheme(darkTheme = darkTheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val nav = rememberNavController()
            val drawer = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            var selected by remember { mutableStateOf(Routes.INBOX) }
            fun open(route: String) {
                selected = route
                scope.launch { drawer.close() }
                nav.navigate(route) { launchSingleTop = true }
            }

            ModalNavigationDrawer(
                drawerState = drawer,
                drawerContent = {
                    ModalDrawerSheet {
                        Column(Modifier.padding(16.dp)) {
                            Text("Cursor Mobile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Android Demo · 对标 iOS Beta", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(12.dp))
                            NavigationDrawerItem(
                                label = { Text("收件箱 Inbox") },
                                selected = selected == Routes.INBOX,
                                onClick = { open(Routes.INBOX) },
                                icon = { Icon(Icons.Filled.Home, contentDescription = null) }
                            )
                            NavigationDrawerItem(
                                label = { Text("发起 Agent") },
                                selected = selected == Routes.NEW_AGENT,
                                onClick = { open(Routes.NEW_AGENT) },
                                icon = { Icon(Icons.Filled.Add, contentDescription = null) }
                            )
                            NavigationDrawerItem(
                                label = { Text("设置") },
                                selected = selected == Routes.SETTINGS,
                                onClick = { open(Routes.SETTINGS) },
                                icon = { Icon(Icons.Filled.Settings, contentDescription = null) }
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("仓库（占位）", style = MaterialTheme.typography.labelMedium)
                            FakeRepository.repos.forEach {
                                Text("· ${it.name} (${it.branch})", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            ) {
                NavHost(navController = nav, startDestination = Routes.INBOX) {
                    composable(Routes.INBOX) {
                        selected = Routes.INBOX
                        InboxScreen(
                            sessions = FakeRepository.sessions,
                            onOpenDrawer = { scope.launch { drawer.open() } },
                            onOpenChat = { nav.navigate(Routes.chat(it)) },
                            onNewAgent = { nav.navigate(Routes.NEW_AGENT) }
                        )
                    }
                    composable(Routes.NEW_AGENT) {
                        selected = Routes.NEW_AGENT
                        NewAgentScreen(onBack = { nav.popBackStack() }, onLaunched = { nav.navigate(Routes.chat(it)) })
                    }
                    composable(Routes.SETTINGS) {
                        selected = Routes.SETTINGS
                        SettingsScreen(
                            darkTheme = darkTheme,
                            onToggleTheme = { darkTheme = it },
                            notifications = notifications,
                            onToggleNotifications = { notifications = it },
                            onOpenDrawer = { scope.launch { drawer.open() } }
                        )
                    }
                    composable(
                        Routes.CHAT,
                        arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
                    ) { backStack ->
                        val id = backStack.arguments?.getString("sessionId") ?: ""
                        ChatScreen(sessionId = id, onBack = { nav.popBackStack() }, onOpenReview = { nav.navigate(Routes.review(it)) })
                    }
                    composable(
                        Routes.REVIEW,
                        arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
                    ) { backStack ->
                        val id = backStack.arguments?.getString("sessionId") ?: ""
                        ReviewScreen(sessionId = id, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
