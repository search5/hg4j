package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.errors.HgLockException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.util.SafeFileIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strip command for truncating/removing changesets and their descendants
 * completely from repository revlogs, rolling back history securely.
 */
public class StripCommand {
    private final HgRepository repository;
    private String revision;

    public StripCommand(HgRepository repository) {
        this.repository = repository;
    }

    public StripCommand setRevision(String revision) {
        this.revision = revision;
        return this;
    }

    private boolean force;
    private boolean keep;
    private boolean backup = true;
    private String topic = "backup";
    private File backupBundle;
    private final List<String> messages = new ArrayList<>();

    /** What the command printed: {@code saved backup bundle to ...}, and the warnings of a failed strip. */
    public List<String> getMessages() {
        return messages;
    }

    /** {@code hg strip --no-backup}: no bundle of the removed changesets is saved (default: one is). */
    public StripCommand setBackup(boolean backup) {
        this.backup = backup;
        return this;
    }

    /** The word at the end of the backup bundle's name ({@code backup} for {@code strip}, {@code copy}, {@code rebase}, ...). */
    public StripCommand setTopic(String topic) {
        this.topic = topic;
        return this;
    }

    /** The bundle of the removed changesets that {@link #call()} saved ({@code saved backup bundle to ...}), or {@code null}. */
    public File getBackupBundle() {
        return backupBundle;
    }

    /** {@code hg strip --force}: uncommitted changes in the working copy are discarded instead of refusing. */
    public StripCommand setForce(boolean force) {
        this.force = force;
        return this;
    }

    /** {@code hg strip --keep}: the working copy files are left alone (only its parent follows the strip). */
    public StripCommand setKeep(boolean keep) {
        this.keep = keep;
        return this;
    }

    /**
     * Executes SCM strip/rollback operation by physically truncating `.i` and `.d` revlogs
     * at target revision offset and resetting working copy parents.
     *
     * @throws IOException if truncation or workspace restoration fails
     */
    private final List<String> moreRevisions = new ArrayList<>();
    private final Set<String> bookmarkNames = new java.util.TreeSet<>();
    private boolean soft;
    private boolean permitEmpty;

    /** Another revision to strip ({@code hg strip REV REV ...}); a revset expression is read like a revision set. */
    public StripCommand addRevision(String revision) {
        moreRevisions.add(revision);
        return this;
    }

    /** {@code -B BOOKMARK}: strip the changesets that only that bookmark reaches, then delete the bookmark. */
    public StripCommand addBookmark(String name) {
        bookmarkNames.add(name);
        return this;
    }

    /** {@code --soft}: not available: it needs the archived phase of a repository with the internal-phase requirement. */
    public StripCommand setSoft(boolean soft) {
        this.soft = soft;
        return this;
    }

    /** {@code --permit-empty-revset} (and {@code debugstrip}): an empty revision set is a success, not an abort. */
    public StripCommand setPermitEmptyRevset(boolean permitEmpty) {
        this.permitEmpty = permitEmpty;
        return this;
    }

    private Set<String> bookmarksToDelete = new java.util.TreeSet<>();

