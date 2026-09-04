package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Porcelain command to perform line-by-line SCM blame (annotate).
 * Evaluates who modified each line of a file and in which revision.
 *
 * <p>Follows rename/copy boundaries by default, matching real {@code hg annotate} (which has no
 * separate {@code --follow} flag for this -- blame across a rename is simply how it always
 * behaves): once the trace walks back to a file's own filelog revision 0, it reads that
 * revision's {@code copy}/{@code copyrev} metadata (see {@link Revlog#getRevisionMetadata}) --
 * the same filelog-level mechanism real hg's default (non-changeset-centric) copy tracing uses
 * (verified against real {@code hg} 7.2's {@code copies.usechangesetcentricalgo()}, which only
 * switches to the changelog {@code SD_FILES} sidedata from backlog items 17/19 for repositories
 * explicitly created with {@code format.use-changelog-v2} plus the
 * {@code exp-copies-sidedata-changeset} requirement -- not the ordinary/default case) -- and, if
 * present, recurses into the copy source at that exact revision to obtain the pre-rename
 * baseline before diffing this file's own history forward on top of it.
 */
public final class AnnotateCommand {
    private final HgRepository repository;
    private String path;
    private int revision = -1;

    public static final class BlameLine {
        private final int lineNumber;
        private final int revision;
        private final String author;
        private final String content;

        public BlameLine(int lineNumber, int revision, String author, String content) {
            this.lineNumber = lineNumber;
            this.revision = revision;
            this.author = author;
            this.content = content;
        }

        public int getLineNumber() { return lineNumber; }
        public int getRevision() { return revision; }
        public String getAuthor() { return author; }
        public String getContent() { return content; }
    }

