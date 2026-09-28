package com.example.supportrouter.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.supportrouter.ApiException
import com.example.supportrouter.Incident
import com.example.supportrouter.R
import com.example.supportrouter.SupportApi
import com.example.supportrouter.ui.BorderCard
import com.example.supportrouter.ui.HSpace
import com.example.supportrouter.ui.PrimaryButton
import com.example.supportrouter.ui.SecondaryButton
import com.example.supportrouter.ui.VSpace
import com.example.supportrouter.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "You're in good hands: track your progress" (image 2, step 7). */
@Composable
fun IncidentScreen(initial: Incident, api: SupportApi, onDone: () -> Unit) {
    var incident by remember { mutableStateOf(initial) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("You're in good hands", style = MaterialTheme.typography.titleLarge)
                Text("Track your progress", color = Brand.TextSecondary)
            }
            IconButton(onClick = onDone) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
        }
        VSpace(20.dp)
        BorderCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(Brand.GreenDark), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_headset), null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
                VSpace(10.dp)
                Text("Incident raised", style = MaterialTheme.typography.titleMedium)
                Text("Reference: ${incident.reference}", color = Brand.GreenBright, style = MaterialTheme.typography.titleMedium)
                VSpace(4.dp)
                Text("We've sent your details to our support team.", color = Brand.TextSecondary, textAlign = TextAlign.Center)
            }
            VSpace(8.dp)
            incident.timeline.forEachIndexed { i, step ->
                TimelineRow(step, isLast = i == incident.timeline.lastIndex)
            }
        }
        VSpace(20.dp)
        error?.let { Text(it, color = Brand.Error, style = MaterialTheme.typography.bodySmall); VSpace(8.dp) }
        if (loading) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = Brand.GreenBright)
            }
        } else {
            PrimaryButton("View my support status", onClick = {
                loading = true
                error = null
                scope.launch {
                    try {
                        incident = withContext(Dispatchers.IO) { api.incident(incident.reference) }
                    } catch (e: ApiException) {
                        error = "Couldn't refresh status (${e.message})."
                    } finally {
                        loading = false
                    }
                }
            })
        }
        VSpace(12.dp)
        SecondaryButton("Back to login", onDone)
    }
}

@Composable
private fun TimelineRow(step: Incident.TimelineStep, isLast: Boolean) {
    Row {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(24.dp)) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .then(
                        if (step.done) Modifier.background(Brand.GreenBright)
                        else Modifier.border(2.dp, Brand.Outline, CircleShape)
                    ),
            )
            if (!isLast) Box(Modifier.width(2.dp).height(32.dp).background(if (step.done) Brand.GreenBright else Brand.OutlineSoft))
        }
        HSpace(10.dp)
        Column(Modifier.padding(bottom = 8.dp)) {
            Text(step.label, color = if (step.done) Color.White else Brand.TextSecondary, style = MaterialTheme.typography.bodyLarge)
            step.at?.let { Text(friendlyTime(it), color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** "Just now" within a minute, otherwise HH:mm (java.time needs API 26; minSdk is 24). */
private fun friendlyTime(iso: String): String = runCatching {
    val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(iso)!!
    val ageMs = System.currentTimeMillis() - date.time
    if (ageMs < 60_000) "Just now" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(date.time))
}.getOrDefault(iso)
