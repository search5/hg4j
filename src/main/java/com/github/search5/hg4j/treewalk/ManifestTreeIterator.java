package com.github.search5.hg4j.treewalk;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.github.search5.hg4j.storage.Revlog;
import java.io.File;
import java.io.IOException;
import java.util.*;
import com.github.search5.hg4j.errors.HgRevisionNotFoundException;
import java.nio.charset.StandardCharsets;

/**
 * TreeIterator implementation that traverses historical repository manifests.
 */
public class ManifestTreeIterator implements TreeIterator {

    private final HgRepository repository;
    private final String revision;
    private final byte[] directManifestNode;
    private final List<Entry> entries = new ArrayList<>();
    private int index = -1;

    public static class Entry {
        final String path;
        final byte[] nodeId;
        final boolean executable;
        final boolean symlink;
        /**
         * treemanifest의 {@code t}(subdirectory-pointer) 플래그. true면 {@link #nodeId}는
         * 실제 파일 콘텐츠가 아니라 {@code meta/<path>/00manifest.i}에 있는 해당 디렉터리
         * 서브매니페스트 revlog의 리비전을 가리키는 노드ID다(실제 hg
         * {@code mercurial/manifest.py}의 {@code treemanifest.parse()}: {@code fl == b't'}일
         * 때 경로에 {@code '/'}를 붙여 lazy subtree로 등록하는 것과 동일한 인코딩,
         * Docker Mercurial 6.0으로 직접 확인함 — 상세는
         * {@code src/test/resources/fixtures/treemanifest/README.md} 참고).
         * {@link #loadEntries()}가 이 항목을 만나면 재귀적으로 펼쳐서 최종
         * {@link #entries} 목록에는 절대 남지 않는다 — 순수 파싱 함수인
         * {@link #parseManifestContent(byte[])}의 결과에만 나타난다.
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

        public boolean isTreeDir() {
            return treeDir;
        }

        /** The entry's path exactly as it appears in this directory level's manifest text (a
         * bare directory name with no trailing slash when {@link #isTreeDir()}, e.g. treemanifest
         * write support in {@code api.CommitCommand} walks a parent's tree via {@link
         * #parseManifestContent} to recover per-directory node hashes for correct parent1/parent2
         * linkage on the new revisions it writes). */
        public String getPath() {
            return path;
        }

        /** This entry's node hash — a file content revision, or (when {@link #isTreeDir()}) the
         * node of that subdirectory's own {@code meta/<dir>/00manifest.i} revision. */
        public byte[] getNodeId() {
            return nodeId;
        }
    }

    public ManifestTreeIterator(HgRepository repository, String revision) {
        this.repository = repository;
        this.revision = revision;
        this.directManifestNode = null;
    }

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
     * treemanifest 저장소({@code experimental.treemanifest=1})를 재귀적으로 펼쳐 flat한
     * 파일 목록으로 만든다. {@code dirPrefix}가 빈 문자열이면 루트 매니페스트 콘텐츠,
     * 아니면 {@code meta/<dirPrefix>/00manifest.i}의 서브 매니페스트 콘텐츠다.
     *
     * <p>{@code t} 플래그가 붙은 각 항목의 경로는 실제 hg의 {@code treemanifest.parse()}
     * 처럼 해당 서브디렉터리 내부에서는 자기 자신을 기준으로 한 상대 경로만 담고 있다
     * (예: {@code sub/00manifest.i}의 콘텐츠는 {@code "b.txt"}, {@code "deep"}이지
     * {@code "sub/b.txt"}가 아니다) — 그래서 재귀 호출할 때마다 누적된 {@code dirPrefix}를
     * 붙여 완전한 저장소 루트 기준 경로로 복원해야 한다. 이 메서드가 반환하는 목록에는
     * 디렉터리 포인터 항목이 하나도 남지 않는다 — 오직 실제 파일 항목만 남으므로,
     * 이 결과를 소비하는 {@link ManifestWalk}/{@code getManifestAtCommit()} 등 기존 코드는
     * flat 매니페스트를 다루는 것과 완전히 동일하게 동작한다.
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
     * {@code meta/<dirPath>/00manifest.i}에서 서브디렉터리 매니페스트 revlog를 열어
     * {@code subManifestNode}가 가리키는 리비전의 콘텐츠를 반환한다(실제 hg의
     * {@code manifestrevlog.dirlog()}: {@code radix = "meta/" + tree + "00manifest"}와
     * 동일한 경로 규칙, {@code tree}는 트레일링 슬래시를 포함한 전체 경로).
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
