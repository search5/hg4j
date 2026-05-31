package com.github.search5.hg4j.errors;

/**
 * Exception thrown on protocol errors, such as capabilities negotiation failure or wire protocol violations.
 * Subclass of {@link HgTransportException}.
 */
public class HgProtocolException extends HgTransportException {
    private static final long serialVersionUID = 1L;

    /**
     * @param remoteUrl      Remote repository URL where the protocol error occurred
     * @param protocolDetail Detailed description of the protocol error
     */
    public HgProtocolException(String remoteUrl, String protocolDetail) {
        super(remoteUrl, protocolDetail);
    }

    /**
     * @param remoteUrl      Remote repository URL where the protocol error occurred
     * @param protocolDetail Detailed description of the protocol error
     * @param cause          The causing exception
     */
    public HgProtocolException(String remoteUrl, String protocolDetail, Throwable cause) {
        super(remoteUrl, protocolDetail, cause);
    }
}