    public AnnotateCommand(HgRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("Repository cannot be null");
        }
        this.repository = repository;
    }

    public AnnotateCommand setPath(String path) {
        this.path = path;
        return this;
    }

    public AnnotateCommand setRevision(int revision) {
        this.revision = revision;
        return this;
    }

    /**
     * Resolves the line-by-line blame metadata.
     *
     * @return list of BlameLine records
     * @throws IOException if filelog reading fails
     */
    public List<BlameLine> call() throws IOException {
        if (path == null) {
            throw new IllegalStateException("Path must be specified.");
        }

        File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        
        if (!flIdx.exists()) {
            throw new HgRevisionNotFoundException("Filelog not found for path: " + path);
        }

        Revlog filelog = repository.getRevlog(flIdx, flDat);
        int targetRev = (revision == -1) ? filelog.getRevisionCount() - 1 : revision;

        if (targetRev < 0 || targetRev >= filelog.getRevisionCount()) {
            return List.of();
        }

        // 1. Trace the origin revision of each line using forward LCS diff matching, crossing
        // rename/copy boundaries (see class-level doc) whenever the trace reaches a filelog's
        // own revision 0.
        TraceResult traced = traceLines(path, targetRev, new HashSet<>());
        String[] prevLines = traced.lines;
        int[] prevSources = traced.linkRevs;

        // 2. Build final BlameLine structures with author info mapped from changelog linkRevs
        List<BlameLine> blameLines = new ArrayList<>(prevLines.length);

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        Map<Integer, String> authorCache = new HashMap<>();

        for (int k = 0; k < prevLines.length; k++) {
            int linkRev = prevSources[k];

            String author = authorCache.get(linkRev);
            if (author == null) {
                author = "unknown";
                if (linkRev >= 0 && linkRev < changelog.getRevisionCount()) {
                    byte[] clContent = changelog.getRevisionContent(linkRev);
                    String clText = new String(clContent, StandardCharsets.UTF_8);
                    String[] clLines = clText.split("\n");
                    if (clLines.length > 1) {
                        author = clLines[1].trim();
                    }
                }
                authorCache.put(linkRev, author);
            }

            blameLines.add(new BlameLine(k + 1, linkRev, author, prevLines[k]));
        }

        return blameLines;
    }

    /**
     * A file's content lines at some filelog revision, paired with the changelog revision each
     * line's content was introduced in (which, for lines that survived a rename unmodified, is a
     * revision of the pre-rename path, not the current one).
     */
    private static final class TraceResult {
        final String[] lines;
        final int[] linkRevs;

        TraceResult(String[] lines, int[] linkRevs) {
            this.lines = lines;
            this.linkRevs = linkRevs;
        }
    }

    /**
     * Computes {@code path}'s filelog revision {@code targetRev}'s content lines and, per line,
     * the changelog revision it was introduced in -- following a rename/copy boundary back into
     * the copy source (recursively) whenever {@code path}'s own filelog revision 0 carries
     * {@code copy}/{@code copyrev} metadata. See the class-level doc for which storage layer this
     * reads and why.
     *
     * @param visitingPaths cycle guard against a pathological copy chain that revisits a path
     *                       already on the current recursion stack (ordinary repository history
     *                       cannot produce this, but a corrupt one could).
     */
    private TraceResult traceLines(String path, int targetRev, Set<String> visitingPaths) throws IOException {
        File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
        if (!flIdx.exists() || !visitingPaths.add(path)) {
            return new TraceResult(new String[0], new int[0]);
        }
        try {
            File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
            Revlog filelog = repository.getRevlog(flIdx, flDat);
            if (targetRev < 0 || targetRev >= filelog.getRevisionCount()) {
                return new TraceResult(new String[0], new int[0]);
            }

            String[] baseLines;
            int[] baseLinkRevs;

            TraceResult crossed = tryCrossRenameBoundary(filelog, visitingPaths);
            if (crossed != null) {
                baseLines = crossed.lines;
                baseLinkRevs = crossed.linkRevs;
            } else {
                byte[] bytes0 = filelog.getRevisionContent(0);
                String text0 = new String(bytes0, StandardCharsets.UTF_8);
                baseLines = text0.split("\n", -1);
                int linkRev0 = filelog.getIndexRecord(0).getLinkRev();
                baseLinkRevs = new int[baseLines.length];
                Arrays.fill(baseLinkRevs, linkRev0);
            }

            String[] prevLines = baseLines;
            int[] prevSources = baseLinkRevs;

            for (int r = 1; r <= targetRev; r++) {
                byte[] bytesR = filelog.getRevisionContent(r);
                String textR = new String(bytesR, StandardCharsets.UTF_8);
                String[] currLines = textR.split("\n", -1);

                int prevN = prevLines.length;
                int currM = currLines.length;
                int newLineLinkRev = filelog.getIndexRecord(r).getLinkRev();

                if ((long) prevN * currM > 2000000) {
                    // Fallback: simple copy-forward for huge files to prevent DP memory overhead
                    int[] currSources = new int[currM];
                    Arrays.fill(currSources, newLineLinkRev);
                    prevSources = currSources;
                    prevLines = currLines;
                    continue;
                }

                int[][] dp = new int[prevN + 1][currM + 1];
                for (int i = 1; i <= prevN; i++) {
                    for (int j = 1; j <= currM; j++) {
                        if (prevLines[i - 1].equals(currLines[j - 1])) {
                            dp[i][j] = dp[i - 1][j - 1] + 1;
                        } else {
                            dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                        }
                    }
                }

                int[] matchInPrev = new int[currM];
                Arrays.fill(matchInPrev, -1);

                int i = prevN, j = currM;
                while (i > 0 || j > 0) {
                    if (i > 0 && j > 0 && prevLines[i - 1].equals(currLines[j - 1])) {
                        matchInPrev[j - 1] = i - 1;
                        i--;
                        j--;
                    } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                        matchInPrev[j - 1] = -1;
                        j--;
                    } else {
                        i--;
                    }
                }

                int[] currSources = new int[currM];
                for (int k = 0; k < currM; k++) {
                    int prevIdx = matchInPrev[k];
                    currSources[k] = (prevIdx != -1) ? prevSources[prevIdx] : newLineLinkRev;
                }

                prevSources = currSources;
                prevLines = currLines;
            }

            return new TraceResult(prevLines, prevSources);
        } finally {
            visitingPaths.remove(path);
        }
    }

    /**
     * If {@code filelog}'s revision 0 carries {@code copy}/{@code copyrev} metadata, traces the
     * copy source at that exact filenode and returns it as the pre-rename baseline; {@code null}
     * when there is no such metadata (or the source can't be resolved), meaning the caller should
     * treat revision 0 as this file's own origin.
     */
    private TraceResult tryCrossRenameBoundary(Revlog filelog, Set<String> visitingPaths) throws IOException {
        Map<String, String> rev0Meta = filelog.getRevisionMetadata(0);
        String copySourcePath = rev0Meta.get("copy");
        String copyRevHex = rev0Meta.get("copyrev");
        if (copySourcePath == null || copySourcePath.isEmpty() || copyRevHex == null || copyRevHex.isEmpty()) {
            return null;
        }

        byte[] copyNode;
        try {
            copyNode = NodeIdUtil.fromHex(copyRevHex);
        } catch (RuntimeException malformedHex) {
            return null;
        }
        if (NodeIdUtil.isAllZero(copyNode)) {
            // CommitCommand's fallback sentinel for "copy source has no resolvable parent
            // manifest entry" -- nothing to cross into.
            return null;
        }

        File srcFlIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), copySourcePath);
        if (!srcFlIdx.exists()) {
            return null;
        }
        File srcFlDat = new File(srcFlIdx.getPath().substring(0, srcFlIdx.getPath().length() - 2) + ".d");
        Revlog sourceFilelog = repository.getRevlog(srcFlIdx, srcFlDat);
        int sourceRev = NodeIdUtil.findRevisionByNodeId(sourceFilelog, copyNode);
        if (sourceRev == -1) {
            return null;
        }

        return traceLines(copySourcePath, sourceRev, visitingPaths);
    }
}
