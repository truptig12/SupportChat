package com.example.supportrouter.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.supportrouter.R
import com.example.supportrouter.ui.theme.Brand

/**
 * LOGO PLACEHOLDER. The official brand mark isn't bundled. Replace the
 * dashed box with Image(painterResource(R.drawable.brand_logo), ...) once
 * the approved asset is added to res/drawable.
 */
@Composable
fun BrandLogo(modifier: Modifier = Modifier, height: Dp = 36.dp) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(height)
                .border(1.dp, Brand.Outline, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("LOGO", color = Brand.Outline, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text("LLOYDS", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
    }
}

/** Green square with a chat icon: the Ask Lloyds avatar. */
@Composable
fun AskAvatar(size: Dp = 36.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            .background(Brand.Green),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_chat), null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
fun BetaChip(onLight: Boolean = false) {
    val c = if (onLight) Brand.OnMint else Color.White
    Text(
        "Beta",
        color = c,
        fontSize = 11.sp,
        modifier = Modifier
            .border(1.dp, c.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 1.dp),
    )
}

/** Small source label used in the support summary and suggestions. */
@Composable
fun Tag(text: String, fg: Color, bg: Color) {
    Text(
        text,
        color = fg,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Brand.Green, contentColor = Color.White),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color.White),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Outlined dark row with optional leading icon, subtitle and a chevron. */
@Composable
fun NavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @DrawableRes iconRes: Int? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, if (enabled) Brand.Outline else Brand.OutlineSoft, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) Color.White else Brand.Outline
        when {
            iconRes != null -> Icon(painterResource(iconRes), null, tint = tint, modifier = Modifier.size(24.dp))
            icon != null -> Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        }
        if (iconRes != null || icon != null) Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = tint, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = tint)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = Color.White, style = MaterialTheme.typography.titleMedium)
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))

/** Rounded card with a light border, as used throughout the mockups. */
@Composable
fun BorderCard(
    modifier: Modifier = Modifier,
    bg: Color = Brand.Surface,
    border: Color = Brand.OutlineSoft,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}
