package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.util.SafeFileIO;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.github.search5.hg4j.revwalk.ChangesetGraph;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.obsolete.HgObsMarker;
import io.github.search5.hg4j.obsolete.HgObsolescenceParser;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * Commands for bookmark management (listing, creating, or deleting bookmarks).
 *
 * @apiNote Typically obtained via {@link Hg#bookmark()} on an open {@link Hg}
 *     instance rather than constructed directly.
 */
public class BookmarkCommand {
    private final HgRepository repository;
    private String bookmarkName;
    private byte[] nodeId;
    private boolean delete = false;
    private boolean active = false;
    private boolean force = false;

    public BookmarkCommand(HgRepository repository) {
        this.repository = repository;
    }

    public BookmarkCommand setBookmarkName(String bookmarkName) {
        this.bookmarkName = bookmarkName;
        return this;
    }

    public BookmarkCommand setNodeId(byte[] nodeId) {
        this.nodeId = nodeId;
        return this;
    }

    public BookmarkCommand setRevision(String revision) {
        if (revision != null && !revision.isEmpty()) {
            this.nodeId = NodeIdUtil.fromHex(revision);
        }
        return this;
    }

    public BookmarkCommand setDelete(boolean delete) {
        this.delete = delete;
        return this;
    }

    public BookmarkCommand setActive(boolean active) {
        this.active = active;
        return this;
    }

    /**
     * {@code hg bookmark -f}/{@code --force}: allows moving an existing bookmark to a revision
     * that is NOT a descendant of its current target (a backward or divergent move). Without
     * this, real hg aborts with {@code bookmark '<name>' already exists (use -f to force)} -- a
     * plain fast-forward move (new target is a descendant of the current one) is always allowed
     * without {@code -f}, exactly like a brand new bookmark name. Irrelevant to {@link #setDelete}
     * (removal never requires force) and to {@link #setActive} (that only touches
     * {@code bookmarks.current}, never a bookmark's target).
     */
    public BookmarkCommand setForce(boolean force) {
        this.force = force;
        return this;
    }

    public Map<String, String> call() throws IOException {
        File bkFile = new File(repository.getHgDir(), "bookmarks");
        File curBkFile = new File(repository.getHgDir(), "bookmarks.current");

        Map<String, String> bookmarks = new LinkedHashMap<>();
        if (bkFile.exists()) {
            List<String> lines = Files.readAllLines(bkFile.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int spaceIdx = line.indexOf(' ');
                if (spaceIdx != -1) {
                    String node = line.substring(0, spaceIdx).trim();
                    String name = line.substring(spaceIdx + 1).trim();
                    bookmarks.put(name, node);
                }
            }
        }

        if (delete) {
            if (bookmarkName == null || bookmarkName.isEmpty()) {
                throw new IllegalArgumentException("Bookmark name must be specified for deletion");
            }
            bookmarks.remove(bookmarkName);
            writeBookmarks(bkFile, bookmarks);
            if (curBkFile.exists()) {
                String cur = Files.readString(curBkFile.toPath(), StandardCharsets.UTF_8).trim();
                if (bookmarkName.equals(cur)) {
                    curBkFile.delete();
                }
            }
            return bookmarks;
        }

        if (active) {
            if (bookmarkName == null || bookmarkName.isEmpty()) {
                if (curBkFile.exists()) {
                    curBkFile.delete();
                }
            } else {
                if (!bookmarks.containsKey(bookmarkName)) {
                    throw new IllegalArgumentException("Bookmark does not exist: " + bookmarkName);
                }
                SafeFileIO.writeStringAtomic(curBkFile, bookmarkName + "\n");
            }
            return bookmarks;
        }

        if (bookmarkName != null && !bookmarkName.isEmpty()) {
            // Running `hg bookmark NAME` with no -r (i.e. implicitly targeting the current
            // working copy parent) automatically makes the new bookmark active (shown with "*"
            // at the current checkout). Explicitly specifying a revision with -r never makes it
            // active (even when that value happens to equal the current parent) -- the deciding
            // factor is purely "was it explicitly specified".
            boolean explicitTarget = nodeId != null;
            byte[] targetNode = nodeId;
            if (targetNode == null) {
                targetNode = repository.getDirstate().getParent1();
            }
            String hex = NodeIdUtil.toHex(targetNode).substring(0, 40);

            // Real hg: moving an EXISTING bookmark to a non-descendant revision without -f
            // aborts instead of silently moving it -- exactly the same gate TagCommand already
            // has for retagging. A no-op "move" to the same target, and any brand-new bookmark
            // name, are both exempt.
            String existingHex = bookmarks.get(bookmarkName);
            if (existingHex != null && !existingHex.equalsIgnoreCase(hex) && !force
                    && !isFastForwardMove(existingHex, hex)) {
                throw new HgValidationException("bookmark '" + bookmarkName + "' already exists (use -f to force)");
            }

            bookmarks.put(bookmarkName, hex);
            writeBookmarks(bkFile, bookmarks);
            if (!explicitTarget) {
                SafeFileIO.writeStringAtomic(curBkFile, bookmarkName + "\n");
            }
            return bookmarks;
        }

        return bookmarks;
    }

    /**
     * Merges remote bookmarks into local ones during a pull/fetch -- a simplified reproduction
     * of {@code mercurial/bookmarks.py}'s {@code comparebookmarks()}/{@code validdest()} logic:
     * create it if there's no local bookmark, ignore it if the values already match, simply
     * advance if the remote is a descendant of local (fast-forward), keep the local value if
     * local is a descendant of remote (local is already ahead), and otherwise (a genuine
     * divergence) create a divergent bookmark named {@code name@remotePathName}.
     *
     * @param remotePathName suffix to append to a divergent bookmark's name (e.g. a remote path
     *                       alias). {@code null} if unknown -- in that case "1" is used
     *                       (matching real hg's own {@code name@1} fallback form).
     */
    public static void mergeFromRemote(HgRepository repository, Map<String, String> remoteBookmarks,
                                        String remotePathName) throws IOException {
        if (remoteBookmarks == null || remoteBookmarks.isEmpty()) {
            return;
        }
        Map<String, String> localBookmarks = new BookmarkCommand(repository).call();

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);
        ChangesetGraph graph = new ChangesetGraph(changelog);

        String suffix = (remotePathName != null && !remotePathName.isEmpty()) ? remotePathName : "1";

        for (Map.Entry<String, String> entry : remoteBookmarks.entrySet()) {
            String name = entry.getKey();
            String remoteHex = entry.getValue();
            String localHex = localBookmarks.get(name);

            if (localHex == null) {
                new BookmarkCommand(repository).setBookmarkName(name).setRevision(remoteHex).call();
                continue;
            }
            if (localHex.equals(remoteHex)) {
                continue;
            }

            byte[] remoteNode = NodeIdUtil.fromHex(remoteHex);
            byte[] localNode = NodeIdUtil.fromHex(localHex);
            int remoteRev = changelog.findRevision(remoteNode);
            int localRev = changelog.findRevision(localNode);

            if (remoteRev == -1) {
                // Local doesn't have the revision the remote points at yet (a failed/partial
                // fetch) -- leave it untouched.
                continue;
            }
            if (localRev == -1) {
                // The local bookmark points at a revision that no longer exists (e.g. stripped)
                // -- adopt the remote value as-is. Bypasses the new force gate since there is no
                // reference revision left to judge an ancestry relationship against (this branch
                // has already made the correct call of "adopt the remote value" on its own).
                new BookmarkCommand(repository).setBookmarkName(name).setRevision(remoteHex).setForce(true).call();
                continue;
            }

            if (graph.isAncestor(localRev, remoteRev)) {
                // Fast-forward: remote is a descendant of local -> simply advance.
                new BookmarkCommand(repository).setBookmarkName(name).setRevision(remoteHex).call();
            } else if (graph.isAncestor(remoteRev, localRev)) {
                // Local is already ahead of remote -> keep it.
            } else {
                // Genuine divergence: leave local as-is and create a divergent bookmark.
                String divergentName = name + "@" + suffix;
                new BookmarkCommand(repository).setBookmarkName(divergentName).setRevision(remoteHex).call();
            }
        }
    }

    public String getActiveBookmark() {
        File curBkFile = new File(repository.getHgDir(), "bookmarks.current");
        if (curBkFile.exists()) {
            try {
                return Files.readString(curBkFile.toPath(), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                // ignore
            }
        }
        return null;
    }

    /**
     * True when {@code newHex} is reachable "forward" from {@code oldHex} -- the condition real
     * hg's own bookmark move gate ({@code bookmarks.validdest}/{@code obsutil.foreground})
     * allows without {@code -f}. This is NOT just a plain changelog-DAG descendant check: real hg
     * also allows moving a bookmark across an obsolescence-successor step (e.g. advancing a
     * bookmark from a commit onto its {@code hg amend}/{@code hg rebase} successor, which is a
     * DAG *sibling*, not a descendant, of the original) -- freely alternating descendant steps and
     * successor steps, exactly like {@link PushCommand}'s own {@code isInForeground} (amending a
     * bookmarked commit and moving the bookmark to the amendment succeeds locally without
     * {@code -f}). Either hex failing to resolve to a known
     * revision (a dangling/stale bookmark target) is treated as "not reachable", matching real
     * hg's cautious default of requiring {@code -f} whenever this can't be established.
     */
    private boolean isFastForwardMove(String oldHex, String newHex) {
        try {
            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");
            Revlog changelog = repository.getRevlog(clIdx, clDat);
            int oldRev = changelog.findRevision(NodeIdUtil.fromHex(oldHex));
            int newRev = changelog.findRevision(NodeIdUtil.fromHex(newHex));
            if (oldRev == -1 || newRev == -1) {
                return false;
            }
            if (oldRev == newRev || new ChangesetGraph(changelog).isAncestor(oldRev, newRev)) {
                return true;
            }
            Map<String, List<String>> obsSuccessors = loadObsSuccessorMap();
            if (obsSuccessors.isEmpty()) {
                return false;
            }
            return isInForeground(changelog, oldRev, newRev, obsSuccessors, buildChildrenMap(changelog));
        } catch (Exception e) {
            return false;
        }
    }

    /** Real hg's {@code obsutil.foreground}: true when {@code targetRev} is reachable from
     * {@code startRev} via a chain that freely alternates changelog-descendant steps and
     * local-obsstore-successor steps. Self-contained near-duplicate of {@link PushCommand}'s own
     * private method of the same name (kept separate deliberately -- see this file's other
     * matrix-test-adjacent javadocs for why this codebase generally prefers small isolated copies
     * of this kind of DAG-walk helper over a shared utility coupling two independently-verified
     * commands). */
    private boolean isInForeground(Revlog changelog, int startRev, int targetRev,
                                    Map<String, List<String>> obsSuccessors,
                                    Map<Integer, List<Integer>> childrenByRev) throws IOException {
        if (startRev == targetRev) {
            return true;
        }
        Set<Integer> visited = new HashSet<>();
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(startRev);
        visited.add(startRev);
        while (!stack.isEmpty()) {
            int cur = stack.pop();
            if (cur == targetRev) {
                return true;
            }
            for (int child : childrenByRev.getOrDefault(cur, List.of())) {
                if (visited.add(child)) {
                    stack.push(child);
                }
            }
            String curHex = NodeIdUtil.toHex(changelog.getIndexRecord(cur).getNodeId());
            for (String succHex : obsSuccessors.getOrDefault(curHex, List.of())) {
                int succRev = changelog.findRevision(NodeIdUtil.fromHex(succHex));
                if (succRev != -1 && visited.add(succRev)) {
                    stack.push(succRev);
                }
            }
        }
        return false;
    }

    /** All child revisions of every revision in the local changelog, {@code rev -> [children]}. */
    private Map<Integer, List<Integer>> buildChildrenMap(Revlog changelog) throws IOException {
        Map<Integer, List<Integer>> children = new HashMap<>();
        int count = changelog.getRevisionCount();
        for (int i = 0; i < count; i++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(i);
            if (rec.getParent1() >= 0) {
                children.computeIfAbsent(rec.getParent1(), k -> new ArrayList<>()).add(i);
            }
            if (rec.getParent2() >= 0) {
                children.computeIfAbsent(rec.getParent2(), k -> new ArrayList<>()).add(i);
            }
        }
        return children;
    }

    /** Reads and decodes this repository's own {@code .hg/store/obsstore} (if any) into a
     * {@code predecessor-hex -> [successor-hex, ...]} map. Empty (never {@code null}) when the
     * repo has no obsstore at all. */
    private Map<String, List<String>> loadObsSuccessorMap() throws IOException {
        File obsstoreFile = new File(repository.getStoreDir(), "obsstore");
        if (!obsstoreFile.exists() || obsstoreFile.length() == 0) {
            return Map.of();
        }
        byte[] bytes = Files.readAllBytes(obsstoreFile.toPath());
        List<HgObsMarker> markers = HgObsolescenceParser.parse(bytes);
        Map<String, List<String>> map = new HashMap<>();
        for (HgObsMarker marker : markers) {
            String predHex = NodeIdUtil.toHex(marker.getPredecessor());
            List<String> succHexes = map.computeIfAbsent(predHex, k -> new ArrayList<>());
            for (byte[] succ : marker.getSuccessors()) {
                succHexes.add(NodeIdUtil.toHex(succ));
            }
        }
        return map;
    }

    private void writeBookmarks(File file, Map<String, String> bookmarks) throws IOException {
        if (bookmarks.isEmpty()) {
            // Real hg (`hg bookmarks --delete` on the last remaining bookmark) leaves
            // `.hg/bookmarks` behind as a 0-byte file rather than deleting it -- it is only ever
            // removed by real hg if it never existed in the first place. Match that: once the
            // file exists, keep it (now empty) instead of unlinking it.
            if (file.exists()) {
                SafeFileIO.writeStringAtomic(file, "");
            }
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : bookmarks.entrySet()) {
            sb.append(entry.getValue()).append(" ").append(entry.getKey()).append("\n");
        }
        SafeFileIO.writeStringAtomic(file, sb.toString());
    }


}
