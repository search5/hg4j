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
     *
     * @return the whitespace-separated capability tokens advertised by the remote (an empty list
     *         if the remote advertised none)
     * @throws IOException if the underlying transport fails
     * @throws HgAuthException if the remote requires authentication that was not supplied or was rejected
     * @throws HgProtocolException if the remote's response cannot be parsed as a capabilities list
     */
    List<String> getCapabilities() throws IOException, HgAuthException, HgProtocolException;

    /**
     * Executes the 'heads' command on the remote server.
     *
     * @return the hex node IDs of the remote's current topological heads
     * @throws IOException if the underlying transport fails
     * @throws HgAuthException if the remote requires authentication that was not supplied or was rejected
     * @throws HgProtocolException if the remote's response cannot be parsed as a heads list
     */
    List<String> getHeads() throws IOException, HgAuthException, HgProtocolException;

    /**
     * Downloads a changegroup bundle for specified head revisions.
     *
     * @param roots hex node IDs the caller already has locally (the changegroup boundary); an
     *              empty or {@code null} list requests the entire history
     * @return the raw changegroup bundle bytes
     * @throws IOException if the underlying transport fails
     * @throws HgAuthException if the remote requires authentication that was not supplied or was rejected
     * @throws HgProtocolException if the remote's response cannot be parsed as a changegroup
     */
    byte[] getChangegroup(List<String> roots) throws IOException, HgAuthException, HgProtocolException;

    /**
     * Executes the 'getbundle' command to download a bundle (changelog, manifest, filelogs).
     *
     * @param common hex node IDs already known to be shared between caller and remote, used to
     *               bound the response to unseen changesets
     * @param heads hex node IDs of the heads the caller wants the bundle to cover; {@code null}
     *              or empty requests all of the remote's heads
     * @param bundleCaps bundle-format capability tokens the caller supports, controlling which
     *                   bundle version/compression the remote may respond with
     * @return the raw bundle bytes (changelog, manifest, and filelog data)
     * @throws IOException if the underlying transport fails
     * @throws HgAuthException if the remote requires authentication that was not supplied or was rejected
     * @throws HgProtocolException if the remote's response cannot be parsed as a bundle
     */
    byte[] getBundle(List<String> common, List<String> heads, List<String> bundleCaps) throws IOException, HgAuthException, HgProtocolException;

    /**
     * Same as {@link #getBundle(List, List, List)}, but additionally negotiates real hg's narrow
     * clone wire arguments ({@code narrow}, {@code includepats}, {@code excludepats} -- part of
     * core {@code wireprototypes.GETBUNDLE_ARGUMENTS}, per Mercurial 7.2's {@code
     * mercurial/wireprototypes.py}/{@code exchange.py}) when {@code
     * narrowScope} is non-{@code null} and the remote advertised {@link #supportsNarrow()}.
     *
     * <p>This is what makes narrow clone/pull actually reduce wire transfer size: a remote that
     * understands these arguments (real hg with the bundled {@code narrow} extension enabled)
     * actually omits out-of-narrowspec filelog data from the response,
     * rather than hg4j always fetching everything and discarding out-of-scope content locally
     * after the fact.
     *
     * <p>The default implementation ignores {@code narrowScope} and delegates to the plain
     * 3-argument overload -- correct (if bandwidth-suboptimal) behavior for any implementation
     * that hasn't been taught the narrow wire arguments.
     *
     * @param common hex node IDs already known to be shared between caller and remote
     * @param heads hex node IDs of the heads the caller wants the bundle to cover
     * @param bundleCaps bundle-format capability tokens the caller supports
     * @param narrowScope the include/exclude narrowspec patterns to negotiate, or {@code null}
     *                    for a full (non-narrow) bundle
     * @return the raw bundle bytes (narrowed to {@code narrowScope} when the remote honors it)
     * @throws IOException if the underlying transport fails
     * @throws HgAuthException if the remote requires authentication that was not supplied or was rejected
     * @throws HgProtocolException if the remote's response cannot be parsed as a bundle
     */
    default byte[] getBundle(List<String> common, List<String> heads, List<String> bundleCaps,
                              NarrowScope narrowScope) throws IOException, HgAuthException, HgProtocolException {
        return getBundle(common, heads, bundleCaps);
    }

    /**
     * A narrowspec scope to negotiate with a remote's {@code getbundle} wire command: the
     * {@code includepats}/{@code excludepats} argument values, each already in real hg's
     * {@code "kind:path"} textual form (e.g. {@code "path:src"}) -- see {@link
     * io.github.search5.hg4j.treewalk.HgTreeFilter.NarrowPattern#toSpecString()}.
     */
    final class NarrowScope {
        /** Include patterns, in real hg's {@code "kind:path"} textual form. */
        public final List<String> includePatterns;
        /** Exclude patterns, in real hg's {@code "kind:path"} textual form. */
        public final List<String> excludePatterns;

        /**
         * Creates a narrowspec scope from include/exclude pattern lists.
         *
         * @param includePatterns patterns to include, or {@code null} to treat as empty
         * @param excludePatterns patterns to exclude, or {@code null} to treat as empty
         */
        public NarrowScope(List<String> includePatterns, List<String> excludePatterns) {
            this.includePatterns = includePatterns != null ? includePatterns : Collections.emptyList();
            this.excludePatterns = excludePatterns != null ? excludePatterns : Collections.emptyList();
        }
    }

    /**
     * Pushes a changegroup bundle to the remote repository.
     *
     * @param bundleBytes the raw changegroup/bundle bytes to send
     * @param heads hex node IDs of the heads the caller expects the remote to have after the push,
     *              used by servers that support unbundle hash verification
     * @return the remote's response to the unbundle command (typically {@code "0\n"} or an error
     *         message on failure, an empty string or output text on success, depending on protocol version)
     * @throws IOException if the underlying transport fails
     * @throws HgAuthException if the remote requires authentication that was not supplied or was rejected
     * @throws HgProtocolException if the remote's response cannot be parsed
     * @throws HgLockException if the remote could not acquire its repository lock for the push
     */
    String push(byte[] bundleBytes, List<String> heads) throws IOException, HgAuthException, HgProtocolException, HgLockException;

    /**
     * Queries remote keys/values for the given namespace (e.g. "bookmarks", "phases").
     *
     * @param namespace the pushkey namespace to list (e.g. {@code "bookmarks"}, {@code "phases"})
     * @return a map of key to value for every entry the remote reports in that namespace (empty if none)
     * @throws IOException if the underlying transport fails
     * @throws HgAuthException if the remote requires authentication that was not supplied or was rejected
     * @throws HgProtocolException if the remote's response cannot be parsed as a keys listing
     */
    Map<String, String> listKeys(String namespace) throws IOException, HgAuthException, HgProtocolException;

    /**
     * Executes the 'pushkey' command to update a key/value pair in a remote namespace (e.g. "bookmarks").
     * Returns true if the remote accepted the update, false otherwise.
     *
     * @param namespace the pushkey namespace to update (e.g. {@code "bookmarks"}, {@code "phases"})
     * @param key the key within the namespace to update
     * @param oldVal the value the caller expects the key to currently have (used for
     *               compare-and-swap semantics by the remote); may be empty
     * @param newVal the value to set the key to
     * @return {@code true} if the remote accepted the update, {@code false} otherwise
     * @throws IOException if the underlying transport fails
     */
    boolean pushkey(String namespace, String key, String oldVal, String newVal) throws IOException;

    /**
     * Executes the 'between' command to query revisions between pairs of nodes.
     *
     * @param pairs the node pairs to query, each pair contributing the ancestry range between the two nodes
     * @return the hex node IDs found between the given pairs; the default implementation always
     *         returns an empty list, since {@code between} is a legacy wire command most
     *         connections do not implement
     * @throws IOException if the underlying transport fails
     */
    default List<String> between(List<String> pairs) throws IOException {
        return Collections.emptyList();
    }

    /**
     * Executes the 'known' command to check if remote knows specified nodes.
     *
     * @param nodes the hex node IDs to check
     * @return a string encoding, per node in {@code nodes}, whether the remote has it; the default
     *         implementation always returns an empty string, since {@code known} support is
     *         optional and connections that don't implement it report nothing
     * @throws IOException if the underlying transport fails
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
     * @throws IOException if the underlying transport fails
     */
    default Map<String, List<String>> getBranchHeads() throws IOException {
        return null;
    }

    /**
     * Sets the credentials provider for authenticating with the remote repository.
     *
     * @param provider the credentials provider to use for subsequent authentication challenges
     */
    default void setCredentialsProvider(CredentialsProvider provider) {
        // Default no-op
    }

    /**
     * Whether the remote advertised the {@code "clonebundles"} v1 capability token (available
     * only after {@link #getCapabilities()} has been called at least once). Real hg's own client
     * checks this generically via {@code remote.capable(b'clonebundles')} regardless of transport
     * ({@code mercurial/exchange.py}'s {@code trypullbundlefromurl}) -- so the bypass is not an
     * HTTP-only feature in real hg, and this interface-level default (overridden by {@link
     * HgRemoteClient} and {@link HgSshClient}, the two transports that actually support it)
     * keeps hg4j's client from artificially restricting the bypass to HTTP-only transports.
     *
     * @return {@code true} if the remote advertised the {@code "clonebundles"} capability; the
     *         base default is {@code false}
     */
    default boolean supportsClonebundles() {
        return false;
    }

    /**
     * Whether the remote advertised real hg's narrow clone wire capability -- {@code
     * "exp-narrow-1"} (per Mercurial 7.2's {@code
     * mercurial/wireprototypes.py}: {@code NARROWCAP = b'exp-narrow-1'}, appended to the
     * server's {@code capabilities} response whenever the {@code narrow} extension is loaded on
     * the server, unconditionally -- not gated on the specific repository being a narrow clone
     * itself). Only meaningful after {@link #getCapabilities()} has been called at least once.
     *
     * <p>When {@code true}, {@link #getBundle(List, List, List, NarrowScope)} can pass a
     * non-{@code null} {@link NarrowScope} to get an actually-filtered response instead of a full
     * one.
     *
     * @return {@code true} if the remote advertised the {@code "exp-narrow-1"} capability; the
     *         base default is {@code false}
     */
    default boolean supportsNarrow() {
        return false;
    }

    /**
     * Fetches the raw text of the remote's clonebundles manifest via the {@code clonebundles} wire
     * command (the underlying transport framing differs between HTTP's {@code ?cmd=clonebundles}
     * and SSH's line-based v1 command protocol, but the semantics -- return {@code
     * .hg/clonebundles.manifest}'s content verbatim -- are identical). Only meaningful when {@link
     * #supportsClonebundles()} is {@code true}; the default here throws since the base interface
     * has no transport to actually issue the command on.
     *
     * @return the clonebundles manifest text, verbatim
     * @throws IOException if the underlying transport fails
     * @throws UnsupportedOperationException if this connection does not support the clonebundles wire command
     */
    default String fetchClonebundlesManifest() throws IOException {
        throw new UnsupportedOperationException("This connection does not support the clonebundles wire command");
    }
}
