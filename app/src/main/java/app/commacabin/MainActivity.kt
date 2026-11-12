package app.commacabin

import ai.pleos.playground.vehicle.Vehicle
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import app.commacabin.probe.AmbientProbe
import app.commacabin.probe.Cancellable
import app.commacabin.probe.FileRecoveryStore
import app.commacabin.probe.ProbeUiState
import app.commacabin.probe.RecordLease
import app.commacabin.probe.Scheduler
import app.commacabin.sdk.CarParkingSource
import app.commacabin.sdk.PleosLightPort
import java.io.File

class MainActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val uiState = mutableStateOf(ProbeUiState())
    private lateinit var vehicle: Vehicle
    private lateinit var parking: CarParkingSource
    private lateinit var probe: AmbientProbe

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
        parking = CarParkingSource(this, main) { state -> probe.onParkingChanged(state) }
        probe = AmbientProbe(
            light = PleosLightPort(vehicle.getLight(), main),
            store = FileRecoveryStore(File(filesDir, FileRecoveryStore.FILE_NAME)),
            scheduler = scheduler,
            parking = { parking.state },
            clock = System::currentTimeMillis,
            lease = RECORD_LEASE,
            log = { line -> Log.i(TAG, line) },
        )
        probe.onState = { uiState.value = it }
        uiState.value = probe.state
        probe.start()
        parking.start()
        // "SDK initialized" does not mean the Vehicle service is bound yet, so the first read waits briefly.
        main.postDelayed({ probe.refresh() }, FIRST_READ_DELAY_MS)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
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

    // The probe is closed first so no late SDK callback can touch the record after this Activity is gone.
    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        probe.close()
        parking.close()
        vehicle.release()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "AmbientProbe"
        const val FIRST_READ_DELAY_MS = 1_500L

        /** One lease per process: a newer Activity's probe makes any older probe inert. */
        val RECORD_LEASE = RecordLease()
    }
}
