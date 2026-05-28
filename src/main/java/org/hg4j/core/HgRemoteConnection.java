package org.hg4j.core;

import java.io.IOException;
import java.util.List;

/**
 * Common connection interface for remote Mercurial repositories,
 * supporting both HTTP and SSH protocols dynamically.
 */
public interface HgRemoteConnection {

    /**
     * Executes the 'capabilities' command on the remote server.
     */
    List<String> getCapabilities() throws IOException;

    /**
     * Executes the 'heads' command on the remote server.
     */
    List<String> getHeads() throws IOException;

    /**
     * Downloads a changegroup bundle for specified head revisions.
     */
    byte[] getChangegroup(List<String> roots) throws IOException;

    /**
     * Executes the 'getbundle' command to download a bundle (changelog, manifest, filelogs).
     */
    byte[] getBundle(List<String> common, List<String> heads, List<String> bundleCaps) throws IOException;

    /**
     * Pushes a changegroup bundle to the remote repository.
     */
    String push(byte[] bundleBytes, List<String> heads) throws IOException;
}
