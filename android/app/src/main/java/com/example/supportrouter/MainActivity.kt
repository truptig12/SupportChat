package com.example.supportrouter

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.supportrouter.journey.ContactNumbers
import com.example.supportrouter.journey.DeviceContext
import com.example.supportrouter.journey.LoginScenario
import com.example.supportrouter.ui.screens.AskLloydsController
import com.example.supportrouter.ui.screens.AskLloydsScreen
import com.example.supportrouter.ui.screens.AskMode
import com.example.supportrouter.ui.screens.CallUsScreen
import com.example.supportrouter.ui.screens.IncidentScreen
import com.example.supportrouter.ui.screens.LoggedInScreen
import com.example.supportrouter.ui.screens.LoginErrorScreen
import com.example.supportrouter.ui.screens.LoginScreen
import com.example.supportrouter.ui.screens.LoginState
import com.example.supportrouter.ui.theme.Brand
import com.example.supportrouter.ui.theme.LloydsTheme

/** Screens, kept on a simple back stack (no navigation library needed). */
sealed interface Screen {
    data object Login : Screen
    data class LoginError(val scenario: LoginScenario) : Screen
    data object CallUs : Screen
    data class Ask(val mode: AskMode, val scenario: LoginScenario?, val nonce: Long = System.nanoTime()) : Screen
    data class IncidentStatus(val incident: Incident) : Screen
    data object LoggedIn : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val config = AppConfig(this)
        val api = SupportApi(config)
        setContent { LloydsTheme { App(config, api) } }
    }
}

@Composable
private fun App(config: AppConfig, api: SupportApi) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stack = remember { mutableStateListOf<Screen>(Screen.Login) }
    val login = remember { LoginState() }

    fun push(s: Screen) = stack.add(s)
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun toLogin() { stack.clear(); stack.add(Screen.Login) }

    /** Opens the dialer pre-filled; never places the call itself. */
    fun dial(number: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No phone app available ($number)", Toast.LENGTH_LONG).show()
        }
    }

    BackHandler(enabled = stack.size > 1) {
        if (stack.last() is Screen.IncidentStatus) toLogin() else pop()
    }

    // Surface sets the default text/icon color (white on dark) for every screen.
    Surface(Modifier.fillMaxSize(), color = Brand.Background, contentColor = Color.White) {
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),  // status bar, nav bar, cutout, keyboard
    ) {
        when (val screen = stack.last()) {
            Screen.Login -> LoginScreen(
                state = login,
                config = config,
                onLogin = {
                    login.attempts++
                    if (login.scenario == LoginScenario.SUCCESS) push(Screen.LoggedIn)
                    else push(Screen.LoginError(login.scenario))
                },
                onAskLloyds = { push(Screen.Ask(AskMode.LOGIN, null)) },
                onCallUs = { push(Screen.CallUs) },
            )

            is Screen.LoginError -> LoginErrorScreen(
                scenario = screen.scenario,
                onTryAgain = { pop() },
                onClose = { pop() },
                onAskLloyds = { push(Screen.Ask(AskMode.LOGIN, screen.scenario)) },
            )

            Screen.CallUs -> CallUsScreen(
                onClose = { pop() },
                onAskLloyds = { push(Screen.Ask(AskMode.GENERAL, null)) },
                onDial = ::dial,
            )

            is Screen.Ask -> {
                val controller = remember(screen) {
                    AskLloydsController(
                        api = api,
                        scope = scope,
                        mode = screen.mode,
                        scenario = screen.scenario,
                        login = login,
                        device = DeviceContext.detect(context),
                        onIncidentRaised = { incident -> push(Screen.IncidentStatus(incident)) },
                    )
                }
                AskLloydsScreen(
                    c = controller,
                    onClose = { pop() },
                    onDialColleague = { dial(ContactNumbers.LOGIN_SUPPORT) },
                    onBackToLogin = { toLogin() },
                )
            }

            is Screen.IncidentStatus -> IncidentScreen(screen.incident, api, onDone = { toLogin() })

            Screen.LoggedIn -> LoggedInScreen(onLogOut = { login.password = ""; toLogin() })
        }
    }
    }
}
