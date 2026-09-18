import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulated downstream service. Its health is flipped by the scenario runner.
 * Counts how many calls actually reached it, so blocked calls can be verified.
 */
public class FlakyRemoteService implements RemoteService {

    private final String name;
    private volatile boolean healthy = true;
    private final AtomicInteger receivedCalls = new AtomicInteger();

    public FlakyRemoteService(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Response call(Request request) {
        receivedCalls.incrementAndGet();
        if (!healthy) {
            throw new RemoteServiceException(name + " is unavailable");
        }
        return new Response(name + " handled: " + request.payload());
    }

    public void setHealthy(boolean healthy) {
        this.healthy = healthy;
    }

    public int receivedCalls() {
        return receivedCalls.get();
    }
}