    /**
     * {@code strip._strip_command}: under the working copy lock the revision set is worked out ({@code -B} names included: the
     * changesets only they reach), a bookmark that is not the only one on its node is only deleted; then {@code strip()} takes the
     * store lock and does the work.
     */
    public void call() throws IOException, HgLockException {
        if ((revision == null || revision.isEmpty()) && moreRevisions.isEmpty() && bookmarkNames.isEmpty()) {
            throw new IllegalArgumentException("Target revision must be specified for strip rollback");
        }
        if (soft) {
            throw new io.github.search5.hg4j.errors.HgValidationException(
                    "strip --soft needs the archived phase (a repository with the internal-phase requirement), which this repository does not have");
        }
        messages.clear();
        docketBackupEntries.clear();
        try (HgLock wlock = repository.lockWorkingCopy()) {
            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");
            Revlog changelog = repository.getRevlog(clIdx, clDat);
            java.util.TreeSet<Integer> requested = new java.util.TreeSet<>();
            List<String> specs = new ArrayList<>();
            if (revision != null && !revision.isEmpty()) {
                specs.add(revision);
            }
            specs.addAll(moreRevisions);
            for (String spec : specs) {
                byte[] node = NodeIdUtil.resolveRevision(changelog, spec);
                if (node != null && changelog.findRevision(node) >= 0) {
                    requested.add(changelog.findRevision(node));
                } else if (specs.size() == 1 && !spec.contains("(") && !spec.contains("::") && !spec.contains(":") && !spec.contains("+")) {
                    throw new IOException("Strip target revision not found: " + spec);
                } else {
                    requested.addAll(ChangesetRewrite.revrange(repository, changelog, spec));
                }
            }
            bookmarksToDelete = new java.util.TreeSet<>();
            if (!bookmarkNames.isEmpty()) {
                Map<String, String> marks = new BookmarkCommand(repository).list();
                if (!marks.keySet().containsAll(bookmarkNames)) {
                    java.util.TreeSet<String> missing = new java.util.TreeSet<>(bookmarkNames);
                    missing.removeAll(marks.keySet());
                    throw new io.github.search5.hg4j.errors.HgValidationException("bookmark '" + String.join(",", missing) + "' not found");
                }
                // a bookmark that shares its node with one that is not asked for only goes away: nothing is stripped for it
                Map<String, List<String>> onNode = new HashMap<>();
                marks.forEach((name, node) -> onNode.computeIfAbsent(node, k -> new ArrayList<>()).add(name));
                for (List<String> names : onNode.values()) {
                    if (bookmarkNames.containsAll(names)) {
                        requested.addAll(bookmarkRevisions(changelog, names.get(0), marks));
                    }
                }
                bookmarksToDelete.addAll(bookmarkNames);
                if (requested.isEmpty()) {
                    try (HgLock lock = repository.lockStore()) {
                        deleteBookmarks();
                    }
                    return;
                }
            }
            if (requested.isEmpty()) {
                if (permitEmpty) {
                    return;
                }
                throw new io.github.search5.hg4j.errors.HgValidationException("empty revision set");
            }
            stripRevisions(changelog, requested);
        }
    }

    /** {@code scmutil.bookmarkrevs}: what only this bookmark reaches (its ancestors, minus those of the unbookmarked heads and of the other bookmarks). */
    private Set<Integer> bookmarkRevisions(Revlog changelog, String mark, Map<String, String> marks) throws IOException {
        int count = changelog.getRevisionCount();
        int markRev = changelog.findRevision(NodeIdUtil.fromHex(marks.get(mark)));
        boolean[] hasChild = new boolean[count];
        for (int r = 0; r < count; r++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(r);
            if (rec.getParent1() >= 0) hasChild[rec.getParent1()] = true;
            if (rec.getParent2() >= 0) hasChild[rec.getParent2()] = true;
        }
        Set<String> marked = new java.util.HashSet<>();
        for (Map.Entry<String, String> e : marks.entrySet()) {
            if (e.getKey().equals(mark)) {
                continue;
            }
            marked.add(e.getValue());
        }
        List<Integer> others = new ArrayList<>();
        for (int r = 0; r < count; r++) {
            String hex = NodeIdUtil.toHex(changelog.getIndexRecord(r).getNodeId());
            boolean isHead = !hasChild[r];
            boolean carriesThisMark = hex.equals(marks.get(mark));
            if (!carriesThisMark && (isHead || marked.contains(hex))) {
                others.add(r);
            }
        }
        boolean[] excluded = new boolean[count];
        for (int o : others) {
            markAncestors(changelog, o, excluded);
        }
        boolean[] reach = new boolean[count];
        markAncestors(changelog, markRev, reach);
        Set<Integer> out = new java.util.TreeSet<>();
        for (int r = 0; r < count; r++) {
            if (reach[r] && !excluded[r]) {
                out.add(r);
            }
        }
        return out;
    }

