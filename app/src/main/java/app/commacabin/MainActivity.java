package app.commacabin;

import android.app.Activity;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String TAG = "CommaCabin";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.KOREA);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.KOREA);
    private static final DateTimeFormatter TRACE = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.KOREA);
    private static final AudioAttributes RAIN = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();
    private static final VehicleSignals.State NO_SIGNALS = new VehicleSignals.State(
            false, false, VehicleSignals.DoorState.UNKNOWN, null);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            applyVehicleAndClock();
            if (visible) main.postDelayed(this, player == null ? 400L : 100L);
        }
    };
    private CabinScreen screen;
    private VehicleSignals vehicle;
    private VehicleSignals.State signals = NO_SIGNALS;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private Object focusOwner;
    private MediaPlayer player;
    private TimePickerDialog timePicker;
    private LocalTime pickup = LocalTime.of(19, 30);
    private Instant sessionEnd;
    private long sessionElapsedLimit;
    private Instant stoppedAt;
    private RestRules.End stopReason;
    private boolean audioUnavailable;
    private boolean visible;
    private float lastBrightness = -1f;

    @Override protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        audio = getSystemService(AudioManager.class);
        if (savedState != null) pickup = LocalTime.ofSecondOfDay(savedState.getInt("pickup", 70_200));
        screen = new CabinScreen(this, this::chooseTime, this::startRest,
                () -> stopRest(RestRules.End.MANUAL));
        setContentView(screen);
        render();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("pickup", pickup.toSecondOfDay());
        super.onSaveInstanceState(out);
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        vehicle = new VehicleSignals(this, state -> {
            if (!visible) return;
            if (!state.equals(signals)) {
                Log.i(TAG, "signals time=" + trace(Instant.now()) + " parked=" + state.parked()
                        + " uxAllows=" + state.drivingAllowed() + " door=" + state.door());
            }
            signals = state;
            if (!canEditPickupTime() && timePicker != null) timePicker.dismiss();
            applyVehicleAndClock();
        });
        vehicle.start();
        main.post(tick);
    }

    @Override protected void onPause() {
        stopRest(RestRules.End.BACKGROUND);
        super.onPause();
    }

    @Override protected void onStop() {
        visible = false;
        if (timePicker != null) timePicker.dismiss();
        main.removeCallbacks(tick);
        if (vehicle != null) {
            vehicle.close();
            vehicle = null;
        }
        signals = NO_SIGNALS;
        super.onStop();
    }

    private void chooseTime() {
        if (!canEditPickupTime() || timePicker != null) return;
        TimePickerDialog dialog = new TimePickerDialog(this, (view, hour, minute) -> {
            if (!canEditPickupTime()) return;
            pickup = LocalTime.of(hour, minute);
            stopReason = null;
            stoppedAt = null;
            audioUnavailable = false;
            render();
        }, pickup.getHour(), pickup.getMinute(), true);
        dialog.setOnDismissListener(ignored -> timePicker = null);
        timePicker = dialog;
        dialog.show();
        int target = Math.round(76 * getResources().getDisplayMetrics().density);
        for (int which : new int[]{DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_POSITIVE}) {
            Button button = dialog.getButton(which);
            button.setMinHeight(target);
            button.setMinWidth(target);
        }
    }

    private boolean canEditPickupTime() {
        return visible && player == null && signals.parked() && signals.drivingAllowed();
    }

    private RestRules.Door door() {
        return switch (signals.door()) {
            case CLOSED -> RestRules.Door.CLOSED;
            case OPEN -> RestRules.Door.OPEN;
            case UNSUPPORTED -> RestRules.Door.UNSUPPORTED;
            case UNKNOWN -> RestRules.Door.UNKNOWN;
        };
    }

    private void startRest() {
        if (player != null || !visible) return;
        Instant now = Instant.now();
        Instant end = RestRules.endAt(now, ZoneId.systemDefault(), pickup);
        if (!RestRules.canStart(now, end, signals.parked(), signals.drivingAllowed(), door())) {
            render();
            return;
        }
        stopReason = null;
        stoppedAt = null;
        audioUnavailable = false;
        Object owner = new Object();
        AudioFocusRequest requested = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(RAIN)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(change -> {
                    if (change < 0 && focusOwner == owner) stopRest(RestRules.End.AUDIO_INTERRUPTED);
                }, main)
                .build();
        if (audio == null || audio.requestAudioFocus(requested) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioUnavailable = true;
            render();
            return;
        }
        focus = requested;
        focusOwner = owner;
        try {
            player = MediaPlayer.create(this, R.raw.rain, RAIN, AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (player == null) throw new IllegalStateException("Rain asset did not open");
            player.setLooping(true);
            player.setVolume(0.6f, 0.6f);
            player.setOnErrorListener((mp, what, extra) -> {
                if (mp == player) stopRest(RestRules.End.AUDIO_INTERRUPTED);
                return true;
            });
            sessionEnd = end;
            sessionElapsedLimit = SystemClock.elapsedRealtime() + Duration.between(now, end).toMillis();
            player.start();
            Log.i(TAG, "started time=" + trace(Instant.now()) + " pickup=" + pickup
                    + " deadline=" + trace(end) + " isPlaying=" + player.isPlaying());
        } catch (RuntimeException e) {
            Log.w(TAG, "Rain could not play", e);
            stopRest(RestRules.End.AUDIO_INTERRUPTED);
            audioUnavailable = true;
        }
        render();
    }
    // Use wall time for the pickup deadline and elapsed time to cap rest if the clock moves back.
    private void applyVehicleAndClock() {
        if (player != null) {
            RestRules.End cause = RestRules.stopCause(Instant.now(), sessionEnd,
                    signals.parked(), signals.drivingAllowed(), door());
            if (cause == null && SystemClock.elapsedRealtime() >= sessionElapsedLimit) {
                cause = RestRules.End.DEADLINE;
            }
            if (cause != null) {
                stopRest(cause);
                return;
            }
        }
        render();
    }

    private void stopRest(RestRules.End reason) {
        MediaPlayer previous = player;
        if (previous == null && focus == null) return;
        player = null;
        stopReason = reason;
        stoppedAt = Instant.now();
        boolean wasPlaying = false;
        boolean isPlaying = false;
        if (previous != null) {
            try {
                wasPlaying = previous.isPlaying();
                if (wasPlaying) previous.pause();
                isPlaying = previous.isPlaying();
            } catch (RuntimeException e) {
                Log.w(TAG, "Player unavailable during stop", e);
            } finally {
                previous.release();
            }
        }
        if (focus != null) {
            focusOwner = null;
            audio.abandonAudioFocusRequest(focus);
            focus = null;
        }
        Log.i(TAG, "stopped time=" + trace(stoppedAt) + " reason=" + reason
                + " deadline=" + (sessionEnd == null ? "none" : trace(sessionEnd))
                + " wasPlaying=" + wasPlaying + " isPlaying=" + isPlaying);
        sessionEnd = null;
        render();
    }

    private void render() {
        Instant now = Instant.now();
        ZoneId zone = ZoneId.systemDefault();
        Instant end = sessionEnd == null ? RestRules.endAt(now, zone, pickup) : sessionEnd;
        boolean playing = player != null;
        boolean canStart = visible && !playing && RestRules.canStart(now, end, signals.parked(), signals.drivingAllowed(), door());
        String headline;
        String detail;
        if (playing) {
            headline = "잠깐 쉬어가세요";
            detail = "픽업 2분 전이나 조수석 문이 열리면 빗소리가 멈춰요.";
        } else if (audioUnavailable) {
            headline = "빗소리를 재생할 수 없어요";
            detail = "다른 오디오가 끝난 뒤 다시 시작해 주세요.";
        } else if (stopReason != null) {
            headline = switch (stopReason) {
                case DEADLINE -> "픽업 준비할 시간이에요";
                case PASSENGER_DOOR -> "조수석 문이 열렸어요";
                case DRIVE_RESTRICTION -> "주행 준비로 휴식을 끝냈어요";
                case SIGNAL_LOST -> "차량 신호를 확인할 수 없어요";
                case BACKGROUND, MANUAL, AUDIO_INTERRUPTED -> "휴식을 끝냈어요";
            };
            detail = CLOCK.format(stoppedAt.atZone(zone)) + "에 빗소리를 껐어요.";
        } else if (!signals.parked() || !signals.drivingAllowed()) {
            headline = "차를 주차해 주세요";
            detail = "P 기어에서 휴식을 시작할 수 있어요.";
        } else if (door() != RestRules.Door.CLOSED) {
            headline = door() == RestRules.Door.OPEN ? "조수석 문을 닫아 주세요" : "조수석 문 상태를 확인하고 있어요";
            detail = "문이 닫힌 상태에서 시작할 수 있어요.";
        } else if (!RestRules.enoughTime(now, end)) {
            headline = "휴식 시간이 부족해요";
            detail = "픽업까지 3분 이상 남아야 시작할 수 있어요.";
        } else {
            headline = "픽업까지 잠깐 쉬어요";
            detail = "픽업 2분 전에 빗소리가 멈춰요.";
        }
        String doorText = switch (door()) {
            case CLOSED -> "닫힘";
            case OPEN -> "열림";
            case UNSUPPORTED -> "지원 안 됨";
            case UNKNOWN -> "확인 중";
        };
        long remaining = Math.max(0, (Duration.between(now, end).toMillis() + 999) / 1000);
        String countdown = String.format(Locale.KOREA, "%02d:%02d", remaining / 60, remaining % 60);
        screen.show(new CabinScreen.Model(pickup.format(TIME), TIME.format(end.atZone(zone)),
                CLOCK.format(now.atZone(zone)), signals.parked() ? "P" : "P 확인 안 됨", doorText,
                headline, detail, countdown, playing, canStart, signals.ambientBrightness(), canEditPickupTime()));
        float brightness = playing && signals.ambientBrightness() != null
                ? 0.20f + 0.25f * (Math.max(0, Math.min(100, signals.ambientBrightness())) / 100f)
                : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        if (brightness != lastBrightness) {
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.screenBrightness = brightness;
            getWindow().setAttributes(params);
            lastBrightness = brightness;
        }
    }

    private static String trace(Instant value) {
        return TRACE.format(value.atZone(ZoneId.systemDefault()));
    }
}
