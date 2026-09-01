package com.github.search5.hg4j.obsolete;

import com.github.search5.hg4j.HgTestUtils;
import com.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;

/**
 * Obsolescence marker(Track B-5) FM1 바이너리 포맷 검증. src/test/resources/fixtures/obsstore-fm1/
 * 는 실제 hg CLI로 생성한 진짜 obsstore 파일이다 (README.md 참고).
 */
@Tag("interop")
public class HgObsolescenceRealHgInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    @Test
    public void testParsesRealHgGeneratedObsstore() throws Exception {
        byte[] realObsstore;
        try (InputStream in = getClass().getResourceAsStream("/fixtures/obsstore-fm1/obsstore")) {
            assertNotNull(in);
            realObsstore = in.readAllBytes();
        }

        List<HgObsMarker> markers = HgObsolescenceParser.parse(realObsstore);
        assertEquals(1, markers.size());
        HgObsMarker m = markers.get(0);
        assertEquals("3eb3e84d83a814e381a70541fe7eedc8655fa277", NodeIdUtil.toHex(m.getPredecessor()));
        assertEquals(1, m.getSuccessors().size());
        assertEquals("9bbd73f724f5f43fdf7bc19fb5bc225db637768d", NodeIdUtil.toHex(m.getSuccessors().get(0)));
        assertEquals("amend", m.getMetadata().get("operation"));
        assertEquals("test <test@example.com>", m.getMetadata().get("user"));
    }

    @Test
    public void testHg4jWrittenMarkerIsReadableByRealHgDebugobsolete(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgTestUtils.nativeRepo(repoDir, dir -> {
            try {
                Files.writeString(new File(dir, "a.txt").toPath(), "one");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "T", "-m", "c1");
        String predHex = HgTestUtils.hg(repoDir, "log", "-r", "0", "--template", "{node}");

        Files.writeString(new File(repoDir, "a.txt").toPath(), "two");
        HgTestUtils.hg(repoDir, "commit", "-u", "T", "-m", "c2");
        String succHex = HgTestUtils.hg(repoDir, "log", "-r", "1", "--template", "{node}");

        File storeDir = new File(repoDir, ".hg/store");
        HgObsMarker.writeMarker(storeDir, NodeIdUtil.fromHex(predHex), List.of(NodeIdUtil.fromHex(succHex)), "rebase");

        // 실제 hg가 usezstd=false로 강제되는 HgTestUtils.hg()가 아니라, evolution 설정을
        // 추가로 켠 별도 프로세스로 debugobsolete를 호출해야 한다.
        ProcessBuilder pb = new ProcessBuilder("hg", "--config", "experimental.evolution=all", "debugobsolete");
        pb.directory(repoDir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();

        assertEquals(0, exit, "실제 hg debugobsolete가 hg4j가 쓴 obsstore를 오류 없이 읽어야 함: " + output);
        assertTrue(output.contains(predHex), "predecessor node가 출력에 있어야 함: " + output);
        assertTrue(output.contains(succHex), "successor node가 출력에 있어야 함: " + output);
    }
}
