package org.hg4j.api;

import org.hg4j.core.HgRepository;
import org.hg4j.core.NodeIdUtil;
import org.hg4j.core.Revlog;

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
 * 두 리비전 사이의 변경 사항(Diff)을 계산하여 각 파일별 Unified Diff 차이점을 제공하는 명령입니다.
 */
public class DiffCommand {
    private final HgRepository repository;
    private int oldRevision = -2; // -2 means not set (defaults to newRevision's parent)
    private int newRevision = -1; // -1 defaults to tip

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

    public DiffCommand setOldRevision(org.hg4j.lib.NodeId oldRevisionNode) {
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

    public DiffCommand setNewRevision(org.hg4j.lib.NodeId newRevisionNode) {
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

        // Load manifests
        Map<String, String> oldManifest = loadManifest(changelog, targetOld);
        Map<String, String> newManifest = loadManifest(changelog, targetNew);

        Set<String> allFiles = new HashSet<>();
        allFiles.addAll(oldManifest.keySet());
        allFiles.addAll(newManifest.keySet());

        List<DiffEntry> diffs = new ArrayList<>();

        for (String path : allFiles) {
            String oldHex = oldManifest.get(path);
            String newHex = newManifest.get(path);

            if (oldHex == null && newHex != null) {
                // ADDED
                byte[] newContent = getFileContent(path, newHex);
                String diffText = generateUnifiedDiff(path, new byte[0], newContent);
                diffs.add(new DiffEntry(path, ChangeType.ADD, diffText));
            } else if (oldHex != null && newHex == null) {
                // DELETED
                byte[] oldContent = getFileContent(path, oldHex);
                String diffText = generateUnifiedDiff(path, oldContent, new byte[0]);
                diffs.add(new DiffEntry(path, ChangeType.DELETE, diffText));
            } else if (oldHex != null && newHex != null && !oldHex.equals(newHex)) {
                // MODIFIED
                byte[] oldContent = getFileContent(path, oldHex);
                byte[] newContent = getFileContent(path, newHex);
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
        
        for (String line : diffLines) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }
}
