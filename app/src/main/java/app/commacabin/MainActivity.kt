package app.commacabin

import ai.pleos.playground.vehicle.Vehicle
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import app.commacabin.probe.AmbientProbe
import app.commacabin.probe.Cancellable
import app.commacabin.probe.FileRecoveryStore
import app.commacabin.probe.ProbeUiState
import app.commacabin.probe.RecordLease
import app.commacabin.probe.Scheduler
import app.commacabin.rest.DoorMonitor
import app.commacabin.rest.RestFlow
import app.commacabin.rest.RestState
import app.commacabin.rest.Screen
import app.commacabin.sdk.CarParkingSource
import app.commacabin.sdk.PleosLightPort
import app.commacabin.sdk.PleosTtsEngine
import app.commacabin.sdk.PleosVehicleReadPort
import app.commacabin.support.SupportDiagnostics
import app.commacabin.support.SupportUiState
import app.commacabin.support.TtsDiagnostics
import app.commacabin.support.TtsUiState
import java.io.File
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val uiState = mutableStateOf(ProbeUiState())
    private val supportState = mutableStateOf(SupportUiState())
    private val supportTab = mutableStateOf(false)
    private val ttsState = mutableStateOf(TtsUiState())
    private lateinit var restState: MutableState<RestState>
    private val devToolsOpen = mutableStateOf(false)
    private lateinit var vehicle: Vehicle
    private lateinit var parking: CarParkingSource
    private lateinit var door: DoorMonitor
    private lateinit var rest: RestFlow
    private lateinit var probe: AmbientProbe
    private lateinit var support: SupportDiagnostics
    private lateinit var tts: TtsDiagnostics
    /** The window's own brightness before the rest flow touched it; the system brightness is never changed. */
    private var baseBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var keepScreenOn = false

    private val scheduler = object : Scheduler {
        override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
            val runnable = Runnable { task() }
            main.postDelayed(runnable, delayMs)
            return Cancellable { main.removeCallbacks(runnable) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vehicle = Vehicle(applicationContext)
        vehicle.initialize()
        val readPort = PleosVehicleReadPort(vehicle, main)
        parking = CarParkingSource(this, main) { state ->
            probe.onParkingChanged(state)
            rest.onParking(state)
        }
        door = DoorMonitor(readPort, scheduler, { line -> Log.i(DOOR_TAG, line) }) { state -> rest.onDoor(state) }
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        rest = RestFlow(
            scheduler = scheduler,
            wallClock = System::currentTimeMillis,
            elapsedClock = SystemClock::elapsedRealtime,
            zone = ZoneId::systemDefault,
            parking = { parking.state },
            door = { door.state },
            onboardingSeen = prefs.getBoolean(KEY_ONBOARDING_SEEN, false),
            markOnboardingSeen = { prefs.edit().putBoolean(KEY_ONBOARDING_SEEN, true).apply() },
            log = { line -> Log.i(REST_TAG, line) },
        )
        restState = mutableStateOf(rest.state)
        rest.onState = { state ->
            restState.value = state
            applyWindow(state)
        }
        baseBrightness = window.attributes.screenBrightness
        probe = AmbientProbe(
            light = PleosLightPort(vehicle.getLight(), main),
            store = FileRecoveryStore(File(filesDir, FileRecoveryStore.FILE_NAME)),
            scheduler = scheduler,
            parking = { parking.state },
            clock = System::currentTimeMillis,
            lease = RECORD_LEASE,
            log = { line -> Log.i(TAG, line) },
        )
        support = SupportDiagnostics(readPort, scheduler, System::currentTimeMillis) { line -> Log.i(SUPPORT_TAG, line) }
        support.onState = { supportState.value = it }
        tts = TtsDiagnostics({ PleosTtsEngine(applicationContext, main) }, scheduler) { line -> Log.i(TTS_TAG, line) }
        tts.onState = { ttsState.value = it }
        probe.onState = { uiState.value = it }
        uiState.value = probe.state
        probe.start()
        parking.start()
        // "SDK initialized" does not mean the Vehicle service is bound yet, so the first read waits briefly.
        main.postDelayed({ probe.refresh() }, FIRST_READ_DELAY_MS)
        val restActions = RestActions(
            dismissOnboarding = rest::dismissOnboarding,
            adjustPickup = rest::adjustPickup,
            start = rest::start,
            touch = rest::touch,
            endByUser = rest::endByUser,
            restAgain = rest::restAgain,
            openDevTools = if (BuildConfig.DEBUG) ({ devToolsOpen.value = true }) else null,
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                // Development diagnostics are a debug-only side screen and can never be open during a rest.
                if (!devToolsOpen.value || restState.value.screen == Screen.RESTING) {
                    RestScreen(restState.value, restActions)
                } else {
                    BackHandler { devToolsOpen.value = false }
                    DevTools()
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun DevTools() {
        Column {
            Row(Modifier.padding(start = 24.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TabButton("← 휴식 화면", selected = false) { devToolsOpen.value = false }
                TabButton("밝기 검증", selected = !supportTab.value) { supportTab.value = false }
                TabButton("지원 진단(읽기 전용)", selected = supportTab.value) { supportTab.value = true }
            }
            if (supportTab.value) {
                SupportScreen(
                    state = supportState.value,
                    tts = ttsState.value,
                    onStart = support::startAll,
                    onStopObserving = support::stopObserving,
                    onTtsStart = tts::start,
                )
            } else {
                ProbeScreen(
                    state = uiState.value,
                    onStart = probe::startChange,
                    onRestore = probe::restore,
                    onDiscard = probe::discardRecord,
                    onRefresh = probe::refresh,
                )
            }
        }
    }

    /** Door polling and its subscription run only while the screen is visible; each start is a new generation. */
    override fun onStart() {
        super.onStart()
        door.start()
        rest.onVisible()
    }

    /** Ends a running rest first (APP_LEFT), then stops door polling so nothing reads in the background. */
    override fun onStop() {
        rest.onHidden()
        door.stop()
        restoreWindow()
        super.onStop()
    }

    private fun applyWindow(state: RestState) {
        val resting = state.screen == Screen.RESTING
        setKeepScreenOn(resting)
        setWindowBrightness(if (resting && state.dimmed) DIM_WINDOW_BRIGHTNESS else baseBrightness)
    }

    private fun restoreWindow() {
        setKeepScreenOn(false)
        setWindowBrightness(baseBrightness)
    }

    private fun setKeepScreenOn(on: Boolean) {
        if (on == keepScreenOn) return
        keepScreenOn = on
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** This window only (LayoutParams.screenBrightness); the system brightness setting is untouched. */
    private fun setWindowBrightness(value: Float) {
        val attrs = window.attributes
        if (attrs.screenBrightness == value) return
        attrs.screenBrightness = value
        window.attributes = attrs
    }

    // The probe is closed first so no late SDK callback can touch the record after this Activity is gone.
    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        restoreWindow()
        support.close()
        tts.close()
        probe.close()
        parking.close()
        vehicle.release()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "AmbientProbe"
        const val SUPPORT_TAG = "VehicleSupport"
        const val TTS_TAG = "TtsProbe"
        const val REST_TAG = "RestFlow"
        const val DOOR_TAG = "RestDoor"
        const val FIRST_READ_DELAY_MS = 1_500L
        const val DIM_WINDOW_BRIGHTNESS = 0.02f
        /** Only the onboarding flag is stored here. */
        const val PREFS_NAME = "comma_cabin_rest"
        const val KEY_ONBOARDING_SEEN = "onboarding_seen"

        /** One lease per process: a newer Activity's probe makes any older probe inert. */
        val RECORD_LEASE = RecordLease()
    }
}

@androidx.compose.runtime.Composable
private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label, fontSize = 20.sp) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label, fontSize = 20.sp) }
    }
}
