package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.util.SafeFileIO;
import com.github.search5.hg4j.errors.HgLockException;
import com.github.search5.hg4j.lib.NodeId;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.github.search5.hg4j.bundle.ChangegroupParser;
import com.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import com.github.search5.hg4j.errors.HgRevisionNotFoundException;
import com.github.search5.hg4j.lib.HgLock;
import com.github.search5.hg4j.obsolete.HgObsMarker;
import java.nio.channels.FileChannel;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;

/**
 * Porcelain command for Interactive Rebase (histedit) on Mercurial repositories.
 * Supports rule-based PICK, DROP, FOLD, and ROLL actions to modify history.
 */
public class HisteditCommand implements AutoCloseable {
    public enum Action { PICK, DROP, FOLD, ROLL }

    public static class Rule {
        public final Action action;
        public final String hexNode;

        public Rule(Action action, String hexNode) {
            this.action = action;
            this.hexNode = hexNode;
        }
    }

    private final HgRepository repository;
    private final List<Rule> rules = new ArrayList<>();

    public HisteditCommand(HgRepository repository) {
        this.repository = repository;
    }

    public HisteditCommand addRule(Action action, String hexNode) {
        if (action != null && hexNode != null && !hexNode.isEmpty()) {
            rules.add(new Rule(action, hexNode));
        }
        return this;
    }

    public void call() throws IOException, HgLockException {
        if (rules.isEmpty()) {
            return;
        }

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);
        File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
        File mfDat = new File(repository.getStoreDir(), "00manifest.d");

        // Backup current repository status for rollback in case of error
        Dirstate dirstate = repository.getDirstate();
        byte[] originalParent = dirstate.getParent1();

        // Crash/failure safety: snapshot every revlog this operation may append to before
        // touching anything, journal them (same scheme as CommitCommand/StripCommand), and
        // back up dirstate — so a mid-histedit failure (this method's own catch block, or a
        // real crash recovered via HgRepository.checkAndPerformAutoRollback()) truncates
        // everything back to its pre-histedit state instead of leaving a half-rewritten
        // history behind.
        Map<File, Long> fileSizes = new LinkedHashMap<>();
        File dirstateFile = new File(repository.getDirectory(), ".hg/dirstate");
        byte[] dirstateBackup = dirstateFile.exists() ? Files.readAllBytes(dirstateFile.toPath()) : null;
        File journalFile = new File(repository.getStoreDir(), "journal");
        File dirstateBackupFile = new File(repository.getDirectory(), ".hg/dirstate.backup");

