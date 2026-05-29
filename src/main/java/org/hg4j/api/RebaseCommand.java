package org.hg4j.api;

import org.hg4j.core.Dirstate;
import org.hg4j.core.HgLock;
import org.hg4j.core.HgRepository;
import org.hg4j.core.NodeIdUtil;
import org.hg4j.core.Revlog;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Porcelain command to rebase revisions on top of another base revision.
 * Performs linear revision cherry-picking with clean manifest integration and dirstate updating.
 * Now supports full physical strip of original duplicate history for complete interop fidelity.
 */
public class RebaseCommand {
    private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger(RebaseCommand.class.getName());

    private final HgRepository repository;
    private byte[] sourceNode;
    private byte[] targetNode;
    private final java.util.List<HgHook> preRebaseHooks = new java.util.ArrayList<>();
    private final java.util.List<HgHook> postRebaseHooks = new java.util.ArrayList<>();

    private static class BackupCommit {
        byte[] originalNode;
        byte[] parent1Node;
        byte[] parent2Node;
        String branch;
        String author;
        String message;
        long time;
        int offsetSeconds;
        byte[] manifestNode;
        byte[] rawChangelogContent;
        byte[] rawManifestContent;
        Map<String, FileBackupInfo> fileBackups = new HashMap<>();
        Map<String, byte[]> fileContents = new HashMap<>();
    }

    private static class FileBackupInfo {
        byte[] node;
        byte[] p1Node;
        byte[] p2Node;
        byte[] rawContent;
    }

    public RebaseCommand(HgRepository repository) {
        this.repository = repository;
    }

    public RebaseCommand registerPreRebaseHook(HgHook hook) {
        if (hook != null) {
            preRebaseHooks.add(hook);
        }
        return this;
    }

    public RebaseCommand registerPostRebaseHook(HgHook hook) {
        if (hook != null) {
            postRebaseHooks.add(hook);
        }
        return this;
    }

    public RebaseCommand setSource(byte[] sourceNode) {
        this.sourceNode = sourceNode;
        return this;
    }

    public RebaseCommand setTarget(byte[] targetNode) {
        this.targetNode = targetNode;
        return this;
    }

