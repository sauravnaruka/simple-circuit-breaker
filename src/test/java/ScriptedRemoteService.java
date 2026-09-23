import java.util.function.Supplier;

/**
 * Downstream double whose next outcome the test chooses, and which counts how
 * many calls actually reached it so blocked calls can be verified.
 *
 * {@link #duringNextCall(Runnable)} runs an action from inside the call, which
 * lets a test observe what a second caller sees while a call is still in flight.
 */
final class ScriptedRemoteService implements RemoteService {

    private final String name;
    private Supplier<Throwable> failure;
    private Runnable duringNextCall = () -> {
    };
    private int receivedCalls;

    ScriptedRemoteService(String name) {
        this.name = name;
    }

    void willSucceed() {
        failure = null;
    }

    void willFailRemotely() {
        failure = () -> new RemoteServiceException(name + " is unavailable");
    }

    void willFailWith(Supplier<Throwable> thrown) {
        failure = thrown;
    }

    /** Runs {@code action} once, from inside the next call, before that call returns. */
    void duringNextCall(Runnable action) {
        duringNextCall = action;
    }

    int receivedCalls() {
        return receivedCalls;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Response call(Request request) {
        receivedCalls++;

        Runnable hook = duringNextCall;
        duringNextCall = () -> {
        };
        hook.run();

        if (failure != null) {
            Throwable thrown = failure.get();
            if (thrown instanceof RuntimeException ex) {
                throw ex;
            }
            throw (Error) thrown;
        }

        return new Response(name + " handled: " + request.payload());
    }
}
