package com.cursor.mobile.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cursor.mobile.android.data.AgentStore
import com.cursor.mobile.android.data.AuthRepository
import com.cursor.mobile.android.data.WorkScope
import com.cursor.mobile.android.ui.navigation.Routes
import com.cursor.mobile.android.ui.screens.ChatScreen
import com.cursor.mobile.android.ui.screens.InboxScreen
import com.cursor.mobile.android.ui.screens.LoginScreen
import com.cursor.mobile.android.ui.screens.NewAgentScreen
import com.cursor.mobile.android.ui.screens.ReviewScreen
import com.cursor.mobile.android.ui.screens.SettingsScreen
import com.cursor.mobile.android.ui.theme.CursorMobileTheme
import com.cursor.mobile.android.ui.theme.accentGradient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CursorMobileApp() }
    }
}

@Composable
fun CursorMobileApp() {
    val context = LocalContext.current
    var darkTheme by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf(true) }
    var loggedIn by remember { mutableStateOf(AuthRepository.isLoggedIn(context)) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var showSplash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(1100)
        showSplash = false
    }

    CursorMobileTheme(darkTheme = darkTheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                MainScaffold(
                    darkTheme = darkTheme,
                    onToggleTheme = { darkTheme = it },
                    notifications = notifications,
                    onToggleNotifications = { notifications = it },
                    loggedIn = loggedIn,
                    loginError = loginError,
                    onLoggedIn = { loggedIn = true },
                    onLogout = {
                        AuthRepository.clear(context)
                        AgentStore.clear()
                        loggedIn = false
                    },
                    onSessionExpired = { message ->
                        AuthRepository.clear(context)
                        AgentStore.clear()
                        loginError = message
                        loggedIn = false
                    }
                )
                AnimatedVisibility(
                    visible = showSplash,
                    enter = fadeIn(tween(250)) + scaleIn(tween(350)),
                    exit = fadeOut(tween(400))
                ) {
                    SplashScreen(dark = darkTheme)
                }
            }
        }
    }
}

@Composable
private fun SplashScreen(dark: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (dark) Brush.linearGradient(listOf(Color(0xFF05070F), Color(0xFF0D1530), Color(0xFF1A1440)))
                else Brush.linearGradient(listOf(Color(0xFFF4F7FF), Color(0xFFEDEBFF), Color(0xFFE4F6FD)))
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                modifier = Modifier.size(88.dp).clip(RoundedCornerShape(28.dp)).background(accentGradient()),
                contentAlignment = Alignment.Center
            ) {
                Text("◈", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "Cursor Mobile",
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp),
                color = if (dark) Color.White else Color(0xFF0B1020)
            )
            Text(
                "BUILD FROM ANYWHERE",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 3.sp),
                color = if (dark) Color.White.copy(alpha = 0.6f) else Color(0xFF5B6478)
            )
        }
    }
}

@Composable
private fun MainScaffold(
    darkTheme: Boolean,
    onToggleTheme: (Boolean) -> Unit,
    notifications: Boolean,
    onToggleNotifications: (Boolean) -> Unit,
    loggedIn: Boolean,
    loginError: String?,
    onLoggedIn: () -> Unit,
    onLogout: () -> Unit,
    onSessionExpired: (String) -> Unit
) {
    val context = LocalContext.current
    val nav = rememberNavController()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(Routes.INBOX) }
    fun open(route: String) {
        selected = route
        scope.launch { drawer.close() }
        nav.navigate(route) { launchSingleTop = true }
    }
    fun requireLogin(route: String) {
        if (loggedIn) open(route)
        else {
            scope.launch { drawer.close() }
            nav.navigate(Routes.LOGIN) { launchSingleTop = true }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.fillMaxHeight().padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(15.dp)).background(accentGradient()),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("◈", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                        Column {
                            Text("Cursor Mobile", style = MaterialTheme.typography.titleMedium)
                            Text("Android · 对标 iOS Beta", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    DrawerEntry("收件箱 Inbox", selected == Routes.INBOX, Icons.Filled.Home) { open(Routes.INBOX) }
                    Text("分类", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    DrawerEntry("Projects", selected == Routes.INBOX && AgentStore.scope == WorkScope.PROJECT, Icons.Filled.Folder) {
                        AgentStore.choose(WorkScope.PROJECT)
                        open(Routes.INBOX)
                    }
                    DrawerEntry("Repositories", selected == Routes.INBOX && AgentStore.scope == WorkScope.REPOSITORY, Icons.Filled.Code) {
                        AgentStore.choose(WorkScope.REPOSITORY)
                        open(Routes.INBOX)
                    }
                    DrawerEntry("发起 Agent", selected == Routes.NEW_AGENT, Icons.Filled.Add) { open(Routes.NEW_AGENT) }
                    DrawerEntry("设置", selected == Routes.SETTINGS, Icons.Filled.Settings) { open(Routes.SETTINGS) }
                    if (loggedIn) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "${AuthRepository.accountLabel(context).ifBlank { "已登录" }} · Cloud Agents 凭证已保存",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        DrawerEntry("退出登录", false, Icons.Filled.ExitToApp) {
                            scope.launch { drawer.close() }
                            onLogout()
                            nav.navigate(Routes.LOGIN) {
                                popUpTo(0) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "从口袋里指挥全部 Agent",
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    ) {
        NavHost(navController = nav, startDestination = if (loggedIn) Routes.INBOX else Routes.LOGIN) {
            composable(Routes.LOGIN) {
                LoginScreen(
                    initialError = loginError,
                    onLoggedIn = {
                        onLoggedIn()
                        nav.navigate(Routes.INBOX) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(Routes.INBOX) {
                selected = Routes.INBOX
                InboxScreen(
                    onOpenDrawer = { scope.launch { drawer.open() } },
                    onOpenChat = { requireLogin(Routes.chat(it)) },
                    onNewAgent = { requireLogin(Routes.NEW_AGENT) },
                    onSessionExpired = { message ->
                        scope.launch { drawer.close() }
                        onSessionExpired(message)
                        nav.navigate(Routes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(Routes.NEW_AGENT) {
                selected = Routes.NEW_AGENT
                NewAgentScreen(
                    onBack = { nav.popBackStack() },
                    onLaunched = { nav.navigate(Routes.chat(it)) },
                    onSessionExpired = { message ->
                        onSessionExpired(message)
                        nav.navigate(Routes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(Routes.SETTINGS) {
                selected = Routes.SETTINGS
                SettingsScreen(
                    darkTheme = darkTheme,
                    onToggleTheme = onToggleTheme,
                    notifications = notifications,
                    onToggleNotifications = onToggleNotifications,
                    onOpenDrawer = { scope.launch { drawer.open() } }
                )
            }
            composable(
                Routes.CHAT,
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
            ) { backStack ->
                val id = backStack.arguments?.getString("sessionId") ?: ""
                ChatScreen(
                    sessionId = id,
                    onBack = { nav.popBackStack() },
                    onOpenReview = { nav.navigate(Routes.review(it)) },
                    onSessionExpired = { message ->
                        onSessionExpired(message)
                        nav.navigate(Routes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                )
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

@Composable
private fun DrawerEntry(label: String, selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        shape = RoundedCornerShape(14.dp),
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        )
    )
}
