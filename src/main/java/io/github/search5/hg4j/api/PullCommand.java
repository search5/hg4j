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
 */
public class PullCommand {
    private static final Logger LOGGER = Logger.getLogger(PullCommand.class.getName());

    private final HgRepository repository;
    private String sourceUrl;
    private ProgressMonitor monitor = NullProgressMonitor.INSTANCE;
    private HgTreeFilter treeFilter = HgTreeFilter.ALL;
    private CredentialsProvider credentialsProvider;

    /**
     * Creates the command.
     * @param repository the repository to pull into; must not be {@code null}
     */
    public PullCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Sets the credentials used to authenticate to the remote.
     * @param credentialsProvider the provider, or {@code null} (the default) to not set one on the connection
     * @return {@code this}
     */
    public PullCommand setCredentialsProvider(CredentialsProvider credentialsProvider) {
        this.credentialsProvider = credentialsProvider;
        return this;
    }

    /**
     * Sets the tree filter limiting which paths are pulled (narrow clone support).
     * @param treeFilter the filter; {@code null} is ignored. By default (and when set to {@code HgTreeFilter.ALL}) the
     *         underlying fetch uses the repository's own narrowspec if it has one
     * @return {@code this}
     */
    public PullCommand setTreeFilter(HgTreeFilter treeFilter) {
        if (treeFilter != null) {
            this.treeFilter = treeFilter;
        }
        return this;
    }

    /**
     * Sets the progress monitor.
     * @param monitor the monitor to notify; {@code null} is ignored and leaves the current one (initially a no-op monitor)
     * @return {@code this}
     */
    public PullCommand setProgressMonitor(ProgressMonitor monitor) {
        if (monitor != null) {
            this.monitor = monitor;
        }
        return this;
    }

    /**
     * Sets where to pull from.
     * @param sourceUrl a URL or a name of a {@code [paths]} entry; {@code null} or empty (the default) means the
     *         {@code default} path of the repository configuration
     * @return {@code this}
     */
    public PullCommand setSource(String sourceUrl) {
        this.sourceUrl = sourceUrl;
        return this;
    }

    private final List<String> revisions = new ArrayList<>();

    /** {@code hg pull -r REV}: only these revisions (and their ancestors); see {@link FetchCommand#setRevisions}. */
    public PullCommand setRevisions(List<String> revisions) {
        this.revisions.clear();
        if (revisions != null) {
            this.revisions.addAll(revisions);
        }
        return this;
    }

    private Boolean stream;
    private java.util.List<String> storeFingerprints;
    private java.util.List<java.util.Set<String>> shardsSets;

    /** The shape fingerprints (text) the clone asks for: they pick the clone bundles ({@code store_fingerprints}). */
    public PullCommand setStoreFingerprints(java.util.List<String> fingerprints) {
        this.storeFingerprints = fingerprints;
        return this;
    }

    /** The sets of shard fingerprints (text) that make a usable group of sharded clone bundles ({@code shards_sets}). */
    public PullCommand setShardsSets(java.util.List<java.util.Set<String>> sets) {
        this.shardsSets = sets;
        return this;
    }
    private final List<String> messages = new ArrayList<>();

    /**
     * {@code hg pull --stream} / {@code --no-stream}: into an empty repository, take the remote's store files as they are instead of
     * the changesets one by one. {@code null} (the default) follows the remote's own {@code stream-preferred}.
     */
    public PullCommand setStream(Boolean stream) {
        this.stream = stream;
        return this;
    }

    /** What the last {@link #call()} reported ({@code streaming all changes}, ...). */
    public List<String> getMessages() {
        return messages;
    }

