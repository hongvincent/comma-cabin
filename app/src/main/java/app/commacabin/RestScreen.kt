package app.commacabin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.commacabin.audio.Announce
import app.commacabin.audio.AudioUi
import app.commacabin.audio.SoundLevel
import app.commacabin.audio.SoundMode
import app.commacabin.climate.ClimateItemUi
import app.commacabin.climate.ClimateProp
import app.commacabin.climate.ClimateResult
import app.commacabin.climate.ClimateUi
import app.commacabin.climate.ClimateValues
import app.commacabin.climate.TempRange
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
    val setFan: (Boolean) -> Unit,
    val setTemp: (Boolean) -> Unit,
    val setTempC: (Float) -> Unit,
    val restoreClimate: () -> Unit,
    val discardClimate: () -> Unit,
    val deferClimate: () -> Unit,
    val showClimate: () -> Unit,
    /** [다시 확인]: read-only check of the vehicle's values for the records left. */
    val checkLighting: () -> Unit,
    val checkClimate: () -> Unit,
    /** Debug builds only; never shown while resting. */
    val openDevTools: (() -> Unit)?,
)

@Composable
fun RestScreen(state: RestState, audio: AudioUi, lighting: LightingUi, climate: ClimateUi, actions: RestActions) {
    // Light product colors for everything inside (dialogs included); the debug tools keep the Activity's theme.
    MaterialTheme(colorScheme = CabinColors) { CabinScreen(state, audio, lighting, climate, actions) }
}

@Composable
private fun CabinScreen(state: RestState, audio: AudioUi, lighting: LightingUi, climate: ClimateUi, actions: RestActions) {
    val dim = state.screen == Screen.RESTING && state.dimmed
    Box(
        Modifier
            .fillMaxSize()
            .background(if (dim) Color.Black else Background)
            // Watches every press without consuming it, so a touch both counts as activity and wakes a dim screen.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) actions.touch()
                    }
                }
            },
    ) {
        if (dim) {
            // No control at all: the first touch only wakes the screen.
            DimmedRest(state)
        } else {
            // A new screen starts at its top, never at the scroll position of the screen before.
            key(state.screen) {
                val scroll = rememberScrollState()
                Column(Modifier.fillMaxSize()) {
                    // The brand line stays put above the scrolling content, so scrolling never hides where you are.
                    TopBar(state, actions)
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        val wide = maxWidth >= WIDE_MIN
                        // Only the ready and end screens use two columns; reading text stays at a comfortable width.
                        val twoColumns = wide && (state.screen == Screen.READY || state.screen == Screen.ENDED)
                        val resting = state.screen == Screen.RESTING
                        Column(
                            Modifier
                                .widthIn(max = if (twoColumns) 1600.dp else 1100.dp)
                                .fillMaxWidth()
                                .verticalScroll(scroll)
                                // Resting: the time sits in the middle of the free height, the sound controls below it.
                                .heightIn(min = if (resting) maxHeight else 0.dp)
                                .padding(horizontal = 40.dp, vertical = 24.dp),
                            verticalArrangement = if (resting) Arrangement.SpaceBetween else Arrangement.spacedBy(24.dp),
                        ) {
                            when (state.screen) {
                                Screen.ONBOARDING -> Onboarding(wide)
                                Screen.READY -> Ready(state, lighting, climate, actions, twoColumns)
                                Screen.RESTING -> Resting(state, audio, lighting, climate, actions, wide)
                                Screen.ENDED -> Ended(state, audio, lighting, climate, actions, twoColumns)
                            }
                        }
                    }
                    BottomBar(state, actions)
                }
            }
        }
    }
}

/** Two columns side by side when the screen is wide, one below the other when it is not. */
private val WIDE_MIN = 1000.dp

@Composable
private fun Columns(wide: Boolean, left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit) {
    if (wide) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp), content = left)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp), content = right)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            left()
            right()
        }
    }
}

