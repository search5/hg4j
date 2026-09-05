package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.util.SafeFileIO;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lfs.HgLfsManager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import io.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.submodule.GitSubrepoUtil;
import io.github.search5.hg4j.submodule.HgSubrepoEntry;
import io.github.search5.hg4j.submodule.HgSubrepoParser;
import io.github.search5.hg4j.treewalk.HgTreeFilter;
import io.github.search5.hg4j.treewalk.ManifestTreeIterator;
import io.github.search5.hg4j.treewalk.TreeWalk;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Porcelain command to update (checkout) working copy to a specified target revision.
 * Built with full transaction isolation and strict dirstate state transitions.
 */
public class UpdateCommand {
    private static final Logger LOGGER = Logger.getLogger(UpdateCommand.class.getName());

    private final HgRepository repository;
    private String targetRevision;
    private boolean force = false;
    private HgTreeFilter treeFilter = HgTreeFilter.ALL;
    private final List<HgHook> preUpdateHooks = new ArrayList<>();
    private final List<HgHook> postUpdateHooks = new ArrayList<>();

    public UpdateCommand(HgRepository repository) {
        this.repository = repository;
    }

    public UpdateCommand registerPreUpdateHook(HgHook hook) {
        if (hook != null) {
            preUpdateHooks.add(hook);
        }
        return this;
    }

    public UpdateCommand registerPostUpdateHook(HgHook hook) {
        if (hook != null) {
            postUpdateHooks.add(hook);
        }
        return this;
    }

    public UpdateCommand setTreeFilter(HgTreeFilter treeFilter) {
        if (treeFilter != null) {
            this.treeFilter = treeFilter;
        }
        return this;
    }

    public UpdateCommand setRevision(String targetRevision) {
        this.targetRevision = targetRevision;
        return this;
    }

    public UpdateCommand setForce(boolean force) {
        this.force = force;
        return this;
    }

