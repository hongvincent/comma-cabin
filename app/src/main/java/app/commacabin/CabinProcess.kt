package app.commacabin

import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.commacabin.control.ControlLoad
import app.commacabin.control.FileControlStore
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
import java.io.File

/**
 * The product lighting for the whole process, apart from any Activity: its own Handler (an Activity's
 * removeCallbacksAndMessages never touches it), its own Vehicle and parking source, and the only
 * [RestLighting] owner, so an answer or a restore already sent finishes while the process lives, and a new
 * Activity never adds a second owner. Main thread only. Nothing here survives the process being killed.
 */
class CabinProcess private constructor(context: Context) {
    /** Only the app's files directory is kept; no Context is held in this process-wide object. */
    private val files: File = context.applicationContext.filesDir
    private val main = Handler(Looper.getMainLooper())
    private val vehicle = Vehicle(context.applicationContext).also { it.initialize() }
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

    val lighting: RestLighting = RestLighting(
        port = SdkLightingPort(PleosLightPort(vehicle.getLight(), main), PleosControlPort(vehicle, main)),
        store = FileLightStore(File(files, FileLightStore.FILE_NAME)),
        scheduler = scheduler,
        parking = { parking.state },
        debugBlock = { debugBusy() ?: debugFiles() },
        lease = RecordLease(),
        log = { line -> Log.i(TAG, line) },
    )

    init {
        parking = CarParkingSource(context.applicationContext, main) { lighting.onParking(it) }
        lighting.start()
        parking.start()
    }

    /** Debug record files (also damaged ones) block product lighting even when no Activity holds them. */
    private fun debugFiles(): String? = when {
        FileRecoveryStore(File(files, FileRecoveryStore.FILE_NAME)).load() != LoadResult.Empty -> "밝기 검증 기록이 남아 있어요"
        FileControlStore(File(files, FileControlStore.FILE_NAME)).load() != ControlLoad.Empty -> "제어 검증 기록이 남아 있어요"
        else -> null
    }

    companion object {
        private const val TAG = "RestLighting"
        private var instance: CabinProcess? = null

        /** Main thread only. */
        fun get(context: Context): CabinProcess = instance ?: CabinProcess(context).also { instance = it }
    }
}
