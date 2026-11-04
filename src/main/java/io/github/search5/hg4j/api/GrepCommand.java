package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lfs.HgLfsFactory;
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
    
    /**
     * One matching line found by {@link GrepCommand}.
     */
    public static class GrepResult {
        /**
         * Repository-relative path (forward slashes) of the file that contains the match.
         */
        public final String path;
        /**
         * Hex node id of the file revision containing the match when the whole file history is searched; an empty string when searching specific revisions with {@link GrepCommand#setRevisions(String)}.
         */
        public final String hexNode;
        /**
         * One-based line number of the match within the file revision.
         */
        public final int lineNumber;
        /**
         * Text of the matching line, without the line terminator.
         */
        public final String lineContent;
        /** The changeset that introduced this file revision ({@code fctx.linkrev()}); -1 if unknown. */
        public final int linkRevision;

        /**
         * Creates a result with an unknown link revision ({@code -1}).
         * @param path repository-relative path of the file
         * @param hexNode hex node id of the file revision
         * @param lineNumber one-based line number of the match
         * @param lineContent text of the matching line
         */
        public GrepResult(String path, String hexNode, int lineNumber, String lineContent) {
            this(path, hexNode, lineNumber, lineContent, -1);
        }

        /**
         * Creates a result.
         * @param path repository-relative path of the file
         * @param hexNode hex node id of the file revision
         * @param lineNumber one-based line number of the match
         * @param lineContent text of the matching line
         * @param linkRevision the changeset revision number associated with the match, or {@code -1} if unknown
         */
        public GrepResult(String path, String hexNode, int lineNumber, String lineContent, int linkRevision) {
            this.linkRevision = linkRevision;
            this.path = path;
            this.hexNode = hexNode;
            this.lineNumber = lineNumber;
            this.lineContent = lineContent;
        }
    }

    private final HgRepository repository;
    private String query;
    private boolean caseInsensitive = false;

    /**
     * Creates a grep command for the given repository.
     * @param repository the repository to search
     */
    public GrepCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Sets the search expression, a Java regular expression ({@link java.util.regex.Pattern}) that is searched for within each line ({@code find()} semantics, not a full-line match).
     * If the query is {@code null} or empty, {@link #call()} returns an empty list. There is no default.
     * @param query the regular expression
     * @return {@code this}
     */
    public GrepCommand setQuery(String query) {
        this.query = query;
        return this;
    }

    private final FileSelection selection = new FileSelection();

    /** {@code hg grep PATTERN [FILE]...}: only the files these patterns select are searched. */
    public GrepCommand setPatterns(String... patterns) {
        selection.patterns(patterns);
        return this;
    }

    /** {@code hg grep -I}. */
    public GrepCommand setInclude(String... patterns) {
        selection.include(patterns);
        return this;
    }

    /** {@code hg grep -X}. */
    public GrepCommand setExclude(String... patterns) {
        selection.exclude(patterns);
        return this;
    }

    /** The current directory relative to the repository root, against which relative patterns are read. */
    public GrepCommand setCwd(String cwd) {
        selection.cwd(cwd);
        return this;
    }

    private String revisions;

    /**
     * {@code hg grep -r REVS}: searches the files of the given revisions (a revision set such as {@code all()} or {@code 0:2}) as
     * they are in each of those revisions, not the history of the files. Binary files are left out. The {@code linkRevision} of a
     * result is then the revision that was searched. Without it the whole history of every file is searched.
     */
    public GrepCommand setRevisions(String revisions) {
        this.revisions = revisions;
        return this;
    }

    private List<GrepResult> callAtRevisions(Pattern pattern) throws IOException {
        io.github.search5.hg4j.fileset.RepositoryFilesetContext wd = new io.github.search5.hg4j.fileset.RepositoryFilesetContext(repository, null);
        io.github.search5.hg4j.match.Matcher selected = selection.isEmpty() ? null : selection.matcher(repository, null);
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);
        List<GrepResult> results = new ArrayList<>();
        for (io.github.search5.hg4j.fileset.FilesetContext rev : wd.revisions(revisions)) {
            io.github.search5.hg4j.fileset.RepositoryFilesetContext ctx = (io.github.search5.hg4j.fileset.RepositoryFilesetContext) rev;
            int revNumber = changelog.findRevision(NodeIdUtil.fromHex(ctx.revision()));
            for (String path : new java.util.TreeSet<>(ctx.trackedFiles())) {
                if (selected != null && !selected.matches(path)) {
                    continue;
                }
                byte[] content = ctx.data(path);
                if (content == null) {
                    continue;
                }
                boolean binary = false;
                for (byte b : content) {
                    if (b == 0) {
                        binary = true;
                        break;
                    }
                }
                if (binary) {
                    continue;
                }
                String[] lines = new String(content, StandardCharsets.UTF_8).split("\n");
                for (int i = 0; i < lines.length; i++) {
                    if (pattern.matcher(lines[i]).find()) {
                        results.add(new GrepResult(path, "", i + 1, lines[i], revNumber));
                    }
                }
            }
        }
        return results;
    }

    /**
     * Sets whether the query is matched case-insensitively (the {@code hg grep -i} option). The default is {@code false}.
     * @param caseInsensitive {@code true} to ignore case
     * @return {@code this}
     */
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
        if (revisions != null) {
            return callAtRevisions(pattern);
        }

        io.github.search5.hg4j.match.Matcher selected = selection.isEmpty() ? null : selection.matcher(repository, null);
        for (String[] entry : enumerateTrackedFilelogs()) {
            String path = entry[0];
            if (selected != null && !selected.matches(path)) {
                continue;
            }
            String storeRelIdx = entry[1];

            File flIdx = new File(repository.getStoreDir(), storeRelIdx);
            File flDat = new File(repository.getStoreDir(), storeRelIdx.substring(0, storeRelIdx.length() - 2) + ".d");

            if (flIdx.exists()) {
                Revlog filelog = repository.getRevlog(flIdx, flDat);
                for (int i = 0; i < filelog.getRevisionCount(); i++) {
                    byte[] node = filelog.getIndexRecord(i).getNodeId();
                    byte[] content = HgLfsFactory.getInstance().readContent(repository, path, filelog, i);
                    String text = new String(content, StandardCharsets.UTF_8);
                    String[] lines = text.split("\n");
                    for (int lineNum = 0; lineNum < lines.length; lineNum++) {
                        if (pattern.matcher(lines[lineNum]).find()) {
                            results.add(new GrepResult(path, NodeIdUtil.toHex(node).substring(0, 40), lineNum + 1, lines[lineNum],
                                    filelog.getIndexRecord(i).getLinkRev()));
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