    public byte[] call() throws IOException, HgLockException {
        // Backlog 30: when the caller hasn't explicitly narrowed this update (still the default
        // HgTreeFilter.ALL), pick up whatever narrowspec the repository itself was narrow cloned
        // with -- so checkout keeps respecting the narrow scope on every later `update`, not just
        // right after NarrowCloneCommand's own initial checkout.
        if (this.treeFilter == HgTreeFilter.ALL) {
            this.treeFilter = HgTreeFilter.loadFromRepository(repository);
        }

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");

        try (HgLock wlock = repository.lockWorkingCopy();
             HgLock storeLock = repository.lockStore()) {

            // PRE_UPDATE hooks trigger
            if (!preUpdateHooks.isEmpty()) {
                Map<String, Object> ctx = new HashMap<>();
                ctx.put("repository", repository);
                ctx.put("targetRevision", targetRevision);
                ctx.put("force", force);
                for (HgHook hook : preUpdateHooks) {
                    if (!hook.run(ctx)) {
                        throw new HgValidationException("Update rejected by PRE_UPDATE hook");
                    }
                }
            }

            Dirstate dirstate = repository.getDirstate();
            if (!force) {
                if (!dirstate.getEntries().isEmpty()) {
                    Status status = new StatusCommand(repository).call();
                    if (!status.getAdded().isEmpty() || !status.getModified().isEmpty() || !status.getRemoved().isEmpty()) {
                        throw new HgValidationException("Working directory has uncommitted changes. Use force to update.");
                    }
                }
            }

            Revlog changelog = repository.getRevlog(clIdx, clDat);
            byte[] targetNodeId = resolveTargetNodeId(changelog);
            if (targetNodeId == null) {
                throw new HgValidationException("Repository is empty, cannot update.");
            }

            int commitRev = NodeIdUtil.findRevisionByNodeId(changelog, targetNodeId);
            if (commitRev == -1) {
                throw new HgRevisionNotFoundException(NodeIdUtil.toHex(targetNodeId));
            }

            // Resolve current parent revision for TreeWalk
            dirstate = repository.getDirstate();
            String parentRev = "";
            if (changelog.getRevisionCount() > 0) {
                byte[] parentNode = dirstate.getParent1();
                int parentRevNum = NodeIdUtil.findRevisionByNodeId(changelog, parentNode);
                if (parentRevNum != -1) {
                    parentRev = String.valueOf(parentRevNum);
                }
            }

            TreeWalk tw = new TreeWalk();
            tw.addTree(new ManifestTreeIterator(repository, parentRev)); // Tree 0: Current Parent Manifest
            tw.addTree(new ManifestTreeIterator(repository, String.valueOf(commitRev))); // Tree 1: Target Commit Manifest

            tw.reset();
            while (tw.next()) {
                String path = tw.getPath();
                if (treeFilter != null && !treeFilter.accept(path)) {
                    continue;
                }
                boolean inParent = tw.isTracked(0);
                boolean inTarget = tw.isTracked(1);

                if (inParent && !inTarget) {
                    // Process deletions: Files in current manifest that are NOT in target manifest
                    File diskFile = new File(repository.getDirectory(), path);
                    if (diskFile.exists()) {
                        Files.delete(diskFile.toPath());
                        // Delete empty parent directories if clean
                        File parent = diskFile.getParentFile();
                        while (parent != null && !parent.equals(repository.getDirectory())) {
                            File[] files = parent.listFiles();
                            if (files == null || files.length == 0) {
                                Files.delete(parent.toPath());
                                parent = parent.getParentFile();
                            } else {
                                break;
                            }
                        }
                    }
                    dirstate.removeEntry(path);
                } else if (inTarget) {
                    // Process updates and creations: Files in target manifest
                    byte[] targetNode = tw.getNodeId(1);
                    String hexNode = NodeIdUtil.toHex(targetNode);
                    boolean executable = tw.isExecutable(1);

                    File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
                    File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");

                    if (!flIdx.exists()) {
                        throw new HgRepositoryNotFoundException("Filelog index not found for tracked file: " + path);
                    }

                    Revlog filelog = repository.getRevlog(flIdx, flDat);
                    int fileRev = NodeIdUtil.findRevisionByNodeId(filelog, targetNode);
                    if (fileRev == -1) {
                        throw new HgRevisionNotFoundException("File version not found in filelog: " + path + " rev hex " + hexNode);
                    }

                    byte[] fileContent = filelog.getRevisionContent(fileRev);

                    // LFS pipeline (backlog 31, extended by backlog 42): a REVIDX_EXTSTORED
                    // revision's filelog content is an LFS pointer, not the real bytes -- resolve
                    // it back to the real content before it reaches disk, mirroring real hg's
                    // hgext/lfs `readfromstore` wrapper. Delegated to HgLfsManager#resolveContent
                    // so this checkout path and AnnotateCommand's content-reading path share one
                    // implementation that consistently honors [lfs] url / usercache config.
                    fileContent = HgLfsManager.resolveContent(repository, fileContent, filelog.isExtStored(fileRev), path);

                    boolean symlink = tw.isSymlink(1);

                    File diskFile = new File(repository.getDirectory(), path);
                    boolean needsWrite = true;

                    if (diskFile.exists() || Files.isSymbolicLink(diskFile.toPath())) {
                        if (!symlink) {
                            try {
                                byte[] existingContent = Files.readAllBytes(diskFile.toPath());
                                if (Arrays.equals(existingContent, fileContent)) {
                                    needsWrite = false;
                                }
                            } catch (Exception ignored) {
                            }
                        } else if (Files.isSymbolicLink(diskFile.toPath())) {
                            // Skip the delete+recreate churn (and its symlink mtime bump) when the
                            // target is already exactly right, mirroring the regular-file
                            // unchanged-content fast path just above.
                            try {
                                String existingTarget = Files.readSymbolicLink(diskFile.toPath()).toString();
                                String desiredTarget = new String(fileContent, StandardCharsets.UTF_8).trim();
                                if (existingTarget.equals(desiredTarget)) {
                                    needsWrite = false;
                                }
                            } catch (Exception ignored) {
                            }
                        }
                        if (needsWrite) {
                            Files.delete(diskFile.toPath());
                        }
                    }

                    // Backlog #39 fix: a symlink's dirstate mode must carry the full S_IFLNK +
                    // rwxrwxrwx bits (0120777), not just the bare S_IFLNK type bits (0120000) --
                    // verified live against real hg 7.2: every symlink's `lstat` mode is
                    // unconditionally 0120777 (symlinks have no real permissions of their own), so
                    // a dirstate entry recorded with the bare 0120000 makes real hg's own `hg
                    // status` (which compares the stored mode against a fresh `lstat`) see a
                    // spurious permission mismatch and report the untouched symlink as modified.
                    int mode = 0644;
                    if (needsWrite) {
                        diskFile.getParentFile().mkdirs();
                        if (symlink) {
                            mode = 0120777;
                            String target = new String(fileContent, StandardCharsets.UTF_8).trim();
                            try {
                                Files.createSymbolicLink(diskFile.toPath(), Path.of(target));
                            } catch (Exception e) {
                                Files.write(diskFile.toPath(), fileContent);
                            }
                        } else {
                            Files.write(diskFile.toPath(), fileContent);
                            diskFile.setExecutable(executable, false);
                            mode = executable ? 0755 : 0644;
                        }
                    } else {
                        if (symlink) {
                            mode = 0120777;
                        } else {
                            diskFile.setExecutable(executable, false);
                            mode = executable ? 0755 : 0644;
                        }
                    }

                    int size = fileContent.length;
                    long time = SafeFileIO.lastModifiedSeconds(diskFile);

                    dirstate.addEntry(path, new Dirstate.Entry('n', mode, size, time));
                }
            }

            // 4. Conclude checkout parent node updates
            dirstate.setParents(targetNodeId, new byte[20]);
            repository.writeDirstate(dirstate);

            // Real hg's `hg update` switches the working directory's active branch to
            // whatever branch the target revision was committed on (`hg branch` reflects
            // dirstate.branch, which `merge.update()` always resets on checkout). Without
            // this, the working branch stays stuck on whatever it was before the update,
            // so e.g. the next commit lands on the wrong branch.
            repository.setBranch(CommitCommand.getBranchOfRevision(changelog, commitRev));

            // 4a. Update active bookmark based on targetNodeId (Auto-Switch)
            BookmarkCommand bookmarkCmd = new BookmarkCommand(repository);
            Map<String, String> allBookmarks = bookmarkCmd.call();
            String targetHex = NodeIdUtil.toHex(targetNodeId);
            List<String> matchingBookmarks = new ArrayList<>();
            for (Map.Entry<String, String> entry : allBookmarks.entrySet()) {
                if (entry.getValue().equals(targetHex)) {
                    matchingBookmarks.add(entry.getKey());
                }
            }
            if (matchingBookmarks.size() == 1) {
                bookmarkCmd.setBookmarkName(matchingBookmarks.get(0)).setActive(true).call();
            } else {
                bookmarkCmd.setActive(true).setBookmarkName(null).call();
            }

            // 5. Recursive Subrepo Checkout (JGit-like subrepository checkout support)
            recursiveSubrepoCheckout(repository);

            // POST_UPDATE hooks trigger
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("targetNode", NodeIdUtil.toHex(targetNodeId));
            ctx.put("repository", repository);
            for (HgHook hook : postUpdateHooks) {
                try {
                    hook.run(ctx);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Post-update hook execution failed", e);
                }
            }

            return targetNodeId;
        }
    }

