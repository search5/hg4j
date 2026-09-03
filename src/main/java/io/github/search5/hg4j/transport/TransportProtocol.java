package io.github.search5.hg4j.transport;

import java.io.IOException;

/**
 * Interface for transport protocol plugins.
 * Provides a flexible abstraction to support new protocols (e.g., custom://).
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
