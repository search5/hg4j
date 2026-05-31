package io.github.search5.hg4j.api;

import io.github.search5.hg4j.core.HgRepository;
import io.github.search5.hg4j.core.Revlog;
import io.github.search5.hg4j.core.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Porcelain command to perform line-by-line SCM blame (annotate).
 * Evaluates who modified each line of a file and in which revision.
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
            throw new io.github.search5.hg4j.errors.HgRevisionNotFoundException("Filelog not found for path: " + path);
        }

        Revlog filelog = repository.getRevlog(flIdx, flDat);
        int targetRev = (revision == -1) ? filelog.getRevisionCount() - 1 : revision;

        if (targetRev < 0 || targetRev >= filelog.getRevisionCount()) {
            return List.of();
        }

        // 1. Trace the origin revision of each line using forward LCS diff matching
        byte[] bytes0 = filelog.getRevisionContent(0);
        String text0 = new String(bytes0, StandardCharsets.UTF_8);
        String[] lines0 = text0.split("\n", -1);
        List<Integer> prevSources = new ArrayList<>();
        for (int k = 0; k < lines0.length; k++) {
            prevSources.add(0);
        }

        String[] prevLines = lines0;

        for (int r = 1; r <= targetRev; r++) {
            byte[] bytesR = filelog.getRevisionContent(r);
            String textR = new String(bytesR, StandardCharsets.UTF_8);
            String[] currLines = textR.split("\n", -1);

            int prevN = prevLines.length;
            int currM = currLines.length;

            if ((long) prevN * currM > 2000000) {
                // Fallback: simple copy-forward for huge files to prevent DP memory overhead
                List<Integer> currSources = new ArrayList<>();
                for (int k = 0; k < currM; k++) {
                    currSources.add(r);
                }
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
            java.util.Arrays.fill(matchInPrev, -1);

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

            List<Integer> currSources = new ArrayList<>(currM);
            for (int k = 0; k < currM; k++) {
                int prevIdx = matchInPrev[k];
                if (prevIdx != -1) {
                    currSources.add(prevSources.get(prevIdx));
                } else {
                    currSources.add(r);
                }
            }

            prevSources = currSources;
            prevLines = currLines;
        }

        // 2. Build final BlameLine structures with author info mapped from changelog linkRevs
        List<BlameLine> blameLines = new ArrayList<>(prevLines.length);
        
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        java.util.Map<Integer, String> authorCache = new java.util.HashMap<>();

        for (int k = 0; k < prevLines.length; k++) {
            int originFileRev = prevSources.get(k);
            int linkRev = filelog.getIndexRecord(originFileRev).getLinkRev();
            
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
}