    private static void markAncestors(Revlog changelog, int rev, boolean[] into) throws IOException {
        java.util.ArrayDeque<Integer> work = new java.util.ArrayDeque<>();
        work.add(rev);
        while (!work.isEmpty()) {
            int r = work.poll();
            if (r < 0 || into[r]) {
                continue;
            }
            into[r] = true;
            Revlog.IndexRecord rec = changelog.getIndexRecord(r);
            work.add(rec.getParent1());
            work.add(rec.getParent2());
        }
    }

    /** The bookmarks asked for with {@code -B} are deleted (the active one is deactivated first) and each is announced. */
    private void deleteBookmarks() throws IOException, HgLockException {
        BookmarkCommand bookmarks = new BookmarkCommand(repository);
        String active = bookmarks.getActiveBookmark();
        if (active != null && bookmarksToDelete.contains(active)) {
            new BookmarkCommand(repository).setBookmarkName(active).setActive(false).call();
        }
        for (String name : bookmarksToDelete) {
            new BookmarkCommand(repository).setBookmarkName(name).setDelete(true).call();
        }
        for (String name : bookmarksToDelete) {
            messages.add("bookmark '" + name + "' deleted\n");
        }
    }

    private final List<String> docketBackupEntries = new ArrayList<>();

    /**
     * Adds what a cut of {@code revlog} leaves to the plan. A version 2 revlog also gets the docket it will have afterwards, kept as a
     * file the journal names as a "backup": recovering installs it, which completes the cut.
     */
    private void addPlan(Map<File, Long> plan, List<String> docketFiles, Revlog revlog, int keepCount) throws IOException {
        Map<File, Long> sizes = revlog.truncatedSizes(keepCount);
        plan.putAll(sizes);
        if (revlog.isDocketRevlog()) {
            sizes.keySet().forEach(f -> docketFiles.add(f.getPath()));
            File docket = revlog.getIndexFile();
            File backup = new File(repository.getStoreDir(), "journal.docket." + java.util.UUID.randomUUID() + ".bck");
            SafeFileIO.writeAtomic(backup, revlog.truncatedDocket(keepCount));
            String rel = repository.getHgDir().toPath().relativize(docket.toPath()).toString().replace('\\', '/');
            String backupRel = repository.getHgDir().toPath().relativize(backup.toPath()).toString().replace('\\', '/');
            docketBackupEntries.add("backup " + rel + "\t" + backupRel);
        }
    }

