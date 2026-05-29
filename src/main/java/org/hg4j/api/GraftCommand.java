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

        // 2. Get current parent and initialize new manifest
        byte[] parent = repository.getDirstate().getParent1();
        java.util.Map<String, String> newManifest = getManifestForCommit(changelog, manifestRevlog, parent);

        // 3. For each modified file, copy the revision from original filelog and commit to new filelog
        for (String path : filesModified) {
            String hexAndFlag = originalManifest.get(path);
            if (hexAndFlag == null) {
                newManifest.remove(path);
                continue;
            }
            String fileHex = hexAndFlag.substring(0, 40);
            String flag = hexAndFlag.substring(40);

            byte[] fileContent = getFileRevisionContent(repository, path, fileHex);

            // Write content to working directory file
            File wFile = new File(repository.getDirectory(), path);
            wFile.getParentFile().mkdirs();
            Files.write(wFile.toPath(), fileContent);

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
                p1FileNode = org.hg4j.core.NodeIdUtil.fromHex(parentFileHex);
                parent1FileRev = org.hg4j.core.NodeIdUtil.findRevisionByNodeId(filelog, p1FileNode);
            }

            int newCommitRev = changelog.getRevisionCount();
            byte[] newFileNode = filelog.appendRevision(fileContent, null, parent1FileRev, -1, p1FileNode, new byte[20], newCommitRev);

            newManifest.put(path, org.hg4j.core.NodeIdUtil.toHex(newFileNode) + flag);
        }

        // 4. Serialize and append new manifest revision
        StringBuilder manifestSb = new StringBuilder();
        for (java.util.Map.Entry<String, String> entry : newManifest.entrySet()) {
            manifestSb.append(entry.getKey()).append('\0').append(entry.getValue()).append('\n');
        }
        byte[] manifestTextBytes = manifestSb.toString().getBytes(StandardCharsets.UTF_8);

        int parent1ManifestRev = -1;
        byte[] p1ManifestNode = new byte[20];
        if (parent != null && !org.hg4j.core.NodeIdUtil.isAllZero(parent)) {
            int pRev = changelog.findRevision(parent);
            if (pRev != -1) {
                byte[] pContent = changelog.getRevisionContent(pRev);
                String pText = new String(pContent, StandardCharsets.UTF_8);
                String[] pLines = pText.split("\n");
                if (pLines.length > 0) {
                    p1ManifestNode = org.hg4j.core.NodeIdUtil.fromHex(pLines[0].trim());
                    parent1ManifestRev = manifestRevlog.findRevision(p1ManifestNode);
                }
            }
        }

        int newCommitRev = changelog.getRevisionCount();
        byte[] manifestNode = manifestRevlog.appendRevision(manifestTextBytes, parent1ManifestRev, -1, p1ManifestNode, new byte[20], newCommitRev);

        // 5. Serialize and append new changelog (commit) revision
        StringBuilder clSb = new StringBuilder();
        clSb.append(org.hg4j.core.NodeIdUtil.toHex(manifestNode)).append('\n');
        clSb.append(author).append('\n');

        long secs = System.currentTimeMillis() / 1000;
        clSb.append(secs).append(" 0 branch:default\n");

        java.util.Collections.sort(filesModified, org.hg4j.core.NodeIdUtil.UTF8_STRING_COMPARATOR);
        for (String path : filesModified) {
            clSb.append(path).append('\n');
        }
        clSb.append('\n'); // empty line separator
        clSb.append(graftMessage);

        byte[] changelogTextBytes = clSb.toString().getBytes(StandardCharsets.UTF_8);

        byte[] p1Normalized = new byte[20];
        if (parent != null) {
            System.arraycopy(parent, 0, p1Normalized, 0, Math.min(parent.length, 20));
        }

        org.hg4j.core.ChangegroupParser.ChangeGroupEntry entry = new org.hg4j.core.ChangegroupParser.ChangeGroupEntry();
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

        // Sync workspace dirstate
        Dirstate d = repository.getDirstate();
        d.setParents(entry.node, new byte[20]);
        repository.writeDirstate(d);
        repository.clearRevlogCache();

        return NodeIdUtil.toHex(entry.node);
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