        try (HgLock storeLock = repository.lockStore();
             HgLock wlock = repository.lockWorkingCopy()) {

            Files.deleteIfExists(journalFile.toPath());
            if (dirstateFile.exists()) {
                Files.copy(dirstateFile.toPath(), dirstateBackupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                appendToJournal(journalFile, "dirstate");
            }
            recordAndJournal(clIdx, fileSizes, journalFile);
            recordAndJournal(clDat, fileSizes, journalFile);
            recordAndJournal(mfIdx, fileSizes, journalFile);
            recordAndJournal(mfDat, fileSizes, journalFile);

            // To support folding/merging revisions, we will parse the target revisions content
            // and perform clean recommits according to actions.
            byte[] lastCommittedNode = originalParent;
            String pendingCommitMsg = null;
            String lastCommitHex = null;
            String lastAuthor = "histedit";
            
            for (Rule rule : rules) {
                byte[] nodeBytes = NodeIdUtil.fromHex(rule.hexNode);
                int rev = changelog.findRevision(nodeBytes);
                if (rev == -1) {
                    throw new IOException("Histedit failed: Revision not found for node " + rule.hexNode);
                }

                byte[] clContent = changelog.getRevisionContent(rev);
                String clText = new String(clContent, StandardCharsets.UTF_8);
                String[] clLines = clText.split("\n");
                
                // Parse commit message
                String author = "unknown";
                if (clLines.length > 1) {
                    author = clLines[1].trim();
                }
                StringBuilder msgBuilder = new StringBuilder();
                int msgStartIdx = -1;
                for (int i = 3; i < clLines.length; i++) {
                    if (clLines[i].isEmpty()) {
                        msgStartIdx = i + 1;
                        break;
                    }
                }
                if (msgStartIdx != -1) {
                    for (int i = msgStartIdx; i < clLines.length; i++) {
                        if (msgBuilder.length() > 0) msgBuilder.append("\n");
                        msgBuilder.append(clLines[i]);
                    }
                } else {
                    for (int i = 3; i < clLines.length; i++) {
                        if (msgBuilder.length() > 0) msgBuilder.append("\n");
                        msgBuilder.append(clLines[i]);
                    }
                }
                String commitMsg = msgBuilder.toString();

                if (rule.action == Action.PICK) {
                    if (pendingCommitMsg != null) {
                        // Commit folded changes first
                        lastCommittedNode = commitNewRev(lastCommittedNode, lastAuthor, pendingCommitMsg, lastCommitHex, fileSizes, journalFile);
                        pendingCommitMsg = null;
                    }
                    pendingCommitMsg = commitMsg;
                    lastCommitHex = rule.hexNode;
                    lastAuthor = author;
                } else if (rule.action == Action.FOLD) {
                    if (pendingCommitMsg == null) {
                        pendingCommitMsg = commitMsg;
                    } else {
                        pendingCommitMsg = pendingCommitMsg + "\n" + commitMsg;
                    }
                    lastCommitHex = rule.hexNode;
                    lastAuthor = author;
                } else if (rule.action == Action.ROLL) {
                    if (pendingCommitMsg == null) {
                        pendingCommitMsg = commitMsg;
                    }
                    // Drop this commit message, keep the accumulated pending CommitMsg
                    lastCommitHex = rule.hexNode;
                    lastAuthor = author;
                } else if (rule.action == Action.DROP) {
                    // Skip completely
                }
            }

            if (pendingCommitMsg != null) {
                lastCommittedNode = commitNewRev(lastCommittedNode, lastAuthor, pendingCommitMsg, lastCommitHex, fileSizes, journalFile);
            }

            // Sync workspace to the last committed node
            Dirstate d = repository.getDirstate();
            d.setParents(lastCommittedNode, new byte[20]);
            repository.writeDirstate(d);
            repository.clearRevlogCache();

            // Leave the working branch matching the new tip's branch, the same way real
            // hg's checkout after a history-rewrite does.
            int newTipRev = changelog.findRevision(lastCommittedNode);
            if (newTipRev != -1) {
                repository.setBranch(CommitCommand.getBranchOfRevision(changelog, newTipRev));
            }

            try {
                CommitCommand.writeUndoInfo(repository, fileSizes, dirstateBackup);
            } catch (Exception e) {
                // non-blocking, same as CommitCommand/StripCommand
            }
            Files.deleteIfExists(journalFile.toPath());
            Files.deleteIfExists(dirstateBackupFile.toPath());
        } catch (Exception e) {
            // Roll every touched revlog back to its pre-histedit size and restore dirstate,
            // same recovery strategy as CommitCommand/StripCommand.
            for (Map.Entry<File, Long> sizeEntry : fileSizes.entrySet()) {
                File file = sizeEntry.getKey();
                long origSize = sizeEntry.getValue();
                if (origSize == 0) {
                    Files.deleteIfExists(file.toPath());
                } else if (file.exists()) {
                    try (FileChannel outChan = FileChannel.open(file.toPath(), StandardOpenOption.WRITE)) {
                        outChan.truncate(origSize);
                        outChan.force(true);
                    }
                }
            }
            if (dirstateBackup != null) {
                SafeFileIO.writeAtomic(dirstateFile, dirstateBackup);
            }
            Files.deleteIfExists(journalFile.toPath());
            Files.deleteIfExists(dirstateBackupFile.toPath());
            repository.clearRevlogCache();
            throw e;
        }
    }

    private void recordAndJournal(File file, Map<File, Long> fileSizes, File journalFile) throws IOException {
        if (fileSizes.containsKey(file)) {
            return;
        }
        long size = file.exists() ? file.length() : 0L;
        fileSizes.put(file, size);
        String relPath = "store/" + repository.getStoreDir().toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
        appendToJournal(journalFile, relPath + "\t" + size);
    }

