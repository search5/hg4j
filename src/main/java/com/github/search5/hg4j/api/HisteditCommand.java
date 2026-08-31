package com.github.search5.hg4j.api;

import com.github.search5.hg4j.core.HgRepository;
import com.github.search5.hg4j.core.Revlog;
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

        // Backup current repository status for rollback in case of error
        Dirstate dirstate = repository.getDirstate();
        byte[] originalParent = dirstate.getParent1();

        try (com.github.search5.hg4j.core.HgLock storeLock = repository.lockStore();
             com.github.search5.hg4j.core.HgLock wlock = repository.lockWorkingCopy()) {

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
                        lastCommittedNode = commitNewRev(lastCommittedNode, lastAuthor, pendingCommitMsg, lastCommitHex);
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
                lastCommittedNode = commitNewRev(lastCommittedNode, lastAuthor, pendingCommitMsg, lastCommitHex);
            }

            // Sync workspace to the last committed node
            Dirstate d = repository.getDirstate();
            d.setParents(lastCommittedNode, new byte[20]);
            repository.writeDirstate(d);
            repository.clearRevlogCache();
        }
    }

    private byte[] commitNewRev(byte[] parent, String author, String message, String originalCommitHex) throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
        File mfDat = new File(repository.getStoreDir(), "00manifest.d");
        Revlog manifestRevlog = repository.getRevlog(mfIdx, mfDat);

        // 1. Get original manifest and changed files
        byte[] origNode = com.github.search5.hg4j.util.NodeIdUtil.fromHex(originalCommitHex);
        int origRev = changelog.findRevision(origNode);
        if (origRev == -1) {
            throw new IOException("Original commit not found: " + originalCommitHex);
        }
        
        byte[] origClContent = changelog.getRevisionContent(origRev);
        String origClText = new String(origClContent, StandardCharsets.UTF_8);
        String[] origClLines = origClText.split("\n");
        
        // Find files modified in original commit
        java.util.List<String> filesModified = new java.util.ArrayList<>();
        int msgStartIdx = -1;
        for (int i = 3; i < origClLines.length; i++) {
            if (origClLines[i].isEmpty()) {
                msgStartIdx = i + 1;
                break;
            }
            filesModified.add(origClLines[i]);
        }
        
        // Get original manifest map
        java.util.Map<String, String> originalManifest = getManifestForCommit(changelog, manifestRevlog, origNode);
        
        // 2. Get parent manifest map to initialize new manifest
        java.util.Map<String, String> newManifest = getManifestForCommit(changelog, manifestRevlog, parent);
        
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
            java.nio.file.Files.write(wFile.toPath(), fileContent);
            
            // Commit to new filelog
            File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
            File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
            flIdx.getParentFile().mkdirs();
            Revlog filelog = repository.getRevlog(flIdx, flDat);
            
            int parent1FileRev = -1;
            byte[] p1FileNode = new byte[20];
            String parentFileHexAndFlag = newManifest.get(path);
            if (parentFileHexAndFlag != null) {
                String parentFileHex = parentFileHexAndFlag.substring(0, 40);
                p1FileNode = com.github.search5.hg4j.util.NodeIdUtil.fromHex(parentFileHex);
                parent1FileRev = com.github.search5.hg4j.util.NodeIdUtil.findRevisionByNodeId(filelog, p1FileNode);
            }
            
            int newCommitRev = changelog.getRevisionCount();
            byte[] newFileNode = filelog.appendRevision(fileContent, null, parent1FileRev, -1, p1FileNode, new byte[20], newCommitRev);
            
            newManifest.put(path, com.github.search5.hg4j.util.NodeIdUtil.toHex(newFileNode) + flag);
        }
        
        // 4. Serialize and append new manifest revision
        StringBuilder manifestSb = new StringBuilder();
        for (java.util.Map.Entry<String, String> entry : newManifest.entrySet()) {
            manifestSb.append(entry.getKey()).append('\0').append(entry.getValue()).append('\n');
        }
        byte[] manifestTextBytes = manifestSb.toString().getBytes(StandardCharsets.UTF_8);
        
        int parent1ManifestRev = -1;
        byte[] p1ManifestNode = new byte[20];
        if (parent != null && !com.github.search5.hg4j.util.NodeIdUtil.isAllZero(parent)) {
            int pRev = changelog.findRevision(parent);
            if (pRev != -1) {
                byte[] pContent = changelog.getRevisionContent(pRev);
                String pText = new String(pContent, StandardCharsets.UTF_8);
                String[] pLines = pText.split("\n");
                if (pLines.length > 0) {
                    p1ManifestNode = com.github.search5.hg4j.util.NodeIdUtil.fromHex(pLines[0].trim());
                    parent1ManifestRev = manifestRevlog.findRevision(p1ManifestNode);
                }
            }
        }
        
        int newCommitRev = changelog.getRevisionCount();
        byte[] manifestNode = manifestRevlog.appendRevision(manifestTextBytes, parent1ManifestRev, -1, p1ManifestNode, new byte[20], newCommitRev);
        
        // 5. Serialize and append new changelog (commit) revision
        StringBuilder clSb = new StringBuilder();
        clSb.append(com.github.search5.hg4j.util.NodeIdUtil.toHex(manifestNode)).append('\n');
        clSb.append(author).append('\n');
        
        long secs = System.currentTimeMillis() / 1000;
        clSb.append(secs).append(" 0 branch:default\n");
        
        java.util.Collections.sort(filesModified, com.github.search5.hg4j.util.NodeIdUtil.UTF8_STRING_COMPARATOR);
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
        
        com.github.search5.hg4j.bundle.ChangegroupParser.ChangeGroupEntry entry = new com.github.search5.hg4j.bundle.ChangegroupParser.ChangeGroupEntry();
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
        return entry.node;
    }

    private java.util.Map<String, String> getManifestForCommit(Revlog changelog, Revlog manifestRevlog, byte[] commitNode) throws IOException {
        java.util.Map<String, String> manifestMap = new java.util.LinkedHashMap<>();
        if (commitNode == null || com.github.search5.hg4j.util.NodeIdUtil.isAllZero(commitNode)) {
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
        byte[] manifestNode = com.github.search5.hg4j.util.NodeIdUtil.fromHex(manifestHex);
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

    private byte[] getFileRevisionContent(com.github.search5.hg4j.core.HgRepository repository, String path, String nodeHex) throws IOException {
        File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        if (!flIdx.exists()) {
            throw new com.github.search5.hg4j.errors.HgRepositoryNotFoundException("Filelog index does not exist for: " + path);
        }
        Revlog filelog = repository.getRevlog(flIdx, flDat);
        int rev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(nodeHex.substring(0, 40)));
        if (rev == -1) {
            throw new com.github.search5.hg4j.errors.HgRevisionNotFoundException("File revision not found: " + path + " @ " + nodeHex);
        }
        return filelog.getRevisionContent(rev);
    }

    @Override
    public void close() {
        // No persistent resources
    }
}