    /**
     * Pulls changesets from the source into this repository.
     * <p>
     * The source is the value of {@link #setSource}, or, when that is {@code null} or empty, the {@code default} entry
     * of {@code [paths]}. A source without {@code ://} that names a {@code [paths]} entry is replaced by that entry's
     * URL. The working copy lock is held for the whole call (the store lock is taken by the underlying fetch). The work is
     * delegated to {@link FetchCommand}. Afterwards, if changesets were imported and the first dirstate parent is the
     * null revision, the dirstate parent is set to the last imported changeset; working copy files are not updated.
     * {@link #getMessages()} is reset at the start and filled during the call.
     * @return the node ids (20 bytes each) of the imported changesets, possibly empty
     * @throws IOException if the connection, the transfer or writing the repository fails
     * @throws HgLockException if a lock cannot be acquired
     * @throws IllegalStateException if no source was set and no {@code default} path is configured
     */
    public List<byte[]> call() throws IOException, HgLockException {
        // 실제 hg 스펙(hg help urls): 소스를 안 주면 paths.default를 쓴다 — 가장 흔한
        // 실사용 형태("그냥 hg pull")인데 2026-09-01 이전에는 여기서 무조건 예외를
        // 던져서 지원이 안 됐다.
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
        // Like real hg, the locks are taken before the first read of the repository state and released when the command ends.
        try (io.github.search5.hg4j.lib.HgLock wlock = repository.lockWorkingCopy()) {
            messages.clear();
            FetchCommand fetchCmd = new FetchCommand(repository);
            fetchCmd.setStream(stream);
            fetchCmd.setStoreFingerprints(storeFingerprints);
            fetchCmd.setShardsSets(shardsSets);
            fetchCmd.setRevisions(revisions);
            // Backlog 30: only forward an explicit override -- if this PullCommand itself was never
            // given one, leave FetchCommand's own default so it can auto-load the repository's own
            // narrowspec (see FetchCommand#resolveNarrowTreeFilterIfDefault) instead of us silently
            // clobbering that with ALL.
            if (this.treeFilter != HgTreeFilter.ALL) {
                fetchCmd.setTreeFilter(this.treeFilter);
            }
            fetchCmd.setProgressMonitor(this.monitor);
            fetchCmd.setSource(resolvedUrl);
            if (this.credentialsProvider != null) {
                fetchCmd.setCredentialsProvider(this.credentialsProvider);
            }
            List<byte[]> results;
            try {
                results = fetchCmd.call();
            } finally {
                messages.addAll(fetchCmd.getMessages());
            }

            // 2. PullCommand exclusive: automatically advance working directory dirstate parent if it was empty
            if (results != null && !results.isEmpty()) {
                final List<byte[]> pulled = results;
                repository.changingParents(() -> {
                    Dirstate dirstate = repository.getDirstate();
                    if (NodeIdUtil.isAllZero(dirstate.getParent1())) {
                        byte[] latestHead = pulled.get(pulled.size() - 1);
                        dirstate.setParents(latestHead, new byte[20]);
                        repository.writeDirstate(dirstate);
                    }
                    return null;
                });
            }

            // 3. bookmark 동기화는 위 1단계의 FetchCommand.call() 안에서
            // BookmarkCommand.mergeFromRemote()로 이미 처리된다(ancestor 기반 fast-forward/
            // 진짜 divergence 구분 포함). 여기서 다시 하면 이미 병합이 끝난 상태를 대상으로
            // 안전하지 않은 하드코딩된 "@default" 분기 로직이 중복 실행되는 문제가 있어
            // 제거함(2026-09-01).

            return results;
        }
    }

    /**
     * Applies an already parsed changegroup bundle, waiting for locks as long as the push lock timeout of the repository.
     * <p>
     * Equivalent to {@link #applyBundle(ChangegroupParser.ChangegroupBundle, int, FetchCommand.PostLockValidator)}
     * with the repository's push lock timeout and no validator.
     * @param bundle the parsed changegroup bundle to apply
     * @return the node ids (20 bytes each) of the imported changesets
     * @throws IOException if reading the bundle or writing the repository fails
     * @throws HgLockException if a lock cannot be acquired within the timeout
     */
    public List<byte[]> applyBundle(ChangegroupParser.ChangegroupBundle bundle) throws IOException, HgLockException {
        return applyBundle(bundle, repository.resolvePushLockTimeoutMs(), null);
    }

