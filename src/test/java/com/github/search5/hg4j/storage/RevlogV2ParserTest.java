package com.github.search5.hg4j.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Revlog v2 docket/index 파싱 검증.
 *
 * <p>모든 픽스처는 실제 {@code hg} CLI(Mercurial 7.2)로
 * {@code hg --config format.exp-use-changelog-v2=enable-unstable-format-and-corrupt-my-data init}
 * 실행 후 2회 커밋해서 얻은 실물 changelog-v2 바이너리다 (src/test/resources/fixtures/revlogv2-changelog/README.md 참고).
 * 자체 제작한 가짜 바이트가 아니라 실제 Mercurial이 생성한 데이터로 검증한다.</p>
 */
@DisplayName("Revlog v2 (changelog-v2) docket/index parsing — verified against real hg-generated fixtures")
public class RevlogV2ParserTest {

    @TempDir
    Path tempDir;

    private void copyFixture(String resourceName, String targetName) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/revlogv2-changelog/" + resourceName)) {
            assertNotNull(in, "fixture resource missing: " + resourceName);
            Files.copy(in, tempDir.resolve(targetName), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 실제 hg가 만든 4개 파일을 실제와 동일한 이름(00changelog.i + UUID 접미사)으로 임시 디렉터리에 배치. */
    private File setupRealFixture() throws IOException {
        copyFixture("docket.i", "00changelog.i");
        copyFixture("data.idx", "00changelog-4ac9cebf.idx");
        copyFixture("data.dat", "00changelog-b2964fda.dat");
        copyFixture("data.sda", "00changelog-61b7777d.sda");
        return tempDir.resolve("00changelog.i").toFile();
    }

    @Test
    @DisplayName("실제 docket 헤더(59바이트 S_HEADER)를 정확히 파싱한다")
    void testParseRealDocketHeader() throws IOException {
        File docket = setupRealFixture();
        RevlogIndex index = new RevlogIndex(docket);

        assertTrue(index.isV2(), "changelog-v2 매직(0xD34D)이 감지되어야 함");
        // 실제 hg가 기록한 값 (python struct로 직접 검증됨)
        assertEquals(0xD34D, index.getVersionHeader() & 0xFFFF);
        assertEquals(0xc0, index.getDocketIndexEnd(), "index_end는 2레코드*96바이트=192(0xc0)");
        assertEquals(0xd0, index.getDocketDataEnd(), "data_end는 실제 zstd 압축 데이터 총합 208(0xd0)");
    }

    @Test
    @DisplayName("docket의 UUID로 실제 index/data 파일을 정확히 찾아 연결한다")
    void testResolvesCompanionFilesByUuid() throws IOException {
        File docket = setupRealFixture();
        RevlogIndex index = new RevlogIndex(docket);

        File resolvedIndex = index.getResolvedIndexFile();
        File resolvedData = index.getResolvedDataFile();
        assertEquals("00changelog-4ac9cebf.idx", resolvedIndex.getName());
        assertEquals("00changelog-b2964fda.dat", resolvedData.getName());
        assertTrue(resolvedIndex.exists());
        assertTrue(resolvedData.exists());
    }

    @Test
    @DisplayName("실제 2개 리비전의 node id를 정확히 파싱한다 (hg log와 대조)")
    void testParsesRealNodeIds() throws IOException {
        File docket = setupRealFixture();
        RevlogIndex index = new RevlogIndex(docket);

        assertEquals(2, index.getRevisionCount());
        // 실제 `hg log --template '{rev}:{node}\n'` 출력과 대조된 값
        assertEquals(0, index.findRevision(com.github.search5.hg4j.util.NodeIdUtil.fromHex(
                "78b00ae5215f47873d210b4c43e9d9adad40e2fb")));
        assertEquals(1, index.findRevision(com.github.search5.hg4j.util.NodeIdUtil.fromHex(
                "a982e222c0e75569b3b55869ec11c16a5944543b")));
    }

    @Test
    @DisplayName("getIndexRecord()가 docket이 아니라 실제 companion 인덱스 파일에서 올바른 오프셋/길이를 읽는다")
    void testGetIndexRecordReadsFromCompanionFile() throws IOException {
        File docket = setupRealFixture();
        RevlogIndex index = new RevlogIndex(docket);

        Revlog.IndexRecord rec0 = index.getIndexRecord(0);
        assertEquals(0, rec0.getOffset());
        assertEquals(103, rec0.getCompLen());
        assertEquals(106, rec0.getUncompLen());
        assertEquals(-1, rec0.getParent1());
        assertEquals(-1, rec0.getParent2());

        Revlog.IndexRecord rec1 = index.getIndexRecord(1);
        assertEquals(103, rec1.getOffset(), "rev1의 데이터 오프셋은 rev0의 압축 길이(103) 바로 뒤");
        assertEquals(105, rec1.getCompLen());
        assertEquals(107, rec1.getUncompLen());
        assertEquals(0, rec1.getParent1(), "rev1의 parent는 rev0");
        assertEquals(-1, rec1.getParent2());
    }

    @Test
    @DisplayName("end-to-end: Revlog를 통해 실제 압축된 changelog 리비전 내용을 올바르게 복원한다")
    void testRevlogDecompressesRealContentEndToEnd() throws IOException {
        File docket = setupRealFixture();
        // v1 생성자와 동일한 시그니처 — v2일 때는 datFile 인자가 무시되고 docket에서 찾은 실제 파일로 대체되어야 한다.
        Revlog revlog = new Revlog(docket, new File(tempDir.toFile(), "00changelog.d"));

        assertEquals(2, revlog.getRevisionCount());

        byte[] raw0 = revlog.getRawRevisionContent(0);
        String text0 = new String(raw0, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text0.startsWith("c180980ee057d123b053b78589cb4040f93d2c97\n"),
                "rev0 실제 복원 내용: " + text0);
        assertTrue(text0.contains("first commit"));

        byte[] raw1 = revlog.getRawRevisionContent(1);
        String text1 = new String(raw1, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text1.contains("second commit"), "rev1 실제 복원 내용: " + text1);
    }

    @Test
    @DisplayName("Revlog.appendRevision()으로 v2 저장소에 새 리비전을 쓰면 실제 디스크에 영속화되고 재오픈해도 읽힌다")
    void testAppendRevisionPersistsToDiskAndSurvivesReopen() throws IOException {
        File docket = setupRealFixture();
        Revlog revlog = new Revlog(docket, new File(tempDir.toFile(), "00changelog.d"));
        assertEquals(2, revlog.getRevisionCount());

        byte[] p1Node = com.github.search5.hg4j.util.NodeIdUtil.fromHex("a982e222c0e75569b3b55869ec11c16a5944543b");
        byte[] p2Node = new byte[20];
        byte[] content = "third commit content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        revlog.appendRevision(content, 1, -1, p1Node, p2Node, 2);

        assertEquals(3, revlog.getRevisionCount());
        assertArrayEquals(content, revlog.getRawRevisionContent(2));

        // 새 Revlog 인스턴스로 다시 열어서 디스크에 실제로 반영됐는지 확인 (in-memory 캐시 우회)
        Revlog reopened = new Revlog(docket, new File(tempDir.toFile(), "00changelog.d"));
        assertEquals(3, reopened.getRevisionCount(), "docket의 index_end/data_end가 갱신되어 재오픈 시에도 3개로 보여야 함");
        assertArrayEquals(content, reopened.getRawRevisionContent(2), "재오픈 후에도 새로 쓴 리비전 내용이 올바르게 복원돼야 함");
    }
}
