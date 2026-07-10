package com.amar.vault.dev

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.ui.theme.CharcoalSoft
import com.amar.vault.ui.theme.Cream
import com.amar.vault.ui.theme.CreamDark
import com.amar.vault.ui.theme.CreamLight
import com.amar.vault.ui.theme.WarmBrown
import com.amar.vault.ui.theme.WarmBrownDark

/**
 * Shared, theme-consistent building blocks for the Developer Tools screens.
 *
 * Presentation only — none of these touch app data or behaviour. They exist so every
 * Developer Tools section looks like the rest of Amar Vault (Cream / WarmBrown palette)
 * without each screen re-implementing scaffolding.
 */

@Composable
fun DevScaffold(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Cream)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(44.dp))
        TextButton(
            onClick = onBack,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.height(34.dp)
        ) {
            Text("← Back", color = WarmBrown, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = CharcoalSoft,
            letterSpacing = (-0.5).sp
        )
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 13.sp, color = WarmBrownDark)
        }
        Spacer(Modifier.height(18.dp))
        val bodyModifier = if (scrollable) {
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
        } else {
            Modifier.fillMaxWidth().weight(1f)
        }
        Column(modifier = bodyModifier, content = content)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun DevSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = WarmBrown,
        letterSpacing = 1.4.sp,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
}

@Composable
fun DevCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CreamLight),
        border = BorderStroke(1.dp, CreamDark)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), content = content)
    }
}

/** A tappable card row — used for hub entries and pickers. */
@Composable
fun DevNavCard(title: String, subtitle: String, emoji: String = "", onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CreamLight),
        border = BorderStroke(1.dp, CreamDark),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (emoji.isNotEmpty()) {
                Text(emoji, fontSize = 22.sp)
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = CharcoalSoft)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 12.sp, color = WarmBrownDark)
            }
            Text("›", fontSize = 22.sp, color = WarmBrown)
        }
    }
}

/** Primary action button. */
@Composable
fun DevButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = WarmBrownDark,
            contentColor = Cream,
            disabledContainerColor = CreamDark,
            disabledContentColor = WarmBrown
        )
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** Secondary/outline action button. */
@Composable
fun DevOutlineButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, WarmBrown),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = WarmBrownDark)
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** A label:value row for diagnostics. */
@Composable
fun DevKeyValue(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(key, fontSize = 13.sp, color = WarmBrownDark, modifier = Modifier.weight(1f))
        Text(
            value,
            fontSize = 13.sp,
            color = CharcoalSoft,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

/** Monospace, horizontally-scrollable text block for raw output (OCR text, traces, JSON). */
@Composable
fun DevMono(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Cream, RoundedCornerShape(8.dp))
            .border(BorderStroke(1.dp, CreamDark), RoundedCornerShape(8.dp))
            .padding(12.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        Text(
            text = text.ifBlank { "—" },
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = CharcoalSoft
        )
    }
}
