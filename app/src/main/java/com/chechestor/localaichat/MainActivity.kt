package com.chechestor.localaichat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chechestor.localaichat.modeltest.ModelTestScreen
import com.chechestor.localaichat.setup.SetupGate
import com.chechestor.localaichat.setup.SetupViewModel

/** Заголовок главного экрана (F001). */
const val APP_TITLE = "Local AI Chat"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalAiChatTheme {
                // F008: пока модели не установлены, доступны только экраны установки.
                val setup: SetupViewModel = viewModel()
                SetupGate(setup) {
                    // F003: временный экран проверки модели; диалоги — F004.
                    ModelTestScreen(onDeleteModels = setup::deleteAndReinstall)
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

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = APP_TITLE, style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MainScreenPreview() {
    LocalAiChatTheme { MainScreen() }
}
