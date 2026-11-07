package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code hg tags}-equivalent: lists every tag known to the repository, verified directly against
 * real hg 7.2.2 on scratch repos (2026-09-02):
 *
 * <ul>
 *   <li>Global tags come from {@code .hgtags} (tracked, working-copy content -- this mirrors the
 *       rest of this codebase's simplified single-file model, e.g. {@link TagCommand} and
 *       {@link io.github.search5.hg4j.revset.HgRevsetEngine}'s {@code tag()} revset, rather than
 *       real hg's per-head manifest merge); local tags come from {@code .hg/localtags} (untracked,
 *       never committed).</li>
 *   <li>When a tag name is redefined by a later line in the same file, the last line wins -- real
 *       hg's {@code _readtags} keeps {@code taghist[-1]}. Moving a tag with {@code hg tag --force}
 *       simply appends a new line; the earlier line for that name becomes dead history.</li>
 *   <li>When the same name is defined by both {@code .hgtags} and {@code .hg/localtags}, the local
 *       definition wins and the tag is reported as local -- verified with {@code hg tag --local -f}
 *       overriding a same-named global tag.</li>
 *   <li>A tag whose node is all-zero (nullid) means "deleted" and is omitted, matching
 *       {@code hg tag --remove}. A tag pointing at a node absent from the changelog (unknown or
 *       pruned) is likewise omitted -- both verified by hand-appending such lines to
 *       {@code .hgtags}/{@code .hg/localtags} and confirming real {@code hg tags} drops them.</li>
 *   <li>The pseudo-tag {@code "tip"} always names the actual repository tip (or the nullid at
 *       revision -1 for an empty repository) regardless of any {@code .hgtags}/{@code localtags}
 *       entry literally named {@code tip} -- real hg's {@code hg tag tip} itself aborts with
 *       "the name 'tip' is reserved", and even a hand-edited {@code .hgtags} line named {@code tip}
 *       is silently overridden by the real tip.</li>
 *   <li>Ordering matches real hg's {@code reversed(repo.tagslist())}: highest revision first, and
 *       for tags sharing a revision, reverse alphabetical order by name (e.g. "zeta" is listed
 *       before "alpha" when both tag the same revision).</li>
 * </ul>
 *
 * <p>By default the global tags are those of the working copy's {@code .hgtags}. A repository filled by push or pull has no working
 * files, and the original reads the {@code .hgtags} of the heads instead; {@link #setReadFromHeads(boolean)} and
 * {@link #setRevision(String)} do that here (see them for the rules).</p>
 */
public class TagsCommand {
    private final HgRepository repository;
    private boolean readFromHeads;
    private String revision;

    /**
     * Creates the command.
     * @param repository the repository whose tags are listed; must not be {@code null}
     */
    public TagsCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Reads the global tags from the {@code .hgtags} of every head of the repository instead of the working copy, as {@code hg tags}
     * does (the working copy may not exist at all, as in a repository filled by push or pull). The heads are read oldest first and a
     * file revision shared by several heads is read once. A tag defined by several heads takes the definition of the later head,
     * unless that definition is one the earlier head's history shows as superseded (the original's {@code _updatetags}).
     * {@code .hg/localtags} is still read, and combined by the same rule. The default ({@code false}) is the working copy reading.
     * @param readFromHeads {@code true} to read the heads' {@code .hgtags}
     * @return {@code this}
     */
    public TagsCommand setReadFromHeads(boolean readFromHeads) {
        this.readFromHeads = readFromHeads;
        return this;
    }

    /**
     * Reads the global tags from the {@code .hgtags} of this one changeset only (not the working copy, not the other heads); implies
     * {@link #setReadFromHeads(boolean)}. A changeset without {@code .hgtags} has no global tags.
     * @param revision a revision number, node (prefix) or {@code tip}; {@code null} clears it
     * @return {@code this}
     */
    public TagsCommand setRevision(String revision) {
        this.revision = revision;
        return this;
    }

    /** One entry in {@code hg tags}' output: a tag name resolved to a revision/node pair. */
    public static class Tag {
        private final String name;
        private final byte[] node;
        private final int rev;
        private final boolean local;

        Tag(String name, byte[] node, int rev, boolean local) {
            this.name = name;
            this.node = node;
            this.rev = rev;
            this.local = local;
        }

        /**
         * Returns the tag name.
         * @return the tag name, never {@code null}
         */
        public String getName() {
            return name;
        }

        /**
         * Returns the node id.
         * @return the 20-byte node id of the tagged changeset (all zeros for {@code tip} of an empty repository)
         */
        public byte[] getNode() {
            return node;
        }

        /**
         * Returns the local revision number.
         * @return the revision number of the tagged changeset, or -1 for {@code tip} of an empty repository
         */
        public int getRev() {
            return rev;
        }

        /** {@code true} for a tag defined in {@code .hg/localtags}, {@code false} for a global/{@code .hgtags} tag. */
        public boolean isLocal() {
            return local;
        }
    }

    /**
     * Lists the tags of the repository.
     * <p>
     * Reads {@code .hgtags} from the working copy and {@code .hg/localtags}, and always adds the pseudo tag
     * {@code tip}. See the class description for precedence, removed tags and ordering.
     * @return the tags, highest revision first; never {@code null}
     * @throws IOException if reading the tag files or the changelog fails
     */
    public List<Tag> call() throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = clIdx.exists() ? repository.getRevlog(clIdx, clDat) : null;
        int count = changelog == null ? 0 : changelog.getRevisionCount();

        Map<String, byte[]> globalTags;
        Map<String, byte[]> localTags;
        if (readFromHeads || revision != null) {
            Map<String, byte[]>[] fromHeads = readFromHeads(changelog, count);
            globalTags = fromHeads[0];
            localTags = fromHeads[1];
        } else {
            globalTags = readTagFile(new File(repository.getDirectory(), ".hgtags"));
            localTags = readTagFile(new File(repository.getHgDir(), "localtags"));
        }

        Map<String, byte[]> merged = new LinkedHashMap<>(globalTags);
        merged.putAll(localTags); // local definitions win over a same-named global tag

        List<Tag> result = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : merged.entrySet()) {
            String name = e.getKey();
            if ("tip".equals(name)) {
                continue; // the pseudo tip tag below always wins, matching real hg
            }
            byte[] node = e.getValue();
            if (NodeIdUtil.isAllZero(node)) {
                continue; // nullid means the tag was removed
            }
            int rev = changelog == null ? -1 : changelog.findRevision(node);
            if (rev == -1) {
                continue; // stale tag pointing at an unknown/pruned node
            }
            result.add(new Tag(name, node, rev, localTags.containsKey(name)));
        }

        int tipRev = count == 0 ? -1 : count - 1;
        byte[] tipNode = count == 0 ? new byte[20] : changelog.getIndexRecord(tipRev).getNodeId();
        result.add(new Tag("tip", tipNode, tipRev, false));

        result.sort((a, b) -> {
            int c = Integer.compare(b.getRev(), a.getRev());
            if (c != 0) {
                return c;
            }
            return b.getName().compareTo(a.getName());
        });
        return result;
    }

    /** A tag's node (hex) with the nodes it had before in the same file or an earlier one (the original's tagsmap entry). */
    private static final class Entry {
        String node;
        final List<String> hist;
        boolean local;

        Entry(String node, List<String> hist, boolean local) {
            this.node = node;
            this.hist = hist;
            this.local = local;
        }
    }

    /**
     * What the original's {@code repo.tagtype()} and {@code repo.tags()} are built from: the tags of the heads' {@code .hgtags} and of
     * {@code .hg/localtags}, a removed tag kept with the null node. Returns {tags, names whose winning definition is local}.
     */
    Map<String, byte[]>[] readRaw() throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = clIdx.exists() ? repository.getRevlog(clIdx, clDat) : null;
        return readFromHeads(changelog, changelog == null ? 0 : changelog.getRevisionCount());
    }

    /** Returns {global tags, local tags} (the second holds the names whose winning definition is local) read as {@code hg tags} does. */
    @SuppressWarnings("unchecked")
    private Map<String, byte[]>[] readFromHeads(Revlog changelog, int count) throws IOException {
        List<Integer> revs = new ArrayList<>();
        if (revision != null) {
            byte[] node = changelog == null ? null : NodeIdUtil.resolveRevision(changelog, revision);
            int rev = node == null ? -1 : changelog.findRevision(node);
            if (rev < 0) {
                throw new HgRevisionNotFoundException(revision);
            }
            revs.add(rev);
        } else if (changelog != null) {
            boolean[] hasChild = new boolean[count];
            for (int r = 0; r < count; r++) {
                Revlog.IndexRecord record = changelog.getIndexRecord(r);
                if (record.getParent1() >= 0) {
                    hasChild[record.getParent1()] = true;
                }
                if (record.getParent2() >= 0) {
                    hasChild[record.getParent2()] = true;
                }
            }
            for (int r = 0; r < count; r++) { // oldest to newest
                if (!hasChild[r]) {
                    revs.add(r);
                }
            }
        }

        Map<String, Entry> all = new LinkedHashMap<>();
        if (!revs.isEmpty()) {
            MergeCommand helper = new MergeCommand(repository);
            Revlog manifestRevlog = repository.getManifestRevlog();
            Set<String> readFileNodes = new LinkedHashSet<>();
            for (int rev : revs) {
                String entry = helper.loadManifestAtCommit(changelog, manifestRevlog, rev).get(".hgtags");
                if (entry == null) {
                    continue;
                }
                String fileNode = entry.substring(0, 40);
                if (!readFileNodes.add(fileNode)) {
                    continue; // the same file revision gives the same tags
                }
                update(all, readTags(new String(helper.getFileRevisionContent(".hgtags", entry), StandardCharsets.UTF_8), false), false);
            }
        }
        File localFile = new File(repository.getHgDir(), "localtags");
        if (localFile.exists()) {
            Map<String, Entry> local = readTags(new String(Files.readAllBytes(localFile.toPath()), StandardCharsets.UTF_8), true);
            update(all, local, true);
        }

        Map<String, byte[]> global = new LinkedHashMap<>();
        Map<String, byte[]> localNames = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> e : all.entrySet()) {
            byte[] node = NodeIdUtil.fromHex(e.getValue().node);
            global.put(e.getKey(), node);
            if (e.getValue().local) {
                localNames.put(e.getKey(), node);
            }
        }
        return new Map[] {global, localNames};
    }

    /** The original's {@code _readtags}: per name the last node and the earlier nodes of the same text, in text order. */
    private static Map<String, Entry> readTags(String text, boolean local) {
        Map<String, List<String>> history = new LinkedHashMap<>();
        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int space = line.indexOf(' ');
            if (space == -1) {
                continue;
            }
            String hex = line.substring(0, space).trim();
            String name = line.substring(space + 1).trim();
            if (name.isEmpty() || hex.length() != 40) {
                continue;
            }
            try {
                NodeIdUtil.fromHex(hex);
            } catch (IllegalArgumentException malformedHex) {
                continue;
            }
            history.computeIfAbsent(name, k -> new ArrayList<>()).add(hex.toLowerCase(java.util.Locale.ROOT));
        }
        Map<String, Entry> tags = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : history.entrySet()) {
            List<String> nodes = e.getValue();
            tags.put(e.getKey(), new Entry(nodes.get(nodes.size() - 1), new ArrayList<>(nodes.subList(0, nodes.size() - 1)), local));
        }
        return tags;
    }

    /** The original's {@code _updatetags}: incorporates the tags of one file, which is newer than what {@code all} holds. */
    private static void update(Map<String, Entry> all, Map<String, Entry> fileTags, boolean local) {
        for (Map.Entry<String, Entry> e : fileTags.entrySet()) {
            Entry older = all.get(e.getKey());
            if (older == null) {
                all.put(e.getKey(), e.getValue());
                continue;
            }
            Entry newer = e.getValue();
            // the older entry is preferred if it supersedes the newer one, or they supersede each other and it has the longer history
            String node = newer.node;
            boolean localWins = local;
            if (!older.node.equals(newer.node) && older.hist.contains(newer.node)
                    && (!newer.hist.contains(older.node) || older.hist.size() > newer.hist.size())) {
                node = older.node;
                localWins = older.local;
            }
            for (String n : older.hist) {
                if (!newer.hist.contains(n)) {
                    newer.hist.add(n);
                }
            }
            newer.node = node;
            newer.local = localWins;
            all.put(e.getKey(), newer);
        }
    }

    static Map<String, byte[]> readTagFile(File file) throws IOException {
        Map<String, byte[]> tags = new LinkedHashMap<>();
        if (!file.exists()) {
            return tags;
        }
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int spaceIdx = line.indexOf(' ');
            if (spaceIdx == -1) {
                continue;
            }
            String hex = line.substring(0, spaceIdx).trim();
            String name = line.substring(spaceIdx + 1).trim();
            if (name.isEmpty() || hex.length() != 40) {
                continue;
            }
            try {
                tags.put(name, NodeIdUtil.fromHex(hex)); // last line for a name wins
            } catch (IllegalArgumentException malformedHex) {
                // not well-formed hex on this line; skip it, mirroring real hg's tolerant parser
            }
        }
        return tags;
    }
}
