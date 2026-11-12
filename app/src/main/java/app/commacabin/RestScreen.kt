package app.commacabin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.commacabin.audio.Announce
import app.commacabin.audio.AudioUi
import app.commacabin.audio.SoundLevel
import app.commacabin.audio.SoundMode
import app.commacabin.lighting.LightItemUi
import app.commacabin.lighting.LightProp
import app.commacabin.lighting.LightResult
import app.commacabin.lighting.LightingUi
import app.commacabin.rest.Block
import app.commacabin.rest.EndKind
import app.commacabin.rest.PREP_MARGIN_MS
import app.commacabin.rest.RestState
import app.commacabin.rest.RestTime
import app.commacabin.rest.Screen
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Background = Color(0xFF0B0F14)
private val Card = Color(0xFF161D26)
private val TextMain = Color(0xFFF2F4F7)
private val TextSub = Color(0xFFAAB4C0)
private val Accent = Color(0xFF7FCFC4)
private val OnAccent = Color(0xFF0B1A18)
private val Warn = Color(0xFFF2B48C)
private val Dimmed = Color(0xFF39414B)
private val ButtonHeight = 96.dp

class RestActions(
    val dismissOnboarding: () -> Unit,
    val adjustPickup: (Long) -> Unit,
    val start: () -> Unit,
    val touch: () -> Unit,
    val endByUser: () -> Unit,
    val restAgain: () -> Unit,
    val setSound: (SoundMode) -> Unit,
    val setLevel: (SoundLevel) -> Unit,
    val setLighting: (Boolean) -> Unit,
    val restoreLighting: () -> Unit,
    val discardLighting: () -> Unit,
    val deferLighting: () -> Unit,
    val showLighting: () -> Unit,
    /** Debug builds only; never shown while resting. */
    val openDevTools: (() -> Unit)?,
)