@Composable
private fun TopBar(state: RestState, actions: RestActions) {
    val section = when (state.screen) {
        Screen.ONBOARDING -> "처음 안내"
        Screen.READY -> "휴식 준비"
        Screen.RESTING -> if (state.finalMinute) "곧 끝나요" else "쉬는 중"
        Screen.ENDED -> "휴식 끝"
    }
    Box(Modifier.fillMaxWidth().background(Background).padding(horizontal = 40.dp, vertical = 8.dp)) {
        BrandRow(section, if (state.screen == Screen.RESTING) null else actions.openDevTools)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

/** The screen's main button, always visible below the scrolling content. Not shown on the dim rest screen. */
@Composable
private fun BottomBar(state: RestState, actions: RestActions) {
    Box(Modifier.fillMaxWidth().background(Background), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line).align(Alignment.TopCenter))
        Column(
            Modifier.widthIn(max = 1100.dp).fillMaxWidth().padding(horizontal = 40.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (state.screen) {
                Screen.ONBOARDING -> PrimaryButton("확인했어요", enabled = true, onClick = actions.dismissOnboarding)
                Screen.READY -> {
                    // The same canStart as before; when it is false the first reason is readable right here.
                    if (!state.canStart) (state.parkingBlock ?: state.doorBlock ?: state.timeBlock)?.let { Text("✕  ${it.text}", color = Warn, fontSize = 24.sp) }
                    state.notice?.let { Text(it, color = Warn, fontSize = 24.sp) }
                    PrimaryButton("쉬기 시작", enabled = state.canStart, onClick = actions.start)
                }
                Screen.RESTING -> {
                    Text("10초 동안 누르지 않으면 화면이 어두워져요. 화면을 누르면 다시 밝아져요.", color = TextSub, fontSize = 20.sp)
                    SecondaryButton("휴식 끝내기", modifier = Modifier.fillMaxWidth(), onClick = actions.endByUser)
                }
                Screen.ENDED -> PrimaryButton("다시 쉬기", enabled = true, onClick = actions.restAgain)
            }
        }
    }
}

@Composable
private fun Onboarding(wide: Boolean) {
    Text("주차한 차 안에서 잠깐 조용히 쉬어요.", color = TextMain, fontSize = 36.sp, fontWeight = FontWeight.Bold)
    Columns(
        wide,
        left = {
            Panel {
                Text("휴식", color = Accent, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Bullet("픽업 시각을 정하면 그 2분 전에 휴식이 끝나요.")
                Bullet("조수석 문이 열리거나, 기어가 P에서 바뀌거나, 앱 화면을 벗어나면 바로 끝나요.")
            }
            Panel {
                Text("소리", color = Accent, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Bullet("휴식 소리를 켜거나 조용히 쉴 수 있어요. 휴식 소리는 끝나기 1분 전부터 작아져요.")
                Bullet("제시간에 끝나면 안내음과 음성으로 알려요. 다른 소리가 있거나 소리를 낼 수 없으면 화면으로만 알려요. 깨워 드리는 것을 보장하지는 않아요.")
            }
        },
        right = {
            Panel {
                Text("차량 설정", color = Accent, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Bullet("켜 둔 항목만 쉬는 동안 차량 설정을 바꾸고 끝나면 원래대로 돌려요: 무드등은 낮추고 따뜻하게, 바람은 한 단계 약하게, 운전석은 고른 온도로.")
                Bullet("꺼져 있는 무드등과 공조는 켜지 않아요. 공조 연동(Sync)이 켜져 있으면 온도는 바꾸지 않아요. 쉬는 동안 직접 바꾼 값은 그대로 둬요.")
                Bullet(SEAT_NOTE)
            }
        },
    )
}

/** The seat requirement is kept, but the app cannot move the seat yet (STEP 7: the public position does not follow the move). */
private const val SEAT_NOTE = "시트 자동 조절은 아직 지원하지 않아요. 차량에서 직접 편한 자세를 맞춰 주세요."

@Composable
private fun Ready(state: RestState, lighting: LightingUi, climate: ClimateUi, actions: RestActions, wide: Boolean) {
    Columns(
        wide,
        left = {
            RestTimeHero(state, actions)
            Conditions(state)
            SeatNotice()
            LightingRecordsOrEntry(lighting, actions)
            ClimateRecordsOrEntry(climate, actions)
            lighting.problem?.let { Text(it, color = Warn, fontSize = 22.sp) }
            climate.problem?.let { Text(it, color = Warn, fontSize = 22.sp) }
        },
        right = {
            SoundSettings(state, actions)
            EnvironmentSettings(state, climate, actions)
        },
    )
}

/** What the user gets: minutes of rest and when it ends. The pickup time and ±5 are the control below it. */
@Composable
private fun RestTimeHero(state: RestState, actions: RestActions) {
    val restMinutes = (state.restMs / 60_000).coerceAtLeast(0)
    val blocked = state.timeBlock != null
    Panel(spacing = 8.dp, padding = 32.dp) {
        Text("쉴 수 있는 시간", color = TextSub, fontSize = 24.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$restMinutes", Modifier.alignByBaseline(), color = if (blocked) Warn else TextMain, fontSize = 132.sp, fontWeight = FontWeight.Bold)
            Text("분", Modifier.alignByBaseline().padding(start = 8.dp), color = if (blocked) Warn else TextMain, fontSize = 48.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "${clock(RestTime.restEnd(state.pickupMs), state.nowMs, state.zone)}에 종료 · 픽업 ${PREP_MARGIN_MS / 60_000}분 전",
            color = TextMain,
            fontSize = 28.sp,
        )
        Spacer(Modifier.height(12.dp))
        Divider()
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text("픽업 시각", color = TextSub, fontSize = 22.sp)
                Text(clock(state.pickupMs, state.nowMs, state.zone), color = TextMain, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            }
            SecondaryButton("−5분", minWidth = 136.dp, minHeight = 80.dp, fontSize = 26) { actions.adjustPickup(-5) }
            SecondaryButton("+5분", minWidth = 136.dp, minHeight = 80.dp, fontSize = 26) { actions.adjustPickup(5) }
        }
        if (state.pickupMs <= state.nowMs) Text("이미 지난 시각이에요", color = Warn, fontSize = 24.sp)
    }
}

/** Parked, door and time in one group. A failed line says why in words, not only in color. */
@Composable
private fun Conditions(state: RestState) {
    Panel(spacing = 6.dp, padding = 24.dp) {
        Text("시작 조건", color = TextSub, fontSize = 22.sp)
        Check(state.parkingBlock, "주차됨 (P, 주행 제한 없음)")
        Check(state.doorBlock, "조수석 문 닫힘")
        Check(state.timeBlock, "쉴 시간 충분")
    }
}

/** ✓ when [block] is null; otherwise the reason in words. */
@Composable
private fun Check(block: Block?, okText: String) {
    val checking = block == Block.DOOR_CHECKING
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when {
                block == null -> "✓"
                checking -> "…"
                else -> "✕"
            },
            Modifier.width(32.dp),
            color = when {
                block == null -> Accent
                checking -> TextSub
                else -> Warn
            },
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(block?.text ?: okText, color = if (block == null || checking) TextMain else Warn, fontSize = 24.sp)
    }
}

/** Rest sound: on or quiet. The level only matters with the sound on, so it is hidden while quiet (and kept). */
@Composable
private fun SoundSettings(state: RestState, actions: RestActions) {
    val quiet = state.sound == SoundMode.QUIET
    Panel(spacing = 8.dp) {
        SettingRow("휴식 소리") {
            Choice(listOf("켬" to !quiet, "조용히" to quiet)) { i -> actions.setSound(if (i == 0) SoundMode.ON else SoundMode.QUIET) }
        }
        if (!quiet) SettingRow("소리 크기") { LevelChoice(state, actions) }
        Text("조용히는 휴식 소리만 꺼요. 종료 안내음·음성은 소리를 낼 수 있을 때 나와요.", color = TextSub, fontSize = 22.sp)
    }
}

/**
 * The cabin choices for the next rest. They are only remembered here: the vehicle is read and written only after a
 * rest really starts, so a switch is a choice, not the vehicle's current state.
 */
@Composable
private fun EnvironmentSettings(state: RestState, climate: ClimateUi, actions: RestActions) {
    Panel(spacing = 4.dp) {
        Text("쉬는 환경", color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("휴식을 시작하면 켜 둔 항목을 조정하고, 끝나면 원래대로 돌려요.", color = TextSub, fontSize = 22.sp)
        Spacer(Modifier.height(4.dp))
        SwitchRow("무드등 조정", "낮고 따뜻하게", state.lighting, actions.setLighting)
        SwitchRow("바람 줄이기", "한 단계 약하게", state.fan, actions.setFan)
        SwitchRow("운전석 온도 맞추기", "고른 온도로", state.temp, actions.setTemp)
        if (state.temp) TempStepper(state, climate, actions)
        Spacer(Modifier.height(4.dp))
        Text("꺼진 무드등·공조는 켜지 않고, 직접 바꾼 설정은 유지해요.", color = TextSub, fontSize = 22.sp)
        Text("연동(Sync) 중에는 온도를 바꾸지 않아요.", color = TextSub, fontSize = 22.sp)
    }
}

@Composable
private fun LevelChoice(state: RestState, actions: RestActions) {
    val levels = listOf(SoundLevel.LOW to "작게", SoundLevel.MEDIUM to "보통", SoundLevel.HIGH to "크게")
    Choice(levels.map { (level, label) -> label to (state.level == level) }, optionWidth = 112.dp) { i -> actions.setLevel(levels[i].first) }
}

/** The ± buttons follow the vehicle's real range and step; until they are read, the value cannot be changed here. */
@Composable
private fun TempStepper(state: RestState, climate: ClimateUi, actions: RestActions) {
    val range = climate.tempRange
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        SmallButton("−", enabled = range != null, minWidth = 96.dp, fontSize = 34) { range?.let { actions.setTempC(stepTemp(state.tempC, -1, it)) } }
        Text(
            "${ClimateValues.show(ClimateProp.TEMP, state.tempC)}°C",
            Modifier.widthIn(min = 150.dp),
            color = TextMain,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        SmallButton("+", enabled = range != null, minWidth = 96.dp, fontSize = 34) { range?.let { actions.setTempC(stepTemp(state.tempC, 1, it)) } }
    }
    when {
        range != null -> Unit
        climate.tempRangeLoading -> Text("차량의 운전석 온도 범위를 확인하고 있어요", color = TextSub, fontSize = 20.sp)
        climate.tempRangeProblem != null -> Text("${climate.tempRangeProblem}. 온도는 바꿀 수 없지만 휴식은 할 수 있어요", color = Warn, fontSize = 20.sp)
        else -> Text("차량의 운전석 온도 범위를 아직 확인하지 못했어요", color = TextSub, fontSize = 20.sp)
    }
    if (range != null && (state.tempC !in range.min..range.max || !ClimateValues.onGrid(state.tempC, range.min, range.increment))) {
        Text(
            "지금 고른 온도는 이 차에서 고를 수 없어요(${ClimateValues.show(ClimateProp.TEMP, range.min)}~${ClimateValues.show(ClimateProp.TEMP, range.max)}°C, ${ClimateValues.show(ClimateProp.TEMP, range.increment)}°C 간격). −/+로 다시 골라 주세요.",
            color = Warn, fontSize = 20.sp,
        )
    }
}

/** The next °C on the vehicle's grid (any step, 0.5 or 0.25 alike), chosen by the user's press. */
private fun stepTemp(value: Float, dir: Int, range: TempRange): Float {
    val k = kotlin.math.round((value + dir * range.increment - range.min) / range.increment)
    return (range.min + k * range.increment).coerceIn(range.min, range.max)
}

/** The seat cannot be adjusted by the app yet: a fact, with no toggle and no seat state. */
@Composable
private fun SeatNotice() {
    Panel(spacing = 8.dp, padding = 24.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("편한 자세 (시트)", Modifier.weight(1f), color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            StatusTag("앱에서 조절 안 함", TextSub)
        }
        Text(SEAT_NOTE, color = TextSub, fontSize = 22.sp)
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
    Panel(spacing = 14.dp, padding = 24.dp) {
        Text("원래대로 돌리지 않은 무드등이 있어요", color = Warn, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        LightLines(lighting.open)
        CheckRow(lighting.checking, lighting.open.any { it.checked != null }, enabled = !lighting.checking && !lighting.working, onCheck = actions.checkLighting)
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
        ConfirmDialog(
            title = "기록만 지울까요?",
            body = "기록을 지워도 무드등은 되돌아가지 않아요. 지금 상태 그대로 남고, 필요하면 차량 설정에서 직접 맞춰야 해요.",
            confirmLabel = "기록 지우기",
            onDismiss = { confirm = false },
        ) {
            confirm = false
            actions.discardLighting()
        }
    }
}

/** A short word for each result, so applied, unchanged, in progress, restored and unknown never look alike. */
private fun lightTag(r: LightResult): Pair<String, Color> = when (r) {
    LightResult.RESTORED -> "원래대로" to Accent
    LightResult.APPLIED -> "맞춤" to Accent
    LightResult.SKIPPED -> "바꾸지 않음" to TextSub
    LightResult.WORKING -> "처리 중" to TextSub
    LightResult.USER_CHANGED -> "직접 바꾼 값 유지" to TextSub
    LightResult.UNKNOWN -> "결과 모름" to Warn
    LightResult.HELD, LightResult.FAILED -> "확인 필요" to Warn
    LightResult.LOCKED -> "직접 확인 필요" to Warn
}

private fun climateTag(r: ClimateResult): Pair<String, Color> = when (r) {
    ClimateResult.RESTORED -> "원래대로" to Accent
    ClimateResult.APPLIED -> "맞춤" to Accent
    ClimateResult.SKIPPED -> "바꾸지 않음" to TextSub
    ClimateResult.WORKING -> "처리 중" to TextSub
    ClimateResult.EXTERNAL -> "직접 바꾼 값 유지" to TextSub
    ClimateResult.UNKNOWN -> "결과 모름" to Warn
    ClimateResult.HELD, ClimateResult.FAILED -> "확인 필요" to Warn
    ClimateResult.LOCKED -> "직접 확인 필요" to Warn
}

@Composable
private fun LightLines(items: List<LightItemUi>) {
    items.forEach { item ->
        // Several areas of one property are told apart by easy seat names, never by SDK keys.
        val areas = items.filter { it.prop == item.prop && it.area.isNotEmpty() }.map { it.area }.distinct()
        val label = item.prop.label + if (areas.size > 1) " · ${seatName(item.area)}" else ""
        val (tag, tagColor) = lightTag(item.result)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                item.original?.let { original ->
                    if (item.prop == LightProp.BRIGHTNESS) {
                        Text("원래 $original", color = TextSub, fontSize = 20.sp)
                    } else {
                        Text("원래 색", color = TextSub, fontSize = 20.sp)
                        Box(
                            Modifier.size(26.dp)
                                .background(Color(0xFF000000 or (original.toLong() and 0xFFFFFF)), RoundedCornerShape(6.dp))
                                .border(1.dp, Outline, RoundedCornerShape(6.dp)),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                StatusTag(tag, tagColor)
            }
            Text(item.text, color = if (tagColor == Warn) Warn else TextSub, fontSize = 20.sp)
            item.checked?.let { Text(it, color = TextMain, fontSize = 20.sp) }
        }
    }
}

/**
 * What was read from the vehicle for the records, and [다시 확인] (read only). A finding is the value at the
 * moment it was read; [원래대로] reads again right before it writes.
 */
@Composable
private fun CheckRow(checking: Boolean, hasFindings: Boolean, enabled: Boolean, onCheck: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                checking -> "차량 설정을 확인하고 있어요"
                hasFindings -> "확인한 설정은 확인한 순간의 값이에요. [원래대로]는 누를 때 다시 확인해요."
                else -> "차량 설정을 아직 확인하지 않았어요"
            },
            Modifier.weight(1f),
            color = TextSub,
            fontSize = 20.sp,
        )
        SmallButton("다시 확인", enabled = enabled, onClick = onCheck)
    }
}

@Composable
private fun ClimateRecordsOrEntry(climate: ClimateUi, actions: RestActions) {
    if (climate.open.isEmpty()) return
    if (!climate.deferred) return ClimateRecords(climate, actions)
    TextButton(onClick = actions.showClimate, modifier = Modifier.heightIn(min = 64.dp)) {
        Text("남은 공조 기록 보기 (${climate.open.count { it.prop != null }}개)", color = Warn, fontSize = 22.sp)
    }
}

/** Climate records not cleaned up: [원래대로] [기록 지우기] (with a confirmation) [나중에]. Originals of fan, temperature and auto are shown. */
@Composable
private fun ClimateRecords(climate: ClimateUi, actions: RestActions) {
    var confirm by remember { mutableStateOf(false) }
    Panel(spacing = 14.dp, padding = 24.dp) {
        Text("원래대로 돌리지 않은 공조 설정이 있어요", color = Warn, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        ClimateLines(climate.open)
        CheckRow(climate.checking, climate.open.any { it.checked != null }, enabled = !climate.checking && !climate.working, onCheck = actions.checkClimate)
        Text("이 공조는 정리할 때까지 다음 휴식에서도 바꾸지 않아요. [원래대로]는 꺼진 공조를 켜지 않아요.", color = TextSub, fontSize = 20.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SmallButton("원래대로", enabled = climate.restoreBlock == null, primary = true, onClick = actions.restoreClimate)
            SmallButton("기록 지우기", enabled = climate.discardBlock == null) { confirm = true }
            SmallButton("나중에", enabled = true, onClick = actions.deferClimate)
        }
        (climate.restoreBlock ?: climate.discardBlock)?.let { Text(it, color = TextSub, fontSize = 20.sp) }
        climate.notice?.let { Text(it, color = TextMain, fontSize = 20.sp) }
    }
    if (confirm) {
        ConfirmDialog(
            title = "공조 기록만 지울까요?",
            body = "기록을 지워도 공조는 되돌아가지 않아요. 지금 상태 그대로 남고, 필요하면 차량에서 직접 맞춰야 해요.",
            confirmLabel = "기록 지우기",
            onDismiss = { confirm = false },
        ) {
            confirm = false
            actions.discardClimate()
        }
    }
}

@Composable
private fun ClimateLines(items: List<ClimateItemUi>) {
    items.forEach { item ->
        // Several areas of one property (or several auto areas) are told apart by easy seat names, never by SDK keys.
        val areas = items.filter { it.prop == item.prop && it.area.isNotEmpty() }.map { it.area }.distinct()
        val label = (item.prop?.label ?: "자동 모드") + if (areas.size > 1) " · ${seatName(item.area)}" else ""
        val (tag, tagColor) = climateTag(item.result)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                item.original?.let { Text(it, color = TextSub, fontSize = 20.sp) }
                Spacer(Modifier.weight(1f))
                StatusTag(tag, tagColor)
            }
            Text(item.text, color = if (tagColor == Warn) Warn else TextSub, fontSize = 20.sp)
            item.checked?.let { Text(it, color = TextMain, fontSize = 20.sp) }
        }
    }
}

/** Resting: the time in the middle, short results under it, the sound controls at the bottom. No setting cards. */
@Composable
private fun Resting(state: RestState, audio: AudioUi, lighting: LightingUi, climate: ClimateUi, actions: RestActions, wide: Boolean) {
    Spacer(Modifier.height(0.dp))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The top bar already says "쉬는 중"; here the big number is labelled so it never reads as a clock time.
        Text(
            if (state.finalMinute) "곧 끝나요" else "남은 시간",
            color = if (state.finalMinute) Warn else TextSub,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(remaining(state.remainingMs), color = TextMain, fontSize = 168.sp, fontWeight = FontWeight.Bold)
        state.session?.let { Text("${clock(it.endWallMs, state.nowMs, state.zone)}에 끝나요", color = TextMain, fontSize = 32.sp) }
        Spacer(Modifier.height(12.dp))
        if (audio.pausedByOther) Text("다른 소리 때문에 휴식 소리를 잠시 멈췄어요", color = Warn, fontSize = 24.sp, textAlign = TextAlign.Center)
        lighting.restLine?.let { Text(it, color = TextSub, fontSize = 22.sp, textAlign = TextAlign.Center) }
        climate.restLine?.let { Text(it, color = TextSub, fontSize = 22.sp, textAlign = TextAlign.Center) }
        state.notice?.let { Text(it, color = Warn, fontSize = 24.sp, textAlign = TextAlign.Center) }
    }
    RestingSound(state, actions, wide)
}

/** Sound and level, changeable while resting; quiet hides the level and keeps it for later. */
@Composable
private fun RestingSound(state: RestState, actions: RestActions, wide: Boolean) {
    val quiet = state.sound == SoundMode.QUIET
    val sound: @Composable () -> Unit = {
        Choice(listOf("켬" to !quiet, "조용히" to quiet)) { i -> actions.setSound(if (i == 0) SoundMode.ON else SoundMode.QUIET) }
    }
    Panel(spacing = 8.dp, padding = 20.dp) {
        if (wide) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("휴식 소리", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                sound()
                Spacer(Modifier.weight(1f))
                if (!quiet) {
                    Text("크기", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    LevelChoice(state, actions)
                }
            }
        } else {
            SettingRow("휴식 소리") { sound() }
            if (!quiet) SettingRow("소리 크기") { LevelChoice(state, actions) }
        }
    }
}

@Composable
private fun DimmedRest(state: RestState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.finalMinute) Text("곧 끝나요", color = Dimmed, fontSize = 32.sp)
            Text(remaining(state.remainingMs), color = Dimmed, fontSize = 140.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Ended(state: RestState, audio: AudioUi, lighting: LightingUi, climate: ClimateUi, actions: RestActions, wide: Boolean) {
    val ending = state.ending ?: return
    Columns(
        wide,
        left = {
            Panel(spacing = 8.dp, padding = 32.dp) {
                Text(
                    when (ending.reason.kind) {
                        EndKind.NORMAL -> "휴식이 끝났어요"
                        EndKind.USER -> "휴식을 끝냈어요"
                        EndKind.STOPPED -> "휴식을 멈췄어요"
                    },
                    color = if (ending.reason.kind == EndKind.STOPPED) Warn else Accent,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(ending.reason.text, color = TextMain, fontSize = 26.sp)
                Spacer(Modifier.height(12.dp))
                Divider()
                Spacer(Modifier.height(4.dp))
                Text("종료 시각", color = TextSub, fontSize = 22.sp)
                Text(clock(ending.atWallMs, state.nowMs, state.zone), color = TextMain, fontSize = 72.sp, fontWeight = FontWeight.Bold)
                if (ending.reason.kind == EndKind.NORMAL) {
                    when (audio.announce) {
                        Announce.RUNNING -> "안내음과 음성으로 알리는 중이에요"
                        Announce.VOICE -> "안내음과 음성으로 알렸어요"
                        Announce.CHIME_ONLY -> "안내음으로 알렸어요. 음성 안내는 하지 못했어요"
                        Announce.SCREEN_ONLY -> "다른 소리가 있거나 소리를 낼 수 없어 화면으로만 알려요"
                        Announce.NONE -> null
                    }?.let { Text(it, color = TextSub, fontSize = 24.sp) }
                }
            }
        },
        right = {
            if (lighting.items.isNotEmpty() || lighting.restLine != null) {
                Panel(spacing = 12.dp, padding = 24.dp) {
                    Text("무드등", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
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
            if (climate.items.isNotEmpty() || climate.restLine != null) {
                Panel(spacing = 12.dp, padding = 24.dp) {
                    Text("공조", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    if (climate.items.isEmpty()) climate.restLine?.let { Text(it, color = TextSub, fontSize = 20.sp) }
                    ClimateLines(climate.items)
                    if (climate.working) Text("공조를 처리하는 중이에요. 휴식 소리와는 따로 진행돼요.", color = TextSub, fontSize = 20.sp)
                }
            }
            if (!climate.working) ClimateRecordsOrEntry(climate, actions)
            if (climate.earlier.isNotEmpty()) {
                Text("이전 공조 기록", color = TextSub, fontSize = 22.sp)
                ClimateLines(climate.earlier)
            }
            climate.problem?.let { Text(it, color = Warn, fontSize = 22.sp) }
        },
    )
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("•", color = Accent, fontSize = 24.sp)
        Text(text, color = TextMain, fontSize = 24.sp, lineHeight = 34.sp)
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
