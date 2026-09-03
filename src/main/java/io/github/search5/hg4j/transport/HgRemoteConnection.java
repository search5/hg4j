package io.github.search5.hg4j.transport;

import java.io.IOException;
import java.util.List;
import io.github.search5.hg4j.errors.HgAuthException;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgProtocolException;
import java.io.Closeable;
import java.util.Collections;
import java.util.Map;

/**
 * Common connection interface for remote Mercurial repositories,
 * supporting both HTTP and SSH protocols dynamically.
 */
public interface HgRemoteConnection extends Closeable {

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
    String push(byte[] bundleBytes, List<String> heads) throws IOException, HgAuthException, HgProtocolException, HgLockException;

    /**
     * Queries remote keys/values for the given namespace (e.g. "bookmarks", "phases").
     */
    Map<String, String> listKeys(String namespace) throws IOException, HgAuthException, HgProtocolException;

    /**
     * Executes the 'pushkey' command to update a key/value pair in a remote namespace (e.g. "bookmarks").
     * Returns true if the remote accepted the update, false otherwise.
     */
    boolean pushkey(String namespace, String key, String oldVal, String newVal) throws IOException;

    /**
     * Executes the 'between' command to query revisions between pairs of nodes.
     */
    default List<String> between(List<String> pairs) throws IOException {
        return Collections.emptyList();
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
