package app.commacabin;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

final class RestRules {
    private static final Duration PICKUP_BUFFER = Duration.ofMinutes(2);
    private static final Duration MINIMUM_REST = Duration.ofMinutes(1);

    enum Door { UNKNOWN, CLOSED, OPEN, UNSUPPORTED }
    enum End { DEADLINE, PASSENGER_DOOR, DRIVE_RESTRICTION, SIGNAL_LOST, MANUAL, BACKGROUND, AUDIO_INTERRUPTED }

    private RestRules() {}
    // The picker has no date. Only the late-night window can roll a pickup into tomorrow.
    static Instant endAt(Instant now, ZoneId zone, LocalTime pickup) {
        ZonedDateTime current = now.atZone(zone);
        ZonedDateTime appointment = current.toLocalDate().atTime(pickup).atZone(zone);
        if (appointment.toInstant().isBefore(now)
                && !current.toLocalTime().isBefore(LocalTime.of(23, 0))
                && pickup.isBefore(LocalTime.of(1, 0))) {
            appointment = appointment.plusDays(1);
        }
        return appointment.toInstant().minus(PICKUP_BUFFER);
    }

    static boolean enoughTime(Instant now, Instant end) {
        return !now.plus(MINIMUM_REST).isAfter(end);
    }

    static boolean canStart(Instant now, Instant end, boolean parked, boolean uxAllows, Door door) {
        return parked && uxAllows && door == Door.CLOSED && enoughTime(now, end);
    }

    static End stopCause(Instant now, Instant end, boolean parked, boolean uxAllows, Door door) {
        if (door == Door.OPEN) return End.PASSENGER_DOOR;
        if (!parked || !uxAllows) return End.DRIVE_RESTRICTION;
        if (door != Door.CLOSED) return End.SIGNAL_LOST;
        return !now.isBefore(end) ? End.DEADLINE : null;
    }
}
