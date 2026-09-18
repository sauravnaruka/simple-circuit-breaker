/** Thrown by WebClient when a call is blocked because the service is considered unhealthy. */
public class CircuitOpenException extends RuntimeException {
    public CircuitOpenException(String message) {
        super(message);
    }
}
