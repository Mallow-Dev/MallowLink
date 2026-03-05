package com.mallowlink.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mallowlink.app.ui.chat.ChatScreen
import com.mallowlink.app.ui.home.HomeScreenContent
import com.mallowlink.app.ui.onboarding.OnboardingScreen
import com.mallowlink.app.ui.settings.SettingsScreen
import com.mallowlink.app.ui.sources.SourcesScreen
import com.mallowlink.app.ui.theme.MallowLinkTheme
import dagger.hilt.android.AndroidEntryPoint

// ─────────────────────────────────────────────────────────────────────────────
// Routes
// ─────────────────────────────────────────────────────────────────────────────

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val CHAT = "chat/{sessionId}"
    const val SOURCES = "sources"
    const val SETTINGS = "settings"

    fun chat(sessionId: String = "new") = "chat/$sessionId"
}

// ─────────────────────────────────────────────────────────────────────────────
// MainActivity
// ─────────────────────────────────────────────────────────────────────────────

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var sharedUriString: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShareIntent(intent)

        setContent {
            MallowLinkTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MallowLinkNavGraph(
                        sharedUri = sharedUriString,
                        onSharedUriConsumed = { sharedUriString = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        intent ?: return
        if (intent.action == Intent.ACTION_SEND) {
            sharedUriString = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                ?.toString()
                ?: intent.getStringExtra(Intent.EXTRA_TEXT)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Navigation graph
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun MallowLinkNavGraph(
    sharedUri: String?,
    onSharedUriConsumed: () -> Unit,
) {
    // In production: read from DataStore whether onboarding is complete
    val onboardingDone by remember { mutableStateOf(false) }
    val navController: NavHostController = rememberNavController()

    // Navigate to chat if a file was shared from another app
    LaunchedEffect(sharedUri) {
        if (sharedUri != null) {
            navController.navigate(Routes.chat())
            onSharedUriConsumed()
        }
    }

    NavHost(
        navController = navController,
        startDestination = if (onboardingDone) Routes.HOME else Routes.ONBOARDING,
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onComplete = { _ ->
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.HOME) {
            HomeScreenContent(
                onNewChat = { navController.navigate(Routes.chat()) },
                onOpenSources = { navController.navigate(Routes.SOURCES) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.CHAT) {
            ChatScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenDocument = { uri ->
                    // Launch a VIEW intent for the document URI
                    navController.context.startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            data = android.net.Uri.parse(uri)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                        }
                    )
                },
            )
        }

        composable(Routes.SOURCES) {
            SourcesScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}

// Extension to get context from NavController
private val NavHostController.context get() = currentBackStackEntry?.context
    ?: throw IllegalStateException("No context available")
