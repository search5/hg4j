package org.hg4j.api;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.hg4j.core.Dirstate;
import org.hg4j.core.HgLock;
import org.hg4j.core.HgRepository;
import org.hg4j.core.Revlog;
import org.hg4j.core.NodeIdUtil;
import org.hg4j.core.SafeFileIO;

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

    public byte[] call() throws IOException {
        if (message == null || message.isEmpty()) {
            throw new IllegalStateException("Commit message must be specified.");
        }

        Map<File, Long> fileSizes = new HashMap<>();
        File dirstateFile = new File(repository.getDirectory(), ".hg/dirstate");
        byte[] dirstateBackup = dirstateFile.exists() ? Files.readAllBytes(dirstateFile.toPath()) : null;
        File fncacheFile = new File(repository.getStoreDir(), "fncache");
        byte[] fncacheBackup = fncacheFile.exists() ? Files.readAllBytes(fncacheFile.toPath()) : null;
        File journalFile = new File(repository.getStoreDir(), "journal");

        try (HgLock wlock = skipLockAndJournal ? HgLock.noOp() : repository.lockWorkingCopy();
             HgLock storeLock = skipLockAndJournal ? HgLock.noOp() : repository.lockStore()) {

            if (!skipLockAndJournal) {
                // Create physical journal and backups for Crash Resilience
                Files.deleteIfExists(journalFile.toPath());
                
                if (dirstateFile.exists()) {
                    File dirstateBackupFile = new File(repository.getDirectory(), ".hg/dirstate.backup");
                    Files.copy(dirstateFile.toPath(), dirstateBackupFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    appendToJournal(journalFile, "dirstate");
                }
                if (fncacheFile.exists()) {
                    File fncacheBackupFile = new File(repository.getStoreDir(), "fncache.backup");
                    Files.copy(fncacheFile.toPath(), fncacheBackupFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
                // 경로는 .hg/ 기준 상대 경로 (실제 hg journal 포맷)
                appendToJournal(journalFile, "store/00changelog.i " + clIdxLen);
                appendToJournal(journalFile, "store/00changelog.d " + clDatLen);
                appendToJournal(journalFile, "store/00manifest.i " + mfIdxLen);
                appendToJournal(journalFile, "store/00manifest.d " + mfDatLen);
            }

            Dirstate dirstate = repository.getDirstate();
            org.hg4j.lib.NodeId p1CommitNode = dirstate.getParent1Node();
            org.hg4j.lib.NodeId p2CommitNode = dirstate.getParent2Node();

            // 1. Load changelog and find parent commit rev index
            Revlog changelog = repository.getRevlog(clIdx, clDat);

            int parent1Rev = -1;
            if (p1CommitNode != null && !p1CommitNode.isNull()) {
                parent1Rev = NodeIdUtil.findRevisionByNodeId(changelog, p1CommitNode.getBytes());
                if (parent1Rev == -1) {
                    throw new org.hg4j.errors.HgRevisionNotFoundException(p1CommitNode.toHex());
                }
            }

            int parent2Rev = -1;
            if (p2CommitNode != null && !p2CommitNode.isNull()) {
                parent2Rev = NodeIdUtil.findRevisionByNodeId(changelog, p2CommitNode.getBytes());
                if (parent2Rev == -1) {
                    throw new org.hg4j.errors.HgRevisionNotFoundException(p2CommitNode.toHex());
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
                java.util.Map<String, String> mf1 = repository.getManifestAtCommit(p1CommitNodeBytes);
                manifestP1.putAll(mf1);
                // manifest node ID를 index에서 추출
                byte[] clContent = changelog.getRevisionContent(parent1Rev);
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
                        String hexNode = new String(clContent, 0, 40, java.nio.charset.StandardCharsets.UTF_8);
                        mfNode = NodeIdUtil.fromHex(hexNode);
                    }
                }
                if (mfNode == null) {
                    if (clContent.length >= 20) {
                        mfNode = new byte[20];
                        System.arraycopy(clContent, 0, mfNode, 0, 20);
                    }
                }
                if (mfNode != null) {
                    p1ManifestNode = mfNode;
                    parent1ManifestRev = NodeIdUtil.findRevisionByNodeId(manifestRevlog, p1ManifestNode);
                }
                LOGGER.log(Level.FINE, "[DEBUG MERGE] parent1Rev={0}, p1ManifestNode={1}, parent1ManifestRev={2}", new Object[]{parent1Rev, (p1ManifestNode != null ? NodeIdUtil.toHex(p1ManifestNode) : "null"), parent1ManifestRev});
            }

            int parent2ManifestRev = -1;
            if (parent2Rev != -1 && p2CommitNode != null && !p2CommitNode.isNull()) {
                byte[] p2CommitNodeBytes = p2CommitNode.getBytes();
                java.util.Map<String, String> mf2 = repository.getManifestAtCommit(p2CommitNodeBytes);
                manifestP2.putAll(mf2);
                byte[] clContent = changelog.getRevisionContent(parent2Rev);
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
                        String hexNode = new String(clContent, 0, 40, java.nio.charset.StandardCharsets.UTF_8);
                        mfNode = NodeIdUtil.fromHex(hexNode);
                    }
                }
                if (mfNode == null) {
                    if (clContent.length >= 20) {
                        mfNode = new byte[20];
                        System.arraycopy(clContent, 0, mfNode, 0, 20);
                    }
                }
                if (mfNode != null) {
                    p2ManifestNode = mfNode;
                    parent2ManifestRev = NodeIdUtil.findRevisionByNodeId(manifestRevlog, p2ManifestNode);
                }
                LOGGER.log(Level.FINE, "[DEBUG MERGE] parent2Rev={0}, p2CommitNode={1}, p2ManifestNode={2}, parent2ManifestRev={3}", new Object[]{parent2Rev, p2CommitNode.toHex(), (p2ManifestNode != null ? NodeIdUtil.toHex(p2ManifestNode) : "null"), parent2ManifestRev});
            }

            // 3. Process dirstate entries and write filelogs
            // M-2: racy-hg 조건 판정을 위해 트랜잭션 시작 시각 기록 (epoch seconds)
            final long txStartSec = System.currentTimeMillis() / 1000;
            Map<String, String> newManifest = new TreeMap<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
            List<String> filesModified = new ArrayList<>();
            Set<String> fncachePaths = new LinkedHashSet<>();

            // Load existing fncache if any
            if (fncacheFile.exists()) {
                fncachePaths.addAll(Files.readAllLines(fncacheFile.toPath()));
            }

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

            org.hg4j.treewalk.TreeWalk tw = new org.hg4j.treewalk.TreeWalk();
            tw.addTree(new org.hg4j.treewalk.ManifestTreeIterator(repository, String.valueOf(parent1Rev))); // Tree 0: P1
            tw.addTree(new org.hg4j.treewalk.ManifestTreeIterator(repository, String.valueOf(parent2Rev))); // Tree 1: P2
            tw.addTree(new org.hg4j.treewalk.WorkingDirTreeIterator(repository));                           // Tree 2: Working Copy

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
                    } else if (workingState == 'a' || workingState == 'm' || workingState == 'n') {
                        File diskFile = new File(repository.getDirectory(), path);
                        if (!diskFile.exists() || !diskFile.isFile()) {
                            throw new org.hg4j.errors.HgValidationException("Tracked file not found on disk: " + path);
                        }

                        // Large file protection check (2GB Limit Truncation Safeguard)
                        long diskSize = diskFile.length();
                        if (diskSize > Integer.MAX_VALUE) {
                            throw new org.hg4j.errors.HgValidationException("File size exceeds 2GB maximum limit allowed for Dirstate: " + path);
                        }

                        // Check if the file has actually changed compared to the recorded dirstate
                        boolean changed = workingState == 'a' || workingState == 'm';
                        if (workingState == 'n') {
                            long diskTime = diskFile.lastModified() / 1000;
                            Dirstate.Entry dEntry = dirstate.getEntries().get(path);
                            if (dEntry != null) {
                                if (dEntry.getSize() != diskSize || dEntry.getTime() != diskTime) {
                                    // 크기나 mtime이 다르면 명확히 변경됨
                                    changed = true;
                                } else if (diskTime >= txStartSec - 1) {
                                    // M-2: racy-hg 판정 (1초 경계선 허용한계 반영)
                                    File flIdx = getFilelogIndex(repository.getStoreDir(), path);
                                    File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
                                    if (flIdx.exists()) {
                                        Revlog filelog = repository.getRevlog(flIdx, flDat);
                                        if (filelog.getRevisionCount() > 0) {
                                            byte[] fileContent = Files.readAllBytes(diskFile.toPath());
                                            byte[] lastContent = filelog.getRevisionContent(filelog.getRevisionCount() - 1);
                                            if (!Arrays.equals(fileContent, lastContent)) {
                                                changed = true;
                                            }
                                        }
                                    }
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
                                    String storeRelIdx = "store/" + NodeIdUtil.encodeFname(path) + ".i";
                                    appendToJournal(journalFile, storeRelIdx + " " + idxLen);
                                }
                            }
                            if (!fileSizes.containsKey(flDat)) {
                                long datLen = flDat.exists() ? flDat.length() : 0L;
                                fileSizes.put(flDat, datLen);
                                if (!skipLockAndJournal) {
                                    String storeRelDat = "store/" + NodeIdUtil.encodeFname(path) + ".d";
                                    appendToJournal(journalFile, storeRelDat + " " + datLen);
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

                            // Copy-Rename Track "Writer" 통합
                            String originalPath = dirstate.getCopyMap().get(path);
                            byte[] finalPayload;

                            if (originalPath != null) {
                                String hexP1 = NodeIdUtil.toHex(p1FileNode);
                                String metaText = "\u0001\ncopy: " + originalPath + "\ncopyrev: " + hexP1 + "\n\u0001";
                                
                                byte[] metaBytes = metaText.getBytes(StandardCharsets.UTF_8);
                                finalPayload = new byte[metaBytes.length + fileContent.length];
                                System.arraycopy(metaBytes, 0, finalPayload, 0, metaBytes.length);
                                System.arraycopy(fileContent, 0, finalPayload, metaBytes.length, fileContent.length);
                            } else {
                                finalPayload = fileContent;
                            }

                            byte[] newFileNode = filelog.appendRevision(finalPayload, parent1FileRev, parent2FileRev, p1FileNode, p2FileNode, newCommitRev);
                            
                            // Capture execution flag and symlink flag for serialization
                            String flag = "";
                            if (Files.isSymbolicLink(diskFile.toPath())) {
                                flag = "l";
                            } else if (diskFile.canExecute()) {
                                flag = "x";
                            }

                            newManifest.put(path, NodeIdUtil.toHex(newFileNode) + flag);
                            filesModified.add(path);

                            // fncache에는 .i 파일 경로만 등록
                            String rawPath = "data/" + path.replace('\\', '/');
                            fncachePaths.add(rawPath + ".i");
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

            // 4. Serialize and write new manifest revision
            StringBuilder manifestSb = new StringBuilder();
            for (Map.Entry<String, String> entry : newManifest.entrySet()) {
                manifestSb.append(entry.getKey()).append('\0').append(entry.getValue()).append('\n');
            }
            byte[] manifestTextBytes = manifestSb.toString().getBytes(StandardCharsets.UTF_8);
            byte[] manifestNode = manifestRevlog.appendRevision(manifestTextBytes, parent1ManifestRev, parent2ManifestRev, p1ManifestNode, p2ManifestNode, newCommitRev);

            // 5. Serialize and write new changelog (commit) revision
            StringBuilder clSb = new StringBuilder();
            clSb.append(NodeIdUtil.toHex(manifestNode)).append('\n');
            clSb.append(author).append('\n');
            long secs = forcedTime != null ? forcedTime : System.currentTimeMillis() / 1000;
            int offsetSeconds = forcedOffset != null ? forcedOffset : -java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000;
            clSb.append(secs).append(" ").append(offsetSeconds);
            String branchName = repository.getBranch();
            if (branchName == null || branchName.isEmpty()) {
                branchName = "default";
            }
            clSb.append(" ").append("branch:").append(encodeExtraKey(branchName));
            clSb.append('\n');
            java.util.Collections.sort(filesModified, NodeIdUtil.UTF8_STRING_COMPARATOR);
            for (String path : filesModified) {
                clSb.append(path).append('\n');
            }
            clSb.append('\n'); // empty line separator
            clSb.append(message);
            byte[] changelogTextBytes = clSb.toString().getBytes(StandardCharsets.UTF_8);

            byte[] p1CommitNodeHash = p1CommitNode != null ? p1CommitNode.getBytes() : new byte[20];
            byte[] p2CommitNodeHash = p2CommitNode != null ? p2CommitNode.getBytes() : new byte[20];

            byte[] commitNode = changelog.appendRevision(changelogTextBytes, parent1Rev, parent2Rev, p1CommitNodeHash, p2CommitNodeHash, newCommitRev);

            // 6. Update and save Dirstate
            dirstate.setParents(new org.hg4j.lib.NodeId(commitNode), org.hg4j.lib.NodeId.NULL);
            
            List<String> pathsToChange = new ArrayList<>(dirstate.getEntries().keySet());
            for (String path : pathsToChange) {
                Dirstate.Entry entry = dirstate.getEntries().get(path);
                if (entry == null) continue;
                
                if (entry.getState() == 'r') {
                    dirstate.removeEntry(path);
                } else if (entry.getState() == 'a' || entry.getState() == 'm' || filesModified.contains(path)) {
                    File diskFile = new File(repository.getDirectory(), path);
                    int mode = diskFile.canExecute() ? 0755 : 0644;
                    int size = (int) diskFile.length();
                    long time = diskFile.lastModified() / 1000;
                    dirstate.addEntry(path, new Dirstate.Entry('n', mode, size, time));
                }
            }
            repository.writeDirstate(dirstate);

            // 6.5. Update Phase for the new commit (all new commits default to DRAFT)
            try {
                org.hg4j.core.PhaseRoots phaseRoots = repository.getPhaseRoots();
                phaseRoots.setPhase(new org.hg4j.lib.NodeId(commitNode), org.hg4j.core.PhaseRoots.Phase.DRAFT, changelog);
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
                    }
                } else {
                    try (FileChannel outChan = FileChannel.open(file.toPath(), StandardOpenOption.WRITE)) {
                        outChan.truncate(origSize);
                        outChan.force(true);
                    } catch (Exception ignored) {
                        LOGGER.log(Level.WARNING, "Failed to truncate file during rollback: " + file, ignored);
                    }
                }
            }
            
            // Restore fncache atomically (N-1 Rollback Refinement)
            if (fncacheBackup != null) {
                try {
                    SafeFileIO.writeAtomic(fncacheFile, fncacheBackup);
                } catch (Exception ignored) {
                    LOGGER.log(Level.WARNING, "Failed to restore fncache backup during rollback", ignored);
                }
            } else {
                try {
                    Files.deleteIfExists(fncacheFile.toPath());
                } catch (Exception ignored) {
                    LOGGER.log(Level.WARNING, "Failed to delete fncache during rollback", ignored);
                }
            }
 
            // Restore dirstate atomically (N-1 Rollback Refinement)
            if (dirstateBackup != null) {
                try {
                    SafeFileIO.writeAtomic(dirstateFile, dirstateBackup);
                } catch (Exception ignored) {
                    LOGGER.log(Level.WARNING, "Failed to restore dirstate backup during rollback", ignored);
                }
            } else {
                try {
                    Files.deleteIfExists(dirstateFile.toPath());
                } catch (Exception ignored) {
                    LOGGER.log(Level.WARNING, "Failed to delete dirstate during rollback", ignored);
                }
            }
            // Cleanup journal and backup files on failure after restore
            try {
                Files.deleteIfExists(journalFile.toPath());
                Files.deleteIfExists(new File(repository.getDirectory(), ".hg/dirstate.backup").toPath());
                Files.deleteIfExists(new File(repository.getStoreDir(), "fncache.backup").toPath());
            } catch (Exception ignored) {
                LOGGER.log(Level.WARNING, "Failed to clean up journal/backups after rollback", ignored);
            }
            repository.clearRevlogCache();
            throw t;
        }
    }

    private byte[] getFileRevisionContent(org.hg4j.core.HgRepository repository, String path, String nodeHex) throws IOException {
        File flIdx = getFilelogIndex(repository.getStoreDir(), path);
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        if (!flIdx.exists()) {
            throw new org.hg4j.errors.HgRepositoryNotFoundException("Filelog index does not exist for: " + path);
        }
        Revlog filelog = repository.getRevlog(flIdx, flDat);
        int rev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(nodeHex.substring(0, 40)));
        if (rev == -1) {
            throw new org.hg4j.errors.HgRevisionNotFoundException("File revision not found: " + path + " @ " + nodeHex);
        }
        return filelog.getRevisionContent(rev);
    }



    public static File getFilelogIndex(File storeDir, String relPath) {
        String encoded = NodeIdUtil.encodeFname(relPath);
        return new File(storeDir, encoded + ".i");
    }

    public static int findUnescapedColon(String s) {
        if (s == null) return -1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == ':') {
                int backslashCount = 0;
                int j = i - 1;
                while (j >= 0 && s.charAt(j) == '\\') {
                    backslashCount++;
                    j--;
                }
                if (backslashCount % 2 == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    public static String encodeExtraKey(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace(":", "\\:").replace("\n", "\\n").replace("\r", "\\r").replace("\0", "\\0");
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
                } else if (next == ':') {
                    sb.append(':');
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

    private void appendToJournal(File journalFile, String entry) throws IOException {
        Files.writeString(journalFile.toPath(), entry + "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        try (java.nio.channels.FileChannel fc = java.nio.channels.FileChannel.open(journalFile.toPath(), java.nio.file.StandardOpenOption.WRITE)) {
            fc.force(true);
        }
    }
}