    public byte[] call() throws IOException {
        repository.clearRevlogCache();
        if (sourceNode == null || targetNode == null) {
            throw new IllegalStateException("Source and Target nodes must be specified for rebase.");
        }

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        int srcRev = NodeIdUtil.findRevisionByNodeId(changelog, sourceNode);
        int tgtRev = NodeIdUtil.findRevisionByNodeId(changelog, targetNode);

        if (srcRev == -1 || tgtRev == -1) {
            throw new org.hg4j.errors.HgRevisionNotFoundException("Source or target revision not found in history.");
        }

        // Collect all descendant revisions of source revision (inclusive)
        org.hg4j.revwalk.ChangesetGraph graph = new org.hg4j.revwalk.ChangesetGraph(changelog);
        List<Integer> revisionsToRebase = new ArrayList<>();
        revisionsToRebase.add(srcRev);
        for (int r = srcRev + 1; r < changelog.getRevisionCount(); r++) {
            if (graph.isAncestor(srcRev, r)) {
                revisionsToRebase.add(r);
            }
        }

        int minOrigRev = srcRev;
        int maxOrigRev = changelog.getRevisionCount() - 1;

        // 1. Back up all commits in the [minOrigRev, tip] range
        List<BackupCommit> backupsToRebase = new ArrayList<>();
        List<BackupCommit> backupsToRestore = new ArrayList<>();

        for (int r = minOrigRev; r <= maxOrigRev; r++) {
            BackupCommit backup = backupRevision(r, changelog);
            if (revisionsToRebase.contains(r)) {
                backupsToRebase.add(backup);
            } else {
                backupsToRestore.add(backup);
            }
        }

        File backupDir = new File(repository.getStoreDir(), "rebase-backup");
        Map<File, File> backupMapping = new HashMap<>();

        // 2. Perform physical backup copies of the store to guarantee 100% crash durability
        try (HgLock storeLock = repository.lockStore();
             HgLock wlock = repository.lockWorkingCopy()) {
            
            // PRE_REBASE hooks trigger
            if (!preRebaseHooks.isEmpty()) {
                Map<String, Object> ctx = new java.util.HashMap<>();
                ctx.put("repository", repository);
                ctx.put("sourceNode", sourceNode);
                ctx.put("targetNode", targetNode);
                for (HgHook hook : preRebaseHooks) {
                    if (!hook.run(ctx)) {
                        throw new org.hg4j.errors.HgValidationException("Rebase rejected by PRE_REBASE hook");
                    }
                }
            }

            deleteDirRecursively(backupDir);
            backupStoreFiles(backupDir, backupMapping, minOrigRev, revisionsToRebase, changelog);
            
            // Create journal for crash recovery
            writeRebaseJournal(backupMapping);

            // Perform physical strip under locked safety
            stripRevisionsFrom(minOrigRev);
            repository.clearRevlogCache();

            changelog = repository.getRevlog(clIdx, clDat);

            // Map to track original nodes to rebased/restored nodes for parent updates
            Map<java.nio.ByteBuffer, byte[]> nodeMapping = new HashMap<>();

            // 4. Cherry-pick each backed up commit onto the target/new parent
            byte[] currentBaseNode = targetNode;
            for (BackupCommit backup : backupsToRebase) {
                byte[] rebasedNode = cherryPickBackup(backup, currentBaseNode, nodeMapping);
                nodeMapping.put(java.nio.ByteBuffer.wrap(Arrays.copyOf(backup.originalNode, 20)), rebasedNode);
                currentBaseNode = rebasedNode;
            }

            // 5. Restore the non-descendant independent branch commits physically preserving original nodeId
            for (BackupCommit backup : backupsToRestore) {
                byte[] p1 = backup.parent1Node;
                byte[] p2 = backup.parent2Node;
                
                byte[] mappedP1 = nodeMapping.get(java.nio.ByteBuffer.wrap(Arrays.copyOf(p1, 20)));
                if (mappedP1 != null) p1 = mappedP1;
                
                byte[] mappedP2 = nodeMapping.get(java.nio.ByteBuffer.wrap(Arrays.copyOf(p2, 20)));
                if (mappedP2 != null) p2 = mappedP2;

                byte[] restoredNode = restoreBackup(backup, p1, p2, nodeMapping);
                nodeMapping.put(java.nio.ByteBuffer.wrap(Arrays.copyOf(backup.originalNode, 20)), restoredNode);
            }

            // 6. Checkout the final rebased state
            checkoutNode(currentBaseNode);

            // Clean physical backup copies and journal on success
            deleteRebaseJournal();
            deleteDirRecursively(backupDir);

            // POST_REBASE hooks trigger
            java.util.Map<String, Object> ctx = new java.util.HashMap<>();
            ctx.put("sourceNode", sourceNode);
            ctx.put("targetNode", targetNode);
            ctx.put("rebasedTipNode", NodeIdUtil.toHex(currentBaseNode));
            ctx.put("repository", repository);
            for (HgHook hook : postRebaseHooks) {
                try {
                    hook.run(ctx);
                } catch (Exception e) {
                    LOGGER.log(java.util.logging.Level.WARNING, "Post-rebase hook execution failed", e);
                }
            }

            return currentBaseNode;

        } catch (Exception t) {
            performPhysicalRollback(backupMapping, backupDir);
            throw t;
        }
    }

