package io.github.search5.hg4j.api;

import java.util.logging.Level;
import java.util.logging.Logger;
import io.github.search5.hg4j.bundle.ChangegroupParser;
import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.transport.HgRemoteConnection;
import io.github.search5.hg4j.transport.HgRemoteConnectionFactory;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.SafeFileIO;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lib.ProgressMonitor;
import io.github.search5.hg4j.lib.NullProgressMonitor;
import io.github.search5.hg4j.transport.CredentialsProvider;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import io.github.search5.hg4j.treewalk.HgTreeFilter;

/**
 * Porcelain command to pull changes from a remote repository.
 * Built with crash-durable transaction protection and full fncache/on-disk layout fidelity.
 *
 * @apiNote Typically obtained via {@link Hg#pull()} on an open {@link Hg}
 *     instance rather than constructed directly.
 */
public class PullCommand {
    private static final Logger LOGGER = Logger.getLogger(PullCommand.class.getName());

    private final HgRepository repository;
    private String sourceUrl;
    private ProgressMonitor monitor = NullProgressMonitor.INSTANCE;
    private HgTreeFilter treeFilter = HgTreeFilter.ALL;
    private CredentialsProvider credentialsProvider;

    /**
     * Creates the command against the given repository.
     *
     * @param repository repository the remote changes are pulled into
     */
    public PullCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Sets the provider used to obtain authentication credentials for the remote, if it requires
     * them.
     *
     * @param credentialsProvider provider consulted for username/password credentials
     * @return this command, for chaining
     */
    public PullCommand setCredentialsProvider(CredentialsProvider credentialsProvider) {
        this.credentialsProvider = credentialsProvider;
        return this;
    }

    /**
     * Restricts the pull to paths accepted by the given filter, matching the scope of a narrow
     * clone. A {@code null} filter is ignored.
     *
     * @param treeFilter filter applied when narrowing the pulled changegroup; {@code null} is ignored
     * @return this command, for chaining
     */
    public PullCommand setTreeFilter(HgTreeFilter treeFilter) {
        if (treeFilter != null) {
            this.treeFilter = treeFilter;
        }
        return this;
    }

    /**
     * Sets the monitor notified of pull progress. A {@code null} monitor is ignored.
     *
     * @param monitor progress monitor; {@code null} is ignored (leaves the current monitor,
     *                a no-op by default, unchanged)
     * @return this command, for chaining
     */
    public PullCommand setProgressMonitor(ProgressMonitor monitor) {
        if (monitor != null) {
            this.monitor = monitor;
        }
        return this;
    }

    /**
     * Sets the remote repository URL (or configured path alias) to pull from.
     *
     * @param sourceUrl remote URL, a {@code [paths]} alias name, or {@code null}/empty to use the
     *                  repository's configured {@code default} path
     * @return this command, for chaining
     */
    public PullCommand setSource(String sourceUrl) {
        this.sourceUrl = sourceUrl;
        return this;
    }

    /**
     * Resolves the source URL, fetches new changesets via {@link FetchCommand}, and advances the
     * working copy's dirstate parent to the pulled tip if the repository was previously empty.
     *
     * @return the raw node ids of the changesets pulled, in changegroup order
     * @throws IOException if the remote cannot be reached or its response cannot be decoded
     * @throws HgLockException if the store or working copy lock cannot be acquired
     */
    public List<byte[]> call() throws IOException, HgLockException {
        // Real hg spec (hg help urls): when no source is given, paths.default is used -- the
        // most common real-world form ("just hg pull").
        String effectiveSource = sourceUrl;
        if (effectiveSource == null || effectiveSource.isEmpty()) {
            effectiveSource = repository.getConfig().getPath("default");
        }
        if (effectiveSource == null || effectiveSource.isEmpty()) {
            throw new IllegalStateException("Remote source URL must be specified.");
        }

        String resolvedUrl = effectiveSource;
        if (!effectiveSource.contains("://")) {
            String configPath = repository.getConfig().getPath(effectiveSource);
            if (configPath != null) {
                resolvedUrl = configPath;
            }
        }

        // 1. Delegate core metadata network fetching and database store sync to FetchCommand
        FetchCommand fetchCmd = new FetchCommand(repository);
        // Only forward an explicit override -- if this PullCommand itself was never given one,
        // leave FetchCommand's own default so it can auto-load the repository's own narrowspec
        // (see FetchCommand#resolveNarrowTreeFilterIfDefault) instead of us silently clobbering
        // that with ALL.
        if (this.treeFilter != HgTreeFilter.ALL) {
            fetchCmd.setTreeFilter(this.treeFilter);
        }
        fetchCmd.setProgressMonitor(this.monitor);
        fetchCmd.setSource(resolvedUrl);
        if (this.credentialsProvider != null) {
            fetchCmd.setCredentialsProvider(this.credentialsProvider);
        }
        List<byte[]> results = fetchCmd.call();

        // 2. PullCommand exclusive: automatically advance working directory dirstate parent if it was empty
        if (results != null && !results.isEmpty()) {
            Dirstate dirstate = repository.getDirstate();
            if (NodeIdUtil.isAllZero(dirstate.getParent1())) {
                byte[] latestHead = results.get(results.size() - 1);
                dirstate.setParents(latestHead, new byte[20]);
                repository.writeDirstate(dirstate);
            }
        }

        // 3. Bookmark sync is already handled by BookmarkCommand.mergeFromRemote() inside
        // FetchCommand.call() in step 1 above (including ancestor-based fast-forward/genuine
        // divergence detection); it must not be duplicated here on top of an already-merged
        // state.

        return results;
    }

