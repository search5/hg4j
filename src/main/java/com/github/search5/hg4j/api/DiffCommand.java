package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.github.search5.hg4j.storage.Revlog;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Command to compute differences (diff) between two revisions and provide Unified Diff format per file.
 */
public class DiffCommand {
    private final HgRepository repository;
    private int oldRevision = -2; // -2 means not set (defaults to newRevision's parent)
    private int newRevision = -1; // -1 defaults to tip
    private com.github.search5.hg4j.treewalk.HgTreeFilter treeFilter = com.github.search5.hg4j.treewalk.HgTreeFilter.ALL;

    public DiffCommand setTreeFilter(com.github.search5.hg4j.treewalk.HgTreeFilter treeFilter) {
        if (treeFilter != null) {
            this.treeFilter = treeFilter;
        }
        return this;
    }

    public enum ChangeType {
        ADD, MODIFY, DELETE
    }

    public static class DiffEntry {
        private final String path;
        private final ChangeType changeType;
        private final String diffContent;

        public DiffEntry(String path, ChangeType changeType, String diffContent) {
            this.path = path;
            this.changeType = changeType;
            this.diffContent = diffContent;
        }

        public String getPath() { return path; }
        public ChangeType getChangeType() { return changeType; }
        public String getDiffContent() { return diffContent; }
    }

    public DiffCommand(HgRepository repository) {
        this.repository = repository;
    }

    public DiffCommand setOldRevision(int oldRevision) {
        this.oldRevision = oldRevision;
        return this;
    }

    public DiffCommand setOldRevision(com.github.search5.hg4j.lib.NodeId oldRevisionNode) {
        if (oldRevisionNode == null) {
            this.oldRevision = -2;
            return this;
        }
        try {
            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");
            Revlog changelog = repository.getRevlog(clIdx, clDat);
            this.oldRevision = NodeIdUtil.findRevisionByNodeId(changelog, oldRevisionNode.getBytes());
        } catch (IOException e) {
            this.oldRevision = -1;
        }
        return this;
    }

    public DiffCommand setNewRevision(int newRevision) {
        this.newRevision = newRevision;
        return this;
    }

    public DiffCommand setNewRevision(com.github.search5.hg4j.lib.NodeId newRevisionNode) {
        if (newRevisionNode == null) {
            this.newRevision = -1;
            return this;
        }
        try {
            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");
            Revlog changelog = repository.getRevlog(clIdx, clDat);
            this.newRevision = NodeIdUtil.findRevisionByNodeId(changelog, newRevisionNode.getBytes());
        } catch (IOException e) {
            this.newRevision = -1;
        }
        return this;
    }

    public List<DiffEntry> call() throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        
        if (!clIdx.exists()) {
            return Collections.emptyList();
        }
        
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        int count = changelog.getRevisionCount();
        if (count == 0) {
            return Collections.emptyList();
        }

        int targetNew = newRevision;
        if (targetNew == -1) {
            targetNew = count - 1;
        }

        int targetOld = oldRevision;
        if (targetOld == -2) {
            // default to newRevision's parent
            Revlog.IndexRecord rec = changelog.getIndexRecord(targetNew);
            targetOld = rec.getParent1();
        }

        List<DiffEntry> diffs = new ArrayList<>();

        com.github.search5.hg4j.treewalk.TreeWalk tw = new com.github.search5.hg4j.treewalk.TreeWalk();
        tw.addTree(new com.github.search5.hg4j.treewalk.ManifestTreeIterator(repository, String.valueOf(targetOld)));
        tw.addTree(new com.github.search5.hg4j.treewalk.ManifestTreeIterator(repository, String.valueOf(targetNew)));

