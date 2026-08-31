package com.github.search5.hg4j.api;

import com.github.search5.hg4j.core.HgRepository;
import com.github.search5.hg4j.core.Revlog;
import com.github.search5.hg4j.core.NodeIdUtil;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Porcelain command for Git-bisect / Hg-bisect style binary search
 * to identify the regression revision in SCM history.
 */
public class BisectCommand {
    private final HgRepository repository;
    private byte[] goodNode;
    private byte[] badNode;

    public BisectCommand(HgRepository repository) {
        this.repository = repository;
    }

    public BisectCommand setGood(byte[] goodNode) {
        this.goodNode = goodNode;
        return this;
    }

    public BisectCommand setBad(byte[] badNode) {
        this.badNode = badNode;
        return this;
    }

    /**
     * Identifies and returns the next mid-revision node to be checked.
     * Updates working directory parents and physically restores files to workspace.
     *
     * @return next bisect candidate node ID
     * @throws IOException if revision lookup fails
     */
    public byte[] next() throws IOException {
        if (goodNode == null || badNode == null) {
            throw new IllegalStateException("Good and Bad revision nodes must be set prior to bisect query");
        }

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        int goodRev = changelog.findRevision(goodNode);
        int badRev = changelog.findRevision(badNode);
        if (goodRev == -1 || badRev == -1) {
            throw new IOException("Bisect error: revision nodes not found in changelog history");
        }

        // 1. DAG-based Topological range search (Graph Algorithm)
        java.util.List<Integer> range = getTopologicalRange(changelog, goodRev, badRev);
        if (range.isEmpty()) {
            throw new IOException("Bisect error: no topological path exists between good and bad nodes");
        }

        int midRev = range.get(range.size() / 2);
        byte[] midNode = changelog.getIndexRecord(midRev).getNodeId();

        // 2. Physical File Checkout & Workspace Sync
        File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
        File mfDat = new File(repository.getStoreDir(), "00manifest.d");
        Revlog manifestRevlog = repository.getRevlog(mfIdx, mfDat);

        java.util.Map<String, String> manifestMap = getManifestForCommit(changelog, manifestRevlog, midNode);
        for (java.util.Map.Entry<String, String> entry : manifestMap.entrySet()) {
            String path = entry.getKey();
            String hexAndFlag = entry.getValue();
            String fileHex = hexAndFlag.substring(0, 40);

            byte[] fileContent = getFileRevisionContent(repository, path, fileHex);
            File wFile = new File(repository.getDirectory(), path);
            wFile.getParentFile().mkdirs();
            java.nio.file.Files.write(wFile.toPath(), fileContent);
        }

        // Update working directory parent to checkout the mid-node automatically
        com.github.search5.hg4j.dirstate.Dirstate d = repository.getDirstate();
        d.setParents(midNode, new byte[20]);
        repository.writeDirstate(d);
        repository.clearRevlogCache();

        return midNode;
    }

    private java.util.List<Integer> getTopologicalRange(Revlog changelog, int goodRev, int badRev) throws IOException {
        java.util.List<Integer> range = new java.util.ArrayList<>();
        int min = Math.min(goodRev, badRev);
        int max = Math.max(goodRev, badRev);

        boolean[] isDescendant = new boolean[max + 1];
        isDescendant[min] = true;
        for (int i = min + 1; i <= max; i++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(i);
            int p1 = rec.getParent1();
            int p2 = rec.getParent2();
            if ((p1 >= min && isDescendant[p1]) || (p2 >= min && isDescendant[p2])) {
                isDescendant[i] = true;
            }
        }

        boolean[] isAncestor = new boolean[max + 1];
        for (int i = max; i >= min; i--) {
            if (i == max) {
                isAncestor[i] = true;
                continue;
            }
            for (int child = i + 1; child <= max; child++) {
                if (isAncestor[child]) {
                    Revlog.IndexRecord childRec = changelog.getIndexRecord(child);
                    if (childRec.getParent1() == i || childRec.getParent2() == i) {
                        isAncestor[i] = true;
                        break;
                    }
                }
            }
        }

        for (int i = min; i <= max; i++) {
            if (isDescendant[i] && isAncestor[i]) {
                range.add(i);
            }
        }
        return range;
    }

    private java.util.Map<String, String> getManifestForCommit(Revlog changelog, Revlog manifestRevlog, byte[] commitNode) throws IOException {
        java.util.Map<String, String> manifestMap = new java.util.LinkedHashMap<>();
        if (commitNode == null || com.github.search5.hg4j.core.NodeIdUtil.isAllZero(commitNode)) {
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
        byte[] manifestNode = com.github.search5.hg4j.core.NodeIdUtil.fromHex(manifestHex);
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
}