    private byte[] resolveTargetNodeId(Revlog changelog) throws IOException {
        if (changelog.getRevisionCount() == 0) {
            return null;
        }
        try {
            byte[] resolved = NodeIdUtil.resolveRevision(changelog, targetRevision);
            if (resolved != null) {
                return resolved;
            }
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("Ambiguous")) {
                throw new HgRevisionNotFoundException(e.getMessage());
            }
            throw e;
        }

        // 3. Try named branch head
        byte[] branchHead = null;
        for (int i = changelog.getRevisionCount() - 1; i >= 0; i--) {
            String branch = CommitCommand.getBranchOfRevision(changelog, i);
            if (targetRevision.equals(branch)) {
                branchHead = changelog.getIndexRecord(i).getNodeId();
                break;
            }
        }
        if (branchHead != null) {
            return branchHead;
        }

        throw new HgRevisionNotFoundException("Unable to resolve revision identifier: " + targetRevision);
    }

    /**
     * Recursively checks out every subrepository declared in the working copy's {@code
     * .hgsub}/{@code .hgsubstate} to its pinned revision -- shared by {@link UpdateCommand}
     * (after checking out a target revision) and {@link CloneCommand} (after checking out the
     * freshly cloned tip), matching real hg's own behavior of recursing into subrepos on both
     * {@code hg update} and {@code hg clone}.
     *
     * <p>Backlog 32 gap #4 (verified live against Mercurial 7.2's {@code hgsubrepo._fetch()}/
     * {@code gitsubrepo._fetch()}, which both check local availability -- {@code
     * hasunlinkedrev}/{@code _githavelocally} -- before ever pulling/fetching): a subrepo whose
     * pinned revision is ALREADY present in its local clone is checked out directly, without
     * first pulling/fetching from the remote. This matters both for matching real hg's actual
     * behavior and for correctness in network-isolated environments (e.g. tests using a stale or
     * unreachable {@code file://} remote after the subrepo was already fully cloned).
     *
     * <p>Backlog 32 gap #3: git subrepos ({@code [git]} prefix in {@code .hgsub}) are checked
     * out too, via the {@code git} CLI -- see {@link GitSubrepoUtil} for exactly what was
     * verified live for the git side.
     */
    static void recursiveSubrepoCheckout(HgRepository repository) {
        File hgsubFile = new File(repository.getDirectory(), ".hgsub");
        File hgsubstateFile = new File(repository.getDirectory(), ".hgsubstate");
        if (!hgsubFile.exists() || !hgsubstateFile.exists()) {
            return;
        }
        try {
            byte[] hgsubBytes = Files.readAllBytes(hgsubFile.toPath());
            byte[] hgsubstateBytes = Files.readAllBytes(hgsubstateFile.toPath());
            Map<String, HgSubrepoEntry> subrepos = HgSubrepoParser.parseSubrepositories(hgsubBytes, hgsubstateBytes);

            for (HgSubrepoEntry subEntry : subrepos.values()) {
                checkoutSubrepoEntry(repository.getDirectory(), subEntry);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to perform recursive subrepo checkout", e);
        }
    }

    /**
     * Checks out a single subrepo entry (hg- or git-typed) to its {@code .hgsubstate}-pinned
     * revision under {@code repositoryDir}. Factored out of {@link #recursiveSubrepoCheckout}
     * (backlog 32 gap #4) so {@link MergeCommand} can reuse the exact same checkout logic for
     * the non-diverged ("remote changed only") case of a two-parent {@code hg merge} that
     * touches {@code .hgsubstate} -- see {@code MergeCommand#mergeSubrepoState} (backlog 32
     * follow-up, gap B).
     */
    static void checkoutSubrepoEntry(File repositoryDir, HgSubrepoEntry subEntry) {
        File subDir = new File(repositoryDir, subEntry.getPath());

        if (subEntry.isGit()) {
            checkoutGitSubrepo(subDir, subEntry);
            return;
        }

        try {
            HgRepository subRepo;
            if (!new File(subDir, ".hg").exists()) {
                subRepo = Hg.init().setDirectory(subDir).call();
            } else {
                subRepo = new HgRepository(subDir);
            }

            try (Hg hgSub = Hg.wrap(subRepo)) {
                String revision = subEntry.getRevision();
                boolean haveLocally = revision != null && !revision.isEmpty()
                        && isRevisionPresentLocally(subRepo, revision);

                if (!haveLocally && subEntry.getSourceUrl() != null && !subEntry.getSourceUrl().isEmpty()) {
                    try {
                        hgSub.pull().setSource(subEntry.getSourceUrl()).call();
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Failed to pull subrepo from: " + subEntry.getSourceUrl() + ", error: " + e.getMessage(), e);
                    }
                }

                if (revision != null && !revision.isEmpty()) {
                    hgSub.update().setRevision(revision).setForce(true).call();
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to check out subrepo \"" + subEntry.getPath() + "\": " + e.getMessage(), e);
        }
    }

    /** Whether {@code revisionHex} already exists in {@code subRepo}'s local changelog --
     * backlog 32 gap #4's "skip the pull when already available locally" check. */
    static boolean isRevisionPresentLocally(HgRepository subRepo, String revisionHex) {
        try {
            File clIdx = new File(subRepo.getStoreDir(), "00changelog.i");
            File clDat = new File(subRepo.getStoreDir(), "00changelog.d");
            if (!clIdx.exists()) {
                return false;
            }
            Revlog changelog = subRepo.getRevlog(clIdx, clDat);
            return NodeIdUtil.findRevisionByNodeId(changelog, NodeIdUtil.fromHex(revisionHex)) != -1;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Checks out a {@code [git]} subrepo to its {@code .hgsubstate}-pinned commit: clones it
     * first if not present locally at all, fetches only if the pinned commit is missing
     * (backlog 32 gap #4, same local-availability check as the hg-subrepo path above), then
     * {@code git checkout}s it (skipped entirely if already at that commit) -- see {@link
     * GitSubrepoUtil} for what this simplifies versus real hg's {@code gitsubrepo.get()} and
     * what was verified live.
     */
    static void checkoutGitSubrepo(File subDir, HgSubrepoEntry subEntry) {
        String targetSha = subEntry.getRevision();
        if (targetSha == null || targetSha.isEmpty()) {
            return;
        }
        try {
            if (!GitSubrepoUtil.isGitCheckout(subDir)) {
                if (subEntry.getSourceUrl() == null || subEntry.getSourceUrl().isEmpty()) {
                    LOGGER.log(Level.WARNING, "Git subrepo at " + subDir + " is not checked out locally and has no source URL to clone from");
                    return;
                }
                GitSubrepoUtil.clone(subDir.getParentFile(), subEntry.getSourceUrl(), subDir);
            }

            if (!GitSubrepoUtil.hasLocally(subDir, targetSha)) {
                try {
                    GitSubrepoUtil.fetch(subDir);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to fetch git subrepo at " + subDir + ": " + e.getMessage(), e);
                }
            }

            String currentHead = null;
            try {
                currentHead = GitSubrepoUtil.revParseHead(subDir);
            } catch (IOException ignored) {
                // Freshly cloned/empty repo with no commits yet -- fall through to checkout,
                // which will report its own error if the target sha still can't be found.
            }
            if (targetSha.equals(currentHead)) {
                return; // Already checked out -- matches gitsubrepo.get()'s own early return.
            }
            GitSubrepoUtil.checkout(subDir, targetSha);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to perform recursive git subrepo checkout for " + subEntry.getPath() + ": " + e.getMessage(), e);
        }
    }
}
