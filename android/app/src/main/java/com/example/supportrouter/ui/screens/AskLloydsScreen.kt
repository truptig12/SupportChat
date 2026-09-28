package com.example.supportrouter.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.supportrouter.R
import com.example.supportrouter.journey.Guidance
import com.example.supportrouter.journey.StepIcon
import com.example.supportrouter.ui.AskAvatar
import com.example.supportrouter.ui.BetaChip
import com.example.supportrouter.ui.BorderCard
import com.example.supportrouter.ui.HSpace
import com.example.supportrouter.ui.NavRow
import com.example.supportrouter.ui.PrimaryButton
import com.example.supportrouter.ui.SecondaryButton
import com.example.supportrouter.ui.Tag
import com.example.supportrouter.ui.VSpace
import com.example.supportrouter.ui.theme.Brand

/**
 * Ask Lloyds (image 3 steps 3-4, image 2 steps 2-6): conversation with
 * service status, issue picker, guided suggestions, outcome check and the
 * editable support summary.
 */
@Composable
fun AskLloydsScreen(
    c: AskLloydsController,
    onClose: () -> Unit,
    onDialColleague: () -> Unit,
    onBackToLogin: () -> Unit,
) {
    LaunchedEffect(c) { c.start() }
    val listState = rememberLazyListState()
    LaunchedEffect(c.items.size, c.busy) {
        val last = c.items.size - 1 + if (c.busy) 1 else 0
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Column(Modifier.fillMaxSize()) {
        // Top bar
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            AskAvatar(32.dp)
            HSpace(10.dp)
            Text("Ask Lloyds", style = MaterialTheme.typography.titleMedium)
            HSpace(8.dp)
            BetaChip()
            Box(Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
        }
        HorizontalDivider(color = Brand.OutlineSoft)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(c.items, key = { it.id }) { item ->
                when (item) {
                    is ChatItem.Bot -> BotBubble(item.text)
                    is ChatItem.User -> UserBubble(item.text)
                    is ChatItem.Status -> StatusCard(item) { t, m -> c.info = t to m }
                    is ChatItem.IssuePicker -> IssuePickerCard(item, c)
                    is ChatItem.Suggestion -> SuggestionCard(item, c)
                    is ChatItem.Outcome -> OutcomeCard(item, c)
                    is ChatItem.Options -> OptionsCard(item, c, onDialColleague)
                    is ChatItem.Summary -> SummaryCard(item, c, onDialColleague)
                    is ChatItem.Resolved -> PrimaryButton("Back to login", onBackToLogin)
                }
            }
            if (c.busy) item(key = "typing") { BotBubble("…") }
        }

        InputBar(c)
    }

    c.info?.let { (title, message) ->
        AlertDialog(
            onDismissRequest = { c.info = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { c.info = null }) { Text("OK") } },
        )
    }
}

// ---------------------------------------------------------------------------
// Bubbles
// ---------------------------------------------------------------------------

@Composable
private fun BotBubble(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        AskAvatar(28.dp)
        HSpace(8.dp)
        Text(
            text,
            color = Brand.OnBubble,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(4.dp, 14.dp, 14.dp, 14.dp))
                .background(Brand.Bubble)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text,
            color = Color.White,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(14.dp, 4.dp, 14.dp, 14.dp))
                .background(Brand.UserBubble)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Service status (mock ServiceNow)
// ---------------------------------------------------------------------------

