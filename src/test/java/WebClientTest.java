import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The durations here are the real ones from the brief — 3 failures in 10 minutes,
 * blocked for 5 minutes — because a hand-moved clock makes them cost nothing.
 */
@DisplayName("WebClient")
class WebClientTest {

    private static final String RISK = "risk-scoring";
    private static final String SANCTIONS = "sanctions-check";

    private static final int FAILURE_THRESHOLD = 3;
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(10);
    private static final Duration OPEN_DURATION = Duration.ofMinutes(5);

    private MutableClock clock;
    private ScriptedRemoteService riskScoring;
    private ScriptedRemoteService sanctionsCheck;
    private WebClient client;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        riskScoring = new ScriptedRemoteService(RISK);
        sanctionsCheck = new ScriptedRemoteService(SANCTIONS);
        client = new WebClient(
                List.of(riskScoring, sanctionsCheck),
                FAILURE_THRESHOLD,
                FAILURE_WINDOW.toMillis(),
                OPEN_DURATION.toMillis(),
                clock);
    }

    private Response call(String service, String payload) {
        return client.execute(new Request(service, payload));
    }

    /** Drives risk-scoring to the failure threshold, leaving its circuit open. */
    private void tripRiskScoring() {
        riskScoring.willFailRemotely();
        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            assertThrows(RemoteServiceException.class, () -> call(RISK, "trip"));
        }
    }

    @Nested
    @DisplayName("Part 1 — core requirements")
    class Part1 {

        @Test
        @DisplayName("routes the request to the named service and returns its response")
        void routesToTheNamedService() {
            Response response = call(RISK, "transfer-1");

            assertTrue(response.body().contains("transfer-1"), "got: " + response);
            assertTrue(response.body().contains(RISK), "got: " + response);
            assertEquals(1, riskScoring.receivedCalls());
            assertEquals(0, sanctionsCheck.receivedCalls(), "the other service must not be called");
        }

        @Test
        @DisplayName("a RemoteServiceException propagates to the caller")
        void remoteFailurePropagates() {
            riskScoring.willFailRemotely();

            RemoteServiceException thrown =
                    assertThrows(RemoteServiceException.class, () -> call(RISK, "transfer-1"));

            assertTrue(thrown.getMessage().contains(RISK), "got: " + thrown.getMessage());
        }

        @Test
        @DisplayName("fewer failures than the threshold do not block")
        void belowThresholdStaysClosed() {
            riskScoring.willFailRemotely();
            for (int i = 0; i < FAILURE_THRESHOLD - 1; i++) {
                assertThrows(RemoteServiceException.class, () -> call(RISK, "fail"));
            }

            int before = riskScoring.receivedCalls();
            assertThrows(RemoteServiceException.class, () -> call(RISK, "still-allowed"));

            assertEquals(before + 1, riskScoring.receivedCalls(),
                    "the circuit must still be closed");
        }

        @Test
        @DisplayName("3 failures inside the window block the next call")
        void thresholdFailuresOpenTheCircuit() {
            tripRiskScoring();

            assertThrows(CircuitOpenException.class, () -> call(RISK, "transfer-4"));
        }

        @Test
        @DisplayName("a blocked call does not reach the downstream service")
        void blockedCallDoesNotReachDownstream() {
            tripRiskScoring();
            int before = riskScoring.receivedCalls();

            assertThrows(CircuitOpenException.class, () -> call(RISK, "transfer-4"));

            assertEquals(before, riskScoring.receivedCalls());
        }

        @Test
        @DisplayName("failures older than the window do not count toward the threshold")
        void failuresAgeOutOfTheWindow() {
            riskScoring.willFailRemotely();
            assertThrows(RemoteServiceException.class, () -> call(RISK, "old"));

            clock.advance(FAILURE_WINDOW.plusMillis(1));
            assertThrows(RemoteServiceException.class, () -> call(RISK, "recent-1"));
            assertThrows(RemoteServiceException.class, () -> call(RISK, "recent-2"));

            int before = riskScoring.receivedCalls();
            assertThrows(RemoteServiceException.class, () -> call(RISK, "recent-3"));

            assertEquals(before + 1, riskScoring.receivedCalls(),
                    "only 2 failures are inside the window, so this call must reach the service");
        }

        @Test
        @DisplayName("calls are still blocked until the open period has fully elapsed")
        void blockedForTheWholeOpenPeriod() {
            tripRiskScoring();

            clock.advance(OPEN_DURATION.minusMillis(1));

            assertThrows(CircuitOpenException.class, () -> call(RISK, "too-early"));
        }

        @Test
        @DisplayName("calls are allowed again once the open period has elapsed")
        void callsResumeAfterTheOpenPeriod() {
            tripRiskScoring();
            riskScoring.willSucceed();

            clock.advance(OPEN_DURATION);

            assertTrue(call(RISK, "transfer-later").body().contains("transfer-later"));
        }

        @Test
        @DisplayName("each service is tracked independently")
        void servicesAreIndependent() {
            tripRiskScoring();

            Response response = call(SANCTIONS, "transfer-9");

            assertTrue(response.body().contains("transfer-9"), "got: " + response);
            assertThrows(CircuitOpenException.class, () -> call(RISK, "blocked"),
                    "risk-scoring must still be open");
        }
    }

    @Nested
    @DisplayName("Part 2 — half-open trial call")
    class Part2 {

        @Test
        @DisplayName("exactly one trial call is allowed after the cooldown")
        void oneTrialCallAfterTheCooldown() {
            tripRiskScoring();
            clock.advance(OPEN_DURATION);
            int before = riskScoring.receivedCalls();

            assertThrows(RemoteServiceException.class, () -> call(RISK, "trial"));
            assertEquals(before + 1, riskScoring.receivedCalls(),
                    "the trial call must reach the service");

            assertThrows(CircuitOpenException.class, () -> call(RISK, "second"));
            assertEquals(before + 1, riskScoring.receivedCalls(),
                    "no call after the trial may reach the service");
        }

        @Test
        @DisplayName("calls arriving while the trial is in flight are blocked, not queued")
        void callsDuringTheTrialAreBlocked() {
            tripRiskScoring();
            clock.advance(OPEN_DURATION);
            riskScoring.willSucceed();
            int before = riskScoring.receivedCalls();

            AtomicReference<Throwable> duringTrial = new AtomicReference<>();
            riskScoring.duringNextCall(() -> duringTrial.set(
                    assertThrows(Throwable.class, () -> call(RISK, "arrives-during-trial"))));

            call(RISK, "trial");

            assertInstanceOf(CircuitOpenException.class, duringTrial.get(),
                    "a call during the trial must be rejected outright");
            assertEquals(before + 1, riskScoring.receivedCalls(),
                    "only the trial call may reach the service");
        }

        @Test
        @DisplayName("a successful trial closes the circuit")
        void successfulTrialClosesTheCircuit() {
            tripRiskScoring();
            clock.advance(OPEN_DURATION);
            riskScoring.willSucceed();

            call(RISK, "trial");

            for (int i = 0; i < 5; i++) {
                assertTrue(call(RISK, "after-" + i).body().contains("after-" + i));
            }
        }

        @Test
        @DisplayName("a successful trial clears the failure history")
        void successfulTrialClearsFailureHistory() {
            tripRiskScoring();
            clock.advance(OPEN_DURATION);
            riskScoring.willSucceed();
            call(RISK, "trial");

            riskScoring.willFailRemotely();
            for (int i = 0; i < FAILURE_THRESHOLD - 1; i++) {
                assertThrows(RemoteServiceException.class, () -> call(RISK, "fresh-fail"));
            }

            int before = riskScoring.receivedCalls();
            assertThrows(RemoteServiceException.class, () -> call(RISK, "third-fresh-fail"));

            assertEquals(before + 1, riskScoring.receivedCalls(),
                    "a full fresh threshold is needed to trip again");
        }

        @Test
        @DisplayName("a failed trial blocks again for the full open period")
        void failedTrialReopensForTheFullPeriod() {
            tripRiskScoring();
            clock.advance(OPEN_DURATION);
            assertThrows(RemoteServiceException.class, () -> call(RISK, "trial"));

            clock.advance(OPEN_DURATION.minusMillis(1));
            assertThrows(CircuitOpenException.class, () -> call(RISK, "too-early"));

            clock.advanceMillis(1);
            riskScoring.willSucceed();
            assertTrue(call(RISK, "now-allowed").body().contains("now-allowed"));
        }
    }

    /**
     * Beyond the brief: our own rule that only a RemoteServiceException counts as
     * the service being unhealthy. Anything else means the service answered — a 4xx
     * says the request was wrong, not that the service is down — so it must neither
     * trip the circuit nor decide the outcome of a trial.
     */
    @Nested
    @DisplayName("failures that do not indicate an unhealthy service")
    class IgnoredFailures {

        @Test
        @DisplayName("do not count toward the threshold while closed")
        void ignoredFailuresDoNotTrip() {
            riskScoring.willFailWith(() -> new IllegalArgumentException("400 bad request"));
            for (int i = 0; i < FAILURE_THRESHOLD + 2; i++) {
                assertThrows(IllegalArgumentException.class, () -> call(RISK, "bad-request"));
            }

            int before = riskScoring.receivedCalls();
            assertThrows(IllegalArgumentException.class, () -> call(RISK, "still-allowed"));

            assertEquals(before + 1, riskScoring.receivedCalls(),
                    "the circuit must still be closed");
        }

        @Test
        @DisplayName("leave the circuit half-open when they happen during a trial")
        void ignoredFailureDuringTrialKeepsTheCircuitHalfOpen() {
            tripRiskScoring();
            clock.advance(OPEN_DURATION);

            riskScoring.willFailWith(() -> new IllegalArgumentException("400 bad request"));
            assertThrows(IllegalArgumentException.class, () -> call(RISK, "trial"));

            // The trial proved nothing either way, so the next call gets to be the
            // trial instead — with no further cooldown.
            riskScoring.willSucceed();
            int before = riskScoring.receivedCalls();
            assertTrue(call(RISK, "next-trial").body().contains("next-trial"));
            assertEquals(before + 1, riskScoring.receivedCalls());
        }

        @Test
        @DisplayName("release the trial permit even when the failure is an Error")
        void errorDuringTrialReleasesThePermit() {
            tripRiskScoring();
            clock.advance(OPEN_DURATION);

            riskScoring.willFailWith(() -> new StackOverflowError("boom"));
            assertThrows(StackOverflowError.class, () -> call(RISK, "trial"));

            riskScoring.willSucceed();
            int before = riskScoring.receivedCalls();
            assertTrue(call(RISK, "next-trial").body().contains("next-trial"));
            assertEquals(before + 1, riskScoring.receivedCalls());
        }
    }
}