    private void stripRevisions(Revlog changelog, java.util.TreeSet<Integer> requested) throws IOException, HgLockException {
        // `strip()`: `with repo.wlock(), repo.lock():` (the working copy lock is already held by the command and is taken again)
        try (HgLock wlock = repository.lockWorkingCopy();
             HgLock storeLock = repository.lockStore()) {
            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");
            changelog = repository.getRevlog(clIdx, clDat);
            int targetRev = requested.first();
            byte[] nodeBytes = changelog.getIndexRecord(targetRev).getNodeId();

            // We truncate all SCM histories to targetRev - 1
            int keepCount = targetRev;
            // Like repair.strip: the target and its descendants are removed; every other revision above it is saved in a bundle
            // before the store is cut at the target and is added again afterwards.
            int total = changelog.getRevisionCount();
            boolean[] stripped = new boolean[total];
            for (int r : requested) {
                stripped[r] = true;
            }
            for (int r = targetRev + 1; r < total; r++) {
                Revlog.IndexRecord rec = changelog.getIndexRecord(r);
                stripped[r] = stripped[r] || (rec.getParent1() >= 0 && stripped[rec.getParent1()])
                        || (rec.getParent2() >= 0 && stripped[rec.getParent2()]);
            }
            java.util.List<Integer> savedRevs = new ArrayList<>();
            java.util.Set<String> strippedHex = new java.util.HashSet<>();
            java.util.Set<String> survivingHex = new java.util.HashSet<>();
            for (int r = 0; r < total; r++) {
                String hex = NodeIdUtil.toHex(changelog.getIndexRecord(r).getNodeId());
                if (stripped[r]) {
                    strippedHex.add(hex);
                } else {
                    survivingHex.add(hex);
                    if (r >= targetRev) {
                        savedRevs.add(r);
                    }
                }
            }
            // strip.py: when a parent of the working copy is removed, checklocalchanges(), then the working copy goes to _findupdatetarget()
            Dirstate wdState = repository.getDirstate();
            boolean wdParentStripped = (wdState.getParent1() != null && strippedHex.contains(NodeIdUtil.toHex(wdState.getParent1())))
                    || (wdState.getParent2() != null && strippedHex.contains(NodeIdUtil.toHex(wdState.getParent2())));
            if (!keep && wdParentStripped) {
                Dirstate wd = repository.getDirstate();
                byte[] wdP2 = wd.getParent2();
                if (!force && wdP2 != null && !NodeIdUtil.isAllZero(wdP2)) {
                    throw new io.github.search5.hg4j.errors.HgValidationException("outstanding uncommitted merge");
                }
                if (!force) {
                    io.github.search5.hg4j.api.Status st = new StatusCommand(repository).call();
                    if (!st.getModified().isEmpty() || !st.getAdded().isEmpty() || !st.getRemoved().isEmpty()) {
                        throw new io.github.search5.hg4j.errors.HgValidationException("uncommitted changes");
                    }
                }
            }
            int updateRev = findUpdateTarget(changelog, targetRev, stripped);
            byte[] rollbackParent = updateRev >= 0 ? changelog.getIndexRecord(updateRev).getNodeId() : new byte[20];
            String updateBranch = updateRev >= 0 ? CommitCommand.getBranchOfRevision(changelog, updateRev) : "default";
            byte[] savedBundle = null;
            java.util.Map<String, io.github.search5.hg4j.phase.PhaseRoots.Phase> savedPhases = new java.util.LinkedHashMap<>();
            if (backup) {
                backupBundle = writeBackupBundle(changelog, targetRev, stripped);
                if (backupBundle != null) {
                    messages.add("saved backup bundle to " + backupBundle.getPath() + "\n");
                }
            }
            if (!savedRevs.isEmpty()) {
                // common = heads of the revisions below the target, heads = heads of the saved ones: the bundle holds exactly the saved set
                boolean[] hasChild = new boolean[total];
                boolean[] savedHasChild = new boolean[total];
                boolean[] isSaved = new boolean[total];
                for (int r : savedRevs) {
                    isSaved[r] = true;
                }
                for (int r = 0; r < total; r++) {
                    Revlog.IndexRecord rec = changelog.getIndexRecord(r);
                    for (int parent : new int[] {rec.getParent1(), rec.getParent2()}) {
                        if (parent >= 0 && r < targetRev) {
                            hasChild[parent] = true;
                        }
                        if (parent >= 0 && isSaved[r]) {
                            savedHasChild[parent] = true;
                        }
                    }
                }
                java.util.List<String> common = new ArrayList<>();
                for (int r = 0; r < targetRev; r++) {
                    if (!hasChild[r]) {
                        common.add(NodeIdUtil.toHex(changelog.getIndexRecord(r).getNodeId()));
                    }
                }
                java.util.List<String> heads = new ArrayList<>();
                for (int r : savedRevs) {
                    if (!savedHasChild[r]) {
                        heads.add(NodeIdUtil.toHex(changelog.getIndexRecord(r).getNodeId()));
                    }
                }
                try (io.github.search5.hg4j.transport.HgLocalClient client = new io.github.search5.hg4j.transport.HgLocalClient(repository)) {
                    savedBundle = client.getBundle(common, heads, null);
                }
                io.github.search5.hg4j.phase.PhaseRoots phases = repository.getPhaseRoots();
                for (int r : savedRevs) {
                    byte[] node = changelog.getIndexRecord(r).getNodeId();
                    savedPhases.put(NodeIdUtil.toHex(node),
                            phases.getPhase(new io.github.search5.hg4j.lib.NodeId(node), changelog));
                }
            }

            // `strip.py::strip`: when a parent of the working copy goes, the working copy is updated before the store is touched
            if (wdParentStripped) {
                if (keep) {
                    // the files stay; the dirstate only has to stop pointing at a changeset that is gone
                    final byte[] parent20 = new byte[20];
                    System.arraycopy(rollbackParent, 0, parent20, 0, 20);
                    repository.changingParents(() -> {
                        Dirstate d = repository.getDirstate();
                        d.setParents(parent20, new byte[20]);
                        repository.writeDirstate(d);
                        return null;
                    });
                } else {
                    RebaseCommand.restoreWorkingCopyCleanTo(repository, updateRev >= 0 ? rollbackParent : null);
                    repository.setBranch(updateBranch);
                }
            }

            // `repair.strip`: the temporary bundle of what is kept above the cut (so it can be added again, and recovered by hand if that fails)
            File tempBundle = null;
            if (savedBundle != null) {
                File dir = new File(repository.getHgDir(), "strip-backup");
                Files.createDirectories(dir.toPath());
                tempBundle = new File(dir, NodeIdUtil.toHex(nodeBytes).substring(0, 12) + "-" + Integer.toHexString(java.util.Arrays.hashCode(savedBundle))
                        + "-temp.hg");
                Files.write(tempBundle.toPath(), savedBundle);
            }

            File journalFile = new File(repository.getStoreDir(), "journal");
            try {
                File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
                File mfDat = new File(repository.getStoreDir(), "00manifest.d");
                Revlog manifest = repository.getRevlog(mfIdx, mfDat);

                // the files with their sizes after the cut, and the filelogs that go: the journal of the first transaction says where each
                // file ends, so that `hg recover` after a crash completes the cut (that is how `revlog.strip` registers its files)
                File fncacheFile = new File(repository.getStoreDir(), "fncache");
                List<String> fncachePaths = fncacheFile.exists()
                        ? Files.readAllLines(fncacheFile.toPath(), StandardCharsets.UTF_8)
                        : new ArrayList<>();
                List<String> updatedFncachePaths = new ArrayList<>();
                List<Object[]> filelogCuts = new ArrayList<>(); // {Revlog, File index, File data, Integer keepCount, String storePath}
                Map<File, Long> plan = new java.util.LinkedHashMap<>();
                List<String> docketLines = new ArrayList<>();
                addPlan(plan, docketLines, changelog, keepCount);
                addPlan(plan, docketLines, manifest, keepCount);
                for (String storePath : fncachePaths) {
                    if (storePath.equals("00changelog.i") || storePath.equals("00manifest.i")) {
                        updatedFncachePaths.add(storePath);
                        continue;
                    }
                    if (!storePath.endsWith(".i")) {
                        continue;
                    }
                    File flIdx = new File(repository.getStoreDir(), storePath);
                    String baseName = flIdx.getPath();
                    File flDat = new File(baseName.substring(0, baseName.length() - 2) + ".d");
                    if (!flIdx.exists()) {
                        continue;
                    }
                    Revlog filelog = repository.getRevlog(flIdx, flDat);
                    int flCount = filelog.getRevisionCount();
                    int flKeepCount = 0;
                    for (int j = 0; j < flCount; j++) {
                        if (filelog.getIndexRecord(j).getLinkRev() < targetRev) {
                            flKeepCount++;
                        } else {
                            break;
                        }
                    }
                    addPlan(plan, docketLines, filelog, flKeepCount);
                    filelogCuts.add(new Object[] {filelog, flIdx, flDat, flKeepCount, storePath});
                }
                // transaction "strip" (the cut)
                for (Map.Entry<File, Long> e : plan.entrySet()) {
                    String rel = repository.getHgDir().toPath().relativize(e.getKey().toPath()).toString().replace('\\', '/');
                    // the companion files of a version 2 revlog are only cut, never deleted, so they are journaled as "trunc"
                    appendToJournal(journalFile, (docketLines.contains(e.getKey().getPath()) ? "trunc " : "") + rel + "\t" + e.getValue());
                }
                for (String entry : docketBackupEntries) {
                    appendToJournal(journalFile, entry);
                }
                for (Object[] cut : filelogCuts) {
                    Revlog filelog = (Revlog) cut[0];
                    File flIdx = (File) cut[1];
                    File flDat = (File) cut[2];
                    int flKeepCount = (Integer) cut[3];
                    if (flKeepCount == 0) {
                        flIdx.delete();
                        if (flDat.exists()) {
                            flDat.delete();
                        }
                    } else {
                        filelog.truncate(flKeepCount);
                        updatedFncachePaths.add((String) cut[4]);
                    }
                }
                SafeFileIO.writeLinesAtomic(fncacheFile, updatedFncachePaths);
                changelog.truncate(keepCount);
                manifest.truncate(keepCount);

                // phase roots that point at a removed changeset go (`register_strip`)
                File phaserootsFile = new File(repository.getStoreDir(), "phaseroots");
                if (phaserootsFile.exists()) {
                    List<String> pLines = Files.readAllLines(phaserootsFile.toPath(), StandardCharsets.UTF_8);
                    List<String> updatedPLines = new ArrayList<>();
                    for (String line : pLines) {
                        if (line.trim().isEmpty()) continue;
                        String[] parts = line.trim().split("\\s+", 2);
                        if (parts.length == 2) {
                            int rev = changelog.findRevision(NodeIdUtil.fromHex(parts[1]));
                            if (rev != -1 && rev < targetRev) {
                                updatedPLines.add(line);
                            }
                        }
                    }
                    if (updatedPLines.isEmpty()) {
                        phaserootsFile.delete();
                    } else {
                        SafeFileIO.writeLinesAtomic(phaserootsFile, updatedPLines);
                    }
                }
                Files.deleteIfExists(journalFile.toPath()); // the first transaction closes

                // second transaction "strip": what was kept above the cut is added again
                if (savedBundle != null) {
                    repository.clearRevlogCache();
                    io.github.search5.hg4j.bundle.ChangegroupParser.ChangegroupBundle bundle =
                            io.github.search5.hg4j.bundle.ChangegroupParser.parseBundle(
                                    new java.io.ByteArrayInputStream(savedBundle, 6, savedBundle.length - 6), "01");
                    // the locks are held here already; unbundle's rule (no working copy lock) keeps the dirstate out of it
                    new FetchCommand(repository).setWorkingCopyLock(false).applyBundle(bundle);
                    repository.clearRevlogCache();
                    Revlog rebuilt = repository.getRevlog(clIdx, clDat);
                    io.github.search5.hg4j.phase.PhaseRoots phases = repository.getPhaseRoots();
                    for (java.util.Map.Entry<String, io.github.search5.hg4j.phase.PhaseRoots.Phase> saved : savedPhases.entrySet()) {
                        if (saved.getValue() != io.github.search5.hg4j.phase.PhaseRoots.Phase.PUBLIC) {
                            phases.setPhase(new io.github.search5.hg4j.lib.NodeId(NodeIdUtil.fromHex(saved.getKey())),
                                    saved.getValue(), rebuilt);
                        }
                    }
                    changelog = rebuilt;
                }

                // third transaction "repair": the bookmarks of removed changesets move back to the surviving parent
                String rollbackParentHex = NodeIdUtil.toHex(rollbackParent);
                File bookmarksFile = new File(repository.getHgDir(), "bookmarks");
                if (bookmarksFile.exists()) {
                    List<String> bLines = Files.readAllLines(bookmarksFile.toPath(), StandardCharsets.UTF_8);
                    List<String> updatedBLines = new ArrayList<>();
                    for (String line : bLines) {
                        if (line.trim().isEmpty()) continue;
                        String[] parts = line.trim().split("\\s+", 2);
                        if (parts.length == 2) {
                            String hexNode = parts[0];
                            String bookmarkName = parts[1];
                            if (strippedHex.contains(hexNode)) {
                                updatedBLines.add(rollbackParentHex + " " + bookmarkName);
                            } else if (survivingHex.contains(hexNode)) {
                                updatedBLines.add(line);
                            }
                            // a bookmark on a node that cannot be resolved (unrelated to this strip) is dropped
                        }
                    }
                    if (updatedBLines.isEmpty()) {
                        bookmarksFile.delete();
                    } else {
                        SafeFileIO.writeLinesAtomic(bookmarksFile, updatedBLines);
                    }
                }

                repository.clearRevlogCache();
                // `transaction.cleanup_undo_files`: a successful strip leaves no `hg rollback` information, and the old one is stale now
                Files.deleteIfExists(new File(repository.getStoreDir(), "undo").toPath());
                Files.deleteIfExists(new File(repository.getStoreDir(), "undo.backup.files").toPath());
                Files.deleteIfExists(new File(repository.getDirectory(), ".hg/undo.backup.dirstate").toPath());
                Files.deleteIfExists(new File(repository.getDirectory(), ".hg/undo.backup.bookmarks").toPath());
                if (tempBundle != null) {
                    Files.deleteIfExists(tempBundle.toPath());
                }
                if (!bookmarksToDelete.isEmpty()) {
                    deleteBookmarks();
                }
            } catch (Exception | Error e) {
                // nothing is put back: the files stay as far as the strip got, and the bundles are what recovers the changesets
                if (backupBundle != null) {
                    messages.add("strip failed, backup bundle stored in '" + backupBundle.getPath() + "'\n");
                }
                if (tempBundle != null) {
                    messages.add("strip failed, unrecovered changes stored in '" + tempBundle.getPath() + "'\n");
                    messages.add("(fix the problem, then recover the changesets with \"hg unbundle '" + tempBundle.getPath() + "'\")\n");
                }
                repository.clearRevlogCache();
                throw e;
            }
        }
    }

