import java.util.List;

/**
 * Scenario runner. Durations are shrunk so a run takes ~10 seconds:
 * failure window = 2s (stands in for 10 minutes), open duration = 1.5s (stands in for 5 minutes).
 *
 * Read this if a scenario fails, but do not weaken the checks.
 */
public class Main {

    private static final int FAILURE_THRESHOLD = 3;
    private static final long FAILURE_WINDOW_MILLIS = 2_000;
    private static final long OPEN_DURATION_MILLIS = 1_500;

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        healthyCallsPassThrough();
        threeFailuresInWindowBlockFurtherCalls();
        servicesAreIndependent();
        failuresOutsideTheWindowDoNotCount();
        callsResumeAfterTheOpenPeriod();

        System.out.println();
        System.out.println("passed: " + passed + "   failed: " + failed);
    }

    // --- scenarios -------------------------------------------------------

    private static void healthyCallsPassThrough() {
        String name = "scenario 1: healthy calls pass through";
        Fixture f = new Fixture();
        try {
            Response response = f.client.execute(new Request("risk-scoring", "transfer-1"));
            check(name, response != null && response.body().contains("transfer-1"),
                    "expected a response from the service, got: " + response);
        } catch (Exception e) {
            fail(name, "expected success, got " + e);
        }
    }

    private static void threeFailuresInWindowBlockFurtherCalls() {
        String name = "scenario 2: 3 failures in the window block the next call";
        Fixture f = new Fixture();
        f.riskScoring.setHealthy(false);

        for (int i = 1; i <= FAILURE_THRESHOLD; i++) {
            try {
                f.client.execute(new Request("risk-scoring", "transfer-" + i));
                fail(name, "call " + i + " should have failed with RemoteServiceException");
                return;
            } catch (RemoteServiceException expected) {
                // failure propagates to the caller, as required
            } catch (Exception e) {
                fail(name, "call " + i + " threw " + e);
                return;
            }
        }

        int callsBefore = f.riskScoring.receivedCalls();
        try {
            f.client.execute(new Request("risk-scoring", "transfer-4"));
            fail(name, "the 4th call should have been blocked with CircuitOpenException");
        } catch (CircuitOpenException expected) {
            check(name, f.riskScoring.receivedCalls() == callsBefore,
                    "a blocked call must not reach the downstream service");
        } catch (Exception e) {
            fail(name, "expected CircuitOpenException, got " + e);
        }
    }

    private static void servicesAreIndependent() {
        String name = "scenario 3: one open service does not affect the other";
        Fixture f = new Fixture();
        f.riskScoring.setHealthy(false);

        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            swallow(() -> f.client.execute(new Request("risk-scoring", "x")));
        }

        try {
            Response response = f.client.execute(new Request("sanctions-check", "transfer-9"));
            check(name, response != null && response.body().contains("transfer-9"),
                    "sanctions-check should still be callable, got: " + response);
        } catch (Exception e) {
            fail(name, "sanctions-check should still be callable, got " + e);
        }
    }

    private static void failuresOutsideTheWindowDoNotCount() {
        String name = "scenario 4: failures older than the window do not count";
        Fixture f = new Fixture();
        f.riskScoring.setHealthy(false);

        swallow(() -> f.client.execute(new Request("risk-scoring", "old")));
        sleep(FAILURE_WINDOW_MILLIS + 300);
        swallow(() -> f.client.execute(new Request("risk-scoring", "recent-1")));
        swallow(() -> f.client.execute(new Request("risk-scoring", "recent-2")));

        int callsBefore = f.riskScoring.receivedCalls();
        try {
            f.client.execute(new Request("risk-scoring", "recent-3"));
            fail(name, "expected RemoteServiceException from the still-unhealthy service");
        } catch (RemoteServiceException expected) {
            check(name, f.riskScoring.receivedCalls() == callsBefore + 1,
                    "only 2 failures are inside the window, so this call must reach the service");
        } catch (CircuitOpenException e) {
            fail(name, "blocked too early: the first failure has aged out of the window");
        } catch (Exception e) {
            fail(name, "unexpected " + e);
        }
    }

    private static void callsResumeAfterTheOpenPeriod() {
        String name = "scenario 5: calls resume once the open period has elapsed";
        Fixture f = new Fixture();
        f.riskScoring.setHealthy(false);

        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            swallow(() -> f.client.execute(new Request("risk-scoring", "x")));
        }

        f.riskScoring.setHealthy(true);
        sleep(OPEN_DURATION_MILLIS + 300);

        try {
            Response response = f.client.execute(new Request("risk-scoring", "transfer-later"));
            check(name, response != null && response.body().contains("transfer-later"),
                    "expected the recovered service to answer, got: " + response);
        } catch (Exception e) {
            fail(name, "expected success after the open period, got " + e);
        }
    }

    // --- harness ---------------------------------------------------------

    private static final class Fixture {
        final FlakyRemoteService riskScoring = new FlakyRemoteService("risk-scoring");
        final FlakyRemoteService sanctionsCheck = new FlakyRemoteService("sanctions-check");
        final WebClient client = new WebClient(
                List.of(riskScoring, sanctionsCheck),
                FAILURE_THRESHOLD,
                FAILURE_WINDOW_MILLIS,
                OPEN_DURATION_MILLIS);
    }

    private static void check(String name, boolean condition, String message) {
        if (condition) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            fail(name, message);
        }
    }

    private static void fail(String name, String message) {
        failed++;
        System.out.println("FAIL  " + name + "  ->  " + message);
    }

    private static void swallow(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // expected in setup steps
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
