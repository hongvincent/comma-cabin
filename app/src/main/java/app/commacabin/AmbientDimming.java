package app.commacabin;

import ai.pleos.playground.vehicle.api.Light;
import ai.pleos.playground.vehicle.api.model.area.SeatArea;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import kotlin.Unit;

/** Controls one readable/writable area and only restores a value still owned by this session. */
final class AmbientDimming {
    private static final String TAG = "CabinAmbient";
    private static final long SETTLE_MS = 700;
    private static final long TIMEOUT_MS = 3000;
    private static final long WRITE_TIMEOUT_MS = 15000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Light light;
    private final SeatArea area;
    private final int minimum;
    private final int maximum;
    private boolean active;
    private boolean stopping;
    private boolean external;
    private boolean pending;
    private Integer original;
    private Integer confirmed;
    private Integer requested;
    private int step;
    private int generation;
    private final List<Runnable> completions = new ArrayList<>();
    private boolean restart;

    AmbientDimming(Light light, SeatArea area, int minimum, int maximum) {
        this.light = light;
        this.area = area;
        this.minimum = minimum;
        this.maximum = maximum;
    }

    void start() {
        if (stopping) {
            restart = true;
            return;
        }
        if (active) return;
        active = true;
        int token = ++generation;
        read(value -> {
            if (token != generation || !active || stopping) return;
            if (value == null || value < minimum || value > maximum) {
                Log.w(TAG, "Ambient brightness unavailable; keeping current lighting");
                active = false;
                return;
            }
            original = value;
            confirmed = value;
            step = 0;
            next();
        });
    }

    void onBrightness(Integer value) {
        if (original == null || value == null || external) return;
        if (value.equals(confirmed) || (pending && value.equals(requested))) return;
        external = true;
        active = false;
        Log.i(TAG, "Ambient brightness changed externally; leaving user setting in place");
    }

    private void next() {
        if (!active || stopping || external || pending) {
            finishIfStopped();
            return;
        }
        int goal = Math.max(minimum, original / 2);
        if (confirmed <= goal || step >= 4) return;
        step++;
        int value = original - (int) Math.ceil((original - goal) * step / 4.0);
        if (value == confirmed) {
            handler.post(this::next);
            return;
        }
        writeAndVerify(value, ok -> {
            if (!ok) {
                active = false;
                Log.w(TAG, "Ambient brightness write not confirmed; keeping app usable");
            }
            if (stopping) finishIfStopped();
            else if (active) handler.postDelayed(this::next, 150);
        });
    }

    private void writeAndVerify(int value, Consumer<Boolean> completed) {
        pending = true;
        requested = value;
        int token = ++generation;
        Runnable verify = () -> read(current -> {
            if (token != generation || !pending) return;
            ++generation;
            pending = false;
            requested = null;
            if (current != null && current == value && !external) {
                confirmed = current;
                Log.i(TAG, "Ambient brightness verified=" + current);
                completed.accept(true);
            } else {
                if (current != null && confirmed != null && !current.equals(confirmed)) external = true;
                Log.w(TAG, "Ambient brightness readback differs, requested=" + value + " actual=" + current);
                completed.accept(false);
            }
        });
        try {
            light.setAmbientLightBrightness(area, value, (changedArea, actual) -> {
                handler.postDelayed(verify, SETTLE_MS);
                return Unit.INSTANCE;
            }, failure -> {
                handler.post(() -> {
                    Log.w(TAG, "Ambient brightness write failed", failure);
                    verify.run();
                });
                return Unit.INSTANCE;
            });
            // A service can fail to call back after accepting a command. Check the actual value.
            handler.postDelayed(verify, WRITE_TIMEOUT_MS);
        } catch (RuntimeException failure) {
            Log.w(TAG, "Ambient brightness write failed", failure);
            verify.run();
        }
    }

    private void read(Consumer<Integer> done) {
        boolean[] complete = { false };
        Consumer<Integer> once = value -> {
            if (complete[0]) return;
            complete[0] = true;
            done.accept(value);
        };
        try {
            light.getAmbientLightBrightness(area, value -> {
                handler.post(() -> once.accept(value));
                return Unit.INSTANCE;
            }, failure -> {
                handler.post(() -> {
                    Log.w(TAG, "Ambient brightness read failed", failure);
                    once.accept(null);
                });
                return Unit.INSTANCE;
            });
            handler.postDelayed(() -> once.accept(null), TIMEOUT_MS);
        } catch (RuntimeException failure) {
            Log.w(TAG, "Ambient brightness read failed", failure);
            once.accept(null);
        }
    }

    void stop(Runnable completion) {
        if (completion != null) completions.add(completion);
        restart = false;
        if (stopping) return;
        active = false;
        stopping = true;
        finishIfStopped();
    }

    private void finishIfStopped() {
        if (!stopping || pending) return;
        if (external || original == null || confirmed == null || original.equals(confirmed)) {
            finish();
            return;
        }
        pending = true;
        read(current -> {
            pending = false;
            if (external || !confirmed.equals(current)) {
                Log.i(TAG, "Ambient setting changed or unreadable; leaving it untouched");
                finish();
                return;
            }
            writeAndVerify(original, ok -> {
                if (!ok) Log.w(TAG, "Ambient brightness restoration could not be verified");
                finish();
            });
        });
    }

    private void finish() {
        handler.removeCallbacksAndMessages(null);
        ++generation;
        active = false;
        stopping = false;
        pending = false;
        original = null;
        confirmed = null;
        requested = null;
        external = false;
        boolean shouldRestart = restart && completions.isEmpty();
        restart = false;
        List<Runnable> done = new ArrayList<>(completions);
        completions.clear();
        for (Runnable completion : done) completion.run();
        if (shouldRestart) start();
    }
}