    /**
     * Real hg's {@code repair.backupbundle}: the removed changesets go to {@code .hg/strip-backup/<12 hex of the first removed
     * node>-<8 hex of the sha1 of the sorted hashes of all removed nodes>-<topic>.hg}.
     */
    private File writeBackupBundle(Revlog changelog, int targetRev, boolean[] stripped) throws IOException {
        int total = changelog.getRevisionCount();
        boolean[] hasChild = new boolean[total];
        boolean[] strippedHasChild = new boolean[total];
        java.util.List<String> strippedHashes = new ArrayList<>();
        for (int r = 0; r < total; r++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(r);
            for (int parent : new int[] {rec.getParent1(), rec.getParent2()}) {
                if (parent >= 0) {
                    hasChild[parent] |= true;
                    if (stripped[r]) {
                        strippedHasChild[parent] = true;
                    }
                }
            }
            if (stripped[r]) {
                strippedHashes.add(NodeIdUtil.toHex(rec.getNodeId()));
            }
        }
        java.util.List<String> common = new ArrayList<>();   // heads of everything that stays: the bundle is exactly the stripped set
        java.util.List<String> heads = new ArrayList<>();
        for (int r = 0; r < total; r++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(r);
            if (!stripped[r]) {
                boolean leaf = true;
                for (int c = r + 1; c < total && leaf; c++) {
                    Revlog.IndexRecord child = changelog.getIndexRecord(c);
                    if (!stripped[c] && (child.getParent1() == r || child.getParent2() == r)) {
                        leaf = false;
                    }
                }
                if (leaf) {
                    common.add(NodeIdUtil.toHex(rec.getNodeId()));
                }
            } else if (!strippedHasChild[r]) {
                heads.add(NodeIdUtil.toHex(rec.getNodeId()));
            }
        }
        byte[] bundle;
        try (io.github.search5.hg4j.transport.HgLocalClient client = new io.github.search5.hg4j.transport.HgLocalClient(repository)) {
            bundle = client.getBundle(common, heads, null);
        }
        java.util.Collections.sort(strippedHashes);
        byte[] digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-1").digest(String.join("", strippedHashes)
                    .getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        StringBuilder hash = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            hash.append(String.format("%02x", digest[i] & 0xff));
        }
        File dir = new File(repository.getHgDir(), "strip-backup");
        Files.createDirectories(dir.toPath());
        String root = NodeIdUtil.toHex(changelog.getIndexRecord(targetRev).getNodeId()).substring(0, 12);
        File file = new File(dir, root + "-" + hash + "-" + topic + ".hg");
        Files.write(file.toPath(), bundle);
        return file;
    }

    /**
     * Real hg's {@code strip._findupdatetarget}: the first parent of the stripped revision; when that is on another branch than the
     * working directory, the newest revision on the working directory's branch among {@code (parents(target::wdir) + wdir) -
     * target::wdir} (the revisions the working directory's parents descend from just outside the stripped range).
     *
     * @return the revision to update to, or -1 for the null revision
     */
    private int findUpdateTarget(Revlog changelog, int targetRev, boolean[] stripped) throws IOException {
        int unode = changelog.getIndexRecord(targetRev).getParent1();
        String currentBranch = repository.getBranch();
        String unodeBranch = unode >= 0 ? CommitCommand.getBranchOfRevision(changelog, unode) : "default";
        if (currentBranch.equals(unodeBranch)) {
            return unode;
        }
        int total = changelog.getRevisionCount();
        Dirstate wd = repository.getDirstate();
        java.util.List<Integer> wdParents = new ArrayList<>();
        for (byte[] parent : new byte[][] {wd.getParent1(), wd.getParent2()}) {
            if (parent != null && !NodeIdUtil.isAllZero(parent)) {
                int rev = changelog.findRevision(parent);
                if (rev >= 0) {
                    wdParents.add(rev);
                }
            }
        }
        // ancestors-or-self of the working directory's parents, inside the stripped range: that is target::wdir
        boolean[] ancestorOfWd = new boolean[total];
        for (int p : wdParents) {
            ancestorOfWd[p] = true;
        }
        for (int r = total - 1; r >= targetRev; r--) {
            if (ancestorOfWd[r]) {
                Revlog.IndexRecord rec = changelog.getIndexRecord(r);
                for (int parent : new int[] {rec.getParent1(), rec.getParent2()}) {
                    if (parent >= targetRev) {
                        ancestorOfWd[parent] = true;
                    }
                }
            }
        }
        boolean[] range = new boolean[total];
        for (int r = targetRev; r < total; r++) {
            range[r] = stripped[r] && ancestorOfWd[r];
        }
        java.util.Set<Integer> candidates = new java.util.TreeSet<>(wdParents);
        for (int r = targetRev; r < total; r++) {
            if (range[r]) {
                Revlog.IndexRecord rec = changelog.getIndexRecord(r);
                if (rec.getParent1() >= 0) {
                    candidates.add(rec.getParent1());
                }
                if (rec.getParent2() >= 0) {
                    candidates.add(rec.getParent2());
                }
            }
        }
        int best = -1;
        for (int candidate : candidates) {
            if (!range[candidate] && CommitCommand.getBranchOfRevision(changelog, candidate).equals(currentBranch)) {
                best = Math.max(best, candidate);
            }
        }
        return best >= 0 ? best : unode;
    }

    private void deleteDirRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteDirRecursively(child);
            }
        }
        file.delete();
    }

    // Revlog truncation itself (the exact-offset .d truncate, the inline-vs-non-inline v1
    // branching, and v2/docket-based end-pointer bookkeeping) now lives on the reusable
    // Revlog.truncate(int) -- see its javadoc for the full history of what this used to get
    // wrong (backlog #39, requirement-matrix expansion to StripCommand, 2026-09-05).

    private void appendToJournal(File journalFile, String entry) throws IOException {
        Files.writeString(journalFile.toPath(), entry + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try (FileChannel fc = FileChannel.open(journalFile.toPath(), StandardOpenOption.WRITE)) {
            fc.force(true);
        }
    }
}
