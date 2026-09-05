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
     * Same as {@link #getBundle(List, List, List)}, but additionally negotiates real hg's narrow
     * clone wire arguments ({@code narrow}, {@code includepats}, {@code excludepats} -- part of
     * core {@code wireprototypes.GETBUNDLE_ARGUMENTS}, verified 2026-09-06 by reading Mercurial
     * 7.2's {@code mercurial/wireprototypes.py}/{@code exchange.py} directly) when {@code
     * narrowScope} is non-{@code null} and the remote advertised {@link #supportsNarrow()}.
     *
     * <p>This is what makes narrow clone/pull actually reduce wire transfer size (backlog item
     * 40): a remote that understands these arguments (real hg with the bundled {@code narrow}
     * extension enabled -- confirmed 2026-09-06 via a real {@code hg --debug clone --narrow}
     * capture: the {@code getbundle} response's bundle2 changegroup part shrank from a 5.46MB
     * full-repo payload to a 29KB narrow one, containing only in-scope filelogs, for the same
     * repository and heads) actually omits out-of-narrowspec filelog data from the response,
     * rather than hg4j always fetching everything and discarding out-of-scope content locally
     * after the fact.
     *
     * <p>The default implementation ignores {@code narrowScope} and delegates to the plain
     * 3-argument overload -- correct (if bandwidth-suboptimal) behavior for any implementation
     * that hasn't been taught the narrow wire arguments.
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
        public final List<String> includePatterns;
        public final List<String> excludePatterns;

        public NarrowScope(List<String> includePatterns, List<String> excludePatterns) {
            this.includePatterns = includePatterns != null ? includePatterns : Collections.emptyList();
            this.excludePatterns = excludePatterns != null ? excludePatterns : Collections.emptyList();
        }
    }

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
     * Whether the remote advertised real hg's narrow clone wire capability -- {@code
     * "exp-narrow-1"} (verified 2026-09-06 against Mercurial 7.2's {@code
     * mercurial/wireprototypes.py}: {@code NARROWCAP = b'exp-narrow-1'}, appended to the
     * server's {@code capabilities} response whenever the {@code narrow} extension is loaded on
     * the server, unconditionally -- not gated on the specific repository being a narrow clone
     * itself). Only meaningful after {@link #getCapabilities()} has been called at least once.
     *
     * <p>When {@code true}, {@link #getBundle(List, List, List, NarrowScope)} can pass a
     * non-{@code null} {@link NarrowScope} to get an actually-filtered response instead of a full
     * one.
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
     */
    default String fetchClonebundlesManifest() throws IOException {
        throw new UnsupportedOperationException("This connection does not support the clonebundles wire command");
    }
}
