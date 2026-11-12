package app.commacabin;

import ai.pleos.playground.vehicle.Vehicle;
import ai.pleos.playground.vehicle.api.Door;
import ai.pleos.playground.vehicle.api.Light;
import ai.pleos.playground.vehicle.api.model.area.DoorArea;
import ai.pleos.playground.vehicle.api.model.area.SeatArea;
import ai.pleos.playground.vehicle.api.model.capability.AccessType;
import ai.pleos.playground.vehicle.constant.area.DoorValue;
import ai.pleos.playground.vehicle.constant.area.SeatValue;
import ai.pleos.playground.vehicle.listener.AmbientLightBrightnessListener;
import ai.pleos.playground.vehicle.listener.DoorPositionListener;
import android.car.Car;
import android.car.VehicleGear;
import android.car.VehiclePropertyIds;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.function.Consumer;
import kotlin.Unit;

/** Read-only vehicle signals. The owning Activity starts and closes the adapter on the main thread. */
final class VehicleSignals implements AutoCloseable {
    enum DoorState { UNKNOWN, CLOSED, OPEN, UNSUPPORTED }

    record State(boolean parked, boolean drivingAllowed, DoorState door, Integer ambientBrightness) {}

    private static final String TAG = "CabinVehicle";
    private static final DoorArea PASSENGER_DOOR = new DoorArea(DoorValue.ROW_1_RIGHT);
    private static final SeatArea DRIVER_SEAT = new SeatArea(SeatValue.ROW_1_LEFT);
    private static final int GEAR_PROPERTY = VehiclePropertyIds.GEAR_SELECTION;
    private static final long PLEOS_BIND_DELAY_MS = 1_500;
    private static final long PLEOS_RETRY_MS = 2_000;
    private static final long PLEOS_TIMEOUT_MS = 2_500;
    private static final long DOOR_CHECK_INTERVAL_MS = 5_000;

    private final Context context;
    private final Consumer<State> onChange;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Session session;

    VehicleSignals(Context context, Consumer<State> onChange) {
        this.context = context.getApplicationContext();
        this.onChange = onChange;
    }

    void start() {
        requireMainThread();
        if (session != null) return;
        Session started = new Session();
        session = started;
        started.publish();
        if (session == started) started.connect();
    }

