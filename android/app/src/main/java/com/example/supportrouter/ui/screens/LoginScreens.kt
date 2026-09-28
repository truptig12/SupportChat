package com.example.supportrouter.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.supportrouter.AppConfig
import com.example.supportrouter.BuildConfig
import com.example.supportrouter.R
import com.example.supportrouter.journey.LoginScenario
import com.example.supportrouter.ui.AskAvatar
import com.example.supportrouter.ui.BrandLogo
import com.example.supportrouter.ui.HSpace
import com.example.supportrouter.ui.PrimaryButton
import com.example.supportrouter.ui.SecondaryButton
import com.example.supportrouter.ui.VSpace
import com.example.supportrouter.ui.theme.Brand

/** State that outlives the login screen (typed user ID, attempts, chosen scenario). */
class LoginState {
    var userId by mutableStateOf("")
    var password by mutableStateOf("")
    var rememberMe by mutableStateOf(true)
    var business by mutableStateOf(false)
    var attempts by mutableStateOf(0)
    var scenario by mutableStateOf(LoginScenario.TECHNICAL_ERROR)
}

// ---------------------------------------------------------------------------
// 1. Login (image 3, step 1)
// ---------------------------------------------------------------------------

@Composable
fun LoginScreen(
    state: LoginState,
    config: AppConfig,
    onLogin: () -> Unit,
    onAskLloyds: () -> Unit,
    onCallUs: () -> Unit,
) {
    var showPassword by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandLogo(Modifier.weight(1f))
            TextButton(onClick = onCallUs) {
                Icon(Icons.Filled.Call, null, tint = Color.White, modifier = Modifier.size(18.dp))
                HSpace(6.dp)
                Text("Call us", color = Color.White)
            }
        }
        VSpace(28.dp)
        Text("Welcome back", style = MaterialTheme.typography.headlineMedium)
        Text("Log in to your account", color = Brand.TextSecondary)
        VSpace(20.dp)

        // Personal / Business tabs
        Row(Modifier.fillMaxWidth()) {
            listOf(false to "Personal", true to "Business").forEach { (isBusiness, label) ->
                val selected = state.business == isBusiness
                Column(
                    Modifier.weight(1f).clickable { state.business = isBusiness },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(label, color = if (selected) Color.White else Brand.TextSecondary,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(vertical = 10.dp))
                    Box(Modifier.fillMaxWidth().height(2.dp)
                        .background(if (selected) Brand.GreenBright else Brand.OutlineSoft))
                }
            }
        }
        VSpace(20.dp)

        FieldLabel("User ID")
        OutlinedTextField(
            value = state.userId,
            onValueChange = { state.userId = it.take(64) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors(),
        )
        VSpace(14.dp)
        FieldLabel("Password")
        OutlinedTextField(
            value = state.password,
            onValueChange = { state.password = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors(),
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        painterResource(if (showPassword) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                        contentDescription = if (showPassword) "Hide password" else "Show password",
                        tint = Brand.TextSecondary,
                    )
                }
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.rememberMe,
                onCheckedChange = { state.rememberMe = it },
                colors = CheckboxDefaults.colors(checkedColor = Brand.Green),
            )
            Text("Remember me")
        }
        VSpace(8.dp)
        PrimaryButton("Log in", onClick = onLogin)
        VSpace(12.dp)
        LinkText("Forgotten your user ID?", onAskLloyds)
        LinkText("Forgotten your password?", onAskLloyds)
        VSpace(20.dp)

        // "Need help?" card
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, Brand.Outline, RoundedCornerShape(12.dp))
                .clickable(onClick = onAskLloyds)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_schedule), null, tint = Color.White)
            HSpace(12.dp)
            Column(Modifier.weight(1f)) {
                Text("Need help?", style = MaterialTheme.typography.titleMedium)
                Text("Try our quick help or Ask Lloyds", color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.White)
        }

        if (BuildConfig.DEBUG) {
            VSpace(24.dp)
            DemoControls(state, config)
        }
        VSpace(16.dp)
    }
}

/** DEBUG builds only: choose what "Log in" does, and point the app at a server. */
@Composable
private fun DemoControls(state: LoginState, config: AppConfig) {
    var showServer by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Brand.Amber.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Text("DEMO CONTROLS (debug build only)", color = Brand.Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text("Log in result:", color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LoginScenario.entries.forEach { s ->
                FilterChip(
                    selected = state.scenario == s,
                    onClick = { state.scenario = s },
                    label = { Text(s.pickerLabel) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Brand.Green, selectedLabelColor = Color.White,
                        labelColor = Brand.TextSecondary,
                    ),
                )
            }
        }
        TextButton(onClick = { showServer = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text("Server settings: ${config.baseUrl}", color = Brand.Amber, fontSize = 12.sp)
        }
    }
    if (showServer) ServerSettingsDialog(config) { showServer = false }
}

@Composable
private fun ServerSettingsDialog(config: AppConfig, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf(config.baseUrl) }
    var key by remember { mutableStateOf(config.appKey) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server settings") },
        text = {
            Column {
                OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, singleLine = true)
                VSpace(8.dp)
                OutlinedTextField(key, { key = it }, label = { Text("App API key (X-API-Key)") }, singleLine = true)
                VSpace(8.dp)
                Text(
                    "USB: run `adb reverse tcp:8000 tcp:8000` and use http://localhost:8000. " +
                        "Emulator: http://10.0.2.2:8000.",
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { config.baseUrl = url; config.appKey = key; onDismiss() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------------------
// 2. Login error with AI option (image 3, step 2)
// ---------------------------------------------------------------------------

@Composable
fun LoginErrorScreen(
    scenario: LoginScenario,
    onTryAgain: () -> Unit,
    onClose: () -> Unit,
    onAskLloyds: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandLogo(Modifier.fillMaxWidth())
        VSpace(48.dp)
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(Brand.Error),
            contentAlignment = Alignment.Center,
        ) { Text("!", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold) }
        VSpace(20.dp)
        Text(scenario.errorTitle, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        VSpace(8.dp)
        Text(scenario.errorBody, color = Brand.TextSecondary, textAlign = TextAlign.Center)
        VSpace(32.dp)
        PrimaryButton("Try again", onTryAgain)
        VSpace(12.dp)
        SecondaryButton("Close", onClose)
        VSpace(16.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(1.5.dp, Brand.GreenBright, RoundedCornerShape(12.dp))
                .clickable(onClick = onAskLloyds)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AskAvatar()
            HSpace(12.dp)
            Column(Modifier.weight(1f)) {
                Text("Ask Lloyds", style = MaterialTheme.typography.titleMedium)
                Text("Get instant help with this issue", color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.White)
        }
        VSpace(40.dp)
        Text("Reference code: ${scenario.referenceCode}", color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------------------------------------------------------------------
// Login success placeholder ("Yes - I can now log in" / Success scenario)
// ---------------------------------------------------------------------------

@Composable
fun LoggedInScreen(onLogOut: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("✓", color = Brand.GreenBright, fontSize = 48.sp)
        VSpace(12.dp)
        Text("You're logged in", style = MaterialTheme.typography.titleLarge)
        Text("(Demo placeholder: the banking app would open here.)", color = Brand.TextSecondary, textAlign = TextAlign.Center)
        VSpace(24.dp)
        SecondaryButton("Log out", onLogOut)
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = Brand.TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = Brand.GreenBright,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 6.dp),
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Brand.GreenBright,
    unfocusedBorderColor = Brand.Outline,
    cursorColor = Brand.GreenBright,
)
