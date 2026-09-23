import java.time.Clock;
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

        this(services, failureThreshold, failureWindowMillis, openDurationMillis, Clock.systemUTC());
    }

    public WebClient(List<RemoteService> services,
            int failureThreshold,
            long failureWindowMillis,
            long openDurationMillis,
            Clock clock) {

        BreakerConfig config = new BreakerConfig(failureThreshold, failureWindowMillis, openDurationMillis);
        this.services = services.stream()
                .map(s -> new CircuitBreakerRemoteService(s, config, clock))
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
        RemoteService service = services.get(request.serviceName());
        if (service == null) {
            throw new IllegalArgumentException("Unknown service: " + request.serviceName());
        }

        return service.call(request);
    }
}
