package io.github.search5.hg4j.merge;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.MergeCommand;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;

/**
 * {@code .hg/merge/state2}를 실제 hg CLI와 양방향으로 대조 검증한다: (1) 실제 hg가 남긴
 * 충돌 상태 파일을 hg4j의 {@link MergeState}가 올바르게 읽는지, (2) hg4j가 남긴 충돌 상태
 * 파일을 실제 {@code hg resolve --list}가 인식하는지.
 */
@Tag("interop")
public class MergeStateInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    @Test
    public void readsRealHgProducedState2(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgTestUtils.nativeRepo(repoDir, dir -> {
            try {
                Files.writeString(new File(dir, "f.txt").toPath(), "line1\n");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "T", "-m", "c1");
        Files.writeString(new File(repoDir, "f.txt").toPath(), "line1-A\n");
        HgTestUtils.hg(repoDir, "commit", "-u", "T", "-m", "c2A");
        HgTestUtils.hg(repoDir, "update", "0");
        Files.writeString(new File(repoDir, "f.txt").toPath(), "line1-B\n");
        HgTestUtils.hg(repoDir, "commit", "-u", "T", "-m", "c2B");
        String otherNode = HgTestUtils.hg(repoDir, "log", "-r", "1", "--template", "{node}");
        String localNode = HgTestUtils.hg(repoDir, "log", "-r", "2", "--template", "{node}");

        // 병합 도구가 대화형으로 뜨지 않도록 internal:merge3를 강제한다 — 충돌은 그대로 남는다.
        ProcessBuilder pb = new ProcessBuilder("hg", "--config", "ui.merge=internal:merge3", "merge", "1");
        pb.directory(repoDir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().readAllBytes();
        p.waitFor();

        File stateFile = new File(repoDir, ".hg/merge/state2");
        assertTrue(stateFile.exists(), "실제 hg가 충돌 시 .hg/merge/state2를 남겨야 함");

        MergeState ms = MergeState.read(stateFile);
        assertEquals(localNode, NodeIdUtil.toHex(ms.local));
        assertEquals(otherNode, NodeIdUtil.toHex(ms.other));
        assertTrue(ms.state.containsKey("f.txt"));
        List<String> fields = ms.state.get("f.txt");
        assertEquals(MergeState.UNRESOLVED, fields.get(0));
        assertEquals("f.txt", fields.get(2)); // lfile
        assertEquals("f.txt", fields.get(3)); // afile
        assertEquals("f.txt", fields.get(5)); // ofile
        assertEquals(1, ms.unresolvedFiles().size());
        assertEquals("f.txt", ms.unresolvedFiles().get(0));
    }

    @Test
    public void hg4jProducedStateIsReadableByRealHgResolve(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);

        File f = new File(repoDir, "f.txt");
        Files.writeString(f.toPath(), "line1\n");
        hg.add().addFile("f.txt").call();
        hg.commit().setAuthor("T").setMessage("c1").call();

        Files.writeString(f.toPath(), "line1-A\n");
        byte[] c2a = hg.commit().setAuthor("T").setMessage("c2A").call();

        hg.update().setRevision("0").call();
        Files.writeString(f.toPath(), "line1-B\n");
        hg.commit().setAuthor("T").setMessage("c2B").call();

        MergeCommand.MergeResult result = new MergeCommand(repo).setNodeId(c2a).call();
        assertTrue(result.isConflicted());

        File stateFile = new File(repoDir, ".hg/merge/state2");
        assertTrue(stateFile.exists(), "hg4j도 충돌 시 .hg/merge/state2를 남겨야 함");

        String nativeResolveList = HgTestUtils.hg(repoDir, "resolve", "--list");
        assertEquals("U f.txt", nativeResolveList.trim());
    }
}