@Composable
private fun StatusCard(item: ChatItem.Status, showInfo: (String, String) -> Unit) {
    val s = item.status
    when {
        item.loading -> BorderCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Brand.GreenBright)
                HSpace(10.dp)
                Text("Checking for known service issues…", color = Brand.TextSecondary)
            }
        }
        s == null -> BorderCard {
            Text("Couldn't check service status right now.", color = Brand.TextSecondary)
        }
        s.isKnownIssue -> BorderCard(bg = Brand.AmberSoft, border = Brand.Amber) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, tint = Brand.Amber)
                HSpace(10.dp)
                Column {
                    Text(s.title, color = Brand.OnAmber, fontWeight = FontWeight.SemiBold)
                    Text(s.message, color = Brand.OnAmber, style = MaterialTheme.typography.bodySmall)
                }
            }
            Link("View details", Brand.OnAmber) {
                showInfo("Known issue", "${s.message}\n\nAffects: ${s.affects.joinToString().ifEmpty { "-" }}\nChecked: ${s.checkedAt}")
            }
        }
        else -> BorderCard(bg = Brand.Mint, border = Brand.MintBorder) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, tint = Brand.Green)
                HSpace(10.dp)
                Column {
                    Text(s.title, color = Brand.OnMint, fontWeight = FontWeight.SemiBold)
                    Text(s.message, color = Brand.OnMint, style = MaterialTheme.typography.bodySmall)
                }
            }
            Link("View service status", Brand.Green) {
                showInfo("Service status", "${s.title}: ${s.message}\n\nChecked: ${s.checkedAt}")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Issue picker + auto-detected context (image 2, step 3)
// ---------------------------------------------------------------------------

@Composable
private fun IssuePickerCard(item: ChatItem.IssuePicker, c: AskLloydsController) {
    BorderCard {
        Guidance.all.forEach { issue ->
            val selected = item.selected == issue.id
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, if (selected) Brand.GreenBright else Brand.OutlineSoft, RoundedCornerShape(8.dp))
                    .clickable(enabled = item.active) { c.chooseIssue(item.id, issue) }
                    .heightIn(min = 52.dp)
                    .padding(end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selected,
                    onClick = if (item.active) ({ c.chooseIssue(item.id, issue) }) else null,
                    enabled = item.active || selected,
                    colors = RadioButtonDefaults.colors(selectedColor = Brand.GreenBright),
                )
                Text(issue.label, color = if (item.active || selected) Color.White else Brand.Outline)
            }
        }
        VSpace(4.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Additional context (auto-detected)", style = MaterialTheme.typography.labelLarge)
            HSpace(6.dp)
            Icon(Icons.Filled.Info, null, tint = Brand.TextSecondary, modifier = Modifier.size(16.dp))
        }
        ContextLine(R.drawable.ic_description, "App version: ${c.device.appVersion}")
        ContextLine(R.drawable.ic_smartphone, "Device: ${c.device.device}")
        ContextLine(R.drawable.ic_wifi, "Network: ${c.device.network}")
        ContextLine(R.drawable.ic_schedule, "Login attempts: ${c.loginAttempts}")
        Text(
            "We use safe, non-sensitive information to suggest the most relevant next steps.",
            color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ContextLine(icon: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, tint = Brand.TextSecondary, modifier = Modifier.size(16.dp))
        HSpace(8.dp)
        Text(text, color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------------------------------------------------------------------
// Suggested next step (image 2, step 4)
// ---------------------------------------------------------------------------

@Composable
private fun SuggestionCard(item: ChatItem.Suggestion, c: AskLloydsController) {
    val step = item.issue.steps[item.index]
    var showWhy by rememberSaveable(item.id) { mutableStateOf(false) }
    BorderCard(border = if (item.active) Brand.Ai.copy(alpha = 0.6f) else Brand.OutlineSoft) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_sparkle), null, tint = Brand.Ai, modifier = Modifier.size(18.dp))
            HSpace(6.dp)
            Text("Suggested next step", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Tag("Guided step ${item.index + 1} of ${item.issue.steps.size}", Brand.Ai, Brand.AiSoft)
        }
        Row(verticalAlignment = Alignment.Top) {
            val (vector, res) = stepIcon(step.icon)
            if (vector != null) Icon(vector, null, tint = Color.White, modifier = Modifier.size(28.dp))
            else Icon(painterResource(res!!), null, tint = Color.White, modifier = Modifier.size(28.dp))
            HSpace(12.dp)
            Column {
                Text(step.title, style = MaterialTheme.typography.titleMedium)
                Text(step.body, color = Brand.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(Modifier.clickable { showWhy = !showWhy }, verticalAlignment = Alignment.CenterVertically) {
            Text("Why this suggestion?", color = Brand.Ai, style = MaterialTheme.typography.bodySmall)
            Icon(if (showWhy) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null,
                tint = Brand.Ai, modifier = Modifier.size(18.dp))
        }
        if (showWhy) Text(step.why, color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall)
        if (item.active) {
            PrimaryButton("I've tried this", { c.triedIt(item) })
            SecondaryButton("Choose a different option", { c.differentOption(item) })
        }
        Text(
            "This is a suggested option, not a confirmed diagnosis. You're always in control and can speak to someone at any time.",
            color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun stepIcon(icon: StepIcon): Pair<ImageVector?, Int?> = when (icon) {
    StepIcon.CLOCK -> null to R.drawable.ic_schedule
    StepIcon.PHONE -> null to R.drawable.ic_smartphone
    StepIcon.WIFI -> null to R.drawable.ic_wifi
    StepIcon.LOCK, StepIcon.KEY -> Icons.Filled.Lock to null
}

// ---------------------------------------------------------------------------
// Did it work? (image 2, step 5)
// ---------------------------------------------------------------------------

@Composable
private fun OutcomeCard(item: ChatItem.Outcome, c: AskLloydsController) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutcomeOption(
            title = "Yes - I can now log in",
            subtitle = "Great! You can continue using the app.",
            selected = item.answer == true,
            enabled = item.answer == null,
        ) { c.answerOutcome(item, true) }
        OutcomeOption(
            title = "No - I still need help",
            subtitle = "We'll prepare a summary for our support team.",
            selected = item.answer == false,
            enabled = item.answer == null,
        ) { c.answerOutcome(item, false) }
    }
}

@Composable
private fun OutcomeOption(title: String, subtitle: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.5.dp, if (selected) Brand.GreenBright else Brand.Outline, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = if (enabled) onClick else null,
            colors = RadioButtonDefaults.colors(selectedColor = Brand.GreenBright))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled || selected) Color.White else Brand.Outline)
            Text(subtitle, color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------------------
// Buttons from the Support Router (/v1/chat)
// ---------------------------------------------------------------------------

@Composable
private fun OptionsCard(item: ChatItem.Options, c: AskLloydsController, onDialColleague: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item.options.forEach { opt ->
            NavRow(opt.label, onClick = { c.tapOption(item, opt, onDialColleague) }, enabled = item.active)
        }
        if (item.active) {
            Link("Still need help? Raise a support request", Brand.GreenBright) { c.prepareSummary(apology = false) }
        }
    }
}

// ---------------------------------------------------------------------------
// Support summary (image 2 step 6, image 3 step 4)
// ---------------------------------------------------------------------------

@Composable
private fun SummaryCard(item: ChatItem.Summary, c: AskLloydsController, onDialColleague: () -> Unit) {
    val values = remember(item.id) { mutableStateListOf(*item.fields.map { it.value }.toTypedArray()) }
    var editing by rememberSaveable(item.id) { mutableStateOf(false) }
    var addMessage by rememberSaveable(item.id) { mutableStateOf(false) }
    var message by rememberSaveable(item.id) { mutableStateOf("") }
    var confirmed by rememberSaveable(item.id) { mutableStateOf(false) }

    BorderCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your support summary", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { editing = !editing }, enabled = !item.submitting) {
                Icon(Icons.Filled.Edit, null, tint = Brand.GreenBright, modifier = Modifier.size(16.dp))
                HSpace(4.dp)
                Text(if (editing) "Done" else "Edit", color = Brand.GreenBright)
            }
        }
        item.fields.forEachIndexed { i, field ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(field.label, color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    when (field.source) {
                        Source.YOU -> Tag(field.source.label, Brand.OnMint, Brand.Mint)
                        Source.SYSTEM -> Tag(field.source.label, Color.White, Brand.SurfaceHigh)
                        Source.SUGGESTED -> Tag(field.source.label, Brand.Ai, Brand.AiSoft)
                    }
                }
                if (editing) {
                    OutlinedTextField(
                        value = values[i],
                        onValueChange = { values[i] = it.take(200) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Brand.GreenBright),
                    )
                } else {
                    Text(values[i], style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (addMessage) {
            OutlinedTextField(
                value = message,
                onValueChange = { message = it.take(1000) },
                label = { Text("Message (optional)") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Brand.GreenBright),
            )
        } else {
            Link("Add a message (optional)", Brand.GreenBright) { addMessage = true }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { confirmed = !confirmed }) {
            Checkbox(confirmed, { confirmed = it }, colors = CheckboxDefaults.colors(checkedColor = Brand.Green))
            Text("I confirm this information is correct and agree to raise an incident.", style = MaterialTheme.typography.bodySmall)
        }
        item.error?.let { Text(it, color = Brand.Error, style = MaterialTheme.typography.bodySmall) }
        if (item.submitting) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), color = Brand.GreenBright)
            }
        } else {
            PrimaryButton("Raise incident", enabled = confirmed, onClick = {
                c.raiseIncident(item.id, item.fields.mapIndexed { i, f -> f.copy(value = values[i]) }, message)
            })
        }
        SecondaryButton("Speak to a colleague instead", onDialColleague, enabled = !item.submitting)
        Text(
            "We'll create a support reference and share your summary with our team, so you don't have to repeat your story.",
            color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ---------------------------------------------------------------------------
// Input
// ---------------------------------------------------------------------------

@Composable
private fun InputBar(c: AskLloydsController) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(c.focusInputRequests) {
        if (c.focusInputRequests > 0) runCatching { focus.requestFocus() }
    }
    val send = { c.sendText(text); text = "" }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(2000) },
            placeholder = { Text("Type your question…") },
            modifier = Modifier.weight(1f).focusRequester(focus),
            shape = RoundedCornerShape(24.dp),
            singleLine = true,  // Enter = send
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) send() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Brand.GreenBright, unfocusedBorderColor = Brand.Outline,
            ),
        )
        IconButton(onClick = { if (text.isNotBlank()) send() }, enabled = !c.busy && text.isNotBlank()) {
            Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (text.isNotBlank()) Brand.GreenBright else Brand.Outline)
        }
    }
}

@Composable
private fun Link(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 2.dp),
    )
}
