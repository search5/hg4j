package io.github.search5.hg4j.api;

import java.util.logging.Level;
import java.util.logging.Logger;
import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.FileIndex;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.treewalk.ManifestTreeIterator;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.util.SafeFileIO;
import io.github.search5.hg4j.errors.HgLockException;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.HashMap;
import io.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.gpg.GpgSignature;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.phase.PhaseRoots;
import io.github.search5.hg4j.treewalk.ManifestTreeIterator;
import io.github.search5.hg4j.treewalk.TreeWalk;
import io.github.search5.hg4j.treewalk.WorkingDirTreeIterator;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.TimeZone;

/**
 * Commits tracked changes to the repository history.
 * Built with robust rollback transaction logic, manifest flags tracking, and large file safety.
 */
public class CommitCommand {
    private static final Logger LOGGER = Logger.getLogger(CommitCommand.class.getName());

    private final HgRepository repository;
    private String author = "user <user@example.com>";
    private String message;
    private Long forcedTime = null;
    private Integer forcedOffset = null;
    private boolean skipLockAndJournal = false;
    
    private final List<HgHook> preCommitHooks = new ArrayList<>();
    private final List<HgHook> postCommitHooks = new ArrayList<>();
    private GpgSignature gpgSignature;
    private boolean closeBranch = false;
    private boolean subrepos = false;

    // Amend support (set only by AmendCommand, package-private): the changeset/manifest
    // revision this commit *declares* as its parent(s) can differ from the dirstate's actual
    // current parent -- e.g. `hg commit --amend` keeps computing "what changed" against the
    // commit being amended (dirstate's real parent, so unchanged-file reuse/racy-check logic
    // below all stays correct unmodified) but must *record* the amended commit's parent(s) as
    // the ORIGINAL commit's own parent(s) (real hg: `base = old.p1()` in
    // mercurial/cmdutil.py's `amend()`) so the amended commit REPLACES the original as a
    // sibling rather than becoming its child. Null (the default) means "same as dirstate's
    // actual parent" -- i.e. normal commit behavior, completely unaffected by this field.
    private boolean hasDeclaredParents = false;
    private NodeId declaredParent1 = NodeId.NULL;
    private NodeId declaredParent2 = NodeId.NULL;

    /**
     * Amend-only (package-private): overrides the parent(s) *recorded* in the new changelog and
     * manifest revisions, independently of the dirstate-derived parent used everywhere else in
     * {@link #call()} to determine which files changed and what an unchanged file's manifest
     * entry should reuse. See the {@link #hasDeclaredParents} field doc for why these must be
     * allowed to differ.
     */
    CommitCommand setAmendDeclaredParents(NodeId p1, NodeId p2) {
        this.hasDeclaredParents = true;
        this.declaredParent1 = (p1 != null) ? p1 : NodeId.NULL;
        this.declaredParent2 = (p2 != null) ? p2 : NodeId.NULL;
        return this;
    }

    public CommitCommand setGpgSignature(GpgSignature gpgSignature) {
        this.gpgSignature = gpgSignature;
        return this;
    }

    /** {@code hg commit --close-branch}: marks the new commit as closing the named branch head it becomes. */
    public CommitCommand setCloseBranch(boolean closeBranch) {
        this.closeBranch = closeBranch;
        return this;
    }

    /**
     * {@code hg commit -S}/{@code --subrepos}: allow a recursive commit when a subrepo declared
     * in {@code .hgsub} has uncommitted local changes -- committing the subrepo first, then
     * recording its new tip in {@code .hgsubstate}. Without this, real hg (and this command)
     * aborts the parent commit instead ({@code uncommitted changes in subrepository "..."}).
     */
    public CommitCommand setSubrepos(boolean subrepos) {
        this.subrepos = subrepos;
        return this;
    }

    public CommitCommand registerPreCommitHook(HgHook hook) {
        if (hook != null) {
            preCommitHooks.add(hook);
        }
        return this;
    }

    public CommitCommand registerPostCommitHook(HgHook hook) {
        if (hook != null) {
            postCommitHooks.add(hook);
        }
        return this;
    }

    public CommitCommand setSkipLockAndJournal(boolean skip) {
        this.skipLockAndJournal = skip;
        return this;
    }

    public CommitCommand(HgRepository repository) {
        this.repository = repository;
    }

    public CommitCommand setAuthor(String author) {
        if (author != null && !author.isEmpty()) {
            this.author = author;
        }
        return this;
    }

    public CommitCommand setDate(long secs, int offsetSeconds) {
        this.forcedTime = secs;
        this.forcedOffset = offsetSeconds;
        return this;
    }

    public CommitCommand setMessage(String message) {
        this.message = message;
        return this;
    }

