import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

public class CircuitBreakerRemoteService implements RemoteService {
    private final RemoteService service;
    private final BreakerConfig config;
    private final Clock clock;
    private final Deque<Instant> failures = new ArrayDeque<>();
    private Instant circuitOpenTime;

    public CircuitBreakerRemoteService(RemoteService service, BreakerConfig config) {
        this(service, config, Clock.systemUTC());
    }

    public CircuitBreakerRemoteService(RemoteService service, BreakerConfig config, Clock clock) {
        this.service = service;
        this.config = config;
        this.clock = clock;
    }

    @Override
    public String name() {
        return service.name();
    }

    @Override
    public Response call(Request request) {
        Instant now = clock.instant();

        if (!isCallAllowed(now)) {
            throw new CircuitOpenException(
                    "Service " + name() + " is unavailable. Retry after " + getRemainingCoolDownTime(now) + " ms");
        }

        try {
            return service.call(request);
        } catch (RemoteServiceException ex) {
            recordFailure(now);
            throw ex;
        }
    }

    private boolean isCallAllowed(Instant now) {
        expireOpenPeriod(now);
        return circuitOpenTime == null;
    }

    private void expireOpenPeriod(Instant now) {
        if (circuitOpenTime == null) {
            return;
        }

        Instant reopenTime = circuitOpenTime.plusMillis(config.openMillis());

        if (now.isBefore(reopenTime)) {
            return;
        }

        // cooldown has finished
        circuitOpenTime = null;
        failures.clear();
    }

    private long getRemainingCoolDownTime(Instant now) {
        if (circuitOpenTime == null) {
            return 0;
        }

        Instant reopenTime = circuitOpenTime.plusMillis(config.openMillis());

        long remaining = Duration.between(now, reopenTime).toMillis();

        return Math.max(remaining, 0);
    }

    private void recordFailure(Instant now) {
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
