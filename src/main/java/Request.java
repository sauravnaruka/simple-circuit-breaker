/** A request destined for a named downstream service. */
public record Request(String serviceName, String payload) {
}
