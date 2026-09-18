/** A downstream service the WebClient can call. */
public interface RemoteService {

    String name();

    /**
     * Performs the remote call.
     *
     * @throws RemoteServiceException when the downstream call fails
     */
    Response call(Request request);
}