    public byte[] call() throws IOException, HgLockException {
        if (message == null || message.isEmpty()) {
            throw new IllegalStateException("Commit message must be specified.");
        }

        // Subrepo bookkeeping (real hg's subrepoutil.precommitstate / commitctx.set_hgsubstate):
        // if .hgsub declares subrepos, refuse the commit when one has uncommitted local changes
        // (unless -S/--subrepos was requested, in which case commit it recursively first), then
        // regenerate .hgsubstate from every subrepo's current checked-out revision and make sure
        // it is tracked -- all BEFORE this method's own backup/journal machinery below captures
        // "the state this commit starts from", so a later rollback of THIS commit correctly
        // leaves any already-completed recursive subrepo commits in place (matching real hg,
        // which never rolls back a subrepo it already committed just because the parent commit
        // subsequently failed).
        applySubrepoStateBeforeCommit();

        // PRE_COMMIT hooks trigger
        if (!preCommitHooks.isEmpty()) {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("author", author);
            ctx.put("message", message);
            ctx.put("repository", repository);
            for (HgHook hook : preCommitHooks) {
                if (!hook.run(ctx)) {
                    throw new HgValidationException("Pre-commit hook execution rejected the commit txn");
                }
            }
        }

        Map<File, Long> fileSizes = new HashMap<>();
        File dirstateFile = new File(repository.getDirectory(), ".hg/dirstate");
        byte[] dirstateBackup = dirstateFile.exists() ? Files.readAllBytes(dirstateFile.toPath()) : null;
        // N-2: snapshot the dirstate-v2 data file this backup's docket references (if any), so a
        // rollback can restore it too -- see the matching restore-site comment for why this is
        // needed (Dirstate.write()'s own "W-LEAK" cleanup deletes the previous uid's data file as
        // soon as it writes a new docket, which would otherwise orphan dirstateBackup on rollback).
        byte[] dirstateV2DataBackup = captureDirstateV2DataBackup(dirstateFile, dirstateBackup);
        File fncacheFile = new File(repository.getStoreDir(), "fncache");
        byte[] fncacheBackup = fncacheFile.exists() ? Files.readAllBytes(fncacheFile.toPath()) : null;
        FileIndex.Snapshot fileIndexBackup = repository.isFileIndexV1()
                ? FileIndex.snapshot(repository.getStoreDir()) : null;
        File journalFile = new File(repository.getStoreDir(), "journal");

        try (HgLock wlock = skipLockAndJournal ? HgLock.noOp() : repository.lockWorkingCopy();
             HgLock storeLock = skipLockAndJournal ? HgLock.noOp() : repository.lockStore()) {

            if (!skipLockAndJournal) {
                // Create physical journal and backups for Crash Resilience
                Files.deleteIfExists(journalFile.toPath());
                
                if (dirstateFile.exists()) {
                    File dirstateBackupFile = new File(repository.getDirectory(), ".hg/dirstate.backup");
                    Files.copy(dirstateFile.toPath(), dirstateBackupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    appendToJournal(journalFile, "dirstate");
                }
                if (fncacheFile.exists()) {
                    File fncacheBackupFile = new File(repository.getStoreDir(), "fncache.backup");
                    Files.copy(fncacheFile.toPath(), fncacheBackupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    appendToJournal(journalFile, "fncache");
                }
            }

            // Initialize Transaction File Sizes Rollback Backup
            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");
            File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
            File mfDat = new File(repository.getStoreDir(), "00manifest.d");

            long clIdxLen = clIdx.exists() ? clIdx.length() : 0L;
            long clDatLen = clDat.exists() ? clDat.length() : 0L;
            long mfIdxLen = mfIdx.exists() ? mfIdx.length() : 0L;
            long mfDatLen = mfDat.exists() ? mfDat.length() : 0L;

            fileSizes.put(clIdx, clIdxLen);
            fileSizes.put(clDat, clDatLen);
            fileSizes.put(mfIdx, mfIdxLen);
            fileSizes.put(mfDat, mfDatLen);

            if (!skipLockAndJournal) {
                // Path is relative to the .hg directory (standard hg journal format)
                appendToJournal(journalFile, "store/00changelog.i\t" + clIdxLen);
                appendToJournal(journalFile, "store/00changelog.d\t" + clDatLen);
                appendToJournal(journalFile, "store/00manifest.i\t" + mfIdxLen);
                appendToJournal(journalFile, "store/00manifest.d\t" + mfDatLen);
            }

            Dirstate dirstate = repository.getDirstate();
            NodeId p1CommitNode = dirstate.getParent1Node();
            NodeId p2CommitNode = dirstate.getParent2Node();

            // 1. Load changelog and find parent commit rev index
            Revlog changelog = repository.getRevlog(clIdx, clDat);

            int parent1Rev = -1;
            if (p1CommitNode != null && !p1CommitNode.isNull()) {
                parent1Rev = NodeIdUtil.findRevisionByNodeId(changelog, p1CommitNode.getBytes());
                if (parent1Rev == -1) {
                    throw new HgRevisionNotFoundException(p1CommitNode.toHex());
                }
            }

            int parent2Rev = -1;
            if (p2CommitNode != null && !p2CommitNode.isNull()) {
                parent2Rev = NodeIdUtil.findRevisionByNodeId(changelog, p2CommitNode.getBytes());
                if (parent2Rev == -1) {
                    throw new HgRevisionNotFoundException(p2CommitNode.toHex());
                }
            }

            int newCommitRev = changelog.getRevisionCount();

            // 2. Load previous manifests
            Map<String, String> manifestP1 = new TreeMap<>();
            Map<String, String> manifestP2 = new TreeMap<>();
            byte[] p1ManifestNode = new byte[20];
            byte[] p2ManifestNode = new byte[20];

            Revlog manifestRevlog = repository.getManifestRevlog();

            int parent1ManifestRev = -1;
            if (parent1Rev != -1) {
                byte[] p1CommitNodeBytes = p1CommitNode.getBytes();
                Map<String, String> mf1 = repository.getManifestAtCommit(p1CommitNodeBytes);
                manifestP1.putAll(mf1);
                byte[] clContent = changelog.getRevisionContent(parent1Rev);
                p1ManifestNode = extractManifestNode(clContent);
                if (p1ManifestNode != null) {
                    parent1ManifestRev = NodeIdUtil.findRevisionByNodeId(manifestRevlog, p1ManifestNode);
                }
                LOGGER.log(Level.FINE, "[DEBUG MERGE] parent1Rev={0}, p1ManifestNode={1}, parent1ManifestRev={2}", new Object[]{parent1Rev, (p1ManifestNode != null ? NodeIdUtil.toHex(p1ManifestNode) : "null"), parent1ManifestRev});
            }

            int parent2ManifestRev = -1;
            if (parent2Rev != -1 && p2CommitNode != null && !p2CommitNode.isNull()) {
                byte[] p2CommitNodeBytes = p2CommitNode.getBytes();
                Map<String, String> mf2 = repository.getManifestAtCommit(p2CommitNodeBytes);
                manifestP2.putAll(mf2);
                byte[] clContent = changelog.getRevisionContent(parent2Rev);
                p2ManifestNode = extractManifestNode(clContent);
                if (p2ManifestNode != null) {
                    parent2ManifestRev = NodeIdUtil.findRevisionByNodeId(manifestRevlog, p2ManifestNode);
                }
                LOGGER.log(Level.FINE, "[DEBUG MERGE] parent2Rev={0}, p2CommitNode={1}, p2ManifestNode={2}, parent2ManifestRev={3}", new Object[]{parent2Rev, p2CommitNode.toHex(), (p2ManifestNode != null ? NodeIdUtil.toHex(p2ManifestNode) : "null"), parent2ManifestRev});
            }

            // 3. Process dirstate entries and write filelogs
            // M-2: Record transaction start time in epoch seconds for racy-hg check
            final long txStartSec = System.currentTimeMillis() / 1000;
            Map<String, String> newManifest = new TreeMap<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
            List<String> filesModified = new ArrayList<>();
            Set<String> fncachePaths = new LinkedHashSet<>();

            // SD_FILES sidedata bookkeeping (backlog item 19) -- only actually encoded/attached
            // below when repository.isSidedataCopies() is true; harmless to always populate.
            Set<String> sdAdded = new LinkedHashSet<>();
            Set<String> sdRemoved = new LinkedHashSet<>();
            Set<String> sdTouched = new LinkedHashSet<>();
            Map<String, String> sdCopiedFromP1 = new LinkedHashMap<>();
            Map<String, String> sdCopiedFromP2 = new LinkedHashMap<>();

            // Load existing fncache if any
            if (fncacheFile.exists()) {
                fncachePaths.addAll(Files.readAllLines(fncacheFile.toPath()));
            }

            // fileindex-v1 repositories track tracked-file logical paths via the fileindex trie
            // instead of fncache; loaded/appended/written the same way fncachePaths is above
            // (monotonic accumulation -- matches real hg's own fncache, which is never pruned on
            // commit either, only via `hg debugrebuildfncache`).
            boolean useFileIndex = repository.isFileIndexV1();
            Set<String> fileIndexPaths = useFileIndex
                    ? new LinkedHashSet<>(FileIndex.readTrackedPaths(repository.getStoreDir()))
                    : Collections.emptySet();

            // Check for unresolved merge conflicts
            for (Map.Entry<String, Dirstate.Entry> item : dirstate.getEntries().entrySet()) {
                if (item.getValue().getState() == 'm') {
                    File file = new File(repository.getDirectory(), item.getKey());
                    if (file.exists() && file.isFile()) {
                        String fileText = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                        if (fileText.contains("<<<<<<<") && fileText.contains("=======") && fileText.contains(">>>>>>>")) {
                            throw new IllegalStateException("Commit blocked: Unresolved merge conflicts in file: " + item.getKey());
                        }
                    }
                }
            }

            TreeWalk tw = new TreeWalk();
            tw.addTree(new ManifestTreeIterator(repository, String.valueOf(parent1Rev))); // Tree 0: P1
            tw.addTree(new ManifestTreeIterator(repository, String.valueOf(parent2Rev))); // Tree 1: P2
            tw.addTree(new WorkingDirTreeIterator(repository));                           // Tree 2: Working Copy

            tw.reset();
            while (tw.next()) {
                String path = tw.getPath();
                boolean inP1 = tw.isTracked(0);
                boolean inP2 = tw.isTracked(1);
                boolean inWorking = tw.isTracked(2);

                if (inWorking) {
                    char workingState = tw.getState(2);
                    if (workingState == 'r') {
                        filesModified.add(path);
                        sdRemoved.add(path);
                    } else if (workingState == 'a' || workingState == 'm' || workingState == 'n') {
                        File diskFile = new File(repository.getDirectory(), path);
                        // A symlink is valid to commit even when its target is missing (dangling)
                        // or not a plain file — real hg tracks it regardless (verified live).
                        // exists()/isFile() alone follow the link and would reject it.
                        if (!Files.isSymbolicLink(diskFile.toPath()) && (!diskFile.exists() || !diskFile.isFile())) {
                            throw new HgValidationException("Tracked file not found on disk: " + path);
                        }

                        // A symlink's "size" (and content, for the M-2 racy check below) must be
                        // its own target-path-string bytes -- File#length()/readAllBytes() follow
                        // the link and read whatever it points at instead, which is wrong for
                        // deciding whether the symlink ITSELF changed (matches the convention
                        // already used elsewhere in this class and in AddCommand/CopyCommand/
                        // GraftCommand/RebaseCommand).
                        boolean diskIsSymlink = Files.isSymbolicLink(diskFile.toPath());
                        // Large file support: safely record 32-bit masked size in dirstate v1
                        long diskSize = diskIsSymlink
                                ? Files.readSymbolicLink(diskFile.toPath()).toString().getBytes(StandardCharsets.UTF_8).length
                                : diskFile.length();

                        // Check if the file has actually changed compared to the recorded dirstate
                        boolean changed = workingState == 'a' || workingState == 'm';
                        if (workingState == 'n') {
                            long diskTime = SafeFileIO.lastModifiedSeconds(diskFile);
                            Dirstate.Entry dEntry = dirstate.getEntries().get(path);
                            if (dEntry != null) {
                                if (dEntry.getSize() != (int) diskSize || dEntry.getTime() != diskTime) {
                                    // Changed if size or mtime differs
                                    changed = true;
                                } else if (diskTime >= txStartSec - 1) {
                                    // M-2: racy-hg check (accounting for 1-second resolution)
                                    File flIdx = getFilelogIndex(repository.getStoreDir(), path);
                                    File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
                                    if (flIdx.exists()) {
                                        Revlog filelog = repository.getRevlog(flIdx, flDat);
                                        if (filelog.getRevisionCount() > 0) {
                                            byte[] fileContent = diskIsSymlink
                                                    ? Files.readSymbolicLink(diskFile.toPath()).toString().getBytes(StandardCharsets.UTF_8)
                                                    : Files.readAllBytes(diskFile.toPath());
                                            byte[] lastContent = filelog.getRevisionContent(filelog.getRevisionCount() - 1);
                                            if (!Arrays.equals(fileContent, lastContent)) {
                                                changed = true;
                                            }
                                        }
                                    }
                                }
                            }

                            // A tracked path marked 'n' (dirstate says "unchanged") that is
                            // absent from BOTH candidate parent manifests cannot legitimately be
                            // "unchanged" -- there is nothing for it to be unchanged FROM. Without
                            // this, such a path silently vanishes from the new commit's manifest
                            // entirely (the "!changed" branch below only knows how to reuse an
                            // *existing* parent manifest entry; when neither parent has one, it
                            // has no else-case and just drops the path). Real caller that hits
                            // this: RebaseCommand.cherryPickBackup marks every file it writes as
                            // 'n' with disk-matching size/mtime (correct fast path for files
                            // genuinely carried over unchanged from the target), including
                            // brand-new files added by the very revision being cherry-picked when
                            // the rebase target never had that path -- caught live against a
                            // real-hg-created repo by RebaseRealHgInteropTest (hg4j-only round
                            // trips never exercised this because they never asserted on the
                            // rebased commit's manifest/file content, only on changelog linkage).
                            if (!changed) {
                                boolean presentInP1 = manifestP1.containsKey(path);
                                boolean presentInP2 = parent2Rev != -1 && manifestP2.containsKey(path);
                                if (!presentInP1 && !presentInP2) {
                                    changed = true;
                                }
                            }
                        }

                        if (changed) {
                            byte[] fileContent;
                            if (Files.isSymbolicLink(diskFile.toPath())) {
                                fileContent = Files.readSymbolicLink(diskFile.toPath()).toString().getBytes(StandardCharsets.UTF_8);
                            } else {
                                fileContent = Files.readAllBytes(diskFile.toPath());
                            }
                            File flIdx = getFilelogIndex(repository.getStoreDir(), path);
                            File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
                            
                            // Capture pre-write file sizes for potential rollback transaction
                            if (!fileSizes.containsKey(flIdx)) {
                                long idxLen = flIdx.exists() ? flIdx.length() : 0L;
                                fileSizes.put(flIdx, idxLen);
                                if (!skipLockAndJournal) {
                                    String storeRelIdx = "store/" + NodeIdUtil.encodeFname(path + ".i");
                                    appendToJournal(journalFile, storeRelIdx + "\t" + idxLen);
                                }
                            }
                            if (!fileSizes.containsKey(flDat)) {
                                long datLen = flDat.exists() ? flDat.length() : 0L;
                                fileSizes.put(flDat, datLen);
                                if (!skipLockAndJournal) {
                                    String storeRelDat = "store/" + NodeIdUtil.encodeFname(path + ".d");
                                    appendToJournal(journalFile, storeRelDat + "\t" + datLen);
                                }
                            }

                            // Ensure parent directories exist in store
                            flIdx.getParentFile().mkdirs();

                            Revlog filelog = repository.getRevlog(flIdx, flDat);

                            // Find parent1 filelog revision index
                            int parent1FileRev = -1;
                            byte[] p1FileNode = new byte[20];
                            if (inP1) {
                                byte[] prevFileNode = tw.getNodeId(0);
                                parent1FileRev = NodeIdUtil.findRevisionByNodeId(filelog, prevFileNode);
                                if (parent1FileRev != -1) {
                                    p1FileNode = prevFileNode;
                                }
                            }

                            // Find parent2 filelog revision index (N-4: Merge ancestral track protection)
                            int parent2FileRev = -1;
                            byte[] p2FileNode = new byte[20];
                            if (inP2) {
                                byte[] prevFileNodeP2 = tw.getNodeId(1);
                                parent2FileRev = NodeIdUtil.findRevisionByNodeId(filelog, prevFileNodeP2);
                                if (parent2FileRev != -1) {
                                    p2FileNode = prevFileNodeP2;
                                }
                            }

                            // Integrates Copy-Rename Track "Writer"
                            String originalPath = dirstate.getCopyMap().get(path);
                            Map<String, String> copyMeta = null;

                            if (originalPath != null) {
                                String sourceEntry = manifestP1.get(originalPath);
                                String hexSource = (sourceEntry != null && sourceEntry.length() >= 40)
                                        ? sourceEntry.substring(0, 40) : "0000000000000000000000000000000000000000";
                                copyMeta = new LinkedHashMap<>();
                                copyMeta.put("copy", originalPath);
                                copyMeta.put("copyrev", hexSource);

                                // SD_FILES copy-tracing (backlog item 19): classify by which
                                // parent's manifest actually contains the copy source -- mirrors
                                // the sourceEntry lookup just above (P1 first, matching real hg's
                                // own preference for p1 when a source exists in both).
                                if (manifestP1.containsKey(originalPath)) {
                                    sdCopiedFromP1.put(path, originalPath);
                                } else if (manifestP2.containsKey(originalPath)) {
                                    sdCopiedFromP2.put(path, originalPath);
                                }
                            }

                            if (workingState == 'a') {
                                sdAdded.add(path);
                            } else {
                                sdTouched.add(path);
                            }

                            byte[] newFileNode = filelog.appendRevision(fileContent, copyMeta, parent1FileRev, parent2FileRev, p1FileNode, p2FileNode, newCommitRev);
                            
                            // Capture execution flag and symlink flag for serialization
                            String flag = "";
                            if (Files.isSymbolicLink(diskFile.toPath())) {
                                flag = "l";
                            } else if (diskFile.canExecute()) {
                                flag = "x";
                            }

                            newManifest.put(path, NodeIdUtil.toHex(newFileNode) + flag);
                            filesModified.add(path);

                            // Register only .i file paths in fncache (raw logical path as per native Mercurial specs)
                            fncachePaths.add("data/" + path + ".i");
                            if (useFileIndex) {
                                fileIndexPaths.add(path);
                            }
                        } else {
                            // File has not changed in working directory
                            String hexP1 = manifestP1.get(path);
                            String hexP2 = manifestP2.get(path);
                            if (parent2Rev == -1) {
                                if (hexP1 != null) {
                                    newManifest.put(path, hexP1);
                                }
                            } else {
                                if (hexP1 != null && hexP2 == null) {
                                    newManifest.put(path, hexP1);
                                } else if (hexP1 == null && hexP2 != null) {
                                    newManifest.put(path, hexP2);
                                } else if (hexP1 != null && hexP2 != null) {
                                    if (hexP1.equals(hexP2)) {
                                        newManifest.put(path, hexP1);
                                    } else {
                                        // Bytes level disambiguation to determine which side this file belongs to
                                        byte[] diskBytes = Files.readAllBytes(diskFile.toPath());
                                        byte[] p1Bytes = null;
                                        try {
                                            p1Bytes = getFileRevisionContent(repository, path, hexP1);
                                        } catch (Exception e) {
                                            // Ignore
                                        }
                                        if (p1Bytes != null && Arrays.equals(diskBytes, p1Bytes)) {
                                            newManifest.put(path, hexP1);
                                        } else {
                                            newManifest.put(path, hexP2);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Write fncache back atomically
            if (!fncachePaths.isEmpty()) {
                SafeFileIO.writeLinesAtomic(fncacheFile, new ArrayList<>(fncachePaths));
            }
            if (useFileIndex) {
                FileIndex.writeTrackedPaths(repository.getStoreDir(), fileIndexPaths);
            }

            // Amend support: resolve the DECLARED parent(s) for the new changelog/manifest
            // revisions separately from the dirstate-derived parent(s) used above (and still
            // used below for the treemanifest sub-directory reuse map) to determine what
            // changed and what an unchanged file's manifest entry should reuse -- see
            // hasDeclaredParents' field doc for why these must be allowed to differ.
            int declaredClRev1 = parent1Rev;
            int declaredClRev2 = parent2Rev;
            byte[] declaredClNode1 = p1CommitNode != null ? p1CommitNode.getBytes() : new byte[20];
            byte[] declaredClNode2 = p2CommitNode != null ? p2CommitNode.getBytes() : new byte[20];
            byte[] declaredMfNode1 = p1ManifestNode;
            byte[] declaredMfNode2 = p2ManifestNode;
            int declaredMfRev1 = parent1ManifestRev;
            int declaredMfRev2 = parent2ManifestRev;
            if (hasDeclaredParents) {
                declaredClNode1 = declaredParent1.getBytes();
                declaredClRev1 = declaredParent1.isNull() ? -1 : NodeIdUtil.findRevisionByNodeId(changelog, declaredClNode1);
                declaredClNode2 = declaredParent2.getBytes();
                declaredClRev2 = declaredParent2.isNull() ? -1 : NodeIdUtil.findRevisionByNodeId(changelog, declaredClNode2);

                declaredMfNode1 = declaredClRev1 != -1 ? extractManifestNode(changelog.getRevisionContent(declaredClRev1)) : new byte[20];
                declaredMfRev1 = isNullNode(declaredMfNode1) ? -1 : NodeIdUtil.findRevisionByNodeId(manifestRevlog, declaredMfNode1);
                declaredMfNode2 = declaredClRev2 != -1 ? extractManifestNode(changelog.getRevisionContent(declaredClRev2)) : new byte[20];
                declaredMfRev2 = isNullNode(declaredMfNode2) ? -1 : NodeIdUtil.findRevisionByNodeId(manifestRevlog, declaredMfNode2);
            }

            // 4. Serialize and write new manifest revision
            byte[] manifestNode;
            if (repository.isTreemanifest()) {
                Map<String, byte[]> p1DirNodes = collectDirNodes(p1ManifestNode);
                Map<String, byte[]> p2DirNodes = collectDirNodes(p2ManifestNode);
                manifestNode = writeTreeManifestDir("", newManifest, p1DirNodes, p2DirNodes,
                        declaredMfNode1, declaredMfNode2, manifestRevlog, newCommitRev);
            } else {
                StringBuilder manifestSb = new StringBuilder();
                for (Map.Entry<String, String> entry : newManifest.entrySet()) {
                    manifestSb.append(entry.getKey()).append('\0').append(entry.getValue()).append('\n');
                }
                byte[] manifestTextBytes = manifestSb.toString().getBytes(StandardCharsets.UTF_8);
                manifestNode = manifestRevlog.appendRevision(manifestTextBytes, declaredMfRev1, declaredMfRev2, declaredMfNode1, declaredMfNode2, newCommitRev);
            }

            // 5. Serialize and write new changelog (commit) revision
            StringBuilder clSb = new StringBuilder();
            clSb.append(NodeIdUtil.toHex(manifestNode)).append('\n');
            clSb.append(author).append('\n');
            long secs = forcedTime != null ? forcedTime : System.currentTimeMillis() / 1000;
            int offsetSeconds = forcedOffset != null ? forcedOffset : -TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000;
            clSb.append(secs).append(" ").append(offsetSeconds);
            String branchName = repository.getBranch();
            // 실제 hg(changelog.add)는 branch extra 항목을 default/빈 브랜치일 때는 아예
            // 쓰지 않는다 — 항상 "branch:default"를 남기면 기본 브랜치 커밋의 changelog
            // 원문 바이트가 실제 hg와 달라져 동일 내용이라도 노드 해시가 어긋난다
            // (2026-09-01 실제 hg로 확인: 기본 브랜치 커밋의 3번째 줄은 "초 tz"뿐이고
            // "branch:" 문구가 전혀 없다).
            // 실제 hg(changelog.encodeextra)는 extra 항목이 여럿이면 키 알파벳순으로 정렬해
            // '\0'로 join한다 -- "branch"가 "close"보다 앞선다.
            List<String> extraParts = new ArrayList<>();
            if (branchName != null && !branchName.isEmpty() && !"default".equals(branchName)) {
                extraParts.add("branch:" + encodeExtraKey(branchName));
            }
            if (this.closeBranch) {
                extraParts.add("close:1");
            }
            if (!extraParts.isEmpty()) {
                clSb.append(" ").append(String.join("\0", extraParts));
            }
            clSb.append('\n');
            Collections.sort(filesModified, NodeIdUtil.UTF8_STRING_COMPARATOR);
            for (String path : filesModified) {
                clSb.append(path).append('\n');
            }
            clSb.append('\n'); // empty line separator
            clSb.append(message);
            byte[] changelogTextBytes = clSb.toString().getBytes(StandardCharsets.UTF_8);

            // See the hasDeclaredParents field doc: an amend commit records its parent(s) as
            // the amended commit's own parent(s) (declaredClNode1/2), not the dirstate's actual
            // (pre-amend) parent -- both are identical for a normal (non-amend) commit.
            byte[] p1CommitNodeHash = declaredClNode1;
            byte[] p2CommitNodeHash = declaredClNode2;

            Map<String, String> clMeta = new HashMap<>();
            if (this.gpgSignature != null) {
                clMeta.put("gpgsig", this.gpgSignature.toAsciiArmored().replace("\n", "\\n"));
                clMeta.put("gpgfingerprint", this.gpgSignature.getKeyFingerprint());
            }

            byte[] sidedataContainer = null;
            if (repository.isSidedataCopies()) {
                byte[] sdFiles = ChangingFiles.encode(sdAdded, sdRemoved, Set.of(), Set.of(), sdTouched, sdCopiedFromP1, sdCopiedFromP2);
                if (sdFiles.length > 0) {
                    sidedataContainer = io.github.search5.hg4j.storage.SidedataCodec.serialize(
                            Map.of(io.github.search5.hg4j.storage.SidedataCodec.SD_FILES, sdFiles));
                }
            }
            byte[] commitNode = changelog.appendRevision(changelogTextBytes, clMeta, declaredClRev1, declaredClRev2, p1CommitNodeHash, p2CommitNodeHash, newCommitRev, sidedataContainer);

            // 6. Update and save Dirstate
            dirstate.setParents(new NodeId(commitNode), NodeId.NULL);
            
            List<String> pathsToChange = new ArrayList<>(dirstate.getEntries().keySet());
            for (String path : pathsToChange) {
                Dirstate.Entry entry = dirstate.getEntries().get(path);
                if (entry == null) continue;
                
                if (entry.getState() == 'r') {
                    dirstate.removeEntry(path);
                } else if (entry.getState() == 'a' || entry.getState() == 'm' || filesModified.contains(path)) {
                    File diskFile = new File(repository.getDirectory(), path);
                    boolean isSymlink = Files.isSymbolicLink(diskFile.toPath());
                    int mode = diskFile.canExecute() ? 0755 : 0644;
                    // A symlink's dirstate "size" must be the length of its own target path
                    // string (lstat semantics, matching the filelog content recorded above),
                    // not File.length() which follows the link to whatever it points at.
                    int size = isSymlink
                            ? Files.readSymbolicLink(diskFile.toPath()).toString().getBytes(StandardCharsets.UTF_8).length
                            : (int) diskFile.length();
                    long time = SafeFileIO.lastModifiedSeconds(diskFile);
                    dirstate.addEntry(path, new Dirstate.Entry('n', mode, size, time));
                }
            }
            repository.writeDirstate(dirstate);

            // 6.5. Update Phase for the new commit (all new commits default to DRAFT)
            try {
                PhaseRoots phaseRoots = repository.getPhaseRoots();
                phaseRoots.setPhase(new NodeId(commitNode), PhaseRoots.Phase.DRAFT, changelog);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to set phase for new commit node", e);
            }

            // Commit transaction: delete journal and backup files
            if (!skipLockAndJournal) {
                try {
                    Files.deleteIfExists(journalFile.toPath());
                    Files.deleteIfExists(new File(repository.getDirectory(), ".hg/dirstate.backup").toPath());
                    Files.deleteIfExists(new File(repository.getStoreDir(), "fncache.backup").toPath());
                } catch (Exception ignored) {
                    LOGGER.log(Level.WARNING, "Failed to clean up transaction backups", ignored);
                }
            }

            // POST_COMMIT hooks trigger
            if (!postCommitHooks.isEmpty()) {
                Map<String, Object> ctx = new HashMap<>();
                ctx.put("author", author);
                ctx.put("message", message);
                ctx.put("commitNode", commitNode);
                ctx.put("repository", repository);
                for (HgHook hook : postCommitHooks) {
                    try {
                        hook.run(ctx);
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Post-commit hook execution failed", e);
                    }
                }
            }

            // 활성 북마크가 존재하면 해당 북마크를 새로운 커밋 노드로 전진시킨다.
            BookmarkCommand bookmarkCmd = new BookmarkCommand(repository);
            String active = bookmarkCmd.getActiveBookmark();
            if (active != null) {
                            bookmarkCmd.setBookmarkName(active)
                           .setRevision(NodeIdUtil.toHex(commitNode))
                           .call();
            }

            // 4b. Write undo info for rollback support
            try {
                writeUndoInfo(repository, fileSizes, dirstateBackup);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to write undo info for rollback", e);
            }
 
            return commitNode;
        } catch (Exception t) {
            if (skipLockAndJournal) {
                repository.clearRevlogCache();
                throw t;
            }
            // N-1: Transaction Rollback Session
            // Rollback files to original truncated sizes
            for (Map.Entry<File, Long> sizeEntry : fileSizes.entrySet()) {
                File file = sizeEntry.getKey();
                long origSize = sizeEntry.getValue();
                if (origSize == 0) {
                    try {
                        Files.deleteIfExists(file.toPath());
                    } catch (Exception ignored) {
                        LOGGER.log(Level.WARNING, "Failed to delete size-0 file during rollback: " + file, ignored);
                        t.addSuppressed(ignored);
                    }
                } else {
                    try (FileChannel outChan = FileChannel.open(file.toPath(), StandardOpenOption.WRITE)) {
                        outChan.truncate(origSize);
                        outChan.force(true);
                    } catch (Exception ignored) {
                        LOGGER.log(Level.WARNING, "Failed to truncate file during rollback: " + file, ignored);
                        t.addSuppressed(ignored);
                    }
                }
            }
            
            // Restore fncache atomically (N-1 Rollback Refinement)
            if (fncacheBackup != null) {
                try {
                    SafeFileIO.writeAtomic(fncacheFile, fncacheBackup);
                } catch (Exception ignored) {
                    LOGGER.log(Level.WARNING, "Failed to restore fncache backup during rollback", ignored);
                    t.addSuppressed(ignored);
                }
            } else {
                try {
                    Files.deleteIfExists(fncacheFile.toPath());
                } catch (Exception ignored) {
                    LOGGER.log(Level.WARNING, "Failed to delete fncache during rollback", ignored);
                    t.addSuppressed(ignored);
                }
            }

            // Restore fileindex atomically (mirrors the fncache rollback above)
            if (fileIndexBackup != null) {
                try {
                    FileIndex.restore(repository.getStoreDir(), fileIndexBackup);
                } catch (Exception ignored) {
                    LOGGER.log(Level.WARNING, "Failed to restore fileindex backup during rollback", ignored);
                    t.addSuppressed(ignored);
                }
            }

            // Restore dirstate atomically (N-1 Rollback Refinement with Dirstate V2 garbage collection)
            if (dirstateBackup != null) {
                try {
                    if (dirstateFile.exists()) {
                        byte[] currentBytes = Files.readAllBytes(dirstateFile.toPath());
                        if (currentBytes.length >= 12 && new String(currentBytes, 0, 12, StandardCharsets.US_ASCII).equals("dirstate-v2\n")) {
                            ByteBuffer currentBuf = ByteBuffer.wrap(currentBytes).order(ByteOrder.BIG_ENDIAN);
                            int currentUidSize = currentBuf.get(124) & 0xFF;
                            byte[] currentUidBytes = new byte[currentUidSize];
                            currentBuf.position(125);
                            currentBuf.get(currentUidBytes);
                            String currentUid = new String(currentUidBytes, StandardCharsets.US_ASCII);
                            
                            String oldUid = null;
                            if (dirstateBackup.length >= 12 && new String(dirstateBackup, 0, 12, StandardCharsets.US_ASCII).equals("dirstate-v2\n")) {
                                ByteBuffer oldBuf = ByteBuffer.wrap(dirstateBackup).order(ByteOrder.BIG_ENDIAN);
                                int oldUidSize = oldBuf.get(124) & 0xFF;
                                byte[] oldUidBytes = new byte[oldUidSize];
                                oldBuf.position(125);
                                oldBuf.get(oldUidBytes);
                                oldUid = new String(oldUidBytes, StandardCharsets.US_ASCII);
                            }
                            
                            if (currentUid != null && !currentUid.equals(oldUid)) {
                                File newDirstateDataFile = new File(dirstateFile.getParentFile(), "dirstate." + currentUid);
                                Files.deleteIfExists(newDirstateDataFile.toPath());
                            }

                            // Dirstate.write()'s own "W-LEAK" cleanup already deleted the
                            // *previous* uid's ".hg/dirstate.<uid>" data file the moment this
                            // (now-failing) transaction wrote its new docket. Restoring
                            // dirstateBackup's docket bytes below would then point at a data
                            // file that no longer exists, leaving a dirstate-v2 repository that
                            // fails to even load. Restore that data file from the snapshot taken
                            // before the transaction started, if it's missing.
                            if (oldUid != null && dirstateV2DataBackup != null) {
                                File oldDataFile = new File(dirstateFile.getParentFile(), "dirstate." + oldUid);
                                if (!oldDataFile.exists()) {
                                    SafeFileIO.writeAtomic(oldDataFile, dirstateV2DataBackup);
                                }
                            }
                        }
                    }
                    SafeFileIO.writeAtomic(dirstateFile, dirstateBackup);
                } catch (Exception ignored) {
                    LOGGER.log(Level.WARNING, "Failed to restore dirstate backup during rollback", ignored);
                    t.addSuppressed(ignored);
                }
            } else {
                try {
                    Files.deleteIfExists(dirstateFile.toPath());
                } catch (Exception ignored) {
                    LOGGER.log(Level.WARNING, "Failed to delete dirstate during rollback", ignored);
                    t.addSuppressed(ignored);
                }
            }
            // Cleanup journal and backup files on failure after restore
            try {
                Files.deleteIfExists(journalFile.toPath());
                Files.deleteIfExists(new File(repository.getDirectory(), ".hg/dirstate.backup").toPath());
                Files.deleteIfExists(new File(repository.getStoreDir(), "fncache.backup").toPath());
            } catch (Exception ignored) {
                LOGGER.log(Level.WARNING, "Failed to clean up journal/backups after rollback", ignored);
                t.addSuppressed(ignored);
            }
            repository.clearRevlogCache();
            throw t;
        }
    }

    /**
     * Real hg's {@code hg commit} automatically manages {@code .hgsubstate} whenever {@code
     * .hgsub} is present in the working directory -- the user never runs a separate "record
     * subrepo state" step, and {@code .hgsubstate} does not need to be {@code hg add}ed by hand
     * (verified live against Mercurial 7.2's {@code subrepoutil.precommitstate}/{@code
     * hgsubrepo.dirty}/{@code hgsubrepo.basestate}): for every path declared in {@code .hgsub}
     * (processed in sorted order, matching {@code subrepoutil.writestate}'s {@code sorted(state)}),
     * if the subrepo has uncommitted local changes, the parent commit aborts with {@code
     * uncommitted changes in subrepository "&lt;path&gt;"} unless {@link #subrepos} (real hg's
     * {@code -S}/{@code --subrepos}) is set, in which case the subrepo is committed first;
     * otherwise the subrepo's current checked-out revision (dirty or not) becomes its recorded
     * state. Git subrepos are left alone entirely (skipped), mirroring the parser's existing
     * git-subrepo handling elsewhere in this codebase (e.g. {@code UpdateCommand}).
     *
     * <p><b>Matches real hg exactly</b> (backlog 23/24, decided 2026-09-04): when a declared
     * subrepo path is not checked out locally as an hg4j repository, real Mercurial 7.2 silently
     * auto-vivifies an *empty* repository there and resets its recorded {@code .hgsubstate}
     * entry to the null revision ({@code 0000000000000000000000000000000000000000}) -- verified
     * live, this actually discards any previously-recorded (real, non-null) revision for that
     * path. hg4j replicates this verbatim: a path that is not checked out locally has its
     * {@code .hgsubstate} entry reset to the null revision here, even when a real, non-null
     * revision was previously recorded for it. Callers that want a non-null revision recorded
     * for a subrepo must check it out locally (e.g. via {@code CloneCommand}/{@code
     * UpdateCommand}) *before* committing, exactly as real hg requires.
     */
    private void applySubrepoStateBeforeCommit() throws IOException, HgLockException {
        File hgsubFile = new File(repository.getDirectory(), ".hgsub");
        if (!hgsubFile.exists()) {
            return;
        }

        Map<String, String> subUrls = new TreeMap<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        Set<String> gitPaths = new LinkedHashSet<>();
        for (String line : Files.readAllLines(hgsubFile.toPath(), StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq == -1) {
                continue;
            }
            String path = trimmed.substring(0, eq).trim();
            String url = trimmed.substring(eq + 1).trim();
            if (url.startsWith("[git]")) {
                gitPaths.add(path);
            }
            subUrls.put(path, url);
        }
        if (subUrls.isEmpty()) {
            return;
        }

        Map<String, String> newState = new TreeMap<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        for (String path : subUrls.keySet()) {
            if (gitPaths.contains(path)) {
                continue;
            }
            File subDir = new File(repository.getDirectory(), path);
            if (!new File(subDir, ".hg").exists()) {
                // Not checked out locally -- real hg auto-vivifies an empty repo here and
                // resets the recorded revision to null (see the class-level note above),
                // discarding whatever non-null revision may have been previously recorded.
                newState.put(path, NodeId.NULL.toHex());
                continue;
            }

            HgRepository subRepo = new HgRepository(subDir);
            Status subStatus = new StatusCommand(subRepo).call();
            boolean dirty = !subStatus.getAdded().isEmpty()
                    || !subStatus.getModified().isEmpty()
                    || !subStatus.getRemoved().isEmpty();

            String revHex;
            if (dirty) {
                if (!this.subrepos) {
                    throw new HgValidationException(
                            "uncommitted changes in subrepository \"" + path
                                    + "\" (use --subrepos for recursive commit)");
                }
                CommitCommand subCommit = new CommitCommand(subRepo)
                        .setMessage(this.message)
                        .setAuthor(this.author)
                        .setSubrepos(true);
                if (forcedTime != null && forcedOffset != null) {
                    subCommit.setDate(forcedTime, forcedOffset);
                }
                revHex = NodeIdUtil.toHex(subCommit.call());
            } else {
                NodeId subParent1 = subRepo.getDirstate().getParent1Node();
                if (subParent1 == null || subParent1.isNull()) {
                    continue; // Subrepo has no commits checked out -- nothing to record.
                }
                revHex = subParent1.toHex();
            }
            newState.put(path, revHex);
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : newState.entrySet()) {
            sb.append(e.getValue()).append(' ').append(e.getKey()).append('\n');
        }
        byte[] newContent = sb.toString().getBytes(StandardCharsets.UTF_8);

        File hgsubstateFile = new File(repository.getDirectory(), ".hgsubstate");
        byte[] oldContent = hgsubstateFile.exists() ? Files.readAllBytes(hgsubstateFile.toPath()) : null;
        if (oldContent == null && newState.isEmpty()) {
            return; // Nothing recorded before, nothing to record now -- do not conjure the file
                     // out of nowhere (e.g. .hgsub declares only subrepos that are neither
                     // checked out locally nor previously recorded).
        }
        if (oldContent != null && Arrays.equals(oldContent, newContent)) {
            return; // No subrepo state changes since the last commit -- leave everything as-is.
        }

        Files.write(hgsubstateFile.toPath(), newContent);

        Dirstate dirstate = repository.getDirstate();
        if (!dirstate.getEntries().containsKey(".hgsubstate")) {
            // Auto-track .hgsubstate the first time it is written, exactly like real hg -- the
            // user is never expected to `hg add .hgsubstate` by hand.
            dirstate.addEntry(".hgsubstate", new Dirstate.Entry('a', 0, 0, 0));
            repository.writeDirstate(dirstate);
        }
        // If already tracked ('n' state from a prior commit), the size/mtime-vs-disk change
        // detection this class's own commit loop already performs (see the workingState == 'n'
        // branch above) picks up the freshly written content without any further dirstate edit.
    }

    /**
     * Reads the ".hg/dirstate.&lt;uid&gt;" data file that a captured dirstate-v2 docket snapshot
     * ({@code docketBytes}) references, so it can be restored alongside the docket on rollback.
     * Best-effort: any parsing/read failure (v1 dirstate, corrupt/short bytes, missing file)
     * simply yields {@code null}.
     */
    private static byte[] captureDirstateV2DataBackup(File dirstateFile, byte[] docketBytes) {
        if (docketBytes == null || docketBytes.length < 125) {
            return null;
        }
        try {
            if (!new String(docketBytes, 0, 12, StandardCharsets.US_ASCII).equals("dirstate-v2\n")) {
                return null;
            }
            int uidSize = docketBytes[124] & 0xFF;
            if (docketBytes.length < 125 + uidSize) {
                return null;
            }
            String uid = new String(docketBytes, 125, uidSize, StandardCharsets.US_ASCII);
            File dataFile = new File(dirstateFile.getParentFile(), "dirstate." + uid);
            return dataFile.exists() ? Files.readAllBytes(dataFile.toPath()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] getFileRevisionContent(HgRepository repository, String path, String nodeHex) throws IOException {
        File flIdx = getFilelogIndex(repository.getStoreDir(), path);
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        if (!flIdx.exists()) {
            throw new HgRepositoryNotFoundException("Filelog index does not exist for: " + path);
        }
        Revlog filelog = repository.getRevlog(flIdx, flDat);
        int rev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(nodeHex.substring(0, 40)));
        if (rev == -1) {
            throw new HgRevisionNotFoundException("File revision not found: " + path + " @ " + nodeHex);
        }
        return filelog.getRevisionContent(rev);
    }
    private static boolean isNullNode(byte[] node) {
        if (node == null) {
            return true;
        }
        for (byte b : node) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * For a {@code treemanifest} repository: walks the manifest tree rooted at {@code
     * manifestNode} and returns a map of repo-root-relative directory path (bare, no trailing
     * slash; {@code ""} for the root itself, mapping to {@code manifestNode} unchanged) to that
     * directory's own {@code meta/<dir>/00manifest.i} (or, for {@code ""}, {@code
     * 00manifest.i}) revision node hash. {@link #writeTreeManifestDir} needs this to correctly
     * set parent1/parent2 on each per-directory revision it (re)writes this commit -- mirrors
     * {@link ManifestTreeIterator#expandTree} but records directory nodes instead of discarding
     * them once expanded into flat file entries.
     *
     * @return an empty map if {@code manifestNode} is the all-zero "no such parent" sentinel
     *         (first commit / no such parent side of a merge).
     */
    private Map<String, byte[]> collectDirNodes(byte[] manifestNode) throws IOException {
        Map<String, byte[]> result = new HashMap<>();
        if (isNullNode(manifestNode)) {
            return result;
        }
        result.put("", manifestNode);
        Revlog manifestRevlog = repository.getManifestRevlog();
        int rev = NodeIdUtil.findRevisionByNodeId(manifestRevlog, manifestNode);
        if (rev == -1) {
            return result;
        }
        collectDirNodesRecursive(manifestRevlog.getRevisionContent(rev), "", result);
        return result;
    }

    private void collectDirNodesRecursive(byte[] mfContent, String dirPrefix, Map<String, byte[]> result) throws IOException {
        for (ManifestTreeIterator.Entry e : ManifestTreeIterator.parseManifestContent(mfContent)) {
            if (!e.isTreeDir()) {
                continue;
            }
            String fullPath = dirPrefix.isEmpty() ? e.getPath() : dirPrefix + "/" + e.getPath();
            result.put(fullPath, e.getNodeId());
            File subIdx = new File(repository.getStoreDir(), "meta/" + fullPath + "/00manifest.i");
            File subDat = new File(repository.getStoreDir(), "meta/" + fullPath + "/00manifest.d");
            Revlog subRevlog = repository.getRevlog(subIdx, subDat);
            int subRev = NodeIdUtil.findRevisionByNodeId(subRevlog, e.getNodeId());
            if (subRev == -1) {
                continue;
            }
            collectDirNodesRecursive(subRevlog.getRevisionContent(subRev), fullPath, result);
        }
    }

    /**
     * Recursively splits the flat new manifest ({@code flatManifest}: full repo-root-relative
     * path -> {@code "<40-hex-node><flag>"}, exactly what the non-treemanifest path already
     * builds) into per-directory revisions for a {@code treemanifest} repository, writing
     * bottom-up (a directory's own revision can only be written once every subdirectory it
     * references has already been written and its new node hash is known) — mirrors real hg's
     * {@code mercurial/manifest.py} {@code manifestlog._addtree}/{@code writesubtrees} recursion.
     *
     * <p>Entries within one directory level are combined and sorted together by their bare name
     * (files and subdirectory pointers interleaved in one list, matching real hg's {@code
     * treemanifest.dirtext()}: {@code sorted(dirs + files)}), NOT files-then-directories.
     *
     * <p><b>Known simplification vs. real hg</b> (documented, not a correctness gap): real hg
     * skips writing a fresh revision for a subdirectory whose entire subtree is byte-identical to
     * one parent's ({@code m.unmodifiedsince(m1)}), reusing that parent's node instead. This
     * method always writes a fresh revision for every directory touched by the recursion (i.e.
     * every directory on the path from the root to any changed file, exactly matching root-level
     * flat-manifest behavior, which already always writes a fresh revision every commit
     * regardless of whether content changed). The result is fully spec-valid (every node hash is
     * still a correct hash of its own real parents+content, verified byte-for-byte readable by
     * real hg in {@code TreemanifestWriteRealFixtureTest}) — just less storage-deduplicated than
     * real hg's own output for a commit that only touches one leaf directory in a deep tree.
     *
     * @param dir "" for the root (written to {@code manifestRevlog}/{@code 00manifest.i}), or a
     *            repo-root-relative bare directory path (written to {@code
     *            meta/<dir>/00manifest.i}) for a recursive sub-call.
     * @param p1DirNodes/p2DirNodes from {@link #collectDirNodes} for each parent.
     * @param p1RootNode/p2RootNode the root manifest's own parent node hashes (used only when
     *                               {@code dir} is {@code ""}, since the root revlog is always
     *                               {@code manifestRevlog} regardless of directory recursion).
     * @return the new node hash written for this directory's own revision.
     */
    private byte[] writeTreeManifestDir(String dir, Map<String, String> flatManifest,
                                         Map<String, byte[]> p1DirNodes, Map<String, byte[]> p2DirNodes,
                                         byte[] p1RootNode, byte[] p2RootNode, Revlog manifestRevlog, int linkRev) throws IOException {
        String prefix = dir.isEmpty() ? "" : dir + "/";
        Map<String, String> lines = new TreeMap<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        java.util.TreeSet<String> subdirNames = new java.util.TreeSet<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        for (Map.Entry<String, String> e : flatManifest.entrySet()) {
            String path = e.getKey();
            if (!path.startsWith(prefix)) {
                continue;
            }
            String rest = path.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash < 0) {
                lines.put(rest, e.getValue());
            } else {
                subdirNames.add(rest.substring(0, slash));
            }
        }
        for (String sub : subdirNames) {
            String subFullPath = prefix + sub;
            byte[] childNode = writeTreeManifestDir(subFullPath, flatManifest, p1DirNodes, p2DirNodes,
                    p1RootNode, p2RootNode, manifestRevlog, linkRev);
            lines.put(sub, NodeIdUtil.toHex(childNode) + "t");
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : lines.entrySet()) {
            sb.append(e.getKey()).append('\0').append(e.getValue()).append('\n');
        }
        byte[] content = sb.toString().getBytes(StandardCharsets.UTF_8);

        Revlog dirRevlog;
        byte[] p1;
        byte[] p2;
        if (dir.isEmpty()) {
            dirRevlog = manifestRevlog;
            p1 = p1RootNode;
            p2 = p2RootNode;
        } else {
            File subIdx = new File(repository.getStoreDir(), "meta/" + dir + "/00manifest.i");
            File subDat = new File(repository.getStoreDir(), "meta/" + dir + "/00manifest.d");
            Files.createDirectories(subIdx.getParentFile().toPath());
            dirRevlog = repository.getRevlog(subIdx, subDat);
            p1 = p1DirNodes.getOrDefault(dir, new byte[20]);
            p2 = p2DirNodes.getOrDefault(dir, new byte[20]);
        }
        int p1Rev = isNullNode(p1) ? -1 : NodeIdUtil.findRevisionByNodeId(dirRevlog, p1);
        int p2Rev = isNullNode(p2) ? -1 : NodeIdUtil.findRevisionByNodeId(dirRevlog, p2);
        return dirRevlog.appendRevision(content, p1Rev, p2Rev, p1, p2, linkRev);
    }

    private static byte[] extractManifestNode(byte[] clContent) {
        if (clContent == null || clContent.length == 0) {
            return new byte[20];
        }
        int firstNewLine = -1;
        for (int i = 0; i < clContent.length; i++) {
            if (clContent[i] == '\n') {
                firstNewLine = i;
                break;
            }
        }
        byte[] mfNode = null;
        if (firstNewLine >= 40) {
            boolean isHexText = true;
            for (int i = 0; i < 40; i++) {
                char c = (char) clContent[i];
                if (Character.digit(c, 16) == -1) {
                    isHexText = false;
                    break;
                }
            }
            if (isHexText) {
                String hexNode = new String(clContent, 0, 40, StandardCharsets.UTF_8);
                mfNode = NodeIdUtil.fromHex(hexNode);
            }
        }
        if (mfNode == null) {
            if (clContent.length >= 20) {
                mfNode = new byte[20];
                System.arraycopy(clContent, 0, mfNode, 0, 20);
            }
        }
        return mfNode != null ? mfNode : new byte[20];
    }

    public static File getFilelogIndex(File storeDir, String relPath) {
        String encoded = NodeIdUtil.encodeFname(relPath + ".i");
        return new File(storeDir, encoded);
    }

    /**
     * Locates the key/value separator inside one already-decoded {@code "key:value"} extra
     * item. Real hg (mercurial/changelog.py {@code decodeextra}) never escapes {@code :} —
     * it splits on the first literal colon ({@code str.split(b':', 1)}) — so this is just that.
     */
    public static int findUnescapedColon(String s) {
        return s == null ? -1 : s.indexOf(':');
    }

    /**
     * Mirrors real hg's {@code changelog._string_escape}: only {@code \}, newline, CR and NUL
     * are escaped. A literal {@code :} is deliberately left untouched (real hg splits
     * {@code "key:value"} on the first colon only, so an embedded colon in the value never
     * needs escaping — escaping it here would produce bytes real hg does not write).
     */
    public static String encodeExtraKey(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\0", "\\0");
    }

    public static String decodeExtraKey(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == '0') {
                    sb.append('\0');
                    i++;
                } else if (next == 'r') {
                    sb.append('\r');
                    i++;
                } else if (next == 'n') {
                    sb.append('\n');
                    i++;
                } else if (next == '\\') {
                    sb.append('\\');
                    i++;
                } else {
                    sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Returns the named branch a changelog revision was committed on, decoded from its
     * {@code branch:<name>} extra field. Real hg never writes that field for the default
     * branch ([[decisions/mercurial-spec-compliance-requirement]] — "Changelog 포맷" row),
     * so its absence means {@code "default"}.
     */
    public static String getBranchOfRevision(Revlog changelog, int rev) throws IOException {
        return parseExtra(changelog, rev).getOrDefault("branch", "default");
    }

    /** Whether a changelog revision closes its named branch head ({@code hg commit --close-branch}'s {@code close:1} extra). */
    public static boolean isRevisionClosingBranch(Revlog changelog, int rev) throws IOException {
        return "1".equals(parseExtra(changelog, rev).get("close"));
    }

    /** Decodes a changelog revision's extras block (date/tz line's {@code \0}-separated {@code key:value} entries). */
    private static Map<String, String> parseExtra(Revlog changelog, int rev) throws IOException {
        Map<String, String> extra = new HashMap<>();
        byte[] content = changelog.getRevisionContent(rev);
        String text = new String(content, StandardCharsets.UTF_8);
        String[] lines = text.split("\n");
        if (lines.length <= 2) {
            return extra;
        }
        String dateLine = lines[2].trim();
        int firstSpace = dateLine.indexOf(' ');
        if (firstSpace == -1) {
            return extra;
        }
        int secondSpace = dateLine.indexOf(' ', firstSpace + 1);
        String extraPart = (secondSpace != -1) ? dateLine.substring(secondSpace + 1) : null;
        if (extraPart == null || extraPart.isEmpty()) {
            return extra;
        }
        for (String part : extraPart.split("\0", -1)) {
            int colonIdx = findUnescapedColon(part);
            if (colonIdx != -1) {
                extra.put(decodeExtraKey(part.substring(0, colonIdx)), decodeExtraKey(part.substring(colonIdx + 1)));
            }
        }
        return extra;
    }

    private void appendToJournal(File journalFile, String entry) throws IOException {
        Files.writeString(journalFile.toPath(), entry + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try (FileChannel fc = FileChannel.open(journalFile.toPath(), StandardOpenOption.WRITE)) {
            fc.force(true);
        }
    }

    public static void writeUndoInfo(HgRepository repository, Map<File, Long> fileSizes, byte[] dirstateBackup) throws IOException {
        File undoFile = new File(repository.getStoreDir(), "undo");
        File undoBackupFiles = new File(repository.getStoreDir(), "undo.backup.files");
        File undoDirstate = new File(repository.getDirectory(), ".hg/undo.backup.dirstate");
        File undoBookmarks = new File(repository.getDirectory(), ".hg/undo.backup.bookmarks");

        // Clean previous undo files
        Files.deleteIfExists(undoFile.toPath());
        Files.deleteIfExists(undoBackupFiles.toPath());
        Files.deleteIfExists(undoDirstate.toPath());
        Files.deleteIfExists(undoBookmarks.toPath());

        // 1. Write undo file (list of store files and original sizes)
        StringBuilder sbUndo = new StringBuilder();
        StringBuilder sbFiles = new StringBuilder();
        File storeDir = repository.getStoreDir();

        for (Map.Entry<File, Long> entry : fileSizes.entrySet()) {
            File f = entry.getKey();
            long size = entry.getValue();
            
            // Get path relative to the store directory
            String relPath = storeDir.toPath().relativize(f.toPath()).toString();
            sbUndo.append(relPath).append("\t").append(size).append("\n");
            sbFiles.append(relPath).append("\n");
        }

        SafeFileIO.writeStringAtomic(undoFile, sbUndo.toString());
        SafeFileIO.writeStringAtomic(undoBackupFiles, sbFiles.toString());

        // 2. Backup dirstate
        if (dirstateBackup != null) {
            SafeFileIO.writeAtomic(undoDirstate, dirstateBackup);
        }

        // 3. Backup bookmarks
        File bookmarksFile = new File(repository.getDirectory(), ".hg/bookmarks");
        if (bookmarksFile.exists()) {
            Files.copy(bookmarksFile.toPath(), undoBookmarks.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
