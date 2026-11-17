package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.util.SafeFileIO;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lfs.HgLfsFactory;

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
import io.github.search5.hg4j.submodule.SvnSubrepoUtil;
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

    /**
     * Creates the command.
     * @param repository the repository to update; must not be {@code null}
     */
    public UpdateCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Registers a hook run before the update; if it returns {@code false} the update fails.
     * @param hook the hook; {@code null} is ignored
     * @return {@code this}
     */
    public UpdateCommand registerPreUpdateHook(HgHook hook) {
        if (hook != null) {
            preUpdateHooks.add(hook);
        }
        return this;
    }

    /**
     * Registers a hook run after the update.
     * @param hook the hook; {@code null} is ignored
     * @return {@code this}
     */
    public UpdateCommand registerPostUpdateHook(HgHook hook) {
        if (hook != null) {
            postUpdateHooks.add(hook);
        }
        return this;
    }

    /**
     * Sets a filter limiting which paths are checked out (narrow support).
     * @param treeFilter the filter; {@code null} is ignored. The default {@code HgTreeFilter.ALL} lets the update use
     *         the repository's own narrowspec
     * @return {@code this}
     */
    public UpdateCommand setTreeFilter(HgTreeFilter treeFilter) {
        if (treeFilter != null) {
            this.treeFilter = treeFilter;
        }
        return this;
    }

    /**
     * Sets the revision to update to.
     * @param targetRevision a revision identifier (revision number, node hex or prefix, symbolic name) or a named
     *         branch, which selects the highest-numbered revision on it; must be set before {@link #call()}
     * @return {@code this}
     */
    public UpdateCommand setRevision(String targetRevision) {
        this.targetRevision = targetRevision;
        return this;
    }

    /**
     * Sets whether uncommitted changes may be overwritten.
     * @param force {@code true} to update even with uncommitted changes (like {@code hg update -C}); default
     *         {@code false}
     * @return {@code this}
     */
    public UpdateCommand setForce(boolean force) {
        this.force = force;
        return this;
    }

    /** Receives the progress of the LFS downloads and the lines Mercurial prints about them (see {@code HgLfsProgress}). */
    public UpdateCommand setProgressMonitor(io.github.search5.hg4j.lib.ProgressMonitor monitor) {
        this.monitor = monitor;
        return this;
    }

    private io.github.search5.hg4j.lib.ProgressMonitor monitor;
    private boolean updateBookmarks = true;

    /** The update of the original's plain {@code update} function (not the {@code hg update} command): the active bookmark is left alone. */
    UpdateCommand withoutBookmarkUpdate() {
        this.updateBookmarks = false;
        return this;
    }

    /**
     * Updates the working copy to the revision set with {@link #setRevision}.
     * <p>
     * Takes the working copy lock and runs inside a dirstate parent-changing context. Pre-update hooks run first
     * (context keys {@code repository}, {@code targetRevision}, {@code force}) and can veto. Unless forced, the update is
     * refused when the working copy has added, modified or removed files. After the checkout, post-update hooks run
     * (context keys {@code targetNode}, {@code repository}); their failures are logged and ignored. If no tree filter
     * was set, the repository's narrowspec is used.
     * @return the 20-byte node id of the revision that is now checked out
     * @throws io.github.search5.hg4j.errors.HgValidationException (an {@link IOException}) if the repository is
     *         empty, a pre-update hook returns {@code false}, or there are uncommitted changes and force is off
     * @throws io.github.search5.hg4j.errors.HgRevisionNotFoundException (an {@link IOException}) if the revision
     *         cannot be resolved or is ambiguous
     * @throws IOException if reading history or writing the working copy fails
     * @throws HgLockException if the working copy lock cannot be acquired
     */
    public byte[] call() throws IOException, HgLockException {
        try (io.github.search5.hg4j.lfs.HgLfsProgress bound = io.github.search5.hg4j.lfs.HgLfsProgress.bind(monitor)) {
            return callUpdate();
        }
    }

    private byte[] callUpdate() throws IOException, HgLockException {
        // Backlog 30: when the caller hasn't explicitly narrowed this update (still the default
        // HgTreeFilter.ALL), pick up whatever narrowspec the repository itself was narrow cloned
        // with -- so checkout keeps respecting the narrow scope on every later `update`, not just
        // right after NarrowCloneCommand's own initial checkout.
        // Like real hg: the locks are taken right after the arguments are checked, before any repository state is read, and released when the command ends.
        try (HgLock wlock = repository.lockWorkingCopy()) {
            // commands.update: `with repo.dirstate.changing_parents(repo)` inside the locks; the dirstate is written when it ends well
            return repository.changingParents(() -> {
            if (this.treeFilter == HgTreeFilter.ALL) {
                this.treeFilter = HgTreeFilter.loadForWorkingCopy(repository);
            }

            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");

            {

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

                // one batch request for every LFS blob this update needs, instead of one request per file
                TreeWalk prefetchWalk = new TreeWalk();
                prefetchWalk.addTree(new ManifestTreeIterator(repository, parentRev));
                prefetchWalk.addTree(new ManifestTreeIterator(repository, String.valueOf(commitRev)));
                prefetchWalk.reset();
                java.util.List<java.util.Map.Entry<Revlog, Integer>> toRead = new java.util.ArrayList<>();
                java.util.List<String> toReadPaths = new java.util.ArrayList<>();
                while (prefetchWalk.next()) {
                    String prefetchPath = prefetchWalk.getPath();
                    if ((treeFilter != null && !treeFilter.accept(prefetchPath)) || !prefetchWalk.isTracked(1)) {
                        continue;
                    }
                    File prefetchIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), prefetchPath);
                    if (!prefetchIdx.exists()) {
                        continue; // reported by the update loop below
                    }
                    File prefetchDat = new File(prefetchIdx.getPath().substring(0, prefetchIdx.getPath().length() - 2) + ".d");
                    Revlog prefetchLog = repository.getRevlog(prefetchIdx, prefetchDat);
                    int prefetchRev = NodeIdUtil.findRevisionByNodeId(prefetchLog, prefetchWalk.getNodeId(1));
                    if (prefetchRev != -1) {
                        toRead.add(java.util.Map.entry(prefetchLog, prefetchRev));
                        toReadPaths.add(prefetchPath);
                    }
                }
                HgLfsFactory.getInstance().prefetch(repository, toRead, toReadPaths);

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
                        // hgext/lfs `readfromstore` wrapper. Delegated to HgLfsFactory#applySmudgeFilter
                        // so this checkout path and AnnotateCommand's content-reading path share one
                        // implementation that consistently honors [lfs] url / usercache config.
                        fileContent = HgLfsFactory.getInstance().applySmudgeFilter(repository, path, fileContent, filelog.isExtStored(fileRev));

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

                // 4a. `update_totally`: the revision name given is remembered ("brev"); if it is a bookmark, that bookmark becomes the
                // active one, any other name given deactivates the active bookmark, and no name leaves it as it is. A bare update
                // (`destupdate`) moves the active bookmark along when it was on the working directory parent, or takes the active
                // bookmark's name as the name. The updates that the original makes with the plain `update` function (share, subrepos)
                // do not touch bookmarks.
                if (updateBookmarks) {
                    BookmarkCommand bookmarkCmd = new BookmarkCommand(repository);
                    String brev = bareDestination != null ? bareDestination.activeMark : targetRevision;
                    if (bareDestination != null && bareDestination.moveFrom != null) {
                        if (!Arrays.equals(bareDestination.moveFrom, targetNodeId)
                                && !moveActiveBookmark(changelog, bareDestination.moveFrom, targetNodeId)) {
                            // a non-linear update: "(leaving bookmark X)"
                            bookmarkCmd.setActive(true).setCurrentOnly(true).setBookmarkName(null).call();
                        }
                    } else if (brev != null && !brev.isEmpty()) {
                        if (bookmarkCmd.call().containsKey(brev)) {
                            bookmarkCmd.setBookmarkName(brev).setActive(true).setCurrentOnly(true).call();
                        } else {
                            bookmarkCmd.setActive(true).setCurrentOnly(true).setBookmarkName(null).call();
                        }
                    }
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
            });
        }
    }

    /** The result of {@code destutil.destupdate}: where a bare update goes, which bookmark to move along, and the bookmark's name. */
    private static final class BareDestination {
        final byte[] node;
        final byte[] moveFrom;
        final String activeMark;

        BareDestination(byte[] node, byte[] moveFrom, String activeMark) {
            this.node = node;
            this.moveFrom = moveFrom;
            this.activeMark = activeMark;
        }
    }

    private BareDestination bareDestination;

    private static boolean isNullNode(byte[] node) {
        for (int i = 0; node != null && i < Math.min(20, node.length); i++) {
            if (node[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /** {@code bookmarks.isactivewdirparent}: the active bookmark points to a parent of the working directory. */
    private boolean isActiveWdirParent(Map<String, String> marks, String active, byte[] p1, byte[] p2) {
        if (active == null || !marks.containsKey(active)) {
            return false;
        }
        String hex = marks.get(active);
        return hex.equals(NodeIdUtil.toHex(p1)) || (p2 != null && hex.equals(NodeIdUtil.toHex(p2)));
    }

    /**
     * {@code destutil._destupdateobs}: when the working directory parent is obsolete (a predecessor in a marker and not public) and has no
     * children, update to the tipmost of its latest successors, or for a pruned changeset to its closest ancestor that is not obsolete.
     * @return the destination, or {@code null} when this step gives none
     */
    private BareDestination destUpdateObsolete(Revlog changelog, byte[] p1, boolean activeIsParent) throws IOException {
        if (isNullNode(p1)) {
            return null;
        }
        Map<String, java.util.List<io.github.search5.hg4j.obsolete.ObsStore.Marker>> succMarkers =
                io.github.search5.hg4j.obsolete.ObsUtil.bySuccessor(io.github.search5.hg4j.obsolete.ObsStore.markers(repository));
        if (succMarkers.isEmpty()) {
            return null;
        }
        int p1Rev = NodeIdUtil.findRevisionByNodeId(changelog, p1);
        if (p1Rev < 0 || !isObsolete(changelog, succMarkers, p1Rev)) {
            return null;
        }
        int count = changelog.getRevisionCount();
        for (int i = p1Rev + 1; i < count; i++) {
            if (changelog.getIndexRecord(i).getParent1() == p1Rev || changelog.getIndexRecord(i).getParent2() == p1Rev) {
                return null;
            }
        }
        java.util.List<java.util.List<String>> successors = io.github.search5.hg4j.obsolete.ObsUtil.successorsSets(
                succMarkers, hex -> NodeIdUtil.findRevisionByNodeId(changelog, NodeIdUtil.fromHex(hex)) >= 0, NodeIdUtil.toHex(p1));
        byte[] node;
        if (!successors.isEmpty()) {
            int best = -1;
            for (java.util.List<String> set : successors) {
                for (String hex : set) {
                    best = Math.max(best, NodeIdUtil.findRevisionByNodeId(changelog, NodeIdUtil.fromHex(hex)));
                }
            }
            node = best < 0 ? null : changelog.getIndexRecord(best).getNodeId();
        } else {
            // pruned: the closest ancestor that is not obsolete
            int rev = changelog.getIndexRecord(p1Rev).getParent1();
            while (rev >= 0 && isObsolete(changelog, succMarkers, rev)) {
                rev = changelog.getIndexRecord(rev).getParent1();
            }
            if (rev < 0) {
                throw new HgValidationException("Cannot update: the working directory parent is obsolete and pruned, and so are its ancestors.");
            }
            node = changelog.getIndexRecord(rev).getNodeId();
        }
        if (node == null) {
            return null;
        }
        return new BareDestination(node, activeIsParent ? p1 : null, null);
    }

    /** {@code obsolete()} of a changeset: not public, and the predecessor of a marker. */
    private boolean isObsolete(Revlog changelog, Map<String, java.util.List<io.github.search5.hg4j.obsolete.ObsStore.Marker>> succMarkers,
                               int rev) throws IOException {
        byte[] node = changelog.getIndexRecord(rev).getNodeId();
        if (!succMarkers.containsKey(NodeIdUtil.toHex(node))) {
            return false;
        }
        return repository.getPhaseRoots().getPhase(new io.github.search5.hg4j.lib.NodeId(node), changelog)
                != io.github.search5.hg4j.phase.PhaseRoots.Phase.PUBLIC;
    }

    /**
     * {@code destutil.destupdate} for a bare update: the steps {@code bookmark}, {@code branch} and {@code branchfallback} are tried in
     * this order until one gives a destination (the {@code evolution} step comes first).
     */
    private BareDestination destUpdate(Revlog changelog) throws IOException {
        Map<String, String> marks = new BookmarkCommand(repository).list();
        String active = new BookmarkCommand(repository).getActiveBookmark();
        Dirstate dirstate = repository.getDirstate();
        byte[] p1 = dirstate.getParent1();
        byte[] p2 = dirstate.getParent2();
        boolean activeIsParent = isActiveWdirParent(marks, active, p1, p2);

        // step evolution (`_destupdateobs`): an obsolete working directory parent without children goes to its successors
        BareDestination evolved = destUpdateObsolete(changelog, p1, activeIsParent);
        if (evolved != null) {
            return evolved;
        }

        // step bookmark (`_destupdatebook`): with the active bookmark away from the working directory, go to it
        if (!activeIsParent && active != null && marks.containsKey(active)) {
            return new BareDestination(NodeIdUtil.fromHex(marks.get(active)), null, active);
        }

        int count = changelog.getRevisionCount();
        int p1Rev = isNullNode(p1) ? -1 : NodeIdUtil.findRevisionByNodeId(changelog, p1);
        String currentBranch = repository.getBranch();
        boolean branchKnown = false;
        for (int i = 0; i < count && !branchKnown; i++) {
            branchKnown = currentBranch.equals(CommitCommand.getBranchOfRevision(changelog, i));
        }
        io.github.search5.hg4j.revwalk.ChangesetGraph graph = new io.github.search5.hg4j.revwalk.ChangesetGraph(changelog);
        byte[] moveFrom = activeIsParent ? p1 : null;

        // step branch (`_destupdatebranch`): the tipmost open head of the current branch that is reachable from the parent
        if (branchKnown) {
            int head = tipmostHeadFrom(changelog, graph, currentBranch, p1Rev, false);
            if (head >= 0) {
                return new BareDestination(changelog.getIndexRecord(head).getNodeId(), moveFrom, null);
            }
        } else if ("default".equals(currentBranch) && p1Rev < 0) {
            // the null parent belongs to the branch "default" that does not exist: the tipmost open head of all branches
            boolean[] hasChild = new boolean[count];
            for (int i = 0; i < count; i++) {
                int a = changelog.getIndexRecord(i).getParent1();
                int b = changelog.getIndexRecord(i).getParent2();
                if (a >= 0) hasChild[a] = true;
                if (b >= 0) hasChild[b] = true;
            }
            for (int i = count - 1; i >= 0; i--) {
                if (!hasChild[i] && !CommitCommand.isRevisionClosingBranch(changelog, i)) {
                    return new BareDestination(changelog.getIndexRecord(i).getNodeId(), null, null);
                }
            }
        } else {
            return new BareDestination(p1, null, null);
        }

        // step branchfallback (`_destupdatebranchfallback`): the closed heads count too
        if (branchKnown) {
            int head = tipmostHeadFrom(changelog, graph, currentBranch, p1Rev, true);
            if (head >= 0) {
                return new BareDestination(changelog.getIndexRecord(head).getNodeId(), moveFrom, null);
            }
        }
        return new BareDestination(changelog.getIndexRecord(count - 1).getNodeId(), null, null);
    }

    /** {@code branch_tip_from}: {@code max(start::heads(branch))}; {@code -1} if no head can be reached from {@code start}. */
    private int tipmostHeadFrom(Revlog changelog, io.github.search5.hg4j.revwalk.ChangesetGraph graph, String branch, int start,
                                boolean closed) throws IOException {
        int best = -1;
        for (String hex : new HeadsCommand(repository).setBranch(branch).setIncludeClosed(closed).call()) {
            int head = NodeIdUtil.findRevisionByNodeId(changelog, NodeIdUtil.fromHex(hex));
            if (head > best && (start < 0 || head == start || graph.isAncestor(start, head))) {
                best = head;
            }
        }
        return best;
    }

    /**
     * {@code newRev in obsutil.foreground([oldRev])}: reachable from the old changeset through children and through the successors that
     * the markers name, until nothing is added.
     */
    private boolean inForeground(Revlog changelog, Map<String, java.util.List<io.github.search5.hg4j.obsolete.ObsStore.Marker>> succMarkers,
                                 int oldRev, int newRev) throws IOException {
        int count = changelog.getRevisionCount();
        java.util.Set<Integer> foreground = descendantsOrSelf(changelog, java.util.List.of(oldRev));
        int previous = -1;
        while (foreground.size() != previous) {
            previous = foreground.size();
            java.util.List<Integer> known = new ArrayList<>(foreground);
            java.util.Deque<String> remaining = new java.util.ArrayDeque<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int rev : foreground) {
                if (isObsoleteOrMutable(changelog, rev)) {
                    String hex = NodeIdUtil.toHex(changelog.getIndexRecord(rev).getNodeId());
                    if (seen.add(hex)) {
                        remaining.push(hex);
                    }
                }
            }
            while (!remaining.isEmpty()) {
                for (io.github.search5.hg4j.obsolete.ObsStore.Marker mark : succMarkers.getOrDefault(remaining.pop(), java.util.List.of())) {
                    for (byte[] suc : mark.sucs()) {
                        String hex = NodeIdUtil.toHex(suc);
                        if (seen.add(hex)) {
                            remaining.push(hex);
                            int rev = NodeIdUtil.findRevisionByNodeId(changelog, suc);
                            if (rev >= 0 && rev < count) {
                                known.add(rev);
                            }
                        }
                    }
                }
            }
            foreground = descendantsOrSelf(changelog, known);
        }
        return foreground.contains(newRev);
    }

    private boolean isObsoleteOrMutable(Revlog changelog, int rev) throws IOException {
        return repository.getPhaseRoots().getPhase(new io.github.search5.hg4j.lib.NodeId(changelog.getIndexRecord(rev).getNodeId()), changelog)
                != io.github.search5.hg4j.phase.PhaseRoots.Phase.PUBLIC;
    }

    /** {@code roots::}: the revisions and all their descendants. */
    private static java.util.Set<Integer> descendantsOrSelf(Revlog changelog, java.util.List<Integer> roots) throws IOException {
        int count = changelog.getRevisionCount();
        boolean[] in = new boolean[count];
        int min = count;
        for (int r : roots) {
            in[r] = true;
            min = Math.min(min, r);
        }
        java.util.Set<Integer> result = new java.util.LinkedHashSet<>();
        for (int i = min; i < count; i++) {
            int a = changelog.getIndexRecord(i).getParent1();
            int b = changelog.getIndexRecord(i).getParent2();
            if ((a >= 0 && in[a]) || (b >= 0 && in[b])) {
                in[i] = true;
            }
            if (in[i]) {
                result.add(i);
            }
        }
        return result;
    }

    /**
     * {@code bookmarks.update(repo, [moveFrom], node)}: the active bookmark follows the working directory to {@code node} if that is a
     * descendant of where the bookmark was, and the divergent versions of it that the move makes obsolete are deleted.
     * @return whether any bookmark changed; {@code false} means the update left the bookmark behind
     */
    private boolean moveActiveBookmark(Revlog changelog, byte[] moveFrom, byte[] node) throws IOException, HgLockException {
        BookmarkCommand reader = new BookmarkCommand(repository);
        String active = reader.getActiveBookmark();
        if (active == null) {
            return false;
        }
        Map<String, String> marks = reader.list();
        String base = active.split("@", 2)[0];
        int newRev = NodeIdUtil.findRevisionByNodeId(changelog, node);
        java.util.Set<String> deleteFrom = new java.util.HashSet<>();
        boolean move = false;
        if (marks.get(active) != null && marks.get(active).equals(NodeIdUtil.toHex(moveFrom))) {
            java.util.Set<Integer> ancestors = new io.github.search5.hg4j.revwalk.ChangesetGraph(changelog).getAllAncestors(newRev);
            for (Map.Entry<String, String> e : marks.entrySet()) {
                if (e.getKey().split("@", 2)[0].equals(base)) {
                    int rev = NodeIdUtil.findRevisionByNodeId(changelog, NodeIdUtil.fromHex(e.getValue()));
                    if (ancestors.contains(rev) || rev == newRev) {
                        deleteFrom.add(e.getValue());
                    }
                }
            }
            int oldRev = NodeIdUtil.findRevisionByNodeId(changelog, NodeIdUtil.fromHex(marks.get(active)));
            // `validdest`: nothing to move when it is the same changeset, otherwise the old one must be an ancestor of the new one
            // with obsolescence markers the new changeset may also be a successor of the old one (`obsutil.foreground`)
            java.util.List<io.github.search5.hg4j.obsolete.ObsStore.Marker> markers = io.github.search5.hg4j.obsolete.ObsStore.markers(repository);
            move = oldRev != newRev && (markers.isEmpty()
                    ? new io.github.search5.hg4j.revwalk.ChangesetGraph(changelog).isAncestor(oldRev, newRev)
                    : inForeground(changelog, io.github.search5.hg4j.obsolete.ObsUtil.bySuccessor(markers), oldRev, newRev));
        } else {
            deleteFrom.add(NodeIdUtil.toHex(moveFrom));
        }
        boolean changed = false;
        if (move) {
            new BookmarkCommand(repository).setBookmarkName(active).setRevision(NodeIdUtil.toHex(node)).setForce(true).call();
            changed = true;
        }
        for (Map.Entry<String, String> e : marks.entrySet()) {
            String name = e.getKey();
            // `divergent2delete`: the divergent versions (name@something) of the active bookmark that sit on the changesets left behind
            if (name.split("@", 2)[0].equals(base) && !name.equals("@") && name.contains("@") && !name.equals(active)
                    && deleteFrom.contains(e.getValue())) {
                new BookmarkCommand(repository).setBookmarkName(name).setDelete(true).call();
                changed = true;
            }
        }
        return changed;
    }

    private byte[] resolveTargetNodeId(Revlog changelog) throws IOException {
        if (changelog.getRevisionCount() == 0) {
            return null;
        }
        bareDestination = null;
        if (targetRevision == null || targetRevision.isEmpty()) {
            bareDestination = destUpdate(changelog);
            return bareDestination.node;
        }
        // a bookmark name is a revision symbol that comes before the branch names and the hash prefixes (`tip` is never a bookmark)
        if (targetRevision != null && !"tip".equals(targetRevision)) {
            String bookmarked = new BookmarkCommand(repository).list().get(targetRevision);
            if (bookmarked != null) {
                byte[] node = NodeIdUtil.fromHex(bookmarked);
                if (node != null && node.length >= 20) {
                    return node;
                }
            }
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

        if (subEntry.isSvn()) {
            checkoutSvnSubrepo(subDir, subEntry);
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

    /**
     * Checks out a {@code [svn]} subrepo to its {@code .hgsubstate}-pinned revision (backlog
     * 41): {@code svn checkout --force <url>@<revision>}, real hg's own {@code svnsubrepo.get()}.
     * Unlike the git-typed sibling ({@link #checkoutGitSubrepo}), real hg's svn {@code get()} has
     * no "already there" fast path of its own -- it unconditionally re-runs {@code checkout
     * --force} whether the working copy already exists at that revision or not (verified live:
     * this is cheap/idempotent against svn's own incremental working-copy update), so this
     * method does not add a skip either, to stay byte-for-byte faithful to real hg's behavior.
     */
    static void checkoutSvnSubrepo(File subDir, HgSubrepoEntry subEntry) {
        String targetRev = subEntry.getRevision();
        if (targetRev == null || targetRev.isEmpty()) {
            return;
        }
        String url = subEntry.getSourceUrl();
        if (url == null || url.isEmpty()) {
            LOGGER.log(Level.WARNING, "Svn subrepo at " + subDir + " has no source URL to check out from");
            return;
        }
        try {
            SvnSubrepoUtil.get(subDir.getParentFile(), url, targetRev, subDir);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to perform recursive svn subrepo checkout for " + subEntry.getPath() + ": " + e.getMessage(), e);
        }
    }
}
