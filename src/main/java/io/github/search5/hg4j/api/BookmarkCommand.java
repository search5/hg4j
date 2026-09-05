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

/**
 * Commands for bookmark management (listing, creating, or deleting bookmarks).
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
     * this, real hg 7.2 aborts with {@code bookmark '<name>' already exists (use -f to force)}
     * (verified directly against the CLI, 2026-09-05) -- a plain fast-forward move (new target is
     * a descendant of the current one) is always allowed without {@code -f}, exactly like a brand
     * new bookmark name. Irrelevant to {@link #setDelete} (removal never requires force) and to
     * {@link #setActive} (that only touches {@code bookmarks.current}, never a bookmark's target).
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
            // 실제 hg CLI로 확인(2026-09-01): `hg bookmark NAME`을 -r 없이(즉 현재 작업 사본
            // 부모를 암묵적으로 대상으로) 실행하면 새 bookmark가 자동으로 active(현재
            // 체크아웃의 "*" 표시)가 된다. -r로 리비전을 명시하면(설령 그 값이 현재 부모와
            // 같더라도) active가 되지 않는다 — 판단 기준은 "명시적으로 지정했는가" 자체다.
            boolean explicitTarget = nodeId != null;
            byte[] targetNode = nodeId;
            if (targetNode == null) {
                targetNode = repository.getDirstate().getParent1();
            }
            String hex = NodeIdUtil.toHex(targetNode).substring(0, 40);

            // real hg 7.2: moving an EXISTING bookmark to a non-descendant revision without -f
            // aborts instead of silently moving it (verified against the CLI, 2026-09-05) --
            // exactly the same gate TagCommand already has for retagging (backlog #36). A no-op
            // "move" to the same target, and any brand-new bookmark name, are both exempt.
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
     * pull/fetch 시 원격 bookmark를 로컬과 병합한다 — {@code mercurial/bookmarks.py}의
     * {@code comparebookmarks()}/{@code validdest()} 로직을 단순화해 재현: 로컬이 없으면
     * 새로 생성, 값이 같으면 무시, remote가 local의 자손(fast-forward)이면 그냥 전진,
     * local이 remote의 자손이면 로컬이 더 앞서 있으므로 유지, 어느 쪽도 아니면(진짜
     * divergence) {@code name@remotePathName} 형태의 분기 bookmark를 생성한다.
     *
     * <p>이 메서드가 나오기 전에는 {@code FetchCommand}가 "remote가 가리키는 노드를 로컬이
     * 갖고 있으면 무조건 덮어쓰기"만 했고, {@code PullCommand}가 별도로(그리고
     * FetchCommand가 이미 덮어쓴 뒤라 사실상 죽은 코드로) 단순 하드코딩된
     * {@code name+"@default"} 분기 로직을 갖고 있었다 — 진짜 divergence를 탐지하지 못하고
     * 로컬의 독자적인 bookmark 이동을 조용히 덮어써버리는 데이터 손실 버그였다
     * (2026-09-01 발견·수정, Track B-3).</p>
     *
     * @param remotePathName divergent bookmark 이름에 붙일 접미사(예: 원격 경로 별칭).
     *                       모르면 {@code null} — 이 경우 "1"을 사용한다(실제 hg의
     *                       {@code name@1} 폴백과 동일한 형태).
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
                // 원격이 가리키는 리비전을 아직 로컬이 갖고 있지 않음 (fetch 실패/부분 실패) — 건드리지 않음.
                continue;
            }
            if (localRev == -1) {
                // 로컬 bookmark가 더 이상 존재하지 않는 리비전을 가리킴(strip 등) — 원격 값을 그대로 채택.
                // 조상 관계를 판정할 기준 리비전 자체가 없으므로 새 force 게이트를 우회한다
                // (이 분기는 이미 "원격을 그대로 채택"이라는 올바른 판단을 스스로 내린 뒤이다).
                new BookmarkCommand(repository).setBookmarkName(name).setRevision(remoteHex).setForce(true).call();
                continue;
            }

            if (graph.isAncestor(localRev, remoteRev)) {
                // fast-forward: 원격이 로컬의 자손 → 그냥 전진.
                new BookmarkCommand(repository).setBookmarkName(name).setRevision(remoteHex).call();
            } else if (graph.isAncestor(remoteRev, localRev)) {
                // 로컬이 이미 원격보다 앞서 있음 → 유지.
            } else {
                // 진짜 divergence: 로컬은 그대로 두고 분기 bookmark를 만든다.
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
     * successor steps, exactly like {@link PushCommand}'s own {@code isInForeground} (verified
     * against real hg 7.2, 2026-09-05: amending a bookmarked commit and moving the bookmark to the
     * amendment succeeds locally without {@code -f}). Either hex failing to resolve to a known
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
        java.util.Set<Integer> visited = new java.util.HashSet<>();
        java.util.Deque<Integer> stack = new java.util.ArrayDeque<>();
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
        Map<Integer, List<Integer>> children = new java.util.HashMap<>();
        int count = changelog.getRevisionCount();
        for (int i = 0; i < count; i++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(i);
            if (rec.getParent1() >= 0) {
                children.computeIfAbsent(rec.getParent1(), k -> new java.util.ArrayList<>()).add(i);
            }
            if (rec.getParent2() >= 0) {
                children.computeIfAbsent(rec.getParent2(), k -> new java.util.ArrayList<>()).add(i);
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
        List<io.github.search5.hg4j.obsolete.HgObsMarker> markers = io.github.search5.hg4j.obsolete.HgObsolescenceParser.parse(bytes);
        Map<String, List<String>> map = new java.util.HashMap<>();
        for (io.github.search5.hg4j.obsolete.HgObsMarker marker : markers) {
            String predHex = NodeIdUtil.toHex(marker.getPredecessor());
            List<String> succHexes = map.computeIfAbsent(predHex, k -> new java.util.ArrayList<>());
            for (byte[] succ : marker.getSuccessors()) {
                succHexes.add(NodeIdUtil.toHex(succ));
            }
        }
        return map;
    }

    private void writeBookmarks(File file, Map<String, String> bookmarks) throws IOException {
        if (bookmarks.isEmpty()) {
            // Real hg (verified against the CLI, 2026-09-05: `hg bookmarks --delete` on the last
            // remaining bookmark) leaves `.hg/bookmarks` behind as a 0-byte file rather than
            // deleting it -- it is only ever removed by real hg if it never existed in the first
            // place. Match that: once the file exists, keep it (now empty) instead of unlinking it.
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
