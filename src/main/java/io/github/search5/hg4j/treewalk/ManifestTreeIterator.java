package io.github.search5.hg4j.treewalk;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.storage.Revlog;
import java.io.File;
import java.io.IOException;
import java.util.*;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import java.nio.charset.StandardCharsets;

/**
 * TreeIterator implementation that traverses historical repository manifests.
 *
 * @apiNote Widely used as one side of a {@link TreeWalk} (e.g. by {@code StatusCommand}, {@code
 *     DiffCommand}, {@code CommitCommand}, {@code UpdateCommand}, {@code MergeCommand}) and
 *     directly by {@code ManifestCommand}/{@code CensorCommand}/{@code BisectCommand} to list a
 *     revision's tracked files. Transparently flattens a {@code treemanifest} repository's
 *     recursive per-directory submanifests into the same flat, sorted entry list a plain
 *     (non-tree) manifest would produce, so callers never need to special-case either format.
 */
public class ManifestTreeIterator implements TreeIterator {

    private final HgRepository repository;
    private final String revision;
    private final byte[] directManifestNode;
    private final List<Entry> entries = new ArrayList<>();
    private int index = -1;

    /**
     * One parsed line of a manifest revlog revision: a path, its content node id, and its
     * executable/symlink/subdirectory flags.
     */
    public static class Entry {
        final String path;
        final byte[] nodeId;
        final boolean executable;
        final boolean symlink;
        /**
         * treemanifest's {@code t} (subdirectory-pointer) flag. When true, {@link #nodeId} is
         * not actual file content but a node ID pointing at a revision of that directory's own
         * submanifest revlog at {@code meta/<path>/00manifest.i} (matches real hg's own {@code
         * mercurial/manifest.py} {@code treemanifest.parse()}: the same encoding it uses when
         * {@code fl == b't'} to register a lazy subtree by appending {@code '/'} to the path).
         * When {@link
         * #loadEntries()} encounters such an entry it recursively expands it, so it never
         * survives into the final {@link #entries} list -- it only ever appears in the result of
         * the pure parsing function {@link #parseManifestContent(byte[])}.
         */
        final boolean treeDir;

        Entry(String path, byte[] nodeId, boolean executable, boolean symlink) {
            this(path, nodeId, executable, symlink, false);
        }

        Entry(String path, byte[] nodeId, boolean executable, boolean symlink, boolean treeDir) {
            this.path = path;
            this.nodeId = nodeId;
            this.executable = executable;
            this.symlink = symlink;
            this.treeDir = treeDir;
        }

        /**
         * Returns whether this entry is a treemanifest subdirectory pointer rather than a file.
         *
         * @return {@code true} if this entry's {@link #getNodeId()} points at a subdirectory's own
         *     submanifest revision rather than file content
         */
        public boolean isTreeDir() {
            return treeDir;
        }

        /** The entry's path exactly as it appears in this directory level's manifest text (a
         * bare directory name with no trailing slash when {@link #isTreeDir()}, e.g. treemanifest
         * write support in {@code api.CommitCommand} walks a parent's tree via {@link
         * #parseManifestContent} to recover per-directory node hashes for correct parent1/parent2
         * linkage on the new revisions it writes).
         *
         * @return the entry's path
         */
        public String getPath() {
            return path;
        }

        /** This entry's node hash — a file content revision, or (when {@link #isTreeDir()}) the
         * node of that subdirectory's own {@code meta/<dir>/00manifest.i} revision.
         *
         * @return the entry's node id
         */
        public byte[] getNodeId() {
            return nodeId;
        }
    }

    /**
     * Creates a new iterator over the manifest for the given changelog revision.
     *
     * @param repository the repository to read the manifest from
     * @param revision the revision to list, in any of the forms {@link
     *     io.github.search5.hg4j.util.NodeIdUtil#resolveRevision} accepts (revision number, hex
     *     node id/prefix, or {@code "tip"})
     */
    public ManifestTreeIterator(HgRepository repository, String revision) {
        this.repository = repository;
        this.revision = revision;
        this.directManifestNode = null;
    }

    /**
     * Creates a new iterator over a specific manifest revision, bypassing changelog revision
     * resolution.
     *
     * @param repository the repository to read the manifest from
     * @param manifestNode the manifest revlog node id to list directly
     */
    public ManifestTreeIterator(HgRepository repository, byte[] manifestNode) {
        this.repository = repository;
        this.revision = null;
        this.directManifestNode = manifestNode;
    }

    @Override
    public void reset() throws IOException {
        entries.clear();
        index = -1;
        loadEntries();
    }