        tw.reset();
        while (tw.next()) {
            String path = tw.getPath();
            if (treeFilter != null && !treeFilter.accept(path)) {
                continue;
            }
            boolean inOld = tw.isTracked(0);
            boolean inNew = tw.isTracked(1);

            byte[] oldNode = tw.getNodeId(0);
            byte[] newNode = tw.getNodeId(1);

            if (!inOld && inNew) {
                // ADDED
                byte[] newContent = getFileContent(path, com.github.search5.hg4j.util.NodeIdUtil.toHex(newNode));
                String diffText = generateUnifiedDiff(path, new byte[0], newContent);
                diffs.add(new DiffEntry(path, ChangeType.ADD, diffText));
            } else if (inOld && !inNew) {
                // DELETED
                byte[] oldContent = getFileContent(path, com.github.search5.hg4j.util.NodeIdUtil.toHex(oldNode));
                String diffText = generateUnifiedDiff(path, oldContent, new byte[0]);
                diffs.add(new DiffEntry(path, ChangeType.DELETE, diffText));
            } else if (inOld && inNew && !java.util.Arrays.equals(oldNode, newNode)) {
                // MODIFIED
                byte[] oldContent = getFileContent(path, com.github.search5.hg4j.util.NodeIdUtil.toHex(oldNode));
                byte[] newContent = getFileContent(path, com.github.search5.hg4j.util.NodeIdUtil.toHex(newNode));
                String diffText = generateUnifiedDiff(path, oldContent, newContent);
                diffs.add(new DiffEntry(path, ChangeType.MODIFY, diffText));
            }
        }

        return diffs;
    }

    private Map<String, String> loadManifest(Revlog changelog, int commitRev) throws IOException {
        if (commitRev < 0 || commitRev >= changelog.getRevisionCount()) {
            return Collections.emptyMap();
        }
        byte[] commitNodeId = changelog.getIndexRecord(commitRev).getNodeId();
        return repository.getManifestAtCommit(commitNodeId);
    }

    private byte[] getFileContent(String path, String nodeHex) throws IOException {
        File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        if (!flIdx.exists()) {
            return new byte[0];
        }
        Revlog filelog = repository.getRevlog(flIdx, flDat);
        String cleanHex = nodeHex.length() > 40 ? nodeHex.substring(0, 40) : nodeHex;
        int rev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(cleanHex));
        if (rev == -1) {
            return new byte[0];
        }
        return filelog.getRevisionContent(rev);
    }

    private String generateUnifiedDiff(String path, byte[] oldBytes, byte[] newBytes) {
        String oldText = new String(oldBytes, StandardCharsets.UTF_8);
        String newText = new String(newBytes, StandardCharsets.UTF_8);

        String[] oldLines = oldText.isEmpty() ? new String[0] : oldText.split("\n", -1);
        String[] newLines = newText.isEmpty() ? new String[0] : newText.split("\n", -1);

        StringBuilder sb = new StringBuilder();
        sb.append("--- a/").append(path).append("\n");
        sb.append("+++ b/").append(path).append("\n");
        
        int n = oldLines.length;
        int m = newLines.length;
        
        // If file content is very large, avoid O(N*M) DP to prevent memory overhead
        if ((long) n * m > 2000000) {
            sb.append("@@ -1," + n + " +1," + m + " @@\n");
            for (String line : oldLines) {
                sb.append("-").append(line).append("\n");
            }
            for (String line : newLines) {
                sb.append("+").append(line).append("\n");
            }
            return sb.toString();
        }

        int[][] dp = new int[n + 1][m + 1];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                if (oldLines[i - 1].equals(newLines[j - 1])) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }

        List<String> diffLines = new ArrayList<>();
        int i = n, j = m;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && oldLines[i - 1].equals(newLines[j - 1])) {
                diffLines.add(" " + oldLines[i - 1]);
                i--;
                j--;
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                diffLines.add("+" + newLines[j - 1]);
                j--;
            } else if (i > 0 && (j == 0 || dp[i - 1][j] >= dp[i][j - 1])) {
                diffLines.add("-" + oldLines[i - 1]);
                i--;
            }
        }
        Collections.reverse(diffLines);
        
        sb.append("@@ -1," + n + " +1," + m + " @@\n");
        for (String line : diffLines) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }
}