    /**
     * Same as {@link #applyBundle(ChangegroupParser.ChangegroupBundle)}, but forwards a store/
     * working-copy lock wait timeout to {@link FetchCommand#applyBundle(ChangegroupParser.ChangegroupBundle, int)}
     * instead of failing immediately on contention -- see that method's doc (backlog item 38).
     *
     * @param lockTimeoutMs how long to wait for the store/wlock to clear, in milliseconds --
     *                      {@code 0} preserves the original fail-fast behavior.
     */
    public List<byte[]> applyBundle(ChangegroupParser.ChangegroupBundle bundle, int lockTimeoutMs) throws IOException, HgLockException {
        return applyBundle(bundle, lockTimeoutMs, null);
    }

    /**
     * Same as {@link #applyBundle(ChangegroupParser.ChangegroupBundle, int)}, but forwards a
     * post-lock, pre-apply validator to {@link
     * FetchCommand#applyBundle(ChangegroupParser.ChangegroupBundle, int, FetchCommand.PostLockValidator)}
     * -- see that method's doc (backlog item 38, push-race re-validation).
     */
    /**
     * Applies an already parsed changegroup bundle with a lock timeout and a validator, then sets the dirstate parent.
     * <p>
     * Takes the working copy lock (waiting up to {@code lockTimeoutMs}), delegates to
     * {@link FetchCommand#applyBundle(ChangegroupParser.ChangegroupBundle, int, FetchCommand.PostLockValidator)} and,
     * if changesets were imported and the first dirstate parent is the null revision, moves the dirstate parent to the
     * last imported changeset. The working copy files are not updated.
     * @param bundle the parsed changegroup bundle to apply
     * @param lockTimeoutMs how long to wait for the locks, in milliseconds; {@code 0} fails immediately on contention
     * @param postLockValidator a check run right after the locks are taken and before anything is written, or
     *         {@code null} for none; an exception it throws aborts the apply with the repository unchanged
     * @return the node ids (20 bytes each) of the imported changesets
     * @throws IOException if reading the bundle or writing the repository fails, or the validator fails
     * @throws HgLockException if a lock cannot be acquired within the timeout
     */
    public List<byte[]> applyBundle(ChangegroupParser.ChangegroupBundle bundle, int lockTimeoutMs,
                                     FetchCommand.PostLockValidator postLockValidator) throws IOException, HgLockException {
        // Like real hg, moving the working copy to the new head happens under the working copy lock.
        try (io.github.search5.hg4j.lib.HgLock wlock = repository.lockWorkingCopy(lockTimeoutMs)) {
            FetchCommand fetchCmd = new FetchCommand(repository);
            // Backlog 30: only forward an explicit override -- if this PullCommand itself was never
            // given one, leave FetchCommand's own default so it can auto-load the repository's own
            // narrowspec (see FetchCommand#resolveNarrowTreeFilterIfDefault) instead of us silently
            // clobbering that with ALL.
            if (this.treeFilter != HgTreeFilter.ALL) {
                fetchCmd.setTreeFilter(this.treeFilter);
            }
            fetchCmd.setProgressMonitor(this.monitor);
            if (this.credentialsProvider != null) {
                fetchCmd.setCredentialsProvider(this.credentialsProvider);
            }

            List<byte[]> results = fetchCmd.applyBundle(bundle, lockTimeoutMs, postLockValidator);

            if (results != null && !results.isEmpty()) {
                final List<byte[]> pulled = results;
                repository.changingParents(() -> {
                    Dirstate dirstate = repository.getDirstate();
                    if (NodeIdUtil.isAllZero(dirstate.getParent1())) {
                        byte[] latestHead = pulled.get(pulled.size() - 1);
                        dirstate.setParents(latestHead, new byte[20]);
                        repository.writeDirstate(dirstate);
                    }
                    return null;
                });
            }
            return results;
        }
    }
}
