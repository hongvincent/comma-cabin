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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import app.commacabin.audio.AudioUi
import app.commacabin.audio.RestAudio
import app.commacabin.audio.RestSettings
import app.commacabin.audio.TtsSlot
import app.commacabin.climate.ClimateChoice
import app.commacabin.climate.ClimateUi
import app.commacabin.control.ControlBench
import app.commacabin.control.ControlUiState
import app.commacabin.control.FileControlStore
import app.commacabin.lighting.LightingUi
import app.commacabin.probe.AmbientProbe
import app.commacabin.probe.Cancellable
import app.commacabin.probe.FileRecoveryStore
import app.commacabin.probe.ProbeUiState
import app.commacabin.probe.RecordLease
import app.commacabin.probe.Scheduler
import app.commacabin.rest.DoorMonitor
import app.commacabin.rest.EndReason
import app.commacabin.rest.RestFlow
import app.commacabin.rest.RestState
import app.commacabin.rest.Screen
import app.commacabin.rest.doorBlock
import app.commacabin.rest.parkingBlock
import app.commacabin.sdk.AndroidAudioFocus
import app.commacabin.sdk.AndroidSounds
import app.commacabin.sdk.CarParkingSource
import app.commacabin.sdk.PleosControlPort
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
    /** Read-only check of the records left from earlier rests, once this screen is visible (after the SDK binding wait). */
    private val checkRecords = Runnable {
        cabin.lighting.checkRecords(this)
        cabin.climate.checkRecords(this)
    }
    private val uiState = mutableStateOf(ProbeUiState())
    private val supportState = mutableStateOf(SupportUiState())
    private val supportTab = mutableStateOf(false)
    private val controlTab = mutableStateOf(false)
    private val controlState = mutableStateOf(ControlUiState())
    private val ttsState = mutableStateOf(TtsUiState())
    private lateinit var restState: MutableState<RestState>
    private val audioState = mutableStateOf(AudioUi())
    private val devToolsOpen = mutableStateOf(false)
    private val lightingState = mutableStateOf(LightingUi())
    private val climateState = mutableStateOf(ClimateUi())
    /** Process-wide owner of the product lighting; never closed by this Activity. */
    private lateinit var cabin: CabinProcess
    /** RestFlow session id (this Activity only) → lighting run id issued by the process-wide owner. */
    private val lightingRuns = mutableMapOf<Int, Int>()
    /** RestFlow session id (this Activity only) → climate run id issued by the process-wide owner. */
    private val climateRuns = mutableMapOf<Int, Int>()
    private val lightingListener: (LightingUi) -> Unit = { lightingState.value = it }
    private val climateListener: (ClimateUi) -> Unit = { climateState.value = it }
    private val debugBusy: () -> String? = { debugBusyReason() }
    /** One TextToSpeech object at a time, shared by the rest announcement and the TTS diagnostic. */
    private val ttsSlot = TtsSlot()
    private lateinit var vehicle: Vehicle
    private lateinit var parking: CarParkingSource
    private lateinit var door: DoorMonitor
    private lateinit var rest: RestFlow
    private lateinit var audio: RestAudio
    private lateinit var bench: ControlBench
    private lateinit var probe: AmbientProbe
    private lateinit var support: SupportDiagnostics
    private lateinit var tts: TtsDiagnostics
    /** The window's own brightness before the rest flow touched it; the system brightness is never changed. */
    private var baseBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var keepScreenOn = false
    /** Activity between onStart and onStop. */
    private var started = false
    /** The [ControlScreen] composable is in the composition (dev tools open, control tab selected, not resting). */
    private var controlShown = false

    private val scheduler = object : Scheduler {
        override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
            val runnable = Runnable { task() }
            main.postDelayed(runnable, delayMs)
            return Cancellable { main.removeCallbacks(runnable) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cabin = CabinProcess.get(applicationContext)
        vehicle = Vehicle(applicationContext)
        vehicle.initialize()
        val readPort = PleosVehicleReadPort(vehicle, main)
        parking = CarParkingSource(this, main) { state ->
            probe.onParkingChanged(state)
            rest.onParking(state)
            bench.onParking(state)
        }
        door = DoorMonitor(readPort, scheduler, { line -> Log.i(DOOR_TAG, line) }) { state ->
            rest.onDoor(state)
            bench.onDoor(state.status)
        }
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        audio = RestAudio(
            scheduler = scheduler,
            elapsed = SystemClock::elapsedRealtime,
            focus = AndroidAudioFocus(this, main),
            sounds = AndroidSounds(applicationContext),
            createVoice = { PleosTtsEngine(applicationContext, main) },
            slot = ttsSlot,
            log = { line -> Log.i(AUDIO_TAG, line) },
        )
        audio.onState = { audioState.value = it }
        audio.vehicleOk = { parkingBlock(parking.state) == null && doorBlock(door.state.status) == null }
        rest = RestFlow(
            scheduler = scheduler,
            wallClock = System::currentTimeMillis,
            elapsedClock = SystemClock::elapsedRealtime,
            zone = ZoneId::systemDefault,
            parking = { parking.state },
            door = { door.state },
            settings = RestSettings.from(prefs.all),
            saveSettings = { s ->
                prefs.edit()
                    .remove(RestSettings.KEY_OLD_ONBOARDING)
                    .putInt(RestSettings.KEY_ONBOARDING, s.onboardingVersion)
                    .putString(RestSettings.KEY_SOUND, s.sound.name)
                    .putString(RestSettings.KEY_LEVEL, s.level.name)
                    .putBoolean(RestSettings.KEY_LIGHTING, s.lighting)
                    .putBoolean(RestSettings.KEY_FAN, s.fan)
                    .putBoolean(RestSettings.KEY_TEMP, s.temp)
                    .putFloat(RestSettings.KEY_TEMP_C, s.tempC)
                    .apply()
            },
            audio = audio,
            log = { line -> Log.i(REST_TAG, line) },
        )
        rest.listenTo(audio)
        restState = mutableStateOf(rest.state)
        rest.onState = { state ->
            restState.value = state
            applyWindow(state)
        }
        // Once per session each; the end hook runs after the session's sound was told to stop.
        rest.onSessionStarted = { id, on ->
            val run = cabin.lighting.onRestStart(on)
            lightingRuns[id] = run
            val s = rest.state
            val climateRun = cabin.climate.onRestStart(ClimateChoice(s.fan, s.temp, s.tempC))
            climateRuns[id] = climateRun
            Log.i(REST_TAG, "session $id → lighting run $run, climate run $climateRun (activity ${System.identityHashCode(this)})")
        }
        rest.onSessionEnded = { id, reason ->
            val run = lightingRuns.remove(id)
            val climateRun = climateRuns.remove(id)
            Log.i(REST_TAG, "session $id end $reason → lighting run $run, climate run $climateRun")
            run?.let { cabin.lighting.onRestEnd(it, reason.name, parkingLost = reason in PARKING_ENDS) }
            climateRun?.let { cabin.climate.onRestEnd(it, reason.name, parkingLost = reason in PARKING_ENDS) }
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
            externalBlock = { cabin.productBusy() },
        )
        bench = ControlBench(
            port = PleosControlPort(vehicle, main),
            store = FileControlStore(File(filesDir, FileControlStore.FILE_NAME)),
            scheduler = scheduler,
            parking = { parking.state },
            door = { door.state.status },
            brightnessRecordPresent = { probe.state.record != null || probe.state.recordProblem != null },
            clock = System::currentTimeMillis,
            lease = CONTROL_LEASE,
            log = { line -> Log.i(CONTROL_TAG, line) },
            externalBlock = { cabin.productBusy() },
        )
        bench.onState = {
            controlState.value = it
            cabin.debugChanged()
        }
        support = SupportDiagnostics(readPort, scheduler, System::currentTimeMillis) { line -> Log.i(SUPPORT_TAG, line) }
        support.onState = { supportState.value = it }
        tts = TtsDiagnostics({ PleosTtsEngine(applicationContext, main) }, scheduler, ttsSlot, AndroidAudioFocus(this, main)) { line -> Log.i(TTS_TAG, line) }
        tts.onState = { ttsState.value = it }
        probe.onState = {
            uiState.value = it
            cabin.debugChanged()
        }
        uiState.value = probe.state
        probe.start()
        bench.start()
        cabin.debugBusy = debugBusy
        cabin.lighting.onState = lightingListener
        lightingState.value = cabin.lighting.state
        cabin.climate.onState = climateListener
        climateState.value = cabin.climate.state
        // The product blocks were computed with the debug tools of before (or none): recompute them with this Activity's.
        cabin.debugChanged()
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
            setSound = rest::setSound,
            setLevel = rest::setLevel,
            setLighting = rest::setLighting,
            restoreLighting = { cabin.lighting.restoreRecords() },
            discardLighting = { cabin.lighting.discardRecords() },
            deferLighting = { cabin.lighting.defer() },
            showLighting = { cabin.lighting.showRecords() },
            setFan = rest::setFan,
            setTemp = rest::setTemp,
            setTempC = rest::setTempC,
            restoreClimate = { cabin.climate.restoreRecords() },
            discardClimate = { cabin.climate.discardRecords() },
            deferClimate = { cabin.climate.defer() },
            showClimate = { cabin.climate.showRecords() },
            checkLighting = { cabin.lighting.checkRecords(this) },
            checkClimate = { cabin.climate.checkRecords(this) },
            openDevTools = if (BuildConfig.DEBUG) ({ devToolsOpen.value = true }) else null,
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                // Development diagnostics are a debug-only side screen and can never be open during a rest.
                if (!devToolsOpen.value || restState.value.screen == Screen.RESTING) {
                    RestScreen(restState.value, audioState.value, lightingState.value, climateState.value, restActions)
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
                TabButton("밝기 검증", selected = !supportTab.value && !controlTab.value) {
                    supportTab.value = false
                    controlTab.value = false
                }
                TabButton("지원 진단(읽기 전용)", selected = supportTab.value) {
                    supportTab.value = true
                    controlTab.value = false
                }
                TabButton("제어 검증", selected = controlTab.value) {
                    controlTab.value = true
                    supportTab.value = false
                }
            }
            if (controlTab.value) {
                // Leaving by another tab, [← 휴식 화면], Back or a rest start removes this composable.
                DisposableEffect(Unit) {
                    controlShown = true
                    syncControlVisibility()
                    onDispose {
                        controlShown = false
                        syncControlVisibility()
                    }
                }
                ControlScreen(controlState.value, bench)
            } else if (supportTab.value) {
                SupportScreen(
                    state = supportState.value,
                    tts = ttsState.value,
                    onStart = support::startAll,
                    onStopObserving = support::stopObserving,
                    onTtsStart = tts::start,
                    onTtsSpeak = tts::speakTest,
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
        // Read-only: the driver temperature range and step for the ready screen's ± buttons.
        if (cabin.climate.state.tempRange == null) cabin.climate.loadTempRange()
        // Read-only, once per showing: what the vehicle has now for each record left (no records = no call).
        main.postDelayed(checkRecords, FIRST_READ_DELAY_MS)
        started = true
        syncControlVisibility()
    }

    /** The bench takes new work only while its screen is really visible: Activity started and the control tab shown. */
    private fun syncControlVisibility() {
        if (started && controlShown) bench.onVisible() else bench.onHidden()
    }

    /**
     * Ends a running rest first (APP_LEFT), stops every sound, focus, voice and announcement, then stops door
     * polling so nothing reads or plays in the background.
     */
    override fun onStop() {
        rest.onHidden()
        // Only this Activity's own pending check: an older Activity stopping late never cancels a newer one's.
        main.removeCallbacks(checkRecords)
        cabin.lighting.stopCheck(this)
        cabin.climate.stopCheck(this)
        audio.stopAll()
        tts.stopSpeech()
        started = false
        syncControlVisibility()
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

    /** Debug tools busy or holding records (also damaged): the product lighting does not apply meanwhile. */
    private fun debugBusyReason(): String? {
        if (!::probe.isInitialized || !::bench.isInitialized) return null
        val p = probe.state
        val c = bench.state
        return when {
            p.busy || p.evaluating || p.pending != null -> "밝기 검증 작업이 진행 중이에요"
            p.record != null || p.recordProblem != null || p.storageFault != null -> "밝기 검증 기록이 남아 있어요"
            c.busy || c.pending.isNotEmpty() || c.stopPending != null || c.seatMoving -> "제어 검증 작업이 진행 중이에요"
            c.records.isNotEmpty() || c.loadProblem != null || c.storageFault != null -> "제어 검증 기록이 남아 있어요"
            else -> null
        }
    }

    // The probe is closed first so no late SDK callback can touch the record after this Activity is gone.
    // The process-wide lighting owner is not closed: its restore goes on with its own Handler and Vehicle.
    override fun onDestroy() {
        if (cabin.lighting.onState === lightingListener) cabin.lighting.onState = {}
        if (cabin.climate.onState === climateListener) cabin.climate.onState = {}
        if (cabin.debugBusy === debugBusy) cabin.debugBusy = { null }
        main.removeCallbacksAndMessages(null)
        restoreWindow()
        audio.stopAll()
        bench.close()
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
        const val AUDIO_TAG = "RestAudio"
        const val CONTROL_TAG = "ControlBench"
        const val FIRST_READ_DELAY_MS = 1_500L
        const val DIM_WINDOW_BRIGHTNESS = 0.02f
        /** Only [RestSettings] (onboarding version, sound choice and level) are stored here. */
        const val PREFS_NAME = "comma_cabin_rest"

        /** One lease per process: a newer Activity's probe makes any older probe inert. */
        val RECORD_LEASE = RecordLease()

        /** Owner of the STEP 7 control record file: one bench per process. */
        val CONTROL_LEASE = RecordLease()

        /** Rest ends caused by losing parking: what the rest changed is held, not restored, until [원래대로]. */
        val PARKING_ENDS = setOf(EndReason.LEFT_PARK, EndReason.RESTRICTED, EndReason.VEHICLE_UNKNOWN)
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
