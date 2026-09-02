package com.github.search5.hg4j.storage;

import com.github.search5.hg4j.api.ChangingFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies reading and decoding a real changelog-v2 revision's sidedata block (copy-tracing
 * info stored by real hg's {@code exp-copies-sidedata-changeset} requirement), against a
 * repository actually created and committed to by the real {@code hg} CLI (Mercurial 7.2) — see
 * {@code src/test/resources/fixtures/sidedata-copytracing/README.md} for the exact generation
 * command, node hashes, and byte-for-byte field values this test asserts against.
 *
 * <p>The fixture has 3 revisions: rev0 adds {@code a.txt}; rev1 renames it to {@code b.txt}
 * (recorded as remove a.txt + add b.txt copied-from-p1 a.txt); rev2 adds {@code c.txt} and
 * copies {@code b.txt} to {@code d.txt}. Every expected value below was cross-checked against
 * real hg's own {@code hg debugchangedfiles &lt;rev&gt;} output on the source repository.
 */
@DisplayName("Revlog v2 sidedata — copy-tracing (SD_FILES) parsing, verified against real hg-generated fixtures")
public class SidedataCopyTracingTest {

    @TempDir
    Path tempDir;

    private void copyFixture(String resourceName, String targetName) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/sidedata-copytracing/" + resourceName)) {
            assertNotNull(in, "fixture resource missing: " + resourceName);
            Files.copy(in, tempDir.resolve(targetName), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Real hg-generated files placed under their real on-disk names (docket + UUID-suffixed companions). */
    private File setupRealFixture() throws IOException {
        copyFixture("docket.i", "00changelog.i");
        copyFixture("data.idx", "00changelog-53306201.idx");
        copyFixture("data.dat", "00changelog-eb558592.dat");
        copyFixture("data.sda", "00changelog-2ddfce29.sda");
        return tempDir.resolve("00changelog.i").toFile();
    }

    @Test
    @DisplayName("IndexRecord이 각 리비전의 sidedata offset/complen/compression mode를 정확히 파싱한다")
    void indexRecordParsesSidedataFields() throws IOException {
        File docket = setupRealFixture();
        Revlog revlog = new Revlog(docket, new File(tempDir.toFile(), "00changelog.d"));

        assertEquals(3, revlog.getRevisionCount());

        Revlog.IndexRecord rec0 = revlog.getIndexRecord(0);
        assertEquals(0L, rec0.getSidedataOffset());
        assertEquals(46, rec0.getSidedataCompLen());
        assertEquals(0, rec0.getSidedataCompressionMode(), "rev0 sidedata is stored PLAIN");

        Revlog.IndexRecord rec1 = revlog.getIndexRecord(1);
        assertEquals(46L, rec1.getSidedataOffset());
        assertEquals(60, rec1.getSidedataCompLen());
        assertEquals(0, rec1.getSidedataCompressionMode(), "rev1 sidedata is stored PLAIN");

        Revlog.IndexRecord rec2 = revlog.getIndexRecord(2);
        assertEquals(106L, rec2.getSidedataOffset());
        assertEquals(74, rec2.getSidedataCompLen());
        assertEquals(0, rec2.getSidedataCompressionMode(), "rev2 sidedata is ALSO stored PLAIN (only its main data chunk uses zstd)");
    }

    @Test
    @DisplayName("Revlog.getSidedata()가 sidedata 컨테이너를 읽고 sha1을 검증해 SD_FILES 페이로드를 반환한다")
    void getSidedataReadsAndVerifiesContainer() throws IOException {
        File docket = setupRealFixture();
        Revlog revlog = new Revlog(docket, new File(tempDir.toFile(), "00changelog.d"));

        for (int rev = 0; rev < 3; rev++) {
            Map<Integer, byte[]> sidedata = revlog.getSidedata(rev);
            assertEquals(1, sidedata.size(), "rev" + rev + " has exactly one sidedata entry (SD_FILES)");
            assertTrue(sidedata.containsKey(SidedataCodec.SD_FILES));
        }
    }

    @Test
    @DisplayName("rev0(add a.txt): added={a.txt}, copy 없음")
    void rev0IsPlainAdd() throws IOException {
        ChangingFiles files = decodeChangingFiles(0);
        assertEquals(Set.of("a.txt"), files.getAdded());
        assertEquals(Set.of("a.txt"), files.getTouched());
        assertTrue(files.getCopiedFromP1().isEmpty());
        assertTrue(files.getCopiedFromP2().isEmpty());
        assertNull(files.getCopySource("a.txt"));
    }

    @Test
    @DisplayName("rev1(rename a.txt -> b.txt): removed={a.txt}, added={b.txt}, b.txt는 a.txt로부터 p1 copy — 실제 `hg debugchangedfiles 1`과 일치")
    void rev1IsRenameRecordedAsRemoveAddCopy() throws IOException {
        ChangingFiles files = decodeChangingFiles(1);
        assertEquals(Set.of("a.txt"), files.getRemoved());
        assertEquals(Set.of("b.txt"), files.getAdded());
        assertEquals(Set.of("a.txt", "b.txt"), files.getTouched());
        assertEquals("a.txt", files.getCopiedFromP1().get("b.txt"));
        assertEquals("a.txt", files.getCopySource("b.txt"));
        assertTrue(files.getCopiedFromP2().isEmpty());
    }

    @Test
    @DisplayName("rev2(add c.txt, copy b.txt -> d.txt): d.txt는 b.txt로부터 p1 copy, b.txt 자신은 touched에 없음 — 실제 `hg debugchangedfiles 2`와 일치")
    void rev2RecordsCopyWithUntouchedSource() throws IOException {
        ChangingFiles files = decodeChangingFiles(2);
        assertEquals(Set.of("c.txt", "d.txt"), files.getAdded());
        assertEquals(Set.of("c.txt", "d.txt"), files.getTouched(), "b.txt is an untouched copy source, not itself touched by rev2");
        assertEquals("b.txt", files.getCopiedFromP1().get("d.txt"));
        assertEquals("b.txt", files.getCopySource("d.txt"));
        assertNull(files.getCopySource("c.txt"));
    }

    private ChangingFiles decodeChangingFiles(int rev) throws IOException {
        File docket = setupRealFixture();
        Revlog revlog = new Revlog(docket, new File(tempDir.toFile(), "00changelog.d"));
        Map<Integer, byte[]> sidedata = revlog.getSidedata(rev);
        return ChangingFiles.decode(sidedata.get(SidedataCodec.SD_FILES));
    }
}
