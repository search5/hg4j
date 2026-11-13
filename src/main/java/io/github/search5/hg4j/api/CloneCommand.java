package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.util.SafeFileIO;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.lib.ProgressMonitor;
import io.github.search5.hg4j.lib.NullProgressMonitor;
import io.github.search5.hg4j.errors.HgLockException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import io.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.errors.HgValidationException;
import java.nio.file.Path;

/**
 * Porcelain command to clone a remote Mercurial repository into a local directory.
 * Built with full revision update/checkout and dirstate reconstruction capabilities.
 */
public class CloneCommand {

    private String sourceUrl;
    private File directory;
    private ProgressMonitor monitor = NullProgressMonitor.INSTANCE;
    private boolean pull;
    private boolean update = true;
    private Boolean stream;
    private final List<String> messages = new ArrayList<>();

    /**
     * {@code hg clone --stream} / {@code --no-stream}; {@code null} follows the remote's {@code stream-preferred}.
     *
     * @param stream {@code true} to force a stream clone, {@code false} to forbid it, {@code null} to follow the remote
     * @return this command
     */
    public CloneCommand setStream(Boolean stream) {
        this.stream = stream;
        return this;
    }

    /**
     * What the clone reported ({@code streaming all changes}, ...).
     *
     * @return the messages, in the order they were reported
     */
    public List<String> getMessages() {
        return messages;
    }

    /**
     * {@code hg clone --pull}: always pull the changesets, even from a local repository (without it, a local source is cloned by
     * copying its store, hard linked where possible, as real hg does).
     *
     * @param pull {@code true} to always pull; default {@code false}
     * @return this command
     */
    public CloneCommand setPull(boolean pull) {
        this.pull = pull;
        return this;
    }

    /**
     * {@code hg clone -U} when {@code false}: the changesets are brought in but no working files are created; the working directory
     * parent stays the null revision and there is no {@code .hg/dirstate} file. The default is {@code true}, which checks out the tip.
     *
     * @param update {@code false} for {@code hg clone -U} (no working files); default {@code true}
     * @return this command
     */
    public CloneCommand setUpdate(boolean update) {
        this.update = update;
        return this;
    }

    /**
     * Sets a monitor that receives progress updates and is polled for cancellation.
     *
     * @param monitor the monitor; {@code null} is ignored and the previous monitor (initially a no-op) stays
     * @return {@code this}
     */
    public CloneCommand setProgressMonitor(ProgressMonitor monitor) {
        if (monitor != null) {
            this.monitor = monitor;
        }
        return this;
    }

    /**
     * Sets the repository to clone from. Required.
     *
     * @param sourceUrl a local path or a remote URL accepted by {@link PullCommand}
     * @return {@code this}
     */
    public CloneCommand setSource(String sourceUrl) {
        this.sourceUrl = sourceUrl;
        return this;
    }

    /**
     * Sets the destination directory. Required. It is created if missing and must be empty if it exists.
     *
     * @param directory the directory to create the repository in
     * @return {@code this}
     */
    public CloneCommand setDirectory(File directory) {
        this.directory = directory;
        return this;
    }

