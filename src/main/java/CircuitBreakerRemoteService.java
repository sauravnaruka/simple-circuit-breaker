import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

public class CircuitBreakerRemoteService implements RemoteService {
    private final RemoteService service;
    private final BreakerConfig config;
    private final Deque<Instant> failures = new ArrayDeque<>();
    private Instant circuitOpenTime;

    public CircuitBreakerRemoteService(RemoteService service, BreakerConfig config) {
        this.service = service;
        this.config = config;
    }

    @Override
    public String name() {
        return service.name();
    }

    @Override
    public Response call(Request request) {
        if (isCircuitOpen()) {
            throw new CircuitOpenException(
                    "Service " + name() + " is unavailable. Retry after " + getRemainingCoolDownTime() + " ms");
        }

        try {
            return service.call(request);
        } catch (Exception ex) {
            recordFailure();
            throw ex;
        }
    }

    private boolean isCircuitOpen() {
        if (circuitOpenTime == null) {
            return false;
        }

        Instant reopenTime = circuitOpenTime.plusMillis(config.openMillis());

        if (Instant.now().isBefore(reopenTime)) {
            return true;
        }

        // cooldown has finished
        circuitOpenTime = null;
        failures.clear();

        return false;
    }

    private long getRemainingCoolDownTime() {
        if (circuitOpenTime == null) {
            return 0;
        }

        Instant reopenTime = circuitOpenTime.plusMillis(config.openMillis());

        long remaining = Duration.between(Instant.now(), reopenTime).toMillis();

        return Math.max(remaining, 0);
    }

    private void recordFailure() {
        Instant now = Instant.now();

        failures.addLast(now);
        removeExpiredFailures(now);

        if (failures.size() >= config.threshold()) {
            circuitOpenTime = now;
        }
    }

    private void removeExpiredFailures(Instant now) {
        Instant cutoff = now.minusMillis(config.windowMillis());

        while (!failures.isEmpty() && failures.peekFirst().isBefore(cutoff)) {
            failures.removeFirst();
        }
    }
}
