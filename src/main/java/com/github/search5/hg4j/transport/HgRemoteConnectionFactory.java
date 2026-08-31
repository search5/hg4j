package com.github.search5.hg4j.transport;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Factory class to dynamically instantiate the appropriate remote connection
 * client (HTTP, SSH, or Local) based on the target URL protocol.
 * Now supports extensibility with pluggable {@link TransportProtocol} registry.
 */
public class HgRemoteConnectionFactory {

    private static final List<TransportProtocol> protocols = new CopyOnWriteArrayList<>();

    static {
        // Register default protocols
        protocols.add(new TransportProtocol() {
            @Override
            public boolean canHandle(String url) {
                return url.startsWith("ssh://");
            }
            @Override
            public HgRemoteConnection open(String url) throws IOException {
                return new HgSshClient(url);
            }
        });
        protocols.add(new TransportProtocol() {
            @Override
            public boolean canHandle(String url) {
                return url.startsWith("file://");
            }
            @Override
            public HgRemoteConnection open(String url) throws IOException {
                return new HgLocalClient(url);
            }
        });
         protocols.add(new TransportProtocol() {
            @Override
            public boolean canHandle(String url) {
                return url.startsWith("http://") || url.startsWith("https://");
            }
            @Override
            public HgRemoteConnection open(String url) throws IOException {
                return new HgRemoteClient(url);
            }
        });
        protocols.add(new TransportProtocol() {
            @Override
            public boolean canHandle(String url) {
                File f = new File(url);
                return f.exists() && f.isDirectory();
            }
            @Override
            public HgRemoteConnection open(String url) throws IOException {
                return new HgLocalClient(url);
            }
        });
    }

    /**
     * Registers a new TransportProtocol with the registry.
     * Custom protocols are registered at the beginning of the list so they take precedence over default protocols.
     *
     * @param protocol The TransportProtocol instance to register
     */
    public static void register(TransportProtocol protocol) {
        if (protocol != null) {
            protocols.add(0, protocol);
        }
    }

    /**
     * Returns an unmodifiable list of all registered TransportProtocols.
     */
    public static List<TransportProtocol> getRegisteredProtocols() {
        return java.util.Collections.unmodifiableList(protocols);
    }

    /**
     * Creates and returns a connection client compatible with the target URL protocol.
     * 
     * @param url the target remote repository URL (ssh://, http://, https://, file://, or local path)
     * @return a concrete {@link HgRemoteConnection} instance
     * @throws IOException if URL protocol is invalid or connection fail
     */
    public static HgRemoteConnection createConnection(String url) throws IOException {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("Connection URL cannot be null or empty.");
        }

        for (TransportProtocol protocol : protocols) {
            if (protocol.canHandle(url)) {
                return protocol.open(url);
            }
        }

        // Default fallback: HTTP client
        return new HgRemoteClient(url);
    }
}
