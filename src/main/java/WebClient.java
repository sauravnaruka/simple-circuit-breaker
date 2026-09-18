import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Routes requests to downstream services.
 *
 * TODO: implement execute(Request) per the requirements in README.md.
 */
public class WebClient {

    private final Map<String, RemoteService> services;
    private final int failureThreshold;
    private final long failureWindowMillis;
    private final long openDurationMillis;

    public WebClient(List<RemoteService> services,
                     int failureThreshold,
                     long failureWindowMillis,
                     long openDurationMillis) {
        this.services = services.stream()
                .collect(Collectors.toMap(RemoteService::name, Function.identity()));
        this.failureThreshold = failureThreshold;
        this.failureWindowMillis = failureWindowMillis;
        this.openDurationMillis = openDurationMillis;
    }

    /**
     * Sends the request to the service named by {@link Request#serviceName()}.
     *
     * @return the downstream response
     * @throws CircuitOpenException   if the service is currently considered unhealthy
     * @throws RemoteServiceException if the downstream call fails
     */
    public Response execute(Request request) {
        // TODO: implement
        throw new UnsupportedOperationException("execute is not implemented yet");
    }
}
