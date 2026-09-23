import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

public class CircuitBreakerRemoteService implements RemoteService {
    private enum CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private enum CircuitEvent {
        FAILURE_THRESHOLD_REACHED,
        COOLDOWN_EXPIRED,
        PROBE_SUCCESS,
        PROBE_FAILURE,
        PROBE_IGNORED
    }

    private final RemoteService service;
    private final BreakerConfig config;
    private final Clock clock;
    private final Deque<Instant> failures = new ArrayDeque<>();
    private Instant circuitOpenTime;
    private CircuitState state = CircuitState.CLOSED;
    private final AtomicBoolean halfOpenProbeInProgress = new AtomicBoolean(false);

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
        if (!isCallAllowed()) {
            throw new CircuitOpenException(
                    "Service " + name() + " is unavailable. Retry after " + getRemainingCoolDownTime() + " ms");
        }

        boolean isProbe = (state == CircuitState.HALF_OPEN);

        try {
            Response response = service.call(request);
            recordSuccess();
            return response;
        } catch (RemoteServiceException ex) {
            recordFailure();
            throw ex;
        } catch (RuntimeException ex) {
            if (isProbe) {
                transition(CircuitEvent.PROBE_IGNORED);
            }
            throw ex;
        }
    }

    private boolean isCallAllowed() {
        switch (state) {
            case CLOSED:
                return true;

            case OPEN:
                return expireOpenPeriod();

            case HALF_OPEN:
                return halfOpenProbeInProgress.compareAndSet(false, true);
        }

        return false;
    }

    private boolean expireOpenPeriod() {
        Instant now = clock.instant();
        Instant reopenTime = circuitOpenTime.plusMillis(config.openMillis());

        if (now.isBefore(reopenTime)) {
            return false;
        }

        // cooldown has finished
        transition(CircuitEvent.COOLDOWN_EXPIRED);
        return true;

    }

    private void recordFailure() {
        if (state == CircuitState.HALF_OPEN) {
            transition(CircuitEvent.PROBE_FAILURE);
            return;
        }

        Instant now = clock.instant();
        failures.addLast(now);
        removeExpiredFailures(now);

        if (failures.size() >= config.threshold()) {
            transition(CircuitEvent.FAILURE_THRESHOLD_REACHED);
        }
    }

    private void removeExpiredFailures(Instant now) {
        Instant cutoff = now.minusMillis(config.windowMillis());

        while (!failures.isEmpty() && failures.peekFirst().isBefore(cutoff)) {
            failures.removeFirst();
        }
    }

    private void recordSuccess() {
        if (state == CircuitState.HALF_OPEN) {
            transition(CircuitEvent.PROBE_SUCCESS);
        }
    }

    private long getRemainingCoolDownTime() {
        Instant now = clock.instant();
        if (circuitOpenTime == null) {
            return 0;
        }

        Instant reopenTime = circuitOpenTime.plusMillis(config.openMillis());

        long remaining = Duration.between(now, reopenTime).toMillis();

        return Math.max(remaining, 0);
    }

    private void transition(CircuitEvent event) {
        switch (state) {

            case CLOSED -> {
                if (event == CircuitEvent.FAILURE_THRESHOLD_REACHED) {
                    state = CircuitState.OPEN;
                    circuitOpenTime = clock.instant();
                    failures.clear();
                }
            }

            case OPEN -> {
                if (event == CircuitEvent.COOLDOWN_EXPIRED) {
                    state = CircuitState.HALF_OPEN;
                    circuitOpenTime = null;
                    halfOpenProbeInProgress.set(true);
                }
            }

            case HALF_OPEN -> {
                halfOpenProbeInProgress.set(false);
                if (event == CircuitEvent.PROBE_SUCCESS) {
                    state = CircuitState.CLOSED;
                    circuitOpenTime = null;
                } else if (event == CircuitEvent.PROBE_FAILURE) {
                    state = CircuitState.OPEN;
                    circuitOpenTime = clock.instant();
                }

                // PROBE_IGNORED: permit released, stay HALF_OPEN for the next probe

            }

        }
    }

}
