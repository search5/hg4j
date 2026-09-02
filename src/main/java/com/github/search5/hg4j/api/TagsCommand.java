package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code hg tags}-equivalent: lists every tag known to the repository, verified directly against
 * real hg 7.2.2 on scratch repos (2026-09-02):
 *
 * <ul>
 *   <li>Global tags come from {@code .hgtags} (tracked, working-copy content -- this mirrors the
 *       rest of this codebase's simplified single-file model, e.g. {@link TagCommand} and
 *       {@link com.github.search5.hg4j.revset.HgRevsetEngine}'s {@code tag()} revset, rather than
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
 */
public class TagsCommand {
    private final HgRepository repository;

    public TagsCommand(HgRepository repository) {
        this.repository = repository;
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

        public String getName() {
            return name;
        }

        public byte[] getNode() {
            return node;
        }

        public int getRev() {
            return rev;
        }

        /** {@code true} for a tag defined in {@code .hg/localtags}, {@code false} for a global/{@code .hgtags} tag. */
        public boolean isLocal() {
            return local;
        }
    }

    public List<Tag> call() throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = clIdx.exists() ? repository.getRevlog(clIdx, clDat) : null;
        int count = changelog == null ? 0 : changelog.getRevisionCount();

        Map<String, byte[]> globalTags = readTagFile(new File(repository.getDirectory(), ".hgtags"));
        Map<String, byte[]> localTags = readTagFile(new File(repository.getHgDir(), "localtags"));

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

    private static Map<String, byte[]> readTagFile(File file) throws IOException {
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