    /**
     * Parses one directory level's raw manifest revlog text into its entries, without recursing
     * into any treemanifest subdirectory pointers it may contain (see {@link Entry#isTreeDir()}).
     * Each line has the format {@code path\0nodehex[flag]}, where {@code flag} is empty, {@code x}
     * (executable), {@code l} (symlink), or {@code t} (treemanifest subdirectory).
     *
     * @param mfContent the raw (decompressed) manifest revlog revision text
     * @return the parsed entries, in the order they appear in {@code mfContent}
     */
    public static List<Entry> parseManifestContent(byte[] mfContent) {
        List<Entry> result = new ArrayList<>();
        int start = 0;
        int len = mfContent.length;
        while (start < len) {
            int end = start;
            while (end < len && mfContent[end] != '\n') {
                end++;
            }

            if (end > start) {
                int nullIdx = -1;
                for (int i = start; i < end; i++) {
                    if (mfContent[i] == '\0') {
                        nullIdx = i;
                        break;
                    }
                }

                if (nullIdx != -1) {
                    String path = new String(mfContent, start, nullIdx - start, StandardCharsets.UTF_8);
                    int valStart = nullIdx + 1;
                    int valLen = end - valStart;

                    if (valLen >= 40) {
                        boolean isHexText = true;
                        for (int i = 0; i < 40; i++) {
                            char c = (char) mfContent[valStart + i];
                            if (Character.digit(c, 16) == -1) {
                                isHexText = false;
                                break;
                            }
                        }

                        if (isHexText) {
                            String hexNodeId = new String(mfContent, valStart, 40, StandardCharsets.UTF_8);
                            String flag = "";
                            if (valLen > 40) {
                                flag = new String(mfContent, valStart + 40, valLen - 40, StandardCharsets.UTF_8).trim();
                            }
                            boolean executable = flag.contains("x");
                            boolean symlink = flag.contains("l");
                            boolean treeDir = "t".equals(flag);
                            result.add(new Entry(path, NodeIdUtil.fromHex(hexNodeId), executable, symlink, treeDir));

                            start = end + 1;
                            continue;
                        }
                    }

                    if (valStart + 20 <= end) {
                        byte[] hashBytes = new byte[20];
                        System.arraycopy(mfContent, valStart, hashBytes, 0, 20);
                        String hexNodeId = NodeIdUtil.toHex(hashBytes);

                        int flagStart = valStart + 20;
                        String flag = "";
                        if (flagStart < end) {
                            flag = new String(mfContent, flagStart, end - flagStart, StandardCharsets.UTF_8).trim();
                        }
                        boolean executable = flag.contains("x");
                        boolean symlink = flag.contains("l");
                        boolean treeDir = "t".equals(flag);
                        result.add(new Entry(path, NodeIdUtil.fromHex(hexNodeId), executable, symlink, treeDir));
                    }
                }
            }
            start = end + 1;
        }
        return result;
    }

    private void loadEntries() throws IOException {
        byte[] mfNode = null;

        if (directManifestNode != null) {
            mfNode = directManifestNode;
        } else {
            if (revision == null || "".equals(revision) || "-1".equals(revision) || "null".equalsIgnoreCase(revision)) {
                return;
            }

            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");
            Revlog changelog = repository.getRevlog(clIdx, clDat);

            byte[] targetNodeId = NodeIdUtil.resolveRevision(changelog, revision);
            if (targetNodeId == null) {
                throw new HgRevisionNotFoundException("Revision not found in changelog: " + revision);
            }

            int commitRev = NodeIdUtil.findRevisionByNodeId(changelog, targetNodeId);
            if (commitRev == -1) {
                throw new HgRevisionNotFoundException("Commit not found in changelog for node: " + NodeIdUtil.toHex(targetNodeId));
            }

            byte[] clContent = changelog.getRevisionContent(commitRev);

            int firstNewLine = -1;
            for (int i = 0; i < clContent.length; i++) {
                if (clContent[i] == '\n') {
                    firstNewLine = i;
                    break;
                }
            }

            if (firstNewLine >= 40) {
                boolean isHexText = true;
                for (int i = 0; i < 40; i++) {
                    char c = (char) clContent[i];
                    if (Character.digit(c, 16) == -1) {
                        isHexText = false;
                        break;
                    }
                }

                if (isHexText) {
                    String hexNode = new String(clContent, 0, 40, StandardCharsets.UTF_8);
                    mfNode = NodeIdUtil.fromHex(hexNode);
                }
            }

            if (mfNode == null) {
                if (clContent.length >= 20) {
                    mfNode = new byte[20];
                    System.arraycopy(clContent, 0, mfNode, 0, 20);
                } else {
                    throw new IOException("Changelog content too short to extract manifest node ID");
                }
            }
        }

        File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
        File mfDat = new File(repository.getStoreDir(), "00manifest.d");
        Revlog manifestRevlog = repository.getRevlog(mfIdx, mfDat);
        int mfRev = NodeIdUtil.findRevisionByNodeId(manifestRevlog, mfNode);
        if (mfRev == -1) {
            throw new IOException("Manifest not found: " + NodeIdUtil.toHex(mfNode));
        }

        byte[] mfContent = manifestRevlog.getRevisionContent(mfRev);
        entries.addAll(expandTree(mfContent, ""));

        entries.sort((e1, e2) -> NodeIdUtil.UTF8_STRING_COMPARATOR.compare(e1.path, e2.path));
    }

