/** Thrown by a downstream service when the call fails. Counts as a failure. */
public class RemoteServiceException extends RuntimeException {
    public RemoteServiceException(String message) {
        super(message);
    }
}
