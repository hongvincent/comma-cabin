package app.commacabin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.commacabin.control.SeatPresetBench
import app.commacabin.control.SeatPresetUi

/** Debug-only seat preset check. Nothing runs unless its button is pressed; a select is never shown as done. */
@Composable
fun SeatPresetScreen(state: SeatPresetUi, bench: SeatPresetBench) {
    val text = MaterialTheme.colorScheme.onBackground
    val basis = remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("시트 프리셋 검증 (debug)", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = text)
        Text(
            "공개 SDK selectSeatMemory는 완료 콜백이 없어요. 요청 뒤에는 \"결과 미확인\"으로만 표시하고, [관측 종료] 뒤에도 운영자 확인 전에는 새로 요청하지 않아요.",
            color = text,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = bench::checkCapability, modifier = Modifier.heightIn(min = 64.dp)) { Text("지원 조회", fontSize = 20.sp) }
            OutlinedButton(onClick = bench::readPositions, modifier = Modifier.heightIn(min = 64.dp)) { Text("시트 값 조회", fontSize = 20.sp) }
        }
        Text("지원: ${state.capability}", color = text)
        Text("SDK 시트 값: " + state.positions.entries.joinToString { "${it.key.label} ${it.value}" }.ifEmpty { "아직 조회하지 않았어요" }, color = text)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { bench.select(1) }, modifier = Modifier.heightIn(min = 64.dp)) { Text("1번 선택", fontSize = 20.sp) }
            Button(onClick = { bench.select(2) }, modifier = Modifier.heightIn(min = 64.dp)) { Text("2번 선택", fontSize = 20.sp) }
            OutlinedButton(onClick = bench::closeObservation, modifier = Modifier.heightIn(min = 64.dp)) { Text("관측 종료", fontSize = 20.sp) }
            OutlinedButton(onClick = bench::stopAll, modifier = Modifier.heightIn(min = 64.dp)) { Text("모든 축 STOP", fontSize = 20.sp) }
        }
        Text("요청 잠금: " + (bench.busyReason() ?: "없음"), color = text)
        OutlinedTextField(
            value = basis.value,
            onValueChange = { basis.value = it },
            label = { Text("운영자 확인 근거(외부 처리 로그, 전 영역 moves 0 · actuator 0)") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { bench.confirm(basis.value) }, modifier = Modifier.heightIn(min = 64.dp)) { Text("운영자 확인", fontSize = 20.sp) }
        state.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.message.isNotEmpty()) Text(state.message, color = MaterialTheme.colorScheme.primary)
        Text("요청 기록", fontWeight = FontWeight.SemiBold, color = text)
        state.jobs.takeLast(6).forEach { j ->
            Text(
                "#${j.jobId.take(8)} ${j.area} ${j.number}번 · ${j.stage.label}" +
                    (j.error?.let { " · onFailed/예외: $it" } ?: "") + (j.basis?.let { " · 근거: $it" } ?: ""),
                fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = text,
            )
        }
        Text("로그", fontWeight = FontWeight.SemiBold, color = text)
        state.log.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = text) }
    }
}