    /**
     * Recursively expands a treemanifest repository ({@code experimental.treemanifest=1}) into a
     * flat file list. When {@code dirPrefix} is empty this is the root manifest content;
     * otherwise it is the submanifest content at {@code meta/<dirPrefix>/00manifest.i}.
     *
     * <p>Each {@code t}-flagged entry's path, exactly like real hg's own {@code
     * treemanifest.parse()}, holds only a path relative to that subdirectory itself (e.g. {@code
     * sub/00manifest.i}'s content is {@code "b.txt"}, {@code "deep"}, not {@code "sub/b.txt"}) --
     * so the accumulated {@code dirPrefix} must be prepended on every recursive call to restore
     * the full repository-root-relative path. Not a single directory-pointer entry survives into
     * the list this method returns -- only actual file entries remain, so existing code that
     * consumes this result (e.g. {@link ManifestWalk}/{@code getManifestAtCommit()}) behaves
     * exactly as if it were dealing with a flat manifest.
     */
    private List<Entry> expandTree(byte[] mfContent, String dirPrefix) throws IOException {
        List<Entry> rawEntries = parseManifestContent(mfContent);
        List<Entry> result = new ArrayList<>(rawEntries.size());
        for (Entry e : rawEntries) {
            String fullPath = dirPrefix.isEmpty() ? e.path : dirPrefix + "/" + e.path;
            if (e.isTreeDir()) {
                byte[] subContent = readSubManifestContent(fullPath, e.nodeId);
                result.addAll(expandTree(subContent, fullPath));
            } else {
                result.add(new Entry(fullPath, e.nodeId, e.executable, e.symlink));
            }
        }
        return result;
    }

    /**
     * Opens the subdirectory manifest revlog at {@code meta/<dirPath>/00manifest.i} and returns
     * the content of the revision {@code subManifestNode} points at (matches real hg's own
     * {@code manifestrevlog.dirlog()} path rule: {@code radix = "meta/" + tree + "00manifest"},
     * where {@code tree} is the full path including a trailing slash).
     */
    private byte[] readSubManifestContent(String dirPath, byte[] subManifestNode) throws IOException {
        File subIdx = new File(repository.getStoreDir(), "meta/" + dirPath + "/00manifest.i");
        File subDat = new File(repository.getStoreDir(), "meta/" + dirPath + "/00manifest.d");
        Revlog subRevlog = repository.getRevlog(subIdx, subDat);
        int subRev = NodeIdUtil.findRevisionByNodeId(subRevlog, subManifestNode);
        if (subRev == -1) {
            throw new IOException("Sub-manifest revision not found for directory '" + dirPath
                    + "': " + NodeIdUtil.toHex(subManifestNode));
        }
        return subRevlog.getRevisionContent(subRev);
    }



    @Override
    public String getEntryPath() {
        if (index >= 0 && index < entries.size()) {
            return entries.get(index).path;
        }
        return null;
    }

    @Override
    public byte[] getEntryNodeId() {
        if (index >= 0 && index < entries.size()) {
            return entries.get(index).nodeId;
        }
        return null;
    }

    @Override
    public boolean isExecutable() {
        if (index >= 0 && index < entries.size()) {
            return entries.get(index).executable;
        }
        return false;
    }

    /**
     * Returns whether the current entry is a symlink.
     *
     * @return {@code true} if the current entry is a symlink, {@code false} if it is a regular
     *     file or the iterator has no current entry
     */
    public boolean isSymlink() {
        if (index >= 0 && index < entries.size()) {
            return entries.get(index).symlink;
        }
        return false;
    }

    @Override
    public char getEntryState() {
        // Manifest is tracked, so normally active 'n'
        return 'n';
    }

    @Override
    public boolean next() throws IOException {
        if (index < entries.size() - 1) {
            index++;
            return true;
        }
        index = entries.size(); // mark as finished
        return false;
    }
}
