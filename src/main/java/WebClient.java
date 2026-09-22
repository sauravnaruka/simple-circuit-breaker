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

    public WebClient(List<RemoteService> services,
            int failureThreshold,
            long failureWindowMillis,
            long openDurationMillis) {

        BreakerConfig config = new BreakerConfig(failureThreshold, failureWindowMillis, openDurationMillis);
        this.services = services.stream()
                .map(s -> new CircuitBreakerRemoteService(s, config))
                .collect(Collectors.toMap(RemoteService::name, Function.identity()));
    }

    /**
     * Sends the request to the service named by {@link Request#serviceName()}.
     *
     * @return the downstream response
     * @throws CircuitOpenException   if the service is currently considered
     *                                unhealthy
     * @throws RemoteServiceException if the downstream call fails
     */
    public Response execute(Request request) {
        if (!services.containsKey(request.serviceName())) {
            throw new UnsupportedOperationException("execute is not implemented yet");
        }

        return services.get(request.serviceName()).call(request);
    }
}
