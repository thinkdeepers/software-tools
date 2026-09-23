package com.cursor.mobile.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.cursor.mobile.ui.review.ReviewScreen
import com.cursor.mobile.ui.settings.SettingsScreen

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val CREATE = "create?repo={repo}"
    const val SETTINGS = "settings"
    const val CHAT = "chat/{agentId}"
    const val REVIEW = "review?prUrl={prUrl}"

    fun chat(agentId: String) = "chat/$agentId"
    fun create(repo: String = "") = "create?repo=${Uri.encode(repo)}"
    fun review(prUrl: String) = "review?prUrl=${Uri.encode(prUrl)}"
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
                    container.watch.start()
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                repository = container.repository,
                watch = container.watch,
                onOpenAgent = { id -> navController.navigate(Routes.chat(id)) },
                onCreateAgent = { repo -> navController.navigate(Routes.create(repo.orEmpty())) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }
        composable(
            route = Routes.CREATE,
            arguments = listOf(navArgument("repo") {
                type = NavType.StringType
                defaultValue = ""
            })
        ) { entry ->
            CreateAgentScreen(
                repository = container.repository,
                sessionStore = container.sessionStore,
                initialRepo = entry.arguments?.getString("repo").orEmpty(),
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
                sessionStore = container.sessionStore,
                onBack = { navController.popBackStack() },
                onLoggedOut = {
                    container.watch.stop()
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
            val incoming by entry.savedStateHandle.getStateFlow("queuedPrompt", "").collectAsState()
            ChatScreen(
                agentId = agentId,
                repository = container.repository,
                incomingPrompt = incoming,
                onIncomingConsumed = { entry.savedStateHandle["queuedPrompt"] = "" },
                onBack = { navController.popBackStack() },
                onOpenReview = { url -> navController.navigate(Routes.review(url)) }
            )
        }
        composable(
            route = Routes.REVIEW,
            arguments = listOf(navArgument("prUrl") { type = NavType.StringType })
        ) { entry ->
            val prUrl = entry.arguments?.getString("prUrl").orEmpty()
            ReviewScreen(
                prUrl = prUrl,
                github = container.github,
                onBack = { navController.popBackStack() },
                onAskAgent = { prompt ->
                    navController.previousBackStackEntry?.savedStateHandle?.set("queuedPrompt", prompt)
                    navController.popBackStack()
                }
            )
        }
    }
}