@Composable
fun RestScreen(state: RestState, audio: AudioUi, lighting: LightingUi, actions: RestActions) {
    Box(
        Modifier
            .fillMaxSize()
            .background(if (state.screen == Screen.RESTING && state.dimmed) Color.Black else Background)
            // Watches every press without consuming it, so a touch both counts as activity and wakes a dim screen.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) actions.touch()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 1100.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 48.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            when (state.screen) {
                Screen.ONBOARDING -> Onboarding(actions)
                Screen.READY -> Ready(state, lighting, actions)
                Screen.RESTING -> if (state.dimmed) DimmedRest(state) else Resting(state, audio, lighting, actions)
                Screen.ENDED -> Ended(state, audio, lighting, actions)
            }
        }
        val dev = actions.openDevTools
        if (dev != null && (state.screen == Screen.READY || state.screen == Screen.ENDED)) {
            TextButton(onClick = dev, modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp)) {
                Text("개발 진단", color = TextSub, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun Onboarding(actions: RestActions) {
    Text("쉼표 캐빈", color = TextMain, fontSize = 48.sp, fontWeight = FontWeight.Bold)
    Text("주차한 차 안에서 잠깐 조용히 쉬어요.", color = TextMain, fontSize = 28.sp)
    Column(Modifier.background(Card, RoundedCornerShape(20.dp)).padding(32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Bullet("픽업 시각을 정하면 그 2분 전에 휴식이 끝나요.")
        Bullet("조수석 문이 열리거나, 기어가 P에서 바뀌거나, 앱 화면을 벗어나면 바로 끝나요.")
        Bullet("휴식 소리를 켜거나 조용히 쉴 수 있어요. 휴식 소리는 끝나기 1분 전부터 점점 작아져요.")
        Bullet("제시간에 끝나면 안내음과 음성으로 알려 드려요. 통화 중처럼 다른 소리가 있거나 소리를 낼 수 없으면 화면으로만 알려요. 깨워 드리는 것을 보장하지는 않아요.")
        Bullet("[무드등 조정]을 켜 두면 쉬는 동안 무드등을 낮추고 따뜻한 색으로 바꿔요. 휴식이 끝나면 원래대로 돌려요. 무드등이 꺼져 있으면 건드리지 않고, 쉬는 중에 직접 바꾼 값은 그대로 둬요. 준비 화면에서 끌 수 있어요.")
    }
    PrimaryButton("확인했어요", enabled = true, onClick = actions.dismissOnboarding)
}

@Composable
private fun Ready(state: RestState, lighting: LightingUi, actions: RestActions) {
    Text("휴식 준비", color = TextMain, fontSize = 44.sp, fontWeight = FontWeight.Bold)
    Column(Modifier.background(Card, RoundedCornerShape(20.dp)).padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("픽업 시각", color = TextSub, fontSize = 24.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            SecondaryButton("−5분") { actions.adjustPickup(-5) }
            Text(
                clock(state.pickupMs, state.nowMs, state.zone),
                Modifier.weight(1f),
                color = TextMain,
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
            )
            SecondaryButton("+5분") { actions.adjustPickup(5) }
        }
        if (state.pickupMs <= state.nowMs) Text("이미 지난 시각이에요", color = Warn, fontSize = 24.sp)
        val restMinutes = state.restMs / 60_000
        Text(
            "휴식 종료 ${clock(RestTime.restEnd(state.pickupMs), state.nowMs, state.zone)} (픽업 ${PREP_MARGIN_MS / 60_000}분 전)" +
                if (restMinutes > 0) " · ${restMinutes}분 쉴 수 있어요" else "",
            color = TextMain,
            fontSize = 26.sp,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Check(state.parkingBlock, "주차됨 (P, 주행 제한 없음)")
        Check(state.doorBlock, "조수석 문 닫힘")
        Check(state.timeBlock, "쉴 시간 충분")
    }
    SoundChoice(state, actions)
    LightingChoice(state, actions)
    LightingRecordsOrEntry(lighting, actions)
    lighting.problem?.let { Text(it, color = Warn, fontSize = 22.sp) }
    PrimaryButton("쉬기 시작", enabled = state.canStart, onClick = actions.start)
    state.notice?.let { Text(it, color = Warn, fontSize = 24.sp) }
}

/** [무드등 조정] on/off. Only remembered here; the vehicle is read and written only after a rest really starts. */
@Composable
private fun LightingChoice(state: RestState, actions: RestActions) {
    Column(Modifier.background(Card, RoundedCornerShape(20.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("무드등 조정", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Toggle("켬", state.lighting) { actions.setLighting(true) }
            Toggle("끔", !state.lighting) { actions.setLighting(false) }
        }
        Text("무드등을 낮추고 따뜻하게 맞춰요. 끝나면 원래대로 돌려요.", color = TextSub, fontSize = 20.sp)
    }
}

/** The records card, or after [나중에] a small entry that unfolds it again (display only). */
@Composable
private fun LightingRecordsOrEntry(lighting: LightingUi, actions: RestActions) {
    if (lighting.open.isEmpty()) return
    if (!lighting.deferred) return LightingRecords(lighting, actions)
    TextButton(onClick = actions.showLighting, modifier = Modifier.heightIn(min = 64.dp)) {
        Text("남은 무드등 기록 보기 (${lighting.open.size}개)", color = Warn, fontSize = 22.sp)
    }
}

/** Records not cleaned up: [원래대로] [기록 지우기] (with a confirmation) [나중에]. */
@Composable
private fun LightingRecords(lighting: LightingUi, actions: RestActions) {
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(20.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("원래대로 돌리지 않은 무드등이 있어요", color = Warn, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        LightLines(lighting.open)
        Text("이 항목은 정리할 때까지 다음 휴식에서도 바꾸지 않아요.", color = TextSub, fontSize = 20.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SmallButton("원래대로", enabled = lighting.restoreBlock == null, primary = true, onClick = actions.restoreLighting)
            SmallButton("기록 지우기", enabled = lighting.discardBlock == null) { confirm = true }
            SmallButton("나중에", enabled = true, onClick = actions.deferLighting)
        }
        (lighting.restoreBlock ?: lighting.discardBlock)?.let { Text(it, color = TextSub, fontSize = 20.sp) }
        lighting.notice?.let { Text(it, color = TextMain, fontSize = 20.sp) }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("기록만 지울까요?") },
            text = { Text("기록을 지워도 무드등은 되돌아가지 않아요. 지금 상태 그대로 남고, 필요하면 차량 설정에서 직접 맞춰야 해요.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    actions.discardLighting()
                }) { Text("기록 지우기") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun LightLines(items: List<LightItemUi>) {
    items.forEach { item ->
        val sameProp = items.filter { it.prop == item.prop && it.area.isNotEmpty() }
        val label = item.prop.label + if (sameProp.size > 1) " ${sameProp.indexOf(item) + 1}" else ""
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TextMain, fontSize = 22.sp)
            item.original?.let { original ->
                if (item.prop == LightProp.BRIGHTNESS) {
                    Text("원래 $original", color = TextSub, fontSize = 20.sp)
                } else {
                    Text("원래 색", color = TextSub, fontSize = 20.sp)
                    Box(Modifier.size(28.dp).background(Color(0xFF000000 or (original.toLong() and 0xFFFFFF)), RoundedCornerShape(6.dp)))
                }
            }
            Text(
                item.text,
                color = when (item.result) {
                    LightResult.RESTORED, LightResult.APPLIED -> Accent
                    LightResult.SKIPPED, LightResult.WORKING -> TextSub
                    else -> Warn
                },
                fontSize = 20.sp,
            )
        }
    }
}

@Composable
private fun SmallButton(label: String, enabled: Boolean, primary: Boolean = false, onClick: () -> Unit) {
    val modifier = Modifier.heightIn(min = 80.dp).widthIn(min = 160.dp)
    if (primary) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent, disabledContainerColor = Card, disabledContentColor = TextSub)) {
            Text(label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier, colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMain)) {
            Text(label, fontSize = 22.sp)
        }
    }
}

/** Rest sound on/quiet and its level. Quiet only turns the rest sound off; the end announcement still plays when possible. */
@Composable
private fun SoundChoice(state: RestState, actions: RestActions) {
    Column(Modifier.background(Card, RoundedCornerShape(20.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Toggle("휴식 소리 켬", state.sound == SoundMode.ON) { actions.setSound(SoundMode.ON) }
            Toggle("조용히", state.sound == SoundMode.QUIET) { actions.setSound(SoundMode.QUIET) }
            Spacer(Modifier.weight(1f))
            Text("크기", color = TextSub, fontSize = 22.sp)
            for ((level, label) in listOf(SoundLevel.LOW to "작게", SoundLevel.MEDIUM to "보통", SoundLevel.HIGH to "크게")) {
                Toggle(label, state.level == level) { actions.setLevel(level) }
            }
        }
        Text("조용히는 휴식 소리만 꺼요. 제시간에 끝나면 안내음과 음성은 소리를 낼 수 있을 때 나와요.", color = TextSub, fontSize = 20.sp)
    }
}

@Composable
private fun Resting(state: RestState, audio: AudioUi, lighting: LightingUi, actions: RestActions) {
    Text(
        if (state.finalMinute) "곧 끝나요" else "쉬는 중",
        color = if (state.finalMinute) Warn else Accent,
        fontSize = 32.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Text(remaining(state.remainingMs), color = TextMain, fontSize = 140.sp, fontWeight = FontWeight.Bold)
    state.session?.let { Text("${clock(it.endWallMs, state.nowMs, state.zone)}에 끝나요", color = TextMain, fontSize = 30.sp) }
    if (audio.pausedByOther) Text("다른 소리 때문에 휴식 소리를 잠시 멈췄어요", color = Warn, fontSize = 24.sp)
    lighting.restLine?.let { Text(it, color = TextSub, fontSize = 24.sp) }
    SoundChoice(state, actions)
    state.notice?.let { Text(it, color = Warn, fontSize = 24.sp) }
    Spacer(Modifier.height(8.dp))
    SecondaryButton("휴식 끝내기", wide = true, onClick = actions.endByUser)
    Text("10초 동안 누르지 않으면 화면이 어두워져요. 화면을 누르면 다시 밝아져요.", color = TextSub, fontSize = 22.sp)
}

@Composable
private fun DimmedRest(state: RestState) {
    Box(Modifier.fillMaxWidth().heightIn(min = 600.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.finalMinute) Text("곧 끝나요", color = Dimmed, fontSize = 32.sp)
            Text(remaining(state.remainingMs), color = Dimmed, fontSize = 140.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Ended(state: RestState, audio: AudioUi, lighting: LightingUi, actions: RestActions) {
    val ending = state.ending ?: return
    Text(
        when (ending.reason.kind) {
            EndKind.NORMAL -> "휴식이 끝났어요"
            EndKind.USER -> "휴식을 끝냈어요"
            EndKind.STOPPED -> "휴식을 멈췄어요"
        },
        color = if (ending.reason.kind == EndKind.STOPPED) Warn else Accent,
        fontSize = 48.sp,
        fontWeight = FontWeight.Bold,
    )
    Text(ending.reason.text, color = TextMain, fontSize = 30.sp)
    Text("종료 시각 ${clock(ending.atWallMs, state.nowMs, state.zone)}", color = TextSub, fontSize = 26.sp)
    if (ending.reason.kind == EndKind.NORMAL) {
        when (audio.announce) {
            Announce.RUNNING -> "안내음과 음성으로 알리는 중이에요"
            Announce.VOICE -> "안내음과 음성으로 알렸어요"
            Announce.CHIME_ONLY -> "안내음으로 알렸어요. 음성 안내는 하지 못했어요"
            Announce.SCREEN_ONLY -> "다른 소리가 있거나 소리를 낼 수 없어 화면으로만 알려요"
            Announce.NONE -> null
        }?.let { Text(it, color = TextSub, fontSize = 24.sp) }
    }
    if (lighting.items.isNotEmpty() || lighting.restLine != null) {
        Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(20.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("무드등", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            if (lighting.items.isEmpty()) lighting.restLine?.let { Text(it, color = TextSub, fontSize = 20.sp) }
            LightLines(lighting.items)
            if (lighting.working) Text("무드등을 처리하는 중이에요. 휴식 소리와는 따로 진행돼요.", color = TextSub, fontSize = 20.sp)
        }
    }
    if (!lighting.working) LightingRecordsOrEntry(lighting, actions)
    if (lighting.earlier.isNotEmpty()) {
        Text("이전 무드등 기록", color = TextSub, fontSize = 22.sp)
        LightLines(lighting.earlier)
    }
    lighting.problem?.let { Text(it, color = Warn, fontSize = 22.sp) }
    PrimaryButton("다시 쉬기", enabled = true, onClick = actions.restAgain)
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("•", color = Accent, fontSize = 26.sp)
        Text(text, color = TextMain, fontSize = 26.sp)
    }
}

/** ✓ when [block] is null; otherwise the reason in words. */
@Composable
private fun Check(block: Block?, okText: String) {
    val checking = block == Block.DOOR_CHECKING
    Row(
        Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(horizontal = 28.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when {
                block == null -> "✓"
                checking -> "…"
                else -> "✕"
            },
            color = when {
                block == null -> Accent
                checking -> TextSub
                else -> Warn
            },
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(block?.text ?: okText, color = TextMain, fontSize = 26.sp)
    }
}

@Composable
private fun Toggle(label: String, selected: Boolean, onClick: () -> Unit) {
    val modifier = Modifier.heightIn(min = 80.dp).widthIn(min = 120.dp)
    if (selected) {
        Button(onClick = onClick, modifier = modifier, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)) {
            Text(label, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMain)) {
            Text(label, fontSize = 24.sp)
        }
    }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
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
private fun SecondaryButton(label: String, wide: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = (if (wide) Modifier.fillMaxWidth() else Modifier.widthIn(min = 180.dp)).heightIn(min = ButtonHeight),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMain),
    ) {
        Text(label, fontSize = 28.sp)
    }
}

private val HourMinute = DateTimeFormatter.ofPattern("HH:mm")

/** "19:30", or with the day when it is not today ("내일 00:10"). */
private fun clock(atMs: Long, nowMs: Long, zone: ZoneId): String {
    val time = Instant.ofEpochMilli(atMs).atZone(zone)
    val day = when (RestTime.dayOffset(atMs, nowMs, zone)) {
        0L -> ""
        1L -> "내일 "
        -1L -> "어제 "
        else -> "${time.monthValue}월 ${time.dayOfMonth}일 "
    }
    return day + time.format(HourMinute)
}

/** Rounded up to the second so the display never shows 0:00 while the rest is still running. */
private fun remaining(ms: Long): String {
    val total = (ms.coerceAtLeast(0) + 999) / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}