    /**
     * Clones the source into the destination directory ({@code hg clone}): initializes a repository there, records the source as the {@code default} path, brings in the changesets (by copying the store of a local source, or by pulling), checks out the tip into the working copy and recursively clones hg subrepositories declared there. Holds the destination working copy lock throughout.
     *
     * @return the new repository; the caller must close it. When the source has no changesets the repository is returned empty, without a checkout.
     * @throws IllegalStateException if the source or the directory was not set
     * @throws io.github.search5.hg4j.errors.HgValidationException if the destination directory exists and is not empty
     * @throws IOException if pulling or the checkout fails
     * @throws HgLockException if the destination lock cannot be acquired
     */
    public HgRepository call() throws IOException, HgLockException {
        if (sourceUrl == null || sourceUrl.isEmpty()) {
            throw new IllegalStateException("Remote source URL must be specified.");
        }
        if (directory == null) {
            throw new IllegalStateException("Local destination directory must be specified.");
        }

        if (directory.exists() && directory.list() != null && directory.list().length > 0) {
            throw new HgValidationException("Destination directory is not empty: " + directory.getAbsolutePath());
        }

        monitor.start("Cloning repository", 3);

        // 1. Initialize empty repository
        HgRepository repo = Hg.init().setDirectory(directory).call();
        repo = recordDefaultPath(repo);
        monitor.update(1);

        // 2. Pull changes from remote source
        // Like real hg (clone.py: destwlock = destrepo.wlock()): the destination is locked from the moment it exists until the last update.
        try (io.github.search5.hg4j.lib.HgLock destWlock = repo.lockWorkingCopy()) {
            // cmd_impls/clone.py: a local source is cloned by copying its store, unless that was not asked for or is not possible
            if (!pull && copyLocalStore(repo)) {
                repo = new HgRepository(directory);
            } else {
                PullCommand pullCmd = new PullCommand(repo);
                pullCmd.setSource(sourceUrl);
                pullCmd.setStream(stream);
                pullCmd.setProgressMonitor(monitor);
                pullCmd.call();
                messages.addAll(pullCmd.getMessages());
            }

            // a stream clone brings the changesets in without a pull "result", so ask the store itself
            File changelogIndex = new File(repo.getStoreDir(), "00changelog.i");
            if (!changelogIndex.isFile() || repo.getRevlog(changelogIndex, new File(repo.getStoreDir(), "00changelog.d"))
                    .getRevisionCount() == 0) {
                monitor.end();
                return repo; // Empty repository pulled
            }

            if (!update) {
                monitor.end();
                return repo; // hg clone -U
            }

            // 3. Checkout (Update) the latest revision to working directory
            checkoutLatest(repo);
            monitor.update(1);

            // 4. Recursively clone/checkout any subrepos declared in the just-checked-out .hgsub
            // (backlog 32 gap #1 -- verified live against Mercurial 7.2: `hg clone` on a parent repo
            // whose tip declares subrepos automatically recurses into each one, checking it out to
            // the revision pinned in .hgsubstate, without any extra flag). Reuses the same recursive
            // checkout logic UpdateCommand's own post-update step uses (see
            // UpdateCommand.recursiveSubrepoCheckout), including its git-subrepo support (gap #3)
            // and its "skip the pull when the pinned revision is already present locally" behavior
            // (gap #4) -- here the subrepo is never present locally yet, so this always clones.
            UpdateCommand.recursiveSubrepoCheckout(repo);

            monitor.end();
            return repo;
        }
    }

    private File localSourceDirectory() {
        String path;
        if (sourceUrl.startsWith("file://")) {
            path = java.net.URI.create(sourceUrl).getPath();
        } else if (sourceUrl.contains("://")) {
            return null;
        } else {
            path = sourceUrl;
        }
        File dir = new File(path);
        return new File(dir, ".hg").isDirectory() ? dir : null;
    }

