package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Porcelain command corresponding to {@code hg identify}/{@code hg id}: a one-line summary of a
 * revision (default: the working directory's own parent(s)) -- id, branch, tags and bookmarks.
 *
 * <p>Output format verified directly against real hg 7.2.2 (2026-09-05) and mirrors its default
 * (no {@code --template}) rendering exactly, {@code mercurial/commands.py}'s {@code identify()}:
 * <ul>
 *   <li>The id is the 12-hex-digit short node of the working copy's parent(s), {@code +}-joined
 *   when a merge is in progress (two parents), followed by one more trailing {@code +} when the
 *   working copy is dirty (uncommitted modified/added/removed files, or -- always -- an unresolved
 *   merge). An empty repository (no revisions at all) identifies as {@code 000000000000}. Passing
 *   {@link #setRevision} switches to a fixed changeset instead of the working directory: no dirty
 *   marker is ever appended then, and exactly one id is shown (real hg's {@code -r} takes a single
 *   revision).</li>
 *   <li>The branch name is shown in parentheses, e.g. {@code (feature)} -- but only when it is
 *   <em>not</em> {@code "default"}; real hg omits it entirely on the default branch.</li>
 *   <li>Tags and bookmarks are aggregated across <em>every</em> current parent -- not just
 *   {@code p1} -- and rendered as their names joined by {@code "/"} in plain alphabetical order.
 *   During an uncommitted merge this means a tag or bookmark pointing solely at {@code p2} still
 *   appears (verified live, 2026-09-05: merging in a revision carrying a local tag shows that tag
 *   even though the working copy's branch/other display comes from {@code p1}). Ordering includes
 *   the pseudo-tag {@code "tip"} sorting exactly where its name falls (verified:
 *   {@code "mytag/tip"} but {@code "tip/zzz"}), reusing {@link TagsCommand}'s own
 *   tip-priority-aware tag resolution rather than re-implementing {@code .hgtags}/
 *   {@code localtags} parsing here (that duplicate parser used to also do a lenient bidirectional
 *   hex-prefix match that real hg does not perform -- {@code .hgtags} entries are always full
 *   40-hex node references, exactly like {@link TagsCommand} already treats them).</li>
 * </ul>
 */
public class IdentifyCommand {
    private final HgRepository repository;
    private String revision;

    public IdentifyCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Real hg's {@code hg identify -r REV}: identify a fixed revision (hex prefix, decimal
     * revision number, or {@code "tip"}) instead of the working directory's own parent(s). No
     * dirty marker is ever appended for a fixed revision.
     */
    public IdentifyCommand setRevision(String revision) {
        this.revision = revision;
        return this;
    }

    /**
     * @return SCM identity summary string, matching real hg's default {@code hg identify} output
     *         (see class javadoc for the exact format rules).
     * @throws IOException if dirstate or changelog parsing fails
     */
    public String call() throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = clIdx.exists() ? repository.getRevlog(clIdx, clDat) : null;

        List<byte[]> nodes = new ArrayList<>();
        boolean dirty = false;

        if (revision != null) {
            byte[] node = changelog == null ? null : NodeIdUtil.resolveRevision(changelog, revision);
            if (node == null) {
                node = new byte[20];
            }
            nodes.add(node);
        } else {
            byte[] p1 = repository.getDirstate().getParent1();
            byte[] p2 = repository.getDirstate().getParent2();
            nodes.add(p1 == null ? new byte[20] : p1);
            boolean merging = p2 != null && !NodeIdUtil.isAllZero(p2);
            if (merging) {
                nodes.add(p2);
            }
            Status status = new StatusCommand(repository).call();
            dirty = merging
                    || !status.getModified().isEmpty()
                    || !status.getAdded().isEmpty()
                    || !status.getRemoved().isEmpty();
        }

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) {
                out.append('+');
            }
            byte[] n = nodes.get(i);
            out.append(NodeIdUtil.isAllZero(n) ? "0".repeat(12) : NodeIdUtil.toHex(n).substring(0, 12));
        }
        if (dirty) {
            out.append('+');
        }

        // The branch shown is the QUERIED context's own branch: real hg's `-r` case reads it off
        // that revision's own changelog entry, not the working directory's current .hg/branch --
        // verified live (2026-09-05): `hg identify -r <older rev on a different branch>` while
        // checked out on "feature" still reports that older revision's own (e.g. default) branch,
        // never the working copy's current one.
        String branch;
        if (revision != null && changelog != null) {
            int rev = changelog.findRevision(nodes.get(0));
            branch = rev == -1 ? "default" : CommitCommand.getBranchOfRevision(changelog, rev);
        } else {
            branch = repository.getBranch();
        }
        if (branch == null || branch.isEmpty()) {
            branch = "default";
        }
        if (!"default".equals(branch)) {
            out.append(" (").append(branch).append(')');
        }

        // Tags/bookmarks are aggregated across ALL of the context's parents -- verified live
        // (2026-09-05): during an uncommitted merge, a tag or bookmark pointing at p2 alone (not
        // just p1) still shows up in the default identify line, e.g. p1 on branch "other" merging
        // p2 which alone carries a local tag still prints that tag. A fixed -r query has exactly
        // one node, so this reduces to that single node's own tags/bookmarks as before.
        List<String> tagNames = new ArrayList<>();
        List<String> bookmarkNames = new ArrayList<>();
        for (byte[] n : nodes) {
            tagNames.addAll(tagsForNode(n));
            bookmarkNames.addAll(bookmarksForNode(n));
        }
        Collections.sort(tagNames);
        Collections.sort(bookmarkNames);
        if (!tagNames.isEmpty()) {
            out.append(' ').append(String.join("/", tagNames));
        }
        if (!bookmarkNames.isEmpty()) {
            out.append(' ').append(String.join("/", bookmarkNames));
        }

        return out.toString();
    }

    private List<String> tagsForNode(byte[] node) throws IOException {
        List<TagsCommand.Tag> allTags = new TagsCommand(repository).call();
        List<String> names = new ArrayList<>();
        for (TagsCommand.Tag t : allTags) {
            if (Arrays.equals(t.getNode(), node)) {
                names.add(t.getName());
            }
        }
        Collections.sort(names);
        return names;
    }

    private List<String> bookmarksForNode(byte[] node) throws IOException {
        Map<String, String> allBookmarks = new BookmarkCommand(repository).call();
        String hex = NodeIdUtil.toHex(node);
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, String> e : allBookmarks.entrySet()) {
            if (hex.equalsIgnoreCase(e.getValue())) {
                names.add(e.getKey());
            }
        }
        Collections.sort(names);
        return names;
    }
}
