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
     * Executes the 'branchmap' wire command: for each named branch the remote knows about,
     * returns its current (topological) head node hexes, closed heads included -- mirrors real
     * hg's own {@code branchmap} wire command ({@code mercurial/wireprotov1server.py}), used by
     * {@link io.github.search5.hg4j.api.PushCommand} the same way real hg's own push-side
     * {@code discovery.checkheads()} uses {@code remote.branchmap()}: to detect a push that
     * would introduce a brand-new named branch on the remote (needs {@code --new-branch}) or
     * that would increase a branch's head count (needs {@code --force}).
     *
     * @return {@code null} if the remote doesn't support this call (real hg's own {@code
     *         remote.capable(b'branchmap')} equivalent -- callers must fall back to a
     *         topological-only, branch-unaware check), otherwise a map of branch name to that
     *         branch's current head node hexes (never {@code null} values, possibly an empty
     *         map for a genuinely empty remote).
     */
    default Map<String, List<String>> getBranchHeads() throws IOException {
        return null;
    }

    /**
     * Sets the credentials provider for authenticating with the remote repository.
     */
    default void setCredentialsProvider(CredentialsProvider provider) {
        // Default no-op
    }

    /**
     * Whether the remote advertised the {@code "clonebundles"} v1 capability token (available
     * only after {@link #getCapabilities()} has been called at least once). Real hg's own client
     * checks this generically via {@code remote.capable(b'clonebundles')} regardless of transport
     * ({@code mercurial/exchange.py}'s {@code trypullbundlefromurl}) -- confirmed 2026-09-05 by
     * reading that source directly -- so the bypass is not an HTTP-only feature in real hg, and
     * this interface-level default (overridden by {@link HgRemoteClient} and {@link HgSshClient},
     * the two transports that actually support it) keeps hg4j's client from artificially
     * restricting the bypass to HTTP the way an earlier version of {@link
     * io.github.search5.hg4j.api.FetchCommand} did (an {@code instanceof HgRemoteClient} check
     * instead of this capability, backlog item 39 wave 5 wire-matrix track).
     */
    default boolean supportsClonebundles() {
        return false;
    }

    /**
     * Fetches the raw text of the remote's clonebundles manifest via the {@code clonebundles} wire
     * command (the underlying transport framing differs between HTTP's {@code ?cmd=clonebundles}
     * and SSH's line-based v1 command protocol, but the semantics -- return {@code
     * .hg/clonebundles.manifest}'s content verbatim -- are identical). Only meaningful when {@link
     * #supportsClonebundles()} is {@code true}; the default here throws since the base interface
     * has no transport to actually issue the command on.
     */
    default String fetchClonebundlesManifest() throws IOException {
        throw new UnsupportedOperationException("This connection does not support the clonebundles wire command");
    }
}