    private void backupStoreFiles(File backupDir, Map<File, File> backupMapping, int startRev, List<Integer> rebaseRevs, Revlog changelog) throws IOException {
        backupDir.mkdirs();
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
        File mfDat = new File(repository.getStoreDir(), "00manifest.d");
        
        copyToBackup(clIdx, backupDir, backupMapping);
        copyToBackup(clDat, backupDir, backupMapping);
        copyToBackup(mfIdx, backupDir, backupMapping);
        copyToBackup(mfDat, backupDir, backupMapping);

        File fncacheFile = new File(repository.getStoreDir(), "fncache");
        if (fncacheFile.exists()) {
            copyToBackup(fncacheFile, backupDir, backupMapping);
            
            // Collect all unique file paths affected in the rebase range (O(delta) selective backup)
            java.util.Set<String> affectedFiles = new java.util.HashSet<>();
            for (int r = startRev; r < changelog.getRevisionCount(); r++) {
                byte[] clContent = changelog.getRevisionContent(r);
                String clText = new String(clContent, StandardCharsets.UTF_8);
                String[] clLines = clText.split("\n");
                
                // Parse affected file log paths: starts after clLines[2] (dateLine) and continues until empty line
                for (int i = 3; i < clLines.length; i++) {
                    String line = clLines[i].trim();
                    if (line.isEmpty()) {
                        break;
                    }
                    affectedFiles.add(line);
                }
            }
            
            // Selectively copy only the affected filelog indices and data files
            for (String path : affectedFiles) {
                File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
                File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
                
                if (flIdx.exists()) {
                    copyToBackup(flIdx, backupDir, backupMapping);
                }
                if (flDat.exists()) {
                    copyToBackup(flDat, backupDir, backupMapping);
                }
            }
        }
    }

