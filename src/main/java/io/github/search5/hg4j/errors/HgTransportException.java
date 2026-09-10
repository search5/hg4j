package io.github.search5.hg4j.errors;

import java.io.IOException;

/**
 * Base exception class for network and wire protocol control errors.
 * Parent class for all exceptions occurring in the HTTP/SSH transport layer.
 *
 * @apiNote Instantiated directly by {@code HgRemoteClient} for generic transport failures such
 *     as a malformed remote URL, and is the common supertype for the more specific {@link
 *     HgAuthException} and {@link HgProtocolException}. A caller of a networked porcelain
 *     command (e.g. {@code PullCommand}, {@code PushCommand}, {@code FetchCommand}, {@code
 *     CloneCommand}) can catch this single type to handle every transport-layer failure, or
 *     catch the two specific subtypes separately when it needs to react differently to a
 *     credential failure versus a protocol failure.
 */
public class HgTransportException extends IOException {
    private static final long serialVersionUID = 1L;

    private final String remoteUrl;

    /**
     * @param remoteUrl Remote repository URL
     * @param message   Error description
     */
    public HgTransportException(String remoteUrl, String message) {
        super("Transport error for '" + remoteUrl + "': " + message);
        this.remoteUrl = remoteUrl;
    }

    /**
     * @param remoteUrl Remote repository URL
     * @param message   Error description
     * @param cause     The causing exception
     */
    public HgTransportException(String remoteUrl, String message, Throwable cause) {
        super("Transport error for '" + remoteUrl + "': " + message, cause);
        this.remoteUrl = remoteUrl;
    }

    /** Returns the remote repository URL. */
    public String getRemoteUrl() {
        return remoteUrl;
    }
}
