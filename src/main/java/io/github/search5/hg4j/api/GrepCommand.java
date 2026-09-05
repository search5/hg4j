package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;
import java.nio.file.Files;

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

        for (String[] entry : enumerateTrackedFilelogs()) {
            String path = entry[0];
            String storeRelIdx = entry[1];

            File flIdx = new File(repository.getStoreDir(), storeRelIdx);
            File flDat = new File(repository.getStoreDir(), storeRelIdx.substring(0, storeRelIdx.length() - 2) + ".d");

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
        return results;
    }

    /**
     * Enumerates every tracked filelog as {@code [logicalPath, storeRelativeIndexPath]} pairs.
     *
     * <p>Found and fixed 2026-09-05 (backlog #39, requirement-matrix expansion to
     * Cat/Files/Locate/Grep/Annotate/Manifest): the old code enumerated files ONLY via {@code
     * fncache}, silently returning zero results whenever that file does not exist -- which is the
     * case for any repository created with {@code format.use-fileindex-v1=yes} or {@code
     * experimental.revlogv2=...} (general-v2, which implies fileindex-v1): both storage
     * extensions replace {@code fncache} with their own internal {@code fileindex}/{@code
     * fileindex-list}/{@code fileindex-tree} sidecar files and never write an {@code fncache} at
     * all (verified against a real {@code hg-rust-7.2.4} container: {@code store/requires} for
     * such a repository lists {@code store} but neither {@code fncache} nor {@code dotencode}).
     * {@code hg grep} itself has no such blind spot in real hg (it walks the manifest, not
     * {@code fncache}), so this was a genuine hg4j completeness gap on an entire, valid class of
     * repositories, not merely a missed optimization.
     *
     * <p>When {@code fncache} exists, it remains the enumeration source (cheap, and correctly
     * handles hash-encoded long-path entries {@link NodeIdUtil#decodeStoreDataPath} cannot
     * reverse). Otherwise, this falls back to a direct recursive walk of {@code store/data/}
     * (dirlogs under {@code store/meta/} are deliberately excluded -- those are treemanifest
     * directory manifests, not user file content) decoding each on-disk path back to its logical
     * form via {@link NodeIdUtil#decodeStoreDataPath}.
     */
    private List<String[]> enumerateTrackedFilelogs() throws IOException {
        List<String[]> out = new ArrayList<>();
        File fncacheFile = new File(repository.getStoreDir(), "fncache");
        if (fncacheFile.exists()) {
            List<String> fncachePaths = Files.readAllLines(fncacheFile.toPath(), StandardCharsets.UTF_8);
            for (String storePath : fncachePaths) {
                if (storePath.endsWith(".i") && storePath.startsWith("data/")) {
                    // Found and fixed 2026-09-05 alongside the fncache-less fallback below: an
                    // fncache entry is the ENCODED on-disk path (e.g. an uppercase letter in the
                    // logical name becomes `_x`), not the logical one -- decoding it the same way
                    // as the fallback path keeps GrepResult#path consistent (and correct) for
                    // filenames that needed any encoding at all, not just plain lowercase ASCII.
                    String path = NodeIdUtil.decodeStoreDataPath(storePath);
                    out.add(new String[]{path, storePath});
                }
            }
            return out;
        }

        File dataDir = new File(repository.getStoreDir(), "data");
        if (!dataDir.isDirectory()) {
            return out;
        }
        Deque<File> stack = new ArrayDeque<>();
        stack.push(dataDir);
        String storeDirPath = repository.getStoreDir().getAbsolutePath();
        while (!stack.isEmpty()) {
            File dir = stack.pop();
            File[] children = dir.listFiles();
            if (children == null) {
                continue;
            }
            for (File child : children) {
                if (child.isDirectory()) {
                    stack.push(child);
                } else if (child.getName().endsWith(".i")) {
                    String storeRel = child.getAbsolutePath().substring(storeDirPath.length() + 1).replace(File.separatorChar, '/');
                    String logicalPath = NodeIdUtil.decodeStoreDataPath(storeRel);
                    out.add(new String[]{logicalPath, storeRel});
                }
            }
        }
        return out;
    }
}
