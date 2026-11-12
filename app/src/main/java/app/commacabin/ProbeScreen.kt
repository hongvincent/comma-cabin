package app.commacabin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import app.commacabin.probe.GearState
import app.commacabin.probe.ProbeUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProbeScreen(
    state: ProbeUiState,
    onStart: () -> Unit,
    onRestore: () -> Unit,
    onDiscard: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("앰비언트 밝기 적용·복원 검증", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Text(
            "버튼을 누르기 전에는 차량 설정을 바꾸지 않아요. 한 영역의 밝기만 낮췄다가 [원래대로]로 되돌려요.",
            color = MaterialTheme.colorScheme.onBackground,
        )

        Section("차량 상태") {
            Field("기어", when (state.parking.gear) {
                GearState.PARK -> "P"
                GearState.NOT_PARK -> "P 아님"
                GearState.UNKNOWN -> "확인 안 됨"
            })
            Field("주행 제한", when (state.parking.uxRestricted) {
                true -> "있음"
                false -> "없음"
                null -> "확인 안 됨"
            })
            Field("쓰기 조건", if (state.parking.parked) "충족(P + 주행 제한 없음)" else "미충족 — 차량에 쓰지 않음")
        }

        Section("Light") {
            Field("연결·응답", state.light.label)
            Field("접근", state.access?.name ?: "-")
            Field("선택 영역", state.area?.key ?: "-")
            Field("선택 근거", state.areaReason ?: "-")
            Field("허용 범위", state.bounds?.let { "${it.first} ~ ${it.last}" } ?: "-")
            Field("스위치", state.switch?.name ?: "-")
            Field("현재 밝기(조회)", state.current?.toString() ?: "-")
        }

        Section("값") {
            Field("원래 밝기", state.original?.toString() ?: "-")
            Field("목표 밝기", state.target?.toString() ?: "-")
            Field("콜백 응답", state.lastCallback ?: "-")
            Field("재조회 밝기", state.lastRead?.toString() ?: "-")
        }

        Section("복원 기록") {
            val r = state.record
            Field("기록", when {
                state.recordProblem != null -> "읽을 수 없음: ${state.recordProblem}"
                r == null -> "없음"
                else -> "${r.stage.label} (작업 ${r.jobId.take(8)})"
            })
            if (r != null) {
                Field("원래 / 목표", "${r.originalValue} / ${r.targetValue}")
                Field("응답 수신", if (r.responseReceived) "받음" else "없음")
                Field("이전 요청", if (r.priorRequestUnresolved) "결과 불명" else "-")
            }
            Field("미완료 요청", state.pending?.let {
                "${it.kind.label} 작업 ${it.jobId.take(8)}" + if (it.timedOut) " — 응답 대기 시간 지남(결과 불명)" else " — 응답 대기"
            } ?: if (state.evaluating) "응답 받음 — 재조회 중" else "없음")
            Field("잠금", state.lockReason ?: "없음")
            Field("기록 저장 오류", state.storageFault ?: "없음")
        }

        state.manualGuide?.let { Text(it, color = MaterialTheme.colorScheme.tertiary, fontSize = 18.sp) }
        if (state.message.isNotEmpty()) Text(state.message, color = MaterialTheme.colorScheme.primary, fontSize = 20.sp)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onStart, enabled = state.canStart, modifier = Modifier.heightIn(min = 72.dp)) {
                Text("밝기 낮추기 검증 시작", fontSize = 20.sp)
            }
            Button(onClick = onRestore, enabled = state.canRestore, modifier = Modifier.heightIn(min = 72.dp)) {
                Text("원래대로", fontSize = 20.sp)
            }
            OutlinedButton(onClick = onDiscard, enabled = state.canDiscard, modifier = Modifier.heightIn(min = 72.dp)) {
                Text("기록 지우기", fontSize = 20.sp)
            }
            OutlinedButton(onClick = onRefresh, enabled = state.canRefresh, modifier = Modifier.heightIn(min = 72.dp)) {
                Text("새로고침(조회만)", fontSize = 20.sp)
            }
        }

        Section("단계 기록") {
            val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.KOREA)
            state.log.asReversed().forEach { line ->
                Text(
                    "${format.format(Date(line.timeMs))}  ${line.step}  ${line.detail}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        content()
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row {
        Text(label, Modifier.width(220.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp)
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp)
    }
}
