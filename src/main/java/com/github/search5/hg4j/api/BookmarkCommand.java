package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.github.search5.hg4j.util.SafeFileIO;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Commands for bookmark management (listing, creating, or deleting bookmarks).
 */
public class BookmarkCommand {
    private final HgRepository repository;
    private String bookmarkName;
    private byte[] nodeId;
    private boolean delete = false;
    private boolean active = false;

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
        com.github.search5.hg4j.storage.Revlog changelog = repository.getRevlog(clIdx, clDat);
        com.github.search5.hg4j.revwalk.ChangesetGraph graph = new com.github.search5.hg4j.revwalk.ChangesetGraph(changelog);

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
                new BookmarkCommand(repository).setBookmarkName(name).setRevision(remoteHex).call();
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

    private void writeBookmarks(File file, Map<String, String> bookmarks) throws IOException {
        if (bookmarks.isEmpty()) {
            if (file.exists()) {
                file.delete();
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
