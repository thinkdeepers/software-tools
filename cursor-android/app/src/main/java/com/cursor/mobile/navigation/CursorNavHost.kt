package com.cursor.mobile.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cursor.mobile.di.AppContainer
import com.cursor.mobile.ui.chat.ChatScreen
import com.cursor.mobile.ui.create.CreateAgentScreen
import com.cursor.mobile.ui.home.HomeScreen
import com.cursor.mobile.ui.login.LoginScreen
import com.cursor.mobile.ui.settings.SettingsScreen

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val CREATE = "create"
    const val SETTINGS = "settings"
    const val CHAT = "chat/{agentId}"

    fun chat(agentId: String) = "chat/$agentId"
}

@Composable
fun CursorNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val startDestination = remember {
        if (container.repository.hasSession()) Routes.HOME else Routes.LOGIN
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Routes.LOGIN) {
            LoginScreen(
                repository = container.repository,
                onLoggedIn = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                repository = container.repository,
                onOpenAgent = { id -> navController.navigate(Routes.chat(id)) },
                onCreateAgent = { navController.navigate(Routes.CREATE) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.CREATE) {
            CreateAgentScreen(
                repository = container.repository,
                sessionStore = container.sessionStore,
                onBack = { navController.popBackStack() },
                onCreated = { agentId ->
                    navController.navigate(Routes.chat(agentId)) {
                        popUpTo(Routes.HOME)
                    }
                }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                repository = container.repository,
                onBack = { navController.popBackStack() },
                onLoggedOut = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("agentId") { type = NavType.StringType })
        ) { entry ->
            val agentId = entry.arguments?.getString("agentId").orEmpty()
            ChatScreen(
                agentId = agentId,
                repository = container.repository,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
