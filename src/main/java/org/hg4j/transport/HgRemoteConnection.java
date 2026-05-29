package org.hg4j.transport;

import java.io.IOException;
import java.util.List;
import org.hg4j.errors.HgAuthException;
import org.hg4j.errors.HgProtocolException;

/**
 * Common connection interface for remote Mercurial repositories,
 * supporting both HTTP and SSH protocols dynamically.
 */
public interface HgRemoteConnection extends java.io.Closeable {

    /**
     * Executes the 'capabilities' command on the remote server.
     */
    List<String> getCapabilities() throws IOException, HgAuthException, HgProtocolException;

    /**
     * Executes the 'heads' command on the remote server.
     */
    List<String> getHeads() throws IOException, HgAuthException, HgProtocolException;

    /**
     * Downloads a changegroup bundle for specified head revisions.
     */
    byte[] getChangegroup(List<String> roots) throws IOException, HgAuthException, HgProtocolException;

    /**
     * Executes the 'getbundle' command to download a bundle (changelog, manifest, filelogs).
     */
    byte[] getBundle(List<String> common, List<String> heads, List<String> bundleCaps) throws IOException, HgAuthException, HgProtocolException;

    /**
     * Pushes a changegroup bundle to the remote repository.
     */
    String push(byte[] bundleBytes, List<String> heads) throws IOException, HgAuthException, HgProtocolException;

    /**
     * Queries remote keys/values for the given namespace (e.g. "bookmarks", "phases").
     */
    java.util.Map<String, String> listKeys(String namespace) throws IOException, HgAuthException, HgProtocolException;

    /**
     * Executes the 'between' command to query revisions between pairs of nodes.
     */
    default List<String> between(List<String> pairs) throws IOException {
        return java.util.Collections.emptyList();
    }

    /**
     * Executes the 'known' command to check if remote knows specified nodes.
     */
    default String known(List<String> nodes) throws IOException {
        return "";
    }

    /**
     * Sets the credentials provider for authenticating with the remote repository.
     */
    default void setCredentialsProvider(CredentialsProvider provider) {
        // Default no-op
    }
}