    /**
     * Real hg's local clone ({@code streamclone.local_copy}): the source's store is copied into the new repository, with hard links
     * where both are on the same device. The source store lock is only tried ({@code srcrepo.lock(wait=False)}): when somebody holds
     * it, the copy is given up and the changesets are pulled instead. A source with a secret changeset is never copied. The caller holds
     * the working copy lock of the new repository.
     *
     * @return whether the store was copied
     */
    private boolean copyLocalStore(HgRepository repo) throws IOException, HgLockException {
        File sourceDir = localSourceDirectory();
        if (sourceDir == null) {
            return false;
        }
        HgRepository source = new HgRepository(sourceDir);
        Revlog sourceChangelog = source.getRevlog(new File(source.getStoreDir(), "00changelog.i"),
                new File(source.getStoreDir(), "00changelog.d"));
        if (sourceChangelog != null && !source.getPhaseRoots().getRoots(io.github.search5.hg4j.phase.PhaseRoots.Phase.SECRET).isEmpty()) {
            return false;
        }
        io.github.search5.hg4j.lib.HgLock sourceLock;
        try {
            sourceLock = source.lockStore(0);
        } catch (HgLockException busy) {
            return false;
        }
        try (io.github.search5.hg4j.lib.HgLock held = sourceLock) {
            File destStore = new File(repo.getHgDir(), "store");
            deleteTree(destStore.toPath());
            try (io.github.search5.hg4j.lib.HgLock destLock = repo.lockStore()) {
                io.github.search5.hg4j.lib.HgLock copyLock = io.github.search5.hg4j.lib.StoreCopy.copyStore(source, repo.getHgDir(),
                        io.github.search5.hg4j.transport.wireprotov1.RepoSupport.isPublishing(source));
                if (copyLock != null) {
                    copyLock.close();
                }
                File sourceBookmarks = new File(source.getHgDir(), "bookmarks");
                if (sourceBookmarks.isFile()) {
                    java.nio.file.Files.copy(sourceBookmarks.toPath(), new File(repo.getHgDir(), "bookmarks").toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                dropWorkingCopyRequirements(new File(repo.getHgDir(), "requires"));
                // the copied store brought its requires (maybe bookmarksinstore, and then store/bookmarks): this open object must see it
                repo.reloadBookmarksInStoreRequirement();
            }
        }
        return true;
    }

    /** {@code creation.clone_requirements}: the store's requirements are the source's, the working directory's are the new repository's own. */
    private static void dropWorkingCopyRequirements(File requires) throws IOException {
        if (!requires.exists()) {
            return;
        }
        List<String> kept = new ArrayList<>();
        for (String line : java.nio.file.Files.readAllLines(requires.toPath(), StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (!t.equals("shared") && !t.equals("relshared") && !t.equals("dirstate-v2")) {
                kept.add(line);
            }
        }
        java.nio.file.Files.writeString(requires.toPath(), String.join("\n", kept) + "\n", StandardCharsets.UTF_8);
    }

    private static void deleteTree(java.nio.file.Path root) throws IOException {
        if (!java.nio.file.Files.exists(root)) {
            return;
        }
        try (var walk = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) {
                java.nio.file.Files.delete(p);
            }
        }
    }

    /**
     * Writes {@code [paths] default = <source>} into the new repository's {@code .hg/hgrc}, in the layout real {@code hg clone}
     * uses, and reopens the repository so its configuration includes it. Later {@code pull}/{@code push} without a destination
     * and the LFS server address (derived from the default path) depend on it. A local source is stored as an absolute path.
     */
    private HgRepository recordDefaultPath(HgRepository repo) throws IOException {
        String stored = sourceUrl.contains("://") ? sourceUrl : new File(sourceUrl).getAbsolutePath();
        String hgrc = "# example repository config (see 'hg help config' for more info)\n"
                + "[paths]\n"
                + "default = " + stored + "\n"
                + "\n"
                + "# path aliases to other clones of this repo in URLs or filesystem paths\n"
                + "# (see 'hg help config.paths' for more info)\n"
                + "#\n"
                + "# default:pushurl = ssh://jdoe@example.net/hg/jdoes-fork\n"
                + "# my-fork         = ssh://jdoe@example.net/hg/jdoes-fork\n"
                + "# my-clone        = /home/jdoe/jdoes-clone\n"
                + "\n"
                + "[ui]\n"
                + "# name and email (local to this repository, optional), e.g.\n"
                + "# username = Jane Doe <jdoe@example.com>\n";
        java.nio.file.Files.writeString(new File(repo.getHgDir(), "hgrc").toPath(), hgrc, StandardCharsets.UTF_8);
        return new HgRepository(directory);
    }

    private void checkoutLatest(HgRepository repo) throws IOException {
        try {
            // clone.py updates the new working copy with the destination's wlock held and its dirstate in a changing_parents context
            repo.changingParents(() -> {
                checkoutLatestInContext(repo);
                return null;
            });
        } catch (io.github.search5.hg4j.errors.HgLockException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private void checkoutLatestInContext(HgRepository repo) throws IOException {
        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");

        Revlog changelog = repo.getRevlog(clIdx, clDat);
        int lastRev = changelog.getRevisionCount() - 1;
        if (lastRev < 0) {
            return;
        }

        byte[] latestCommit = changelog.getIndexRecord(lastRev).getNodeId();

        // Extract manifest node hex from changelog content
        byte[] clContent = changelog.getRevisionContent(lastRev);
        String clText = new String(clContent, StandardCharsets.UTF_8);
        String firstLine = clText.split("\n")[0];
        byte[] mfNode = NodeIdUtil.fromHex(firstLine.trim().substring(0, 40));

        Revlog manifest = repo.getManifestRevlog();
        int mfRev = NodeIdUtil.findRevisionByNodeId(manifest, mfNode);
        if (mfRev == -1) {
            throw new HgRevisionNotFoundException("Manifest revision not found: " + NodeIdUtil.toHex(mfNode));
        }

        byte[] mfContent = manifest.getRevisionContent(mfRev);
        String mfText = new String(mfContent, StandardCharsets.UTF_8);

        // Parse manifest entries
        Map<String, String> manifestEntries = new HashMap<>();
        String[] lines = mfText.split("\n");
        for (String line : lines) {
            if (line.isEmpty()) continue;
            int nullIdx = line.indexOf('\0');
            if (nullIdx != -1) {
                String path = line.substring(0, nullIdx);
                String nodeWithFlags = line.substring(nullIdx + 1);
                manifestEntries.put(path, nodeWithFlags.trim());
            }
        }

        // Restore files to disk and record in Dirstate
        Dirstate dirstate = repo.getDirstate();
        dirstate.setParents(latestCommit, new byte[20]);

        // one batch request for every LFS blob this checkout needs, instead of one request per file
        java.util.List<Map.Entry<Revlog, Integer>> toRead = new java.util.ArrayList<>();
        java.util.List<String> toReadPaths = new java.util.ArrayList<>();
        for (Map.Entry<String, String> entry : manifestEntries.entrySet()) {
            File prefetchIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), entry.getKey());
            if (!prefetchIdx.exists()) {
                continue; // reported by the checkout loop below
            }
            File prefetchDat = new File(prefetchIdx.getPath().substring(0, prefetchIdx.getPath().length() - 2) + ".d");
            Revlog prefetchLog = repo.getRevlog(prefetchIdx, prefetchDat);
            int prefetchRev = NodeIdUtil.findRevisionByNodeId(prefetchLog, NodeIdUtil.fromHex(entry.getValue().substring(0, 40)));
            if (prefetchRev != -1) {
                toRead.add(Map.entry(prefetchLog, prefetchRev));
                toReadPaths.add(entry.getKey());
            }
        }
        // the clone's own steps use the monitor, so the LFS download only passes its message lines on
        ProgressMonitor own = monitor;
        try (io.github.search5.hg4j.lfs.HgLfsProgress bound = io.github.search5.hg4j.lfs.HgLfsProgress.bind(new ProgressMonitor() {
            @Override public void start(String title, int totalWork) { }
            @Override public void update(int completed) { }
            @Override public void end() { }
            @Override public boolean isCancelled() { return own.isCancelled(); }
            @Override public void message(String text) { own.message(text); }
        })) {
            io.github.search5.hg4j.lfs.HgLfsFactory.getInstance().prefetch(repo, toRead, toReadPaths);
        }

        for (Map.Entry<String, String> entry : manifestEntries.entrySet()) {
            String path = entry.getKey();
            String nodeWithFlags = entry.getValue();
            String hexNode = nodeWithFlags.substring(0, 40);
            String flags = nodeWithFlags.substring(40);

            File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), path);
            File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");

            if (!flIdx.exists()) {
                throw new HgRepositoryNotFoundException("Filelog index not found for tracked file: " + path);
            }

            Revlog filelog = repo.getRevlog(flIdx, flDat);
            int fileRev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(hexNode));
            if (fileRev == -1) {
                throw new HgRevisionNotFoundException("File version not found in filelog: " + path + " rev hex " + hexNode);
            }

            byte[] fileContent = io.github.search5.hg4j.lfs.HgLfsFactory.getInstance().readContent(repo, path, filelog, fileRev);

            // Write to working copy
            File diskFile = new File(repo.getDirectory(), path);
            diskFile.getParentFile().mkdirs();
            if (diskFile.exists() || Files.isSymbolicLink(diskFile.toPath())) {
                Files.delete(diskFile.toPath());
            }

            int mode = 0644;
            if (flags.contains("l")) {
                mode = 0120000;
                String target = new String(fileContent, StandardCharsets.UTF_8).trim();
                try {
                    Files.createSymbolicLink(diskFile.toPath(), Path.of(target));
                } catch (Exception e) {
                    Files.write(diskFile.toPath(), fileContent);
                }
            } else {
                Files.write(diskFile.toPath(), fileContent);
                // Apply executable flag if 'x'
                boolean executable = flags.contains("x");
                diskFile.setExecutable(executable, false);
                mode = executable ? 0755 : 0644;
            }

            int size = fileContent.length;
            long time = SafeFileIO.lastModifiedSeconds(diskFile);

            dirstate.addEntry(path, new Dirstate.Entry('n', mode, size, time));
        }

        repo.writeDirstate(dirstate);
    }
}
