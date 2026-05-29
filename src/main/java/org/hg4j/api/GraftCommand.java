package org.hg4j.api;

import org.hg4j.core.HgRepository;
import org.hg4j.core.Revlog;
import org.hg4j.core.NodeIdUtil;
import org.hg4j.core.Dirstate;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Graft command (equivalent to git cherry-pick) for Mercurial repositories.
 * Copies the changes of a source revision and commits them on top of the current parent.
 */
public class GraftCommand {
    private final HgRepository repository;
    private String sourceRevision;

    public GraftCommand(HgRepository repository) {
        this.repository = repository;
    }

    public GraftCommand setSource(String sourceRevision) {
        this.sourceRevision = sourceRevision;
        return this;
    }

    /**
     * Executes the graft operation.
     * Extracts source file contents and commits them to the current parent, updating the workspace.
     *
     * @return hex node ID of the newly grafted commit
     * @throws IOException if history traversal or file write fails
     */
    public String call() throws IOException {
        if (sourceRevision == null || sourceRevision.isEmpty()) {
            throw new IllegalArgumentException("Source revision must be specified for graft");
        }

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
        File mfDat = new File(repository.getStoreDir(), "00manifest.d");
        Revlog manifestRevlog = repository.getRevlog(mfIdx, mfDat);

        byte[] origNode = org.hg4j.core.NodeIdUtil.resolveRevision(changelog, sourceRevision);
        if (origNode == null) {
            throw new IOException("Graft source revision not found: " + sourceRevision);
        }
        int origRev = changelog.findRevision(origNode);

        // 1. Get original manifest and changed files
        byte[] origClContent = changelog.getRevisionContent(origRev);
        String origClText = new String(origClContent, StandardCharsets.UTF_8);
        String[] origClLines = origClText.split("\n");

        java.util.List<String> filesModified = new java.util.ArrayList<>();
        String author = "graft";
        if (origClLines.length > 1) {
            author = origClLines[1].trim();
        }
        StringBuilder msgBuilder = new StringBuilder();
        int msgStartIdx = -1;
        for (int i = 3; i < origClLines.length; i++) {
            if (origClLines[i].isEmpty()) {
                msgStartIdx = i + 1;
                break;
            }
            filesModified.add(origClLines[i]);
        }
        if (msgStartIdx != -1) {
            for (int i = msgStartIdx; i < origClLines.length; i++) {
                if (msgBuilder.length() > 0) msgBuilder.append("\n");
                msgBuilder.append(origClLines[i]);
            }
        }
        String graftMessage = msgBuilder.toString() + "\n(grafted from " + NodeIdUtil.toHex(origNode).substring(0, 12) + ")";

        java.util.Map<String, String> originalManifest = getManifestForCommit(changelog, manifestRevlog, origNode);

        // Acquire lock explicitly to restore files and commit safely in a transaction
        try (org.hg4j.core.HgLock storeLock = repository.lockStore();
             org.hg4j.core.HgLock wlock = repository.lockWorkingCopy()) {
            
            // 2. For each modified file in the source revision, copy contents and write to working copy
            for (String path : filesModified) {
                String hexAndFlag = originalManifest.get(path);
                if (hexAndFlag == null) {
                    // File deleted in source revision -> delete in working copy too
                    File wFile = new File(repository.getDirectory(), path);
                    if (wFile.exists()) {
                        wFile.delete();
                    }
                    continue;
                }
                String fileHex = hexAndFlag.substring(0, 40);
                byte[] fileContent = getFileRevisionContent(repository, path, fileHex);

                File wFile = new File(repository.getDirectory(), path);
                wFile.getParentFile().mkdirs();
                Files.write(wFile.toPath(), fileContent);
            }

            // 3. Delegate execution to CommitCommand to ensure locks, rollback journal, 
            // fncache registry, phase draft transition, and hooks are fully executed!
            CommitCommand commitCmd = new CommitCommand(repository);
            commitCmd.setAuthor(author);
            commitCmd.setMessage(graftMessage);
            commitCmd.setSkipLockAndJournal(true);

            byte[] newCommitNode = commitCmd.call();
            return NodeIdUtil.toHex(newCommitNode);
        }
    }

    private java.util.Map<String, String> getManifestForCommit(Revlog changelog, Revlog manifestRevlog, byte[] commitNode) throws IOException {
        java.util.Map<String, String> manifestMap = new java.util.LinkedHashMap<>();
        if (commitNode == null || org.hg4j.core.NodeIdUtil.isAllZero(commitNode)) {
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
        byte[] manifestNode = org.hg4j.core.NodeIdUtil.fromHex(manifestHex);
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

    private byte[] getFileRevisionContent(org.hg4j.core.HgRepository repository, String path, String nodeHex) throws IOException {
        File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
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
}
