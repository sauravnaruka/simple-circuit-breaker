import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock the tests move by hand, so the window and cooldown rules can be
 * exercised without Thread.sleep. Keeps the suite fast and free of timing flakes.
 */
final class MutableClock extends Clock {

    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    void advance(Duration amount) {
        now = now.plus(amount);
    }

    void advanceMillis(long millis) {
        now = now.plusMillis(millis);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
