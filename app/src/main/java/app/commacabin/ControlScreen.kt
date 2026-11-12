package app.commacabin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.commacabin.control.ControlBench
import app.commacabin.control.ControlProp
import app.commacabin.control.ControlUiState
import app.commacabin.control.Move

/** Debug-only STEP 7 control verification. Nothing here runs unless its button is pressed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlScreen(state: ControlUiState, bench: ControlBench) {
    val onBg = MaterialTheme.colorScheme.onBackground
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("차량 제어 검증 (debug)", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = onBg)
        Text("버튼을 누를 때만 차량 설정을 바꿔요. 한 항목씩 적용 → 원래대로 순서로 시험하고, 명령 전에 기록을 저장해요.", color = onBg)
        if (state.message.isNotEmpty()) Text(state.message, color = MaterialTheme.colorScheme.primary, fontSize = 20.sp)
        state.loadProblem?.let { Text("기록 읽기 실패: $it", color = MaterialTheme.colorScheme.error) }
        state.storageFault?.let { Text("저장 오류: $it", color = MaterialTheme.colorScheme.error) }

        Row("조회") { Act("새로고침(조회만)") { bench.refresh() } }
        Row("운전석 등받이") {
            Act("BACKWARD 탐색") { bench.exploreSeat(Move.BACKWARD) }
            Act("FORWARD 탐색") { bench.exploreSeat(Move.FORWARD) }
            Act("원위치") { bench.restoreSeat() }
            Act("STOP", primary = true) { bench.stopSeat("수동") }
            Act("기록 지우기") { bench.discard(ControlProp.SEAT_BACKREST) }
        }
        Row("무드등 색") {
            Act("따뜻한 색 적용") { bench.apply(ControlProp.AMBIENT_COLOR) }
            Act("원래 색") { bench.restore(ControlProp.AMBIENT_COLOR) }
            Act("기록 지우기") { bench.discard(ControlProp.AMBIENT_COLOR) }
        }
        Row("풍량") {
            Act("한 단계 낮추기") { bench.apply(ControlProp.HVAC_FAN_SPEED) }
            Act("원래 풍량") { bench.restore(ControlProp.HVAC_FAN_SPEED) }
            Act("기록 지우기") { bench.discard(ControlProp.HVAC_FAN_SPEED) }
        }
        Row("운전석 목표 온도") {
            Act("한 간격 바꾸기") { bench.apply(ControlProp.HVAC_TEMPERATURE) }
            Act("원래 온도") { bench.restore(ControlProp.HVAC_TEMPERATURE) }
            Act("기록 지우기") { bench.discard(ControlProp.HVAC_TEMPERATURE) }
        }

        Text("기록", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = onBg)
        if (state.records.isEmpty()) Text("없음", color = onBg)
        state.records.forEach { r ->
            Text(
                "${r.prop.label} ${r.area}: ${r.stage.label} · 원래 ${r.original} · 목표 ${r.target} · 응답 ${if (r.responseReceived) "받음" else "없음"}" +
                    (r.lastEcho?.let { " · $it" } ?: "") + (r.lastRead?.let { " · 재조회 $it" } ?: "") + (bench.lockReason(r)?.let { " · 잠금: $it" } ?: ""),
                color = onBg,
            )
        }
        (state.pending.map { (p, q) -> "${p.label} ${q.label} #${q.requestId.take(8)}${if (q.timedOut) " (결과 불명·응답 대기)" else ""}" } +
            listOfNotNull(state.stopPending?.let { "${it.label}${if (it.timedOut) " (결과 불명·응답 대기)" else ""}" }))
            .forEach { Text("미완료 요청: $it", color = MaterialTheme.colorScheme.tertiary) }

        Text("현재값(조회·리스너)", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = onBg)
        state.live.toSortedMap().forEach { (k, v) -> Text("$k = $v", fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = onBg) }

        Text("단계 기록", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = onBg)
        state.log.takeLast(80).asReversed().forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = onBg) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Row(title: String, content: @Composable () -> Unit) {
    Column(Modifier.background(MaterialTheme.colorScheme.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun Act(label: String, primary: Boolean = false, onClick: () -> Unit) {
    if (primary) {
        Button(onClick = onClick, modifier = Modifier.heightIn(min = 64.dp)) { Text(label, fontSize = 18.sp) }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 64.dp)) { Text(label, fontSize = 18.sp) }
    }
}
