package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.storage.Revlog;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.treewalk.HgTreeFilter;
import io.github.search5.hg4j.treewalk.ManifestTreeIterator;
import io.github.search5.hg4j.treewalk.TreeWalk;
import java.util.Arrays;

/**
 * Command to compute differences (diff) between two revisions and provide Unified Diff format per file.
 */
public class DiffCommand {
    private final HgRepository repository;
    private int oldRevision = -2; // -2 means not set (defaults to newRevision's parent)
    private int newRevision = -1; // -1 defaults to tip
    private HgTreeFilter treeFilter = HgTreeFilter.ALL;

    public DiffCommand setTreeFilter(HgTreeFilter treeFilter) {
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

    public DiffCommand setOldRevision(NodeId oldRevisionNode) {
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

    public DiffCommand setNewRevision(NodeId newRevisionNode) {
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
        // Backlog #39: guard against a long-lived HgRepository handle serving a stale cached
        // changelog-v2 revlog after an external process appended a revision -- see
        // DescribeCommand#call()'s javadoc for the full root-cause writeup. Cheap no-op in the
        // common (freshly-opened-per-call) case.
        repository.refreshIfChangedOnDisk();
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

        TreeWalk tw = new TreeWalk();
        tw.addTree(new ManifestTreeIterator(repository, String.valueOf(targetOld)));
        tw.addTree(new ManifestTreeIterator(repository, String.valueOf(targetNew)));

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
                byte[] newContent = getFileContent(path, NodeIdUtil.toHex(newNode));
                String diffText = generateUnifiedDiff(path, new byte[0], newContent, ChangeType.ADD);
                diffs.add(new DiffEntry(path, ChangeType.ADD, diffText));
            } else if (inOld && !inNew) {
                // DELETED
                byte[] oldContent = getFileContent(path, NodeIdUtil.toHex(oldNode));
                String diffText = generateUnifiedDiff(path, oldContent, new byte[0], ChangeType.DELETE);
                diffs.add(new DiffEntry(path, ChangeType.DELETE, diffText));
            } else if (inOld && inNew && !Arrays.equals(oldNode, newNode)) {
                // MODIFIED
                byte[] oldContent = getFileContent(path, NodeIdUtil.toHex(oldNode));
                byte[] newContent = getFileContent(path, NodeIdUtil.toHex(newNode));
                String diffText = generateUnifiedDiff(path, oldContent, newContent, ChangeType.MODIFY);
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

    private String generateUnifiedDiff(String path, byte[] oldBytes, byte[] newBytes, ChangeType changeType) {
        String oldText = new String(oldBytes, StandardCharsets.UTF_8);
        String newText = new String(newBytes, StandardCharsets.UTF_8);

        // Backlog #39 (2026-09-05) bug fix: a plain `text.split("\n", -1)` on content that ends
        // with a trailing newline (the overwhelmingly common case for any normal text file)
        // produces a SPURIOUS extra empty trailing element -- "hello\n".split("\n", -1) is
        // ["hello", ""], i.e. TWO "lines", when the file actually has exactly one. This used to
        // make every such diff hunk claim (and add/remove) one phantom blank line beyond the
        // file's real content -- confirmed live (2026-09-05): re-importing hg4j's own exported
        // patch (via real `hg import`, or via this backlog item's rewritten ImportCommand) applied
        // that phantom "+"/"-" blank line literally, corrupting the reconstructed file's bytes
        // (an extra trailing "\n") and therefore its filelog/manifest/changeset node hash --
        // caught by RequirementMatrixExportImportCoreRoundTripTest's byte-identical-node
        // assertions. {@link #splitLines} strips exactly that phantom element; whether the real
        // final line lacks ITS OWN trailing newline (a much rarer, unrelated case) is tracked
        // separately via {@code oldHasTrailingNewline}/{@code newHasTrailingNewline} below and
        // annotated with the standard `\ No newline at end of file` marker real diff/hg emit for
        // it -- {@link ImportCommand}'s patch parser (this backlog item's other half) understands
        // that marker on the way back in.
        String[] oldLines = splitLines(oldText);
        String[] newLines = splitLines(newText);
        boolean oldHasTrailingNewline = oldBytes.length == 0 || oldText.endsWith("\n");
        boolean newHasTrailingNewline = newBytes.length == 0 || newText.endsWith("\n");

        int n = oldLines.length;
        int m = newLines.length;

        // Real hg's default (non-`--git`) unified-diff format marks a pure add/delete with a
        // literal "/dev/null" on the missing side, rather than "a/<path>"/"b/<path>" for a file
        // that doesn't exist on that side at all -- verified against real `hg export`/`hg diff`
        // output (2026-09-05, backlog #39): a brand-new file's header is "--- /dev/null" / "+++
        // b/<path>", and a deleted file's is "--- a/<path>" / "+++ /dev/null". This used to
        // unconditionally emit "a/<path>"/"b/<path>" on both sides regardless of add/delete,
        // which real `hg import` still tolerates for a MODIFY but which real `hg import`'s own
        // patch.py cannot correctly classify as "create a new file"/"delete this file" without
        // the /dev/null marker (a bare "+++ b/newfile" line with no matching tracked file present
        // is otherwise ambiguous). ImportCommand's own patch parser (this backlog item's other
        // half) now depends on exactly this convention too.
        String oldSpec = (changeType == ChangeType.ADD) ? "/dev/null" : "a/" + path;
        String newSpec = (changeType == ChangeType.DELETE) ? "/dev/null" : "b/" + path;

        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(oldSpec).append("\n");
        sb.append("+++ ").append(newSpec).append("\n");

        // Real unified diff convention: a hunk side whose FILE IS ABSENT ENTIRELY (add/delete,
        // matching the /dev/null marker above) is reported as starting at line 0 (e.g. "@@ -0,0
        // +1,N @@" for a pure add), not "line 1" -- verified against real `hg export`'s own output
        // for a newly added file. This is keyed off changeType, NOT off n/m == 0: a MODIFY between
        // two revisions that both happen to be an empty (0-line) file still anchors at "1,0" on
        // that side (the file exists, it's merely empty) -- only a side with no file at all drops
        // to "0,0".
        String oldHunkSpec = (changeType == ChangeType.ADD) ? "0,0" : "1," + n;
        String newHunkSpec = (changeType == ChangeType.DELETE) ? "0,0" : "1," + m;

        // If file content is very large, avoid O(N*M) DP to prevent memory overhead
        if ((long) n * m > 2000000) {
            sb.append("@@ -").append(oldHunkSpec).append(" +").append(newHunkSpec).append(" @@\n");
            for (int oi = 0; oi < n; oi++) {
                sb.append("-").append(oldLines[oi]).append("\n");
                if (oi == n - 1 && !oldHasTrailingNewline) {
                    sb.append("\\ No newline at end of file\n");
                }
            }
            for (int ni = 0; ni < m; ni++) {
                sb.append("+").append(newLines[ni]).append("\n");
                if (ni == m - 1 && !newHasTrailingNewline) {
                    sb.append("\\ No newline at end of file\n");
                }
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

        sb.append("@@ -").append(oldHunkSpec).append(" +").append(newHunkSpec).append(" @@\n");
        // Annotate the entry that consumes the OLD sequence's true last line (a "-" or " " line)
        // and/or the one that consumes the NEW sequence's true last line (a "+" or " " line) with
        // the standard `\ No newline at end of file` marker when that side lacks one -- tracked by
        // running counts rather than by position, since the two "last lines" need not be the same
        // diffLines entry (e.g. a line appended after the old content's own last line).
        int oldSeen = 0;
        int newSeen = 0;
        for (String line : diffLines) {
            char type = line.charAt(0);
            sb.append(line).append("\n");
            if (type == '-' || type == ' ') {
                oldSeen++;
            }
            if (type == '+' || type == ' ') {
                newSeen++;
            }
            boolean touchesOldLast = (type == '-' || type == ' ') && oldSeen == n && !oldHasTrailingNewline;
            boolean touchesNewLast = (type == '+' || type == ' ') && newSeen == m && !newHasTrailingNewline;
            if (touchesOldLast || touchesNewLast) {
                sb.append("\\ No newline at end of file\n");
            }
        }
        return sb.toString();
    }

    /**
     * Splits {@code text} into lines the way real unified diff/{@code hg export} count them: a
     * trailing {@code "\n"} terminates the last real line, it does NOT introduce an additional
     * empty line after it. Plain {@code text.split("\n", -1)} disagrees with this for any content
     * ending in a newline (see {@link #generateUnifiedDiff}'s comment for the concrete bug this
     * caused) -- this drops exactly that phantom trailing element, if present.
     */
    private static String[] splitLines(String text) {
        if (text.isEmpty()) {
            return new String[0];
        }
        String[] parts = text.split("\n", -1);
        if (parts.length > 0 && parts[parts.length - 1].isEmpty()) {
            return Arrays.copyOf(parts, parts.length - 1);
        }
        return parts;
    }
}
