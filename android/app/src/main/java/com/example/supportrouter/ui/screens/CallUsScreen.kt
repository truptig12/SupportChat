package com.example.supportrouter.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.supportrouter.R
import com.example.supportrouter.journey.ContactNumbers
import com.example.supportrouter.ui.AskAvatar
import com.example.supportrouter.ui.BetaChip
import com.example.supportrouter.ui.HSpace
import com.example.supportrouter.ui.SectionLabel
import com.example.supportrouter.ui.VSpace
import com.example.supportrouter.ui.theme.Brand

/**
 * "Call us (AI-first)" (image 1, proposed). Ask Lloyds sits on top; the
 * existing call options stay underneath so calling is still one tap away.
 */
@Composable
fun CallUsScreen(onClose: () -> Unit, onAskLloyds: () -> Unit, onDial: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Call us", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
        }
        VSpace(12.dp)

        AskLloydsCard(onAskLloyds)

        // "or" divider
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f), color = Brand.OutlineSoft)
            Text("or", color = Brand.TextSecondary, modifier = Modifier.padding(horizontal = 12.dp))
            HorizontalDivider(Modifier.weight(1f), color = Brand.OutlineSoft)
        }

        SectionLabel("What's your call about?")
        VSpace(10.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CallTile("Your accounts", R.drawable.ic_folder, Modifier.weight(1f)) { onDial(ContactNumbers.YOUR_ACCOUNTS) }
            CallTile("Internet Banking", R.drawable.ic_computer, Modifier.weight(1f)) { onDial(ContactNumbers.INTERNET_BANKING) }
        }
        VSpace(10.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Brand.Outline, RoundedCornerShape(8.dp))
                .clickable { onDial(ContactNumbers.OTHER_QUERY) }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_help), null, tint = Color.White)
            HSpace(12.dp)
            Text("Other banking query", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.White)
        }

        VSpace(22.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, null, tint = Color.White, modifier = Modifier.size(18.dp))
            HSpace(8.dp)
            SectionLabel("Security and travel emergencies")
        }
        VSpace(6.dp)
        listOf(
            "Suspected fraud" to ContactNumbers.SUSPECTED_FRAUD,
            "Lost or stolen cards" to ContactNumbers.LOST_OR_STOLEN_CARDS,
            "Emergency cash abroad" to ContactNumbers.EMERGENCY_CASH_ABROAD,
            "Medical assistance abroad" to ContactNumbers.MEDICAL_ASSISTANCE_ABROAD,
        ).forEach { (label, number) ->
            Row(
                Modifier.fillMaxWidth().clickable { onDial(number) }.padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.White)
            }
            HorizontalDivider(color = Brand.OutlineSoft)
        }
        VSpace(16.dp)
    }
}

@Composable
private fun AskLloydsCard(onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Brand.Mint)
            .border(1.dp, Brand.MintBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AskAvatar(40.dp)
            HSpace(12.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ask Lloyds", color = Brand.OnMint, style = MaterialTheme.typography.titleMedium)
                    HSpace(8.dp)
                    BetaChip(onLight = true)
                }
                Text("Get instant help and step-by-step guidance for your issue.",
                    color = Brand.OnMint, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Brand.OnMint)
        }
        Column(
            Modifier.fillMaxWidth().background(Color(0xFFD5EEDE)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Why try Ask Lloyds first?", color = Brand.OnMint, style = MaterialTheme.typography.labelLarge)
            listOf(
                "Helps you resolve common issues quickly",
                "Checks for any known service problems",
                "Prepares a support request if you still need help",
            ).forEach { CheckLine(it) }
        }
    }
}

@Composable
private fun CheckLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(Brand.Green), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(12.dp))
        }
        HSpace(8.dp)
        Text(text, color = Brand.OnMint, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CallTile(label: String, icon: Int, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .height(96.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Brand.Outline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(icon), null, tint = Color.White)
        VSpace(8.dp)
        Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}
