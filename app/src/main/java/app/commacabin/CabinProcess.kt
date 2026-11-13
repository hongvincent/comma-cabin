package app.commacabin

import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.commacabin.climate.FileClimateStore
import app.commacabin.climate.RestClimate
import app.commacabin.climate.SdkClimatePort
import app.commacabin.control.ControlLoad
import app.commacabin.control.FileControlStore
import app.commacabin.control.FilePresetStore
import app.commacabin.control.PresetStage
import app.commacabin.lighting.FileLightStore
import app.commacabin.lighting.RestLighting
import app.commacabin.lighting.SdkLightingPort
import app.commacabin.probe.Cancellable
import app.commacabin.probe.FileRecoveryStore
import app.commacabin.probe.LoadResult
import app.commacabin.probe.RecordLease
import app.commacabin.probe.Scheduler
import app.commacabin.sdk.CarParkingSource
import app.commacabin.sdk.PleosControlPort
import app.commacabin.sdk.PleosLightPort
import app.commacabin.sdk.SdkLane
import app.commacabin.seat.ControlSeatAxisPort
import app.commacabin.seat.FileSeatJournal
import app.commacabin.seat.RestSeat
import app.commacabin.seat.SeatActivityRelay
import app.commacabin.seat.controlCapability
import java.io.File

/**
 * The product lighting for the whole process, apart from any Activity: its own Handler (an Activity's
 * removeCallbacksAndMessages never touches it), the process's only Vehicle and SDK lane, its parking source,
 * and the only [RestLighting] owner, so an answer or a restore already sent finishes while the process lives,
 * and a new Activity never adds a second owner. Main thread only. Nothing here survives the process being killed.
 */
class CabinProcess private constructor(context: Context) {
    /** Only the app's files directory is kept; no Context is held in this process-wide object. */
    private val files: File = context.applicationContext.filesDir
    private val main = Handler(Looper.getMainLooper())
    /**
     * The process's only Vehicle, also for the Activity's debug tools. Never released while the process lives: an
     * Activity's end does not prove that its SDK calls, callbacks and listener removals are over.
     */
    val vehicle = Vehicle(context.applicationContext).also { it.initialize() }

    /** The app's only way into the SDK (one worker thread). Its callbacks go to this process-wide Handler. */
    val lane = SdkLane(post = { main.post(it) }, log = { line -> Log.i(LANE_TAG, line) }, clock = SystemClock::elapsedRealtime)
    private val scheduler = object : Scheduler {
        override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
            val runnable = Runnable { task() }
            main.postDelayed(runnable, delayMs)
            return Cancellable { main.removeCallbacks(runnable) }
        }
    }
    private lateinit var parking: CarParkingSource

    /** Set by the live Activity: its debug tools' busy reason. */
    var debugBusy: () -> String? = { null }

    /** Set by the live Activity: its debug seat preset tool's busy reason. */
    var debugSeatBusy: () -> String? = { null }

    val lighting: RestLighting = RestLighting(
        port = SdkLightingPort(PleosLightPort(vehicle.getLight(), lane, this), PleosControlPort(vehicle, lane, this)),
        store = FileLightStore(File(files, FileLightStore.FILE_NAME)),
        scheduler = scheduler,
        parking = { parking.state },
        debugBlock = { debugBusy() ?: debugFiles() },
        lease = RecordLease(),
        log = { line -> Log.i(TAG, line) },
    )

    /** Product climate: the same lifetime, Handler, Vehicle and parking source as the lighting; an independent owner. */
    val climate: RestClimate = RestClimate(
        port = SdkClimatePort(PleosControlPort(vehicle, lane, this)),
        store = FileClimateStore(File(files, FileClimateStore.FILE_NAME)),
        scheduler = scheduler,
        parking = { parking.state },
        debugBlock = { debugBusy() ?: debugFiles() },
        lease = RecordLease(),
        log = { line -> Log.i(CLIMATE_TAG, line) },
    )

    /**
     * The product seat's own SDK owner: its STOP gives up only this owner's motion writes (writes with a motion key)
     * not yet in the SDK, never another owner's. Stable for the whole process; no Activity closes it.
     */
    private val seatOwner = Any()
    private val seatControl = PleosControlPort(vehicle, lane, seatOwner)

    /**
     * Product seat: no verified environment, so a new rest never reads, watches or moves the seat. At start an
     * unfinished earlier record still gets its safety STOP and is kept (never deleted or completed here).
     */
    val seat: RestSeat = RestSeat(
        port = ControlSeatAxisPort(seatControl),
        capability = controlCapability(seatControl),
        journal = FileSeatJournal(File(files, FileSeatJournal.FILE_NAME)),
        scheduler = scheduler,
        parking = { parking.state },
        log = { line -> Log.i(SEAT_TAG, line) },
        startBlock = { debugBusy() ?: debugFiles() ?: debugSeatBusy() ?: presetFile() ?: lane.motion?.let { "다른 시트 이동의 STOP 응답 전이에요" } },
    )

    /** The live Activity's door and screen, for the seat only (an older Activity's late signals are dropped). */
    val seatSignals = SeatActivityRelay(seat)

    init {
        parking = CarParkingSource(context.applicationContext, main) { state ->
            seat.onParking(state)
            lighting.onParking(state)
            climate.onParking(state)
        }
        lighting.start()
        climate.start()
        seat.start()
        parking.start()
    }

    /** The live Activity's debug tools changed (work, records, errors): the product screens show the current blocks. Display only. */
    fun debugChanged() {
        lighting.onDebugChanged()
        climate.onDebugChanged()
    }

    /** For the debug write tools: product lighting, climate or seat busy or holding records. They never block each other. */
    fun productBusy(): String? = lighting.busyReason() ?: climate.busyReason() ?: seat.busyReason()

    /** Debug record files (also damaged ones) block product lighting even when no Activity holds them. */
    private fun debugFiles(): String? = when {
        FileRecoveryStore(File(files, FileRecoveryStore.FILE_NAME)).load() != LoadResult.Empty -> "밝기 검증 기록이 남아 있어요"
        FileControlStore(File(files, FileControlStore.FILE_NAME)).load() != ControlLoad.Empty -> "제어 검증 기록이 남아 있어요"
        else -> null
    }

    /** The debug seat preset record, also with no Activity: damaged, or a request the operator has not confirmed. */
    private fun presetFile(): String? {
        val jobs = FilePresetStore(File(files, FilePresetStore.FILE_NAME)).load() ?: return "시트 프리셋 진단 기록을 읽을 수 없어요"
        return "시트 프리셋 진단 요청이 운영자 확인 전이에요".takeIf { jobs.any { it.stage != PresetStage.CONFIRMED } }
    }

    companion object {
        private const val TAG = "RestLighting"
        private const val CLIMATE_TAG = "RestClimate"
        private const val LANE_TAG = "SdkLane"
        private const val SEAT_TAG = "RestSeat"
        private var instance: CabinProcess? = null

        /** Main thread only. */
        fun get(context: Context): CabinProcess = instance ?: CabinProcess(context).also { instance = it }
    }
}
