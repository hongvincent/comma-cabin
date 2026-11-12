package app.commacabin.sdk

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.util.Log
import app.commacabin.probe.GearState
import app.commacabin.probe.ParkingState
import app.commacabin.probe.ParkingTracker

/**
 * Reads GEAR_SELECTION and CarUxRestrictions. Each Car (re)connection gets a new binding id; callbacks
 * carry the id they were registered with, so queued events from an older binding or after close() are
 * ignored. Unknown or disconnected means not parked. Main thread only.
 */
class CarParkingSource(
    context: Context,
    private val main: Handler,
    onChange: (ParkingState) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val tracker = ParkingTracker(onChange)
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var gearCallback: CarPropertyManager.CarPropertyEventCallback? = null
    private var restrictions: CarUxRestrictionsManager? = null

    val state: ParkingState get() = tracker.state

    fun start() {
        if (car != null) return
        car = try {
            Car.createCar(appContext, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                main.post { onCar(connected, ready) }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Car connection failed", e)
            null
        }
    }

    private fun onCar(connected: Car, ready: Boolean) {
        unbind()
        val id = tracker.newBinding() ?: return // closed: never register again
        if (!ready) return
        try {
            properties = connected.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            properties?.let { manager ->
                val callback = gearCallbackFor(id)
                if (manager.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, 0f)) {
                    gearCallback = callback
                    onGear(id, manager.getProperty<Any>(VehiclePropertyIds.GEAR_SELECTION, 0))
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "GEAR_SELECTION unavailable", e)
        }
        try {
            restrictions = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            restrictions?.let { manager ->
                manager.registerListener { value -> main.post { onUx(id, value) } }
                onUx(id, manager.currentCarUxRestrictions)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "CarUxRestrictions unavailable", e)
        }
    }

    private fun gearCallbackFor(id: Int) = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            main.post { onGear(id, value) }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            main.post { tracker.gear(id, GearState.UNKNOWN, 0) }
        }
    }

    private fun onGear(id: Int, value: CarPropertyValue<*>?) {
        val gear = when {
            value == null || value.status != CarPropertyValue.STATUS_AVAILABLE -> GearState.UNKNOWN
            (value.value as? Int) == VehicleGear.GEAR_PARK -> GearState.PARK
            else -> GearState.NOT_PARK
        }
        tracker.gear(id, gear, value?.timestamp ?: 0)
    }

    private fun onUx(id: Int, value: CarUxRestrictions?) {
        tracker.uxRestricted(id, value?.isRequiresDistractionOptimization)
    }

    private fun unbind() {
        val manager = properties
        val callback = gearCallback
        if (manager != null && callback != null) runCatching { manager.unregisterCallback(callback) }
        restrictions?.let { runCatching { it.unregisterListener() } }
        properties = null
        gearCallback = null
        restrictions = null
    }

    override fun close() {
        tracker.close()
        unbind()
        car?.let { runCatching { it.disconnect() } }
        car = null
    }

    private companion object {
        const val TAG = "AmbientProbeCar"
    }
}
