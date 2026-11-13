package app.commacabin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Product screen look: the existing dark navy and mint, used sparingly. System font, native Material3 parts.
internal val Background = Color(0xFF0B0F14)
internal val Card = Color(0xFF161D26)
internal val Line = Color(0xFF2A3440)
internal val TextMain = Color(0xFFF2F4F7)
internal val TextSub = Color(0xFFAAB4C0)
internal val Accent = Color(0xFF7FCFC4)
/** Selected choice: a quiet mint tint, so a chosen option never looks like the screen's main button. */
internal val AccentSoft = Color(0xFF1D3634)
internal val OnAccent = Color(0xFF0B1A18)
internal val Warn = Color(0xFFF2B48C)
internal val Dimmed = Color(0xFF39414B)
internal val ButtonHeight = 96.dp
internal val ControlHeight = 72.dp

/** A rounded surface holding one group. */
@Composable
internal fun Panel(modifier: Modifier = Modifier, spacing: Dp = 16.dp, padding: Dp = 28.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(Card, RoundedCornerShape(20.dp)).padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** A thin line between parts of one panel. */
@Composable
internal fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

/** "쉼표 캐빈 · 휴식 준비", with the debug-only [개발 진단] at the end of the same line (never while resting). */
@Composable
internal fun BrandRow(section: String, openDevTools: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("쉼표 캐빈", color = Accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("  ·  $section", Modifier.weight(1f), color = TextSub, fontSize = 24.sp)
        openDevTools?.let { dev ->
            TextButton(onClick = dev, modifier = Modifier.heightIn(min = 64.dp)) { Text("개발 진단", color = TextSub, fontSize = 18.sp) }
        }
    }
}

/** A label (and an optional short note) on the left, its control on the right. */
@Composable
internal fun SettingRow(label: String, note: String? = null, control: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = ControlHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            note?.let { Text(it, color = TextSub, fontSize = 21.sp) }
        }
        control()
    }
}

/**
 * An on/off choice: the whole row is the touch target (one click handler, the switch only shows the state), and the
 * label, note and checked state are read together as one switch.
 */
@Composable
internal fun SwitchRow(label: String, note: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = ControlHeight).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            note?.let { Text(it, color = TextSub, fontSize = 21.sp) }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = OnAccent,
                checkedTrackColor = Accent,
                checkedBorderColor = Accent,
                uncheckedThumbColor = TextSub,
                uncheckedTrackColor = Background,
                uncheckedBorderColor = Line,
            ),
        )
    }
}

/**
 * One choice of several (native segmented buttons: a check mark and the radio-button semantics tell the selected
 * one apart, not the color alone). Disabled = shown but not usable now; the selection is kept.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Choice(options: List<Pair<String, Boolean>>, enabled: Boolean = true, optionWidth: Dp = 124.dp, onSelect: (Int) -> Unit) {
    val colors = SegmentedButtonDefaults.colors(
        activeContainerColor = AccentSoft,
        activeContentColor = TextMain,
        activeBorderColor = Accent,
        inactiveContainerColor = Color.Transparent,
        inactiveContentColor = TextSub,
        inactiveBorderColor = Line,
        disabledActiveContainerColor = Card,
        disabledActiveContentColor = TextSub.copy(alpha = 0.6f),
        disabledActiveBorderColor = Line,
        disabledInactiveContainerColor = Color.Transparent,
        disabledInactiveContentColor = TextSub.copy(alpha = 0.45f),
        disabledInactiveBorderColor = Line,
    )
    SingleChoiceSegmentedButtonRow {
        options.forEachIndexed { i, (label, selected) ->
            SegmentedButton(
                selected = selected,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                modifier = Modifier.heightIn(min = ControlHeight).widthIn(min = optionWidth),
                enabled = enabled,
                colors = colors,
            ) {
                Text(label, fontSize = 22.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** A short state word in a thin outline, next to a longer sentence that says the same in full. */
@Composable
internal fun StatusTag(text: String, color: Color) {
    Text(
        text,
        Modifier.border(BorderStroke(1.dp, color), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 2.dp),
        color = color,
        fontSize = 20.sp,
    )
}

/** The screen's one main action. */
@Composable
internal fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = ButtonHeight),
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent, disabledContainerColor = Card, disabledContentColor = TextSub),
    ) {
        Text(label, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun SecondaryButton(label: String, modifier: Modifier = Modifier, minWidth: Dp = 180.dp, minHeight: Dp = ButtonHeight, fontSize: Int = 28, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.widthIn(min = minWidth).heightIn(min = minHeight), colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMain), border = BorderStroke(1.dp, Line)) {
        Text(label, fontSize = fontSize.sp)
    }
}

@Composable
internal fun SmallButton(label: String, enabled: Boolean, primary: Boolean = false, minWidth: Dp = 160.dp, fontSize: Int = 22, onClick: () -> Unit) {
    val modifier = Modifier.heightIn(min = 80.dp).widthIn(min = minWidth)
    if (primary) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent, disabledContainerColor = Card, disabledContentColor = TextSub)) {
            Text(label, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier, colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMain), border = BorderStroke(1.dp, Line)) {
            Text(label, fontSize = fontSize.sp)
        }
    }
}
