package com.github.search5.hg4j.treewalk;

import com.github.search5.hg4j.HgTestUtils;
import com.github.search5.hg4j.api.Hg;
import com.github.search5.hg4j.errors.HgValidationException;
import com.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.Set;

/**
 * {@code .hg/sparse}(및 {@code %include} 프로파일) 파싱을 real hg CLI로 만든 저장소/파일
 * 형태, 그리고 {@code mercurial/sparse.py}의 {@code parseconfig}/{@code patternsforrev}
 * 소스로 직접 확인한 규칙과 대조 검증한다.
 */
@Tag("interop")
public class SparseConfigInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    @Test
    public void parsesIncludeExcludeAndProfileSections() throws Exception {
        // 2026-09-01 실제 hg CLI로 만든 .hg/sparse 파일을 그대로 재현한 픽스처.
        String raw = "[include]\n"
                + "a/*.txt\n"
                + "b\n"
                + "%include profile.sparse\n"
                + "[exclude]\n"
                + "b/skip.txt\n";
        SparseConfig cfg = SparseConfig.parse(raw);
        assertEquals(Set.of("a/*.txt", "b"), cfg.includes);
        assertEquals(Set.of("b/skip.txt"), cfg.excludes);
        assertEquals(Set.of("profile.sparse"), cfg.profiles);
    }

    @Test
    public void commentsAndBlankLinesAreIgnored() throws Exception {
        String raw = "# a comment\n\n[include]\n# another comment\nfoo\n\n";
        SparseConfig cfg = SparseConfig.parse(raw);
        assertEquals(Set.of("foo"), cfg.includes);
    }

    @Test
    public void leadingSlashPatternIsIgnoredNotFatal() throws Exception {
        String raw = "[include]\n/absolute/path\nrelative/path\n";
        SparseConfig cfg = SparseConfig.parse(raw);
        assertEquals(Set.of("relative/path"), cfg.includes);
    }

    @Test
    public void patternOutsideSectionIsRejected() {
        assertThrows(HgValidationException.class, () -> SparseConfig.parse("just/a/path\n"));
    }

    @Test
    public void includeAfterExcludeIsRejected() {
        assertThrows(HgValidationException.class, () ->
                SparseConfig.parse("[exclude]\nfoo\n[include]\nbar\n"));
    }

    @Test
    public void resolvesTrackedProfileFromManifestAndAppliesHgStarAutoInclude(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);

        new File(repoDir, "a").mkdirs();
        new File(repoDir, "c").mkdirs();
        Files.writeString(new File(repoDir, "a/1.txt").toPath(), "x");
        Files.writeString(new File(repoDir, "c/3.txt").toPath(), "x");
        Files.writeString(new File(repoDir, "profile.sparse").toPath(), "[include]\nc/*.txt\n");
        hg.add().addFile("a/1.txt").call();
        hg.add().addFile("c/3.txt").call();
        hg.add().addFile("profile.sparse").call();
        hg.commit().setAuthor("T").setMessage("c1").call();

        File sparseFile = new File(repoDir, ".hg/sparse");
        Files.writeString(sparseFile.toPath(), "[include]\na/*.txt\n%include profile.sparse\n");

        SparseConfig resolved = hg.sparseConfig(0);
        assertTrue(resolved.includes.contains("a/*.txt"));
        assertTrue(resolved.includes.contains("c/*.txt"), "profile.sparse의 include가 병합되어야 함: " + resolved.includes);
        assertTrue(resolved.includes.contains(".hg*"), "include가 비어있지 않으면 실제 hg는 .hg* 자동 include 규칙을 추가한다");
        assertTrue(resolved.profiles.contains("profile.sparse"));

        PathFilter filter = resolved.toPathFilter();
        assertTrue(filter.accept("a/1.txt"));
        assertTrue(filter.accept("c/3.txt"));
        assertFalse(filter.accept("b/2.txt"));
    }

    @Test
    public void missingProfileIsSkippedNotFatal(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);
        Files.writeString(new File(repoDir, "a.txt").toPath(), "x");
        hg.add().addFile("a.txt").call();
        hg.commit().setAuthor("T").setMessage("c1").call();

        Files.writeString(new File(repoDir, ".hg/sparse").toPath(), "[include]\na.txt\n%include nonexistent.sparse\n");

        // 존재하지 않는 프로파일이어도 예외를 던지지 않고(real hg의 ManifestLookupError
        // 처리와 동일하게) 조용히 건너뛴다. 실제 hg의 patternsforrev도 "방문한" 프로파일
        // 집합(찾았든 못 찾았든)을 그대로 profiles로 반환하므로, 여기서도 포함되어 있는
        // 것이 정상이다 — 단지 그 내용(include/exclude)만 병합에서 제외된다.
        SparseConfig resolved = assertDoesNotThrow(() -> hg.sparseConfig(0));
        assertEquals(Set.of("a.txt", ".hg*"), resolved.includes,
                "존재하지 않는 프로파일 자체의 규칙은 병합되지 않아야 함");
    }
}
