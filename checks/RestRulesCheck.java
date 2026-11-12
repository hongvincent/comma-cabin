package app.commacabin;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

public final class RestRulesCheck {
    public static void main(String[] args) {
        ZoneId seoul = ZoneId.of("Asia/Seoul");
        Instant now = Instant.parse("2026-11-12T10:26:50Z");
        Instant end = RestRules.endAt(now, seoul, LocalTime.of(19, 30));
        check(end.equals(Instant.parse("2026-11-12T10:28:00Z")), "19:30 pickup → 19:28 end");
        check(RestRules.canStart(now, end, true, true, RestRules.Door.CLOSED), "parked/closed/70s left");
        check(RestRules.canStart(end.minusSeconds(60), end, true, true, RestRules.Door.CLOSED), "60s is enough");
        check(!RestRules.canStart(end.minusMillis(59_999), end, true, true, RestRules.Door.CLOSED), "under 60s blocked");
        check(!RestRules.canStart(now, end, true, true, RestRules.Door.UNKNOWN), "unknown door blocked");
        check(!RestRules.canStart(now, end, true, true, RestRules.Door.UNSUPPORTED), "unsupported door blocked");
        check(!RestRules.canStart(now, end, false, true, RestRules.Door.CLOSED), "non-P blocked");
        check(RestRules.stopCause(end.minusMillis(1), end, true, true, RestRules.Door.CLOSED) == null, "plays until deadline");
        check(RestRules.stopCause(end, end, true, true, RestRules.Door.CLOSED) == RestRules.End.DEADLINE, "deadline inclusive");
        check(RestRules.stopCause(now, end, true, true, RestRules.Door.OPEN) == RestRules.End.PASSENGER_DOOR, "door preempts timer");
        check(RestRules.stopCause(now, end, true, false, RestRules.Door.CLOSED) == RestRules.End.DRIVE_RESTRICTION, "UX restrictions stop");
        check(RestRules.stopCause(now, end, true, true, RestRules.Door.UNKNOWN) == RestRules.End.SIGNAL_LOST, "lost door state stops");
        Instant late = Instant.parse("2026-11-12T14:58:00Z");
        check(Duration.between(late, RestRules.endAt(late, seoul, LocalTime.of(0, 2))).toMinutes() == 2, "rollover to next day");
        System.out.println("PASS: deadline, eligibility, door stop, drive restriction, signal loss, midnight");
    }
    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
}
