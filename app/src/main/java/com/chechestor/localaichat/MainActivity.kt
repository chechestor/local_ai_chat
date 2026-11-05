package com.chechestor.localaichat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.chechestor.localaichat.chat.ChatScreen
import com.chechestor.localaichat.settings.SettingsScreen
import com.chechestor.localaichat.setup.SetupGate
import com.chechestor.localaichat.setup.SetupViewModel

/** Название приложения (F001). */
const val APP_TITLE = "Local AI Chat"

private const val ROUTE_CHAT = "chat"
private const val ROUTE_SETTINGS = "settings"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalAiChatTheme {
                // F008: пока модели не установлены, доступны только экраны установки.
                val setup: SetupViewModel = viewModel()
                SetupGate(setup) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = ROUTE_CHAT) {
                        composable(ROUTE_CHAT) { ChatScreen(onOpenSettings = { nav.navigate(ROUTE_SETTINGS) }) }
                        composable(ROUTE_SETTINGS) {
                            SettingsScreen(
                                onBack = { nav.popBackStack() },
                                onDeleteModels = { nav.popBackStack(); setup.deleteAndReinstall() },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LocalAiChatTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors, content = content)
}