    private void appendToJournal(File journalFile, String entry) throws IOException {
        Files.writeString(journalFile.toPath(), entry + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try (FileChannel fc = FileChannel.open(journalFile.toPath(), StandardOpenOption.WRITE)) {
            fc.force(true);
        }
    }

    private byte[] commitNewRev(byte[] parent, String author, String message, String originalCommitHex,
                                 Map<File, Long> fileSizes, File journalFile) throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
        File mfDat = new File(repository.getStoreDir(), "00manifest.d");
        Revlog manifestRevlog = repository.getRevlog(mfIdx, mfDat);

        // 1. Get original manifest and changed files
        byte[] origNode = NodeIdUtil.fromHex(originalCommitHex);
        int origRev = changelog.findRevision(origNode);
        if (origRev == -1) {
            throw new IOException("Original commit not found: " + originalCommitHex);
        }
        
        byte[] origClContent = changelog.getRevisionContent(origRev);
        String origClText = new String(origClContent, StandardCharsets.UTF_8);
        String[] origClLines = origClText.split("\n");
        
        // Find files modified in original commit
        List<String> filesModified = new ArrayList<>();
        int msgStartIdx = -1;
        for (int i = 3; i < origClLines.length; i++) {
            if (origClLines[i].isEmpty()) {
                msgStartIdx = i + 1;
                break;
            }
            filesModified.add(origClLines[i]);
        }
        
        // Get original manifest map
        Map<String, String> originalManifest = getManifestForCommit(changelog, manifestRevlog, origNode);
        
        // 2. Get parent manifest map to initialize new manifest
        Map<String, String> newManifest = getManifestForCommit(changelog, manifestRevlog, parent);
        
        // 3. For each modified file, copy the revision from original filelog and commit to new filelog
        for (String path : filesModified) {
            String hexAndFlag = originalManifest.get(path);
            if (hexAndFlag == null) {
                // File deleted in original commit
                newManifest.remove(path);
                continue;
            }
            
            String fileHex = hexAndFlag.substring(0, 40);
            String flag = hexAndFlag.substring(40);
            
            // Get original file content
            byte[] fileContent = getFileRevisionContent(repository, path, fileHex);
            
            // Write content to working directory file to physically update workspace!
            File wFile = new File(repository.getDirectory(), path);
            wFile.getParentFile().mkdirs();
            Files.write(wFile.toPath(), fileContent);
            
            // Commit to new filelog
            File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
            File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
            flIdx.getParentFile().mkdirs();
            recordAndJournal(flIdx, fileSizes, journalFile);
            recordAndJournal(flDat, fileSizes, journalFile);
            Revlog filelog = repository.getRevlog(flIdx, flDat);
            
            int parent1FileRev = -1;
            byte[] p1FileNode = new byte[20];
            String parentFileHexAndFlag = newManifest.get(path);
            if (parentFileHexAndFlag != null) {
                String parentFileHex = parentFileHexAndFlag.substring(0, 40);
                p1FileNode = NodeIdUtil.fromHex(parentFileHex);
                parent1FileRev = NodeIdUtil.findRevisionByNodeId(filelog, p1FileNode);
            }
            
            int newCommitRev = changelog.getRevisionCount();
            byte[] newFileNode = filelog.appendRevision(fileContent, null, parent1FileRev, -1, p1FileNode, new byte[20], newCommitRev);
            
            newManifest.put(path, NodeIdUtil.toHex(newFileNode) + flag);
        }
        
        // 4. Serialize and append new manifest revision
        StringBuilder manifestSb = new StringBuilder();
        for (Map.Entry<String, String> entry : newManifest.entrySet()) {
            manifestSb.append(entry.getKey()).append('\0').append(entry.getValue()).append('\n');
        }
        byte[] manifestTextBytes = manifestSb.toString().getBytes(StandardCharsets.UTF_8);
        
        int parent1ManifestRev = -1;
        byte[] p1ManifestNode = new byte[20];
        if (parent != null && !NodeIdUtil.isAllZero(parent)) {
            int pRev = changelog.findRevision(parent);
            if (pRev != -1) {
                byte[] pContent = changelog.getRevisionContent(pRev);
                String pText = new String(pContent, StandardCharsets.UTF_8);
                String[] pLines = pText.split("\n");
                if (pLines.length > 0) {
                    p1ManifestNode = NodeIdUtil.fromHex(pLines[0].trim());
                    parent1ManifestRev = manifestRevlog.findRevision(p1ManifestNode);
                }
            }
        }
        
        int newCommitRev = changelog.getRevisionCount();
        byte[] manifestNode = manifestRevlog.appendRevision(manifestTextBytes, parent1ManifestRev, -1, p1ManifestNode, new byte[20], newCommitRev);
        
        // 5. Serialize and append new changelog (commit) revision
        StringBuilder clSb = new StringBuilder();
        clSb.append(NodeIdUtil.toHex(manifestNode)).append('\n');
        clSb.append(author).append('\n');
        
        long secs = System.currentTimeMillis() / 1000;
        clSb.append(secs).append(" 0");
        // Preserve the branch the original (rewritten) commit was on — real hg does not
        // write this extra field for the default branch, and never re-branches a picked
        // commit onto whatever branch happens to be active during the histedit.
        String branchName = CommitCommand.getBranchOfRevision(changelog, origRev);
        if (branchName != null && !branchName.isEmpty() && !"default".equals(branchName)) {
            clSb.append(" ").append("branch:").append(CommitCommand.encodeExtraKey(branchName));
        }
        clSb.append("\n");
        
        Collections.sort(filesModified, NodeIdUtil.UTF8_STRING_COMPARATOR);
        for (String path : filesModified) {
            clSb.append(path).append('\n');
        }
        clSb.append('\n'); // empty line separator
        clSb.append(message);
        
        byte[] changelogTextBytes = clSb.toString().getBytes(StandardCharsets.UTF_8);
        
        byte[] p1Normalized = new byte[20];
        if (parent != null) {
            System.arraycopy(parent, 0, p1Normalized, 0, Math.min(parent.length, 20));
        }
        
        ChangegroupParser.ChangeGroupEntry entry = new ChangegroupParser.ChangeGroupEntry();
        entry.node = NodeIdUtil.computeNodeId(changelogTextBytes, p1Normalized, new byte[20]);
        byte[] entryNode20 = new byte[20];
        System.arraycopy(entry.node, 0, entryNode20, 0, 20);
        entry.node = entryNode20;
        entry.p1 = p1Normalized;
        entry.p2 = new byte[20];
        entry.cs = entry.node;
        entry.deltabase = new byte[20];
        entry.delta = Revlog.createDelta(new byte[0], changelogTextBytes);
        
        changelog.appendChangeGroupEntry(entry, newCommitRev);

        // Register obsolescence marker linking original commit to histedited commit
        try {
            HgObsMarker.writeMarker(repository.getStoreDir(), origNode, List.of(entry.node), "histedit");
        } catch (Exception e) {
            // non-blocking
        }

        return entry.node;
    }

