package org.hg4j.transport;

import java.io.IOException;

/**
 * Factory class to dynamically instantiate the appropriate remote connection
 * client (HTTP or SSH) based on the target URL protocol.
 */
public class HgRemoteConnectionFactory {

    /**
     * Creates and returns a connection client compatible with the target URL protocol.
     * 
     * @param url the target remote repository URL (ssh:// or http:// or https://)
     * @return a concrete {@link HgRemoteConnection} instance
     * @throws IOException if URL protocol is invalid or connection fail
     */
    public static HgRemoteConnection createConnection(String url) throws IOException {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("Connection URL cannot be null or empty.");
        }

        if (url.startsWith("ssh://")) {
            return new HgSshClient(url);
        } else {
            return new HgRemoteClient(url);
        }
    }
}