    /**
     * Applies an already-parsed changegroup bundle directly to the repository, bypassing the
     * network fetch step of {@link #call()} -- delegates to {@link
     * FetchCommand#applyBundle(ChangegroupParser.ChangegroupBundle)}.
     *
     * @param bundle parsed changegroup to apply
     * @return the raw node ids of the changesets imported, in changegroup order
     * @throws IOException if applying the changegroup fails
     * @throws HgLockException if the store or working copy lock cannot be acquired
     */
    public List<byte[]> applyBundle(ChangegroupParser.ChangegroupBundle bundle) throws IOException, HgLockException {
        return applyBundle(bundle, 0, null);
    }

    /**
     * Same as {@link #applyBundle(ChangegroupParser.ChangegroupBundle)}, but forwards a store/
     * working-copy lock wait timeout to {@link FetchCommand#applyBundle(ChangegroupParser.ChangegroupBundle, int)}
     * instead of failing immediately on contention -- see that method's doc.
     *
     * @param bundle parsed changegroup to apply
     * @param lockTimeoutMs how long to wait for the store/wlock to clear, in milliseconds --
     *                      {@code 0} preserves the original fail-fast behavior.
     * @return the raw node ids of the changesets imported, in changegroup order
     * @throws IOException if applying the changegroup fails
     * @throws HgLockException if the store or working copy lock cannot be acquired within the timeout
     */
    public List<byte[]> applyBundle(ChangegroupParser.ChangegroupBundle bundle, int lockTimeoutMs) throws IOException, HgLockException {
        return applyBundle(bundle, lockTimeoutMs, null);
    }

    /**
     * Same as {@link #applyBundle(ChangegroupParser.ChangegroupBundle, int)}, but forwards a
     * post-lock, pre-apply validator to {@link
     * FetchCommand#applyBundle(ChangegroupParser.ChangegroupBundle, int, FetchCommand.PostLockValidator)}
     * -- see that method's doc (push-race re-validation).
     *
     * @param bundle parsed changegroup to apply
     * @param lockTimeoutMs how long to wait for the store/wlock to clear, in milliseconds
     * @param postLockValidator validator run immediately after the locks are acquired and before
     *                          any mutation begins; {@code null} skips this check
     * @return the raw node ids of the changesets imported, in changegroup order
     * @throws IOException if applying the changegroup fails, or {@code postLockValidator} throws
     * @throws HgLockException if the store or working copy lock cannot be acquired within the timeout
     */
    public List<byte[]> applyBundle(ChangegroupParser.ChangegroupBundle bundle, int lockTimeoutMs,
                                     FetchCommand.PostLockValidator postLockValidator) throws IOException, HgLockException {
        FetchCommand fetchCmd = new FetchCommand(repository);
        // Only forward an explicit override -- if this PullCommand itself was never given one,
        // leave FetchCommand's own default so it can auto-load the repository's own narrowspec
        // (see FetchCommand#resolveNarrowTreeFilterIfDefault) instead of us silently clobbering
        // that with ALL.
        if (this.treeFilter != HgTreeFilter.ALL) {
            fetchCmd.setTreeFilter(this.treeFilter);
        }
        fetchCmd.setProgressMonitor(this.monitor);
        if (this.credentialsProvider != null) {
            fetchCmd.setCredentialsProvider(this.credentialsProvider);
        }

        List<byte[]> results = fetchCmd.applyBundle(bundle, lockTimeoutMs, postLockValidator);

        if (results != null && !results.isEmpty()) {
            Dirstate dirstate = repository.getDirstate();
            if (NodeIdUtil.isAllZero(dirstate.getParent1())) {
                byte[] latestHead = results.get(results.size() - 1);
                dirstate.setParents(latestHead, new byte[20]);
                repository.writeDirstate(dirstate);
            }
        }
        return results;
    }
}