    private void copyToBackup(File sourceFile, File backupDir, Map<File, File> backupMapping) throws IOException {
        if (!sourceFile.exists()) return;
        String relPath = repository.getStoreDir().toPath().relativize(sourceFile.toPath()).toString();
        File targetFile = new File(backupDir, relPath);
        targetFile.getParentFile().mkdirs();
        Files.copy(sourceFile.toPath(), targetFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        backupMapping.put(sourceFile, targetFile);
    }

    private void deleteDirRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirRecursively(child);
                }
            }
        }
        file.delete();
    }

    private BackupCommit backupRevision(int rev, Revlog changelog) throws IOException {
        BackupCommit backup = new BackupCommit();
        
        Revlog.IndexRecord revRec = changelog.getIndexRecord(rev);
        backup.originalNode = revRec.getNodeId();
        backup.rawChangelogContent = changelog.getRawRevisionContent(rev);
        
        // Parent node resolution
        if (revRec.getParent1() != -1) {
            backup.parent1Node = changelog.getIndexRecord(revRec.getParent1()).getNodeId();
        } else {
            backup.parent1Node = new byte[20];
        }
        if (revRec.getParent2() != -1) {
            backup.parent2Node = changelog.getIndexRecord(revRec.getParent2()).getNodeId();
        } else {
            backup.parent2Node = new byte[20];
        }

        byte[] clContent = changelog.getRevisionContent(rev);
        String clText = new String(clContent, StandardCharsets.UTF_8);
        String[] clLines = clText.split("\n");

        backup.manifestNode = NodeIdUtil.fromHex(clLines[0].trim().substring(0, 40));
        backup.author = clLines[1];

        // Parse branch and other extra metadata from dateLine
        String dateLine = clLines[2].trim();
        String branch = "default";
        long time = 0;
        int offset = 0;
        int firstSpace = dateLine.indexOf(' ');
        if (firstSpace != -1) {
            try {
                time = Long.parseLong(dateLine.substring(0, firstSpace));
            } catch (NumberFormatException ignored) {}
            int secondSpace = dateLine.indexOf(' ', firstSpace + 1);
            String extraPart = null;
            if (secondSpace != -1) {
                try {
                    offset = Integer.parseInt(dateLine.substring(firstSpace + 1, secondSpace));
                } catch (NumberFormatException ignored) {}
                extraPart = dateLine.substring(secondSpace + 1);
            } else {
                try {
                    offset = Integer.parseInt(dateLine.substring(firstSpace + 1));
                } catch (NumberFormatException ignored) {}
            }
            if (extraPart != null && !extraPart.isEmpty()) {
                String[] extraItems = extraPart.split("\0", -1);
                for (String part : extraItems) {
                    int colonIdx = CommitCommand.findUnescapedColon(part);
                    if (colonIdx != -1) {
                        String key = part.substring(0, colonIdx);
                        String val = part.substring(colonIdx + 1);
                        key = CommitCommand.decodeExtraKey(key);
                        val = CommitCommand.decodeExtraKey(val);
                        if ("branch".equals(key)) {
                            branch = val;
                        }
                    }
                }
            }
        } else {
            try {
                time = Long.parseLong(dateLine);
            } catch (NumberFormatException ignored) {}
        }
        backup.branch = branch;
        backup.time = time;
        backup.offsetSeconds = offset;

        int msgStartIdx = 3;
        while (msgStartIdx < clLines.length && !clLines[msgStartIdx].isEmpty()) {
            msgStartIdx++;
        }
        msgStartIdx++; // Skip empty line separator

        StringBuilder msgSb = new StringBuilder();
        for (int i = msgStartIdx; i < clLines.length; i++) {
            if (i > msgStartIdx) msgSb.append("\n");
            msgSb.append(clLines[i]);
        }
        backup.message = msgSb.toString();

        // Backup file contents referenced in manifest
        Revlog manifest = repository.getManifestRevlog();

        int mfRev = NodeIdUtil.findRevisionByNodeId(manifest, backup.manifestNode);
        backup.rawManifestContent = manifest.getRawRevisionContent(mfRev);
        byte[] mfContent = manifest.getRevisionContent(mfRev);
        String mfText = new String(mfContent, StandardCharsets.UTF_8);

        String[] lines = mfText.split("\n");
        for (String line : lines) {
            if (line.isEmpty()) continue;
            int nullIdx = line.indexOf('\0');
            if (nullIdx != -1) {
                String path = line.substring(0, nullIdx);
                String nodeWithFlags = line.substring(nullIdx + 1).trim();
                String hexNode = nodeWithFlags.substring(0, 40);

                File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
                File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
                Revlog filelog = repository.getRevlog(flIdx, flDat);

                int fileRev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(hexNode));
                byte[] fileContent = filelog.getRevisionContent(fileRev);
                backup.fileContents.put(path, fileContent);

                Revlog.IndexRecord flRec = filelog.getIndexRecord(fileRev);
                
                FileBackupInfo fBackup = new FileBackupInfo();
                fBackup.node = flRec.getNodeId();
                
                if (flRec.getParent1() != -1) {
                    fBackup.p1Node = filelog.getIndexRecord(flRec.getParent1()).getNodeId();
                } else {
                    fBackup.p1Node = new byte[20];
                }
                if (flRec.getParent2() != -1) {
                    fBackup.p2Node = filelog.getIndexRecord(flRec.getParent2()).getNodeId();
                } else {
                    fBackup.p2Node = new byte[20];
                }
                fBackup.rawContent = filelog.getRawRevisionContent(fileRev);
                
                backup.fileBackups.put(path, fBackup);
            }
        }

        return backup;
    }

    private void stripRevisionsFrom(int startRev) throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
        File mfDat = new File(repository.getStoreDir(), "00manifest.d");

        Revlog changelog = repository.getRevlog(clIdx, clDat);
        Revlog manifest = repository.getManifestRevlog();

        // Calculate truncate boundaries
        long clIdxSize = (long) startRev * 64;
        long clDatSize = 0;
        if (startRev > 0) {
            clDatSize = changelog.getIndexRecord(startRev).getOffset();
        }

        // We also truncate manifest starting from the linkRev mapping to startRev
        int minMfRev = -1;
        for (int i = 0; i < manifest.getRevisionCount(); i++) {
            if (manifest.getIndexRecord(i).getLinkRev() >= startRev) {
                minMfRev = i;
                break;
            }
        }

        long mfIdxSize = manifest.getRevisionCount() * 64L;
        long mfDatSize = mfDat.exists() ? mfDat.length() : 0L;
        if (minMfRev != -1) {
            mfIdxSize = (long) minMfRev * 64;
            if (minMfRev > 0) {
                mfDatSize = manifest.getIndexRecord(minMfRev).getOffset();
            } else {
                mfDatSize = 0;
            }
        }

        // Truncate filelogs registered in fncache (Solve filelog strip defect)
        File fncacheFile = new File(repository.getStoreDir(), "fncache");
        if (fncacheFile.exists()) {
            List<String> fncachePaths = Files.readAllLines(fncacheFile.toPath(), StandardCharsets.UTF_8);
            for (String relPath : fncachePaths) {
                if (relPath.endsWith(".i")) {
                    File flIdx = new File(repository.getStoreDir(), relPath);
                    String datPath = relPath.substring(0, relPath.length() - 2) + ".d";
                    File flDat = new File(repository.getStoreDir(), datPath);

                    if (flIdx.exists()) {
                        try {
                            Revlog filelog = repository.getRevlog(flIdx, flDat);
                            int minFileRev = -1;
                            for (int i = 0; i < filelog.getRevisionCount(); i++) {
                                if (filelog.getIndexRecord(i).getLinkRev() >= startRev) {
                                    minFileRev = i;
                                    break;
                                }
                            }
                            if (minFileRev != -1) {
                                long flIdxSize = (long) minFileRev * 64;
                                long flDatSize = 0;
                                if (minFileRev > 0) {
                                    flDatSize = filelog.getIndexRecord(minFileRev).getOffset();
                                }
                                truncateFile(flIdx, flIdxSize);
                                truncateFile(flDat, flDatSize);
                            }
                        } catch (Exception ignored) {
                            // Ignore load/strip failure of single filelog
                        }
                    }
                }
            }
        }

        // Perform truncate physically
        truncateFile(clIdx, clIdxSize);
        truncateFile(clDat, clDatSize);
        truncateFile(mfIdx, mfIdxSize);
        truncateFile(mfDat, mfDatSize);
    }

    private void truncateFile(File file, long size) throws IOException {
        if (!file.exists()) return;
        if (size == 0) {
            Files.deleteIfExists(file.toPath());
        } else {
            try (java.nio.channels.FileChannel outChan = java.nio.channels.FileChannel.open(file.toPath(), java.nio.file.StandardOpenOption.WRITE)) {
                outChan.truncate(size);
                outChan.force(true);
            }
        }
    }

    private byte[] cherryPickBackup(BackupCommit backup, byte[] newParentNode, Map<java.nio.ByteBuffer, byte[]> nodeMapping) throws IOException {
        // 1. Temporarily checkout the new parent node to prepare files
        checkoutNode(newParentNode);

        // 2. Write backed up file contents to working directory
        Dirstate dirstate = repository.getDirstate();
        for (Map.Entry<String, byte[]> entry : backup.fileContents.entrySet()) {
            String path = entry.getKey();
            byte[] fileContent = entry.getValue();

            File diskFile = new File(repository.getDirectory(), path);
            diskFile.getParentFile().mkdirs();
            Files.write(diskFile.toPath(), fileContent);

            int mode = diskFile.canExecute() ? 0755 : 0644;
            dirstate.addEntry(path, new Dirstate.Entry('n', mode, fileContent.length, diskFile.lastModified() / 1000));
        }

        dirstate.setParents(newParentNode, new byte[20]);
        repository.writeDirstate(dirstate);

        CommitCommand commitCmd = new CommitCommand(repository)
                .setAuthor(backup.author)
                .setMessage("[rebase] " + backup.message)
                .setDate(backup.time, backup.offsetSeconds)
                .setSkipLockAndJournal(true);

        // Keep original branch if any
        if (backup.branch != null && !backup.branch.isEmpty() && !"default".equals(backup.branch)) {
            repository.setBranch(backup.branch);
        } else {
            repository.setBranch("default");
        }

        return commitCmd.call();
    }

    private byte[] restoreBackup(BackupCommit backup, byte[] p1Node, byte[] p2Node, Map<java.nio.ByteBuffer, byte[]> nodeMapping) throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");

        Revlog changelog = repository.getRevlog(clIdx, clDat);
        Revlog manifest = repository.getManifestRevlog();

        // 1. Restore filelogs physically
        for (Map.Entry<String, FileBackupInfo> entry : backup.fileBackups.entrySet()) {
            String path = entry.getKey();
            FileBackupInfo fb = entry.getValue();

            File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
            File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
            flIdx.getParentFile().mkdirs();
            Revlog filelog = repository.getRevlog(flIdx, flDat);

            if (filelog.findRevision(fb.node) == -1) {
                int p1FileRev = filelog.findRevision(fb.p1Node);
                int p2FileRev = filelog.findRevision(fb.p2Node);
                int linkRev = changelog.getRevisionCount(); // Restored changelog link index mapping
                
                filelog.appendRawRevision(fb.rawContent, fb.node, p1FileRev, p2FileRev, fb.p1Node, fb.p2Node, linkRev);
            }
        }

        // 2. Restore Manifest physically
        int p1MfRev = -1;
        byte[] p1MfNode = new byte[20];
        if (backup.parent1Node != null && !Arrays.equals(backup.parent1Node, new byte[20])) {
            int p1CommitRev = changelog.findRevision(backup.parent1Node);
            if (p1CommitRev != -1) {
                byte[] clContent = changelog.getRevisionContent(p1CommitRev);
                String clText = new String(clContent, StandardCharsets.UTF_8);
                p1MfNode = NodeIdUtil.fromHex(clText.split("\n")[0].trim().substring(0, 40));
                p1MfRev = manifest.findRevision(p1MfNode);
            }
        }

        int p2MfRev = -1;
        byte[] p2MfNode = new byte[20];
        if (backup.parent2Node != null && !Arrays.equals(backup.parent2Node, new byte[20])) {
            int p2CommitRev = changelog.findRevision(backup.parent2Node);
            if (p2CommitRev != -1) {
                byte[] clContent = changelog.getRevisionContent(p2CommitRev);
                String clText = new String(clContent, StandardCharsets.UTF_8);
                p2MfNode = NodeIdUtil.fromHex(clText.split("\n")[0].trim().substring(0, 40));
                p2MfRev = manifest.findRevision(p2MfNode);
            }
        }

        byte[] mappedP1 = nodeMapping.get(java.nio.ByteBuffer.wrap(Arrays.copyOf(backup.parent1Node, 20)));
        if (mappedP1 != null) {
            int rebasedP1Rev = changelog.findRevision(mappedP1);
            if (rebasedP1Rev != -1) {
                byte[] clContent = changelog.getRevisionContent(rebasedP1Rev);
                String clText = new String(clContent, StandardCharsets.UTF_8);
                p1MfNode = NodeIdUtil.fromHex(clText.split("\n")[0].trim().substring(0, 40));
                p1MfRev = manifest.findRevision(p1MfNode);
            }
        }
        byte[] mappedP2 = nodeMapping.get(java.nio.ByteBuffer.wrap(Arrays.copyOf(backup.parent2Node, 20)));
        if (mappedP2 != null) {
            int rebasedP2Rev = changelog.findRevision(mappedP2);
            if (rebasedP2Rev != -1) {
                byte[] clContent = changelog.getRevisionContent(rebasedP2Rev);
                String clText = new String(clContent, StandardCharsets.UTF_8);
                p2MfNode = NodeIdUtil.fromHex(clText.split("\n")[0].trim().substring(0, 40));
                p2MfRev = manifest.findRevision(p2MfNode);
            }
        }

        manifest.appendRawRevision(backup.rawManifestContent, backup.manifestNode, p1MfRev, p2MfRev,
                p1MfNode, p2MfNode, changelog.getRevisionCount());

        // 3. Restore Changelog physically preserving identical nodeId
        int parent1ChangelogRev = changelog.findRevision(p1Node);
        int parent2ChangelogRev = changelog.findRevision(p2Node);

        changelog.appendRawRevision(backup.rawChangelogContent, backup.originalNode, parent1ChangelogRev, parent2ChangelogRev,
                p1Node, p2Node, changelog.getRevisionCount());

        return backup.originalNode;
    }

    private void applyManifestToWorkingCopy(byte[] manifestNode) throws IOException {
        Revlog manifest = repository.getManifestRevlog();

        int mfRev = NodeIdUtil.findRevisionByNodeId(manifest, manifestNode);
        if (mfRev == -1) {
            throw new org.hg4j.errors.HgRevisionNotFoundException("Manifest revision not found for node: " + NodeIdUtil.toHex(manifestNode));
        }

        Map<String, String> entries = new HashMap<>();
        org.hg4j.treewalk.ManifestWalk mw = new org.hg4j.treewalk.ManifestWalk(repository, manifestNode);
        while (mw.next()) {
            org.hg4j.treewalk.ManifestWalk.Entry entry = mw.getEntry();
            String flag = entry.isExecutable() ? "x" : (entry.isSymlink() ? "l" : "");
            entries.put(entry.getPath(), entry.getNodeIdHex() + flag);
        }

        Dirstate dirstate = repository.getDirstate();

        for (Map.Entry<String, String> entry : entries.entrySet()) {
            String path = entry.getKey();
            String nodeWithFlags = entry.getValue();
            String hexNode = nodeWithFlags.substring(0, 40);
            String flags = nodeWithFlags.length() > 40 ? nodeWithFlags.substring(40) : "";

            File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
            File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
            Revlog filelog = repository.getRevlog(flIdx, flDat);

            int fileRev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(hexNode));
            byte[] fileContent = filelog.getRevisionContent(fileRev);

            File diskFile = new File(repository.getDirectory(), path);
            diskFile.getParentFile().mkdirs();
            if (diskFile.exists() || Files.isSymbolicLink(diskFile.toPath())) {
                Files.delete(diskFile.toPath());
            }

            int mode = 0644;
            if (flags.contains("l")) {
                mode = 0120000;
                String target = new String(fileContent, StandardCharsets.UTF_8).trim();
                try {
                    Files.createSymbolicLink(diskFile.toPath(), java.nio.file.Path.of(target));
                } catch (Exception e) {
                    Files.write(diskFile.toPath(), fileContent);
                }
            } else {
                Files.write(diskFile.toPath(), fileContent);
                if (flags.contains("x")) {
                    diskFile.setExecutable(true, false);
                    mode = 0755;
                } else {
                    diskFile.setExecutable(false, false);
                    mode = 0644;
                }
            }

            dirstate.addEntry(path, new Dirstate.Entry('n', mode, fileContent.length, diskFile.lastModified() / 1000));
        }

        repository.writeDirstate(dirstate);
    }

    private void checkoutNode(byte[] node) throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        int rev = NodeIdUtil.findRevisionByNodeId(changelog, node);
        byte[] clContent = changelog.getRevisionContent(rev);
        String clText = new String(clContent, StandardCharsets.UTF_8);
        String firstLine = clText.split("\n")[0];
        byte[] mfNode = NodeIdUtil.fromHex(firstLine.trim().substring(0, 40));

        applyManifestToWorkingCopy(mfNode);

        Dirstate dirstate = repository.getDirstate();
        dirstate.setParents(node, new byte[20]);
        repository.writeDirstate(dirstate);
    }

    private void writeRebaseJournal(Map<File, File> backupMapping) throws IOException {
        File journalFile = new File(repository.getStoreDir(), "journal");
        Files.deleteIfExists(journalFile.toPath());
        
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<File, File> entry : backupMapping.entrySet()) {
            File origFile = entry.getKey();
            File backupFile = entry.getValue();
            
            String origRel = repository.getHgDir().toPath().relativize(origFile.toPath()).toString().replace('\\', '/');
            String backupRel = repository.getHgDir().toPath().relativize(backupFile.toPath()).toString().replace('\\', '/');
            
            sb.append("backup ").append(origRel).append("\t").append(backupRel).append("\n");
        }
        Files.writeString(journalFile.toPath(), sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
        try (java.nio.channels.FileChannel fc = java.nio.channels.FileChannel.open(journalFile.toPath(), java.nio.file.StandardOpenOption.WRITE)) {
            fc.force(true);
        }
    }

    private void deleteRebaseJournal() throws IOException {
        File journalFile = new File(repository.getStoreDir(), "journal");
        Files.deleteIfExists(journalFile.toPath());
    }

    private void performPhysicalRollback(Map<File, File> backupMapping, File backupDir) {
        for (Map.Entry<File, File> entry : backupMapping.entrySet()) {
            File originalFile = entry.getKey();
            File backupCopy = entry.getValue();
            if (backupCopy.exists()) {
                try {
                    originalFile.getParentFile().mkdirs();
                    Files.copy(backupCopy.toPath(), originalFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ignored) {}
            } else {
                try {
                    Files.deleteIfExists(originalFile.toPath());
                } catch (Exception ignored) {}
            }
        }
        try {
            deleteRebaseJournal();
        } catch (Exception ignored) {}
        deleteDirRecursively(backupDir);
        repository.clearRevlogCache();
    }
}
