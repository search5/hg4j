package io.github.search5.hg4j.errors;

/**
 * Exception thrown on protocol errors, such as capabilities negotiation failure or wire protocol violations.
 * Subclass of {@link HgTransportException}.
 *
 * @apiNote Thrown by the client-side transports ({@code HgRemoteClient}, {@code
 *     HgRemoteClientV2}, {@code HgSshClient}) on a malformed or unexpected server response, by
 *     {@code HgHttpWireServer} when the incoming request violates the wire protocol, and by the
 *     wire protocol v2 layer ({@code Wire2Commands}, {@code Wire2Transport}) on CBOR
 *     framing/command errors. {@code ClonebundlesCommand} also throws it when a clonebundles
 *     manifest entry cannot be fetched over its declared protocol. Unlike {@link
 *     HgAuthException}, this indicates a protocol-level mismatch rather than a rejected
 *     credential, so retrying with different credentials will not help.
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
