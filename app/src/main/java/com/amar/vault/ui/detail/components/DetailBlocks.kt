package com.amar.vault.ui.detail.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.bounceClick
import com.amar.vault.ui.detail.models.*
import com.amar.vault.ui.renderengine.models.BadgeInfo

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetadataChipGroup(chips: List<BadgeInfo>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        chips.forEach { chip ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(chip.style.accent.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (chip.icon != null) Text(text = chip.icon, fontSize = 12.sp)
                Text(text = chip.text, color = chip.style.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** The single prominent primary action button. */
@Composable
fun ActionGrid(actions: List<ActionPresentation>, theme: DetailTheme, onAction: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        actions.forEach { action ->
            val bg = if (action.isPrimary) theme.accent else Color.White
            val fg = if (action.isPrimary) Color.White else theme.foreground
            val border = if (action.isPrimary) Color.Transparent else Color.LightGray.copy(alpha = 0.6f)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(bg)
                    .border(1.dp, border, RoundedCornerShape(26.dp))
                    .bounceClick { onAction(action.id) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(text = action.icon, fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text(text = action.label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Horizontal row of secondary quick actions (share, copy link, open app, favorite…). */
@Composable
fun QuickActionRow(
    actions: List<ActionPresentation>,
    theme: DetailTheme,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        actions.forEach { action ->
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .border(1.dp, Color.LightGray.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    .bounceClick { onAction(action.id) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(action.icon, fontSize = 18.sp)
                Spacer(Modifier.height(4.dp))
                Text(action.label, fontSize = 11.sp, color = theme.foreground.copy(alpha = 0.75f), fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

@Composable
fun TitleBlock(presentation: TitleBlockPresentation, theme: DetailTheme, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = presentation.title,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            color = theme.foreground,
            lineHeight = 34.sp
        )
        if (presentation.author != null || presentation.domain != null) {
            Spacer(modifier = Modifier.height(8.dp))
            val sub = listOfNotNull(presentation.author, presentation.domain).joinToString(" • ")
            Text(text = sub, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = theme.foreground.copy(alpha = 0.6f))
        }
    }
}

@Composable
fun SectionCard(section: DetailSection, theme: DetailTheme, modifier: Modifier = Modifier) {
    if (section.isEmpty) return
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .animateContentSize()
            .then(if (section.isExpandable) Modifier.bounceClick { expanded = !expanded } else Modifier)
            .padding(20.dp)
    ) {
        Text(
            text = section.title.uppercase(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = theme.foreground.copy(alpha = 0.45f),
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = section.content,
            fontSize = 15.sp,
            color = theme.foreground,
            lineHeight = 22.sp,
            maxLines = if (section.isExpandable && !expanded) 4 else Int.MAX_VALUE
        )
        if (section.isExpandable) {
            Text(
                text = if (expanded) "Show less" else "Show more",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = theme.accent,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
fun DangerZone(presentation: DangerZonePresentation, onAction: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        presentation.actions.forEach { action ->
            val fg = if (action.isDestructive) Color(0xFFD64541) else Color(0xFF3D3530)
            val bg = if (action.isDestructive) Color(0xFFFDECEB) else Color.White
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(bg)
                    .bounceClick { onAction(action.id) }
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = action.icon, fontSize = 18.sp)
                Spacer(Modifier.width(16.dp))
                Text(text = action.label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
