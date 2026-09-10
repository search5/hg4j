package io.github.search5.hg4j.transport;

import java.io.IOException;

/**
 * Interface for transport protocol plugins.
 * Provides a flexible abstraction to support new protocols (e.g., custom://).
 *
 * @apiNote Register a custom implementation via {@link HgRemoteConnectionFactory#register} to
 *     let {@link HgRemoteConnectionFactory#createConnection} dispatch a new URL scheme to it,
 *     ahead of the built-in ssh://, http(s)://, and file:// handlers.
 */
public interface TransportProtocol {
    /**
     * Returns whether this protocol handler can process the specified URL.
     */
    boolean canHandle(String url);

    /**
     * Creates and returns an HgRemoteConnection instance corresponding to the specified URL.
     */
    HgRemoteConnection open(String url) throws IOException;
}