    @Override public void close() {
        requireMainThread();
        Session previous = session;
        if (previous == null) return;
        session = null;
        main.removeCallbacksAndMessages(null);
        previous.close();
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Vehicle signal lifecycle must run on the main thread");
        }
    }

    private final class Session {
        private Car car;
        private AaosBinding aaos;
        private PleosBinding pleos;
        private boolean parked;
        private boolean drivingAllowed;
        private DoorState door = DoorState.UNKNOWN;
        private Integer brightness;
        private State lastState;

        void connect() {
            try {
                car = Car.createCar(context, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT,
                        (connectedCar, ready) -> main.post(() -> carChanged(connectedCar, ready)));
            } catch (RuntimeException failure) {
                Log.w(TAG, "AAOS car connection failed", failure);
            }
            connectPleos();
        }

        void carChanged(Car connectedCar, boolean ready) {
            if (session != this) return;
            if (aaos != null) aaos.close();
            aaos = null;
            parked = false;
            drivingAllowed = false;
            publish();
            if (ready && session == this) {
                aaos = new AaosBinding(this);
                aaos.connect(connectedCar);
            }
        }

        void connectPleos() {
            if (session != this) return;
            pleos = new PleosBinding(this);
            pleos.connect();
        }

        void publish() {
            State updated = new State(parked, drivingAllowed, door, brightness);
            if (session != this || updated.equals(lastState)) return;
            lastState = updated;
            Log.i(TAG, "parked=" + parked + " uxAllowed=" + drivingAllowed + " passengerDoor=" + door
                    + " ambientBrightness=" + brightness);
            onChange.accept(updated);
        }

        void close() {
            if (aaos != null) aaos.close();
            if (car != null) cleanUp("disconnect AAOS", car::disconnect);
            if (pleos != null) pleos.close();
        }
    }

    private final class AaosBinding {
        private final Session owner;
        private CarPropertyManager properties;
        private CarUxRestrictionsManager restrictions;
        private long lastGearTimestamp = Long.MIN_VALUE;

        private final CarPropertyManager.CarPropertyEventCallback gearListener =
                new CarPropertyManager.CarPropertyEventCallback() {
                    @Override public void onChangeEvent(CarPropertyValue value) {
                        main.post(() -> gearChanged(value));
                    }

                    @Override public void onErrorEvent(int property, int area) {
                        main.post(() -> gearChanged(null));
                    }
                };

        AaosBinding(Session owner) {
            this.owner = owner;
        }

        void connect(Car car) {
            try {
                properties = (CarPropertyManager) car.getCarManager(Car.PROPERTY_SERVICE);
                if (properties != null && properties.registerCallback(gearListener, GEAR_PROPERTY, 0)) {
                    gearChanged(properties.getProperty(GEAR_PROPERTY, 0));
                }
            } catch (RuntimeException failure) {
                Log.w(TAG, "AAOS gear unavailable", failure);
            }
            if (!isCurrent()) return;
            try {
                restrictions = (CarUxRestrictionsManager) car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE);
                if (restrictions != null) {
                    restrictions.registerListener(value -> main.post(() -> uxChanged(value)));
                    uxChanged(restrictions.getCurrentCarUxRestrictions());
                }
            } catch (RuntimeException failure) {
                Log.w(TAG, "AAOS UX restrictions unavailable", failure);
            }
        }

        private boolean isCurrent() {
            return session == owner && owner.aaos == this;
        }

        private void gearChanged(CarPropertyValue<?> value) {
            if (!isCurrent()) return;
            if (value != null && value.getTimestamp() > 0) {
                if (value.getTimestamp() < lastGearTimestamp) return;
                lastGearTimestamp = value.getTimestamp();
            }
            owner.parked = value != null && value.getStatus() == CarPropertyValue.STATUS_AVAILABLE
                    && Integer.valueOf(VehicleGear.GEAR_PARK).equals(value.getValue());
            owner.publish();
        }

        private void uxChanged(CarUxRestrictions value) {
            if (!isCurrent()) return;
            owner.drivingAllowed = value != null && !value.isRequiresDistractionOptimization();
            owner.publish();
        }

        void close() {
            if (properties != null) cleanUp("unregister AAOS gear", () -> properties.unregisterCallback(gearListener));
            if (restrictions != null) cleanUp("unregister AAOS UX", restrictions::unregisterListener);
        }
    }

    private final class PleosBinding {
        private final Session owner;
        private Vehicle vehicle;
        private Door door;
        private Light light;
        private SeatArea lightArea;
        private boolean doorSubscribed;
        private int doorEvents;
        private int brightnessEvents;
        private final Runnable startReading = this::checkCapabilities;
        private final Runnable nextDoorRead = this::readDoor;
        private final Runnable doorTimeout = () -> reconnect("Pleos door read timed out", null);
        private final DoorPositionListener doorListener = new DoorPositionListener() {
            @Override public void onPositionUpdated(Integer value) {
                main.post(() -> {
                    if (!isCurrent()) return;
                    doorEvents++;
                    setDoor(value);
                });
            }

            @Override public void onFailed(Exception failure) {
                main.post(() -> reconnect("Pleos door subscription failed", failure));
            }
        };
        private final AmbientLightBrightnessListener lightListener = new AmbientLightBrightnessListener() {
            @Override public void onBrightnessUpdated(Integer value) {
                main.post(() -> {
                    brightnessEvents++;
                    setBrightness(value);
                });
            }

            @Override public void onFailed(Exception failure) {
                onBrightnessUpdated(null);
            }
        };

        PleosBinding(Session owner) {
            this.owner = owner;
        }

        void connect() {
            try {
                vehicle = new Vehicle(context);
                vehicle.initialize();
                main.postDelayed(startReading, PLEOS_BIND_DELAY_MS);
            } catch (RuntimeException failure) {
                reconnect("Pleos initialization failed", failure);
            }
        }

        private boolean isCurrent() {
            return session == owner && owner.pleos == this;
        }

        private void checkCapabilities() {
            if (!isCurrent()) return;
            try {
                door = vehicle.getDoor();
                main.postDelayed(doorTimeout, PLEOS_TIMEOUT_MS);
                door.checkDoorPositionCapabilities((config, areas) -> {
                    main.post(() -> doorCapability(canRead(config.getAccessType()) && areas.containsKey(PASSENGER_DOOR)));
                    return Unit.INSTANCE;
                }, failure -> {
                    main.post(() -> reconnect("Pleos door capability check failed", failure));
                    return Unit.INSTANCE;
                });
            } catch (RuntimeException failure) {
                reconnect("Pleos door capability check failed", failure);
            }
            if (isCurrent()) checkLight();
        }

        private void doorCapability(boolean supported) {
            if (!isCurrent()) return;
            main.removeCallbacks(doorTimeout);
            if (!supported) {
                owner.door = DoorState.UNSUPPORTED;
                owner.publish();
                return;
            }
            try {
                doorSubscribed = true;
                door.registerDoorPosition(PASSENGER_DOOR, doorListener);
                readDoor();
            } catch (RuntimeException failure) {
                reconnect("Pleos door subscription failed", failure);
            }
        }

        private void readDoor() {
            if (!isCurrent()) return;
            // A read also checks service liveness when the door stays still and emits no changes.
            int eventsAtRead = doorEvents;
            main.postDelayed(doorTimeout, PLEOS_TIMEOUT_MS);
            try {
                door.getCurrentDoorPosition(PASSENGER_DOOR, value -> {
                    main.post(() -> {
                        if (!isCurrent()) return;
                        main.removeCallbacks(doorTimeout);
                        // The subscription has no timestamps. A newer live event wins over this snapshot.
                        // A closed snapshot cannot arm the app before the live stream has delivered an event.
                        boolean liveConfirmed = doorEvents != 0;
                        if (eventsAtRead == doorEvents && (liveConfirmed || !Integer.valueOf(0).equals(value))) {
                            setDoor(value);
                        }
                        if (isCurrent()) main.postDelayed(nextDoorRead, DOOR_CHECK_INTERVAL_MS);
                    });
                    return Unit.INSTANCE;
                }, failure -> {
                    main.post(() -> reconnect("Pleos door read failed", failure));
                    return Unit.INSTANCE;
                });
            } catch (RuntimeException failure) {
                reconnect("Pleos door read failed", failure);
            }
        }

        private void setDoor(Integer position) {
            if (position == null || position < 0) {
                owner.door = DoorState.UNKNOWN;
            } else {
                owner.door = position == 0 ? DoorState.CLOSED : DoorState.OPEN;
            }
            owner.publish();
        }

        private void checkLight() {
            try {
                light = vehicle.getLight();
                light.checkAmbientLightBrightnessCapabilities((config, areas) -> {
                    if (canRead(config.getAccessType()) && !areas.isEmpty()) {
                        SeatArea area = areas.containsKey(DRIVER_SEAT) ? DRIVER_SEAT : areas.keySet().iterator().next();
                        main.post(() -> readLight(area));
                    }
                    return Unit.INSTANCE;
                }, failure -> {
                    Log.d(TAG, "Pleos ambient brightness capability unavailable", failure);
                    return Unit.INSTANCE;
                });
            } catch (RuntimeException failure) {
                Log.d(TAG, "Pleos ambient brightness capability unavailable", failure);
            }
        }

        private void readLight(SeatArea area) {
            if (!isCurrent()) return;
            try {
                lightArea = area;
                int eventsAtRead = brightnessEvents;
                light.registerAmbientLightBrightnessListener(area, lightListener);
                light.getAmbientLightBrightness(area, value -> {
                    main.post(() -> {
                        if (eventsAtRead == brightnessEvents) setBrightness(value);
                    });
                    return Unit.INSTANCE;
                }, failure -> Unit.INSTANCE);
            } catch (RuntimeException failure) {
                Log.d(TAG, "Pleos ambient brightness unavailable", failure);
            }
        }

        private void setBrightness(Integer value) {
            if (!isCurrent()) return;
            owner.brightness = value != null && value >= 0 && value <= 100 ? value : null;
            owner.publish();
        }

        private void reconnect(String operation, Exception failure) {
            if (!isCurrent()) return;
            Log.w(TAG, operation, failure);
            owner.pleos = null;
            owner.door = DoorState.UNKNOWN;
            owner.brightness = null;
            owner.publish();
            close();
            if (session == owner) main.postDelayed(owner::connectPleos, PLEOS_RETRY_MS);
        }

        void close() {
            main.removeCallbacks(startReading);
            main.removeCallbacks(nextDoorRead);
            main.removeCallbacks(doorTimeout);
            if (doorSubscribed) cleanUp("unregister Pleos door", () -> door.unregisterDoorPosition(PASSENGER_DOOR, doorListener));
            if (lightArea != null) cleanUp("unregister Pleos light", () -> light.unregisterAmbientLightBrightnessListener(lightArea, lightListener));
            if (vehicle != null) cleanUp("release Pleos", vehicle::release);
        }
    }

    private static boolean canRead(AccessType access) {
        return access == AccessType.READ || access == AccessType.READ_WRITE;
    }

    private static void cleanUp(String operation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            Log.d(TAG, operation, failure);
        }
    }
}