    private Map<String, String> getManifestForCommit(Revlog changelog, Revlog manifestRevlog, byte[] commitNode) throws IOException {
        Map<String, String> manifestMap = new LinkedHashMap<>();
        if (commitNode == null || NodeIdUtil.isAllZero(commitNode)) {
            return manifestMap;
        }
        int rev = changelog.findRevision(commitNode);
        if (rev == -1) {
            return manifestMap;
        }
        byte[] content = changelog.getRevisionContent(rev);
        String text = new String(content, StandardCharsets.UTF_8);
        String[] lines = text.split("\n");
        if (lines.length == 0) return manifestMap;
        
        String manifestHex = lines[0].trim();
        byte[] manifestNode = NodeIdUtil.fromHex(manifestHex);
        int mRev = manifestRevlog.findRevision(manifestNode);
        if (mRev != -1) {
            byte[] mContent = manifestRevlog.getRevisionContent(mRev);
            String mText = new String(mContent, StandardCharsets.UTF_8);
            for (String line : mText.split("\n")) {
                if (line.isEmpty()) continue;
                int nullIdx = line.indexOf('\0');
                if (nullIdx != -1) {
                    manifestMap.put(line.substring(0, nullIdx), line.substring(nullIdx + 1));
                }
            }
        }
        return manifestMap;
    }

    private byte[] getFileRevisionContent(HgRepository repository, String path, String nodeHex) throws IOException {
        File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
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

    @Override
    public void close() {
        // No persistent resources
    }
}
