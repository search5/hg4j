package com.github.search5.hg4j.api;

import com.github.search5.hg4j.core.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.util.NodeIdUtil;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Porcelain command for searching strings or regular expressions across
 * historical file revisions in Mercurial repositories.
 */
public class GrepCommand {
    
    public static class GrepResult {
        public final String path;
        public final String hexNode;
        public final int lineNumber;
        public final String lineContent;

        public GrepResult(String path, String hexNode, int lineNumber, String lineContent) {
            this.path = path;
            this.hexNode = hexNode;
            this.lineNumber = lineNumber;
            this.lineContent = lineContent;
        }
    }

    private final HgRepository repository;
    private String query;
    private boolean caseInsensitive = false;

    public GrepCommand(HgRepository repository) {
        this.repository = repository;
    }

    public GrepCommand setQuery(String query) {
        this.query = query;
        return this;
    }

    public GrepCommand setCaseInsensitive(boolean caseInsensitive) {
        this.caseInsensitive = caseInsensitive;
        return this;
    }

    /**
     * Executes the grep search across all file historical revisions.
     *
     * @return list of matching occurrences
     * @throws IOException if historical file reading fails
     */
    public List<GrepResult> call() throws IOException {
        List<GrepResult> results = new ArrayList<>();
        if (query == null || query.isEmpty()) {
            return results;
        }

        Pattern pattern = Pattern.compile(query, caseInsensitive ? Pattern.CASE_INSENSITIVE : 0);

        File fncacheFile = new File(repository.getStoreDir(), "fncache");
        if (!fncacheFile.exists()) {
            return results;
        }

        List<String> fncachePaths = java.nio.file.Files.readAllLines(fncacheFile.toPath(), StandardCharsets.UTF_8);
        for (String storePath : fncachePaths) {
            if (storePath.endsWith(".i")) {
                String path = storePath.substring(0, storePath.length() - 2);
                if (path.startsWith("data/")) {
                    path = path.substring(5); // strip data/
                }
                
                File flIdx = new File(repository.getStoreDir(), storePath);
                File flDat = new File(repository.getStoreDir(), storePath.substring(0, storePath.length() - 2) + ".d");
                
                if (flIdx.exists()) {
                    Revlog filelog = repository.getRevlog(flIdx, flDat);
                    for (int i = 0; i < filelog.getRevisionCount(); i++) {
                        byte[] node = filelog.getIndexRecord(i).getNodeId();
                        byte[] content = filelog.getRevisionContent(i);
                        String text = new String(content, StandardCharsets.UTF_8);
                        String[] lines = text.split("\n");
                        for (int lineNum = 0; lineNum < lines.length; lineNum++) {
                            if (pattern.matcher(lines[lineNum]).find()) {
                                results.add(new GrepResult(path, NodeIdUtil.toHex(node).substring(0, 40), lineNum + 1, lines[lineNum]));
                            }
                        }
                    }
                }
            }
        }
        return results;
    }
}
