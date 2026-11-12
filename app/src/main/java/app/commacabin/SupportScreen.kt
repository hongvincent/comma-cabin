package app.commacabin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import app.commacabin.support.RowStatus
import app.commacabin.support.SupportUiState
import app.commacabin.support.TtsStatus
import app.commacabin.support.TtsUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SupportScreen(state: SupportUiState, tts: TtsUiState, onStart: () -> Unit, onStopObserving: () -> Unit, onTtsStart: () -> Unit) {
    val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.KOREA)
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("차량 지원 진단 (읽기 전용)", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Text(
            "문·시트·공조·앰비언트 색상의 지원 여부와 현재값을 조회만 해요. 차량 설정은 바꾸지 않아요.",
            color = MaterialTheme.colorScheme.onBackground,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onStart, modifier = Modifier.heightIn(min = 72.dp)) {
                Text(if (state.running) "다시 조회(이전 조회 무시)" else "지원 조회 시작", fontSize = 20.sp)
            }
            OutlinedButton(
                onClick = onStopObserving,
                enabled = !state.observeStopped && (state.observing.isNotEmpty() || state.running),
                modifier = Modifier.heightIn(min = 72.dp),
            ) {
                Text("구독 중지", fontSize = 20.sp)
            }
        }
        if (state.message.isNotEmpty()) Text("${state.message} (실행 ${state.run})", color = MaterialTheme.colorScheme.primary, fontSize = 18.sp)
        Text(
            if (state.observeStopped) "구독: 중지됨(다시 조회하기 전까지 구독하지 않아요)"
            else "구독 중: ${state.observing.ifEmpty { listOf("없음") }.joinToString()}",
            color = MaterialTheme.colorScheme.onBackground,
        )

        Column(Modifier.background(MaterialTheme.colorScheme.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.rows.forEach { row ->
                val color = when (row.status) {
                    RowStatus.OK -> MaterialTheme.colorScheme.onSurface
                    RowStatus.RUNNING, RowStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.error
                }
                Row {
                    Text("${row.feature.label} · ${row.kind.label}${row.area?.let { " · $it" } ?: ""}", Modifier.width(560.dp), color = color, fontSize = 16.sp)
                    Text("[${row.status.label}] ${row.detail}", color = color, fontSize = 16.sp)
                }
            }
        }

        Text("음성 안내(TTS) 초기화 진단", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
        Text("ON_DEVICE 초기화와 한국어 설정 요청만 해요. 소리는 내지 않아요.", color = MaterialTheme.colorScheme.onBackground)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onTtsStart, modifier = Modifier.heightIn(min = 72.dp)) {
                Text(if (tts.status == TtsStatus.RUNNING) "다시 시도(이전 시도 해제)" else "TTS 초기화 시도", fontSize = 20.sp)
            }
        }
        Text(
            "[${tts.status.label}]${if (tts.attempt > 0) " 시도 ${tts.attempt}" else ""} ${tts.detail}",
            color = if (tts.status == TtsStatus.FAILED || tts.status == TtsStatus.TIMEOUT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
            fontSize = 18.sp,
        )

        Text("구독 알림", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
        state.events.asReversed().forEach { e ->
            Text(
                "${format.format(Date(e.atMs))}  ${e.feature.label} ${e.area}  ${e.value ?: "null(값 없음)"}",
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}
