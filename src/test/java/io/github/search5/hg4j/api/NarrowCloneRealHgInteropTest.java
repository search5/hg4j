package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.treewalk.HgTreeFilter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Backlog 28 (narrow clone / LFS 카테고리): gap table의 "Narrow clone / narrowspec" 행이
 * {@code HgNarrowCloneTest}(hg4j끼리만 대조)만 근거로 "✅"였던 것을, 실제 hg CLI(7.2, {@code narrow}
 * 확장, {@code mercurial/narrowspec.py})와 처음 대조한다.
 *
 * <p>대조 과정에서 발견해 고친 실제 버그들 ({@link NarrowCloneCommand}, {@link HgTreeFilter}):
 * <ul>
 *   <li>narrowspec 파일 위치: 실제 hg는 {@code .hg/narrowspec}이 아니라 {@code .hg/store/narrowspec}
 *       (+ 작업 카피 미러 {@code .hg/narrowspec.dirstate})에 쓴다.</li>
 *   <li>{@code .hg/requires}에 기록하는 requirement 키: {@code "narrowspec"}이 아니라
 *       {@code "narrowhg-experimental"}.</li>
 *   <li>narrowspec 파일 포맷: {@code [includes]}/{@code [excludes]}(복수형)가 아니라
 *       {@code [include]}/{@code [exclude]}(단수형), 각 패턴은 {@code path:}/{@code rootfilesin:}
 *       kind가 붙은 정규화된 형태(끝 슬래시 제거).</li>
 *   <li>매칭 규칙: {@code include=["srcdir"]}가 이름이 비슷한 형제 디렉터리 {@code srcdirextra/}까지
 *       잘못 매치하던 버그(단순 {@code String#startsWith}) -- 실제 hg는 경로 컴포넌트 경계를
 *       지킨다.</li>
 * </ul>
 */
@Tag("interop")
public class NarrowCloneRealHgInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
        Assumptions.assumeTrue(isNarrowExtensionAvailable(), "Native hg's narrow extension is not available. Skipping.");
    }

    private static boolean isNarrowExtensionAvailable() {
        try {
            Process p = new ProcessBuilder("hg", "--config", "extensions.narrow=", "version").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Runs real hg with the narrow extension force-enabled (this host's hg 7.2 ships it but
     * does not enable it by default). */
    private static String hgNarrow(File repoDir, String... args) throws Exception {
        String[] cmd = new String[args.length + 3];
        cmd[0] = "hg";
        cmd[1] = "--config";
        cmd[2] = "extensions.narrow=";
        System.arraycopy(args, 0, cmd, 3, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(repoDir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int code = p.waitFor();
        if (code != 0) {
            throw new AssertionError("hg " + String.join(" ", args) + " failed with exit code " + code + ": " + out);
        }
        return out;
    }

    /**
     * 시나리오 1: hg4j로 narrow clone을 만든 뒤, 실제 hg CLI({@code hg tracked}/{@code hg files}/
     * {@code hg status})로 열어서 include/exclude와 필터링된 워킹 카피를 정확히 인식하는지 확인한다.
     */
    @Test
    public void realHgRecognizesHg4jProducedNarrowClone(@TempDir Path tempDir) throws Exception {
        File srcRepoDir = tempDir.resolve("src").toFile();
        File destRepoDir = tempDir.resolve("narrow_dest").toFile();

        HgRepository srcRepo = Hg.init().setDirectory(srcRepoDir).call();
        try (Hg hgSrc = Hg.wrap(srcRepo)) {
            writeFile(srcRepoDir, "srcdir/A.java", "class A");
            writeFile(srcRepoDir, "srcdir/sub/B.java", "class B");
            writeFile(srcRepoDir, "docs/readme.txt", "doc readme");
            hgSrc.add().addFile("srcdir/A.java").addFile("srcdir/sub/B.java").addFile("docs/readme.txt").call();
            hgSrc.commit().setAuthor("Tester").setMessage("init commit").call();
        }

        Hg.narrowClone()
                .setSource(srcRepoDir.getAbsolutePath())
                .setDirectory(destRepoDir)
                .addIncludePath("srcdir")
                .addExcludePath("srcdir/sub")
                .call();

        // Real hg must recognize the requirement + narrowspec hg4j wrote well enough to just
        // work, with no abort/warning.
        assertEquals("I path:srcdir\nX path:srcdir/sub", hgNarrow(destRepoDir, "tracked"),
                "real hg's own \"hg tracked\" must report exactly the include/exclude hg4j wrote");

        String files = hgNarrow(destRepoDir, "files");
        assertEquals("srcdir/A.java", files,
                "real hg's own \"hg files\" must see only the narrow-included, non-excluded file");

        assertEquals("", hgNarrow(destRepoDir, "status"),
                "real hg must consider the hg4j-produced narrow working copy clean");
    }

    /**
     * 시나리오 2: include 패턴만 있고 exclude가 없는 단순한 경우도 동일하게 real hg가 인식하는지
     * (그리고 component-boundary 버그가 실제로 고쳐졌는지) 확인한다.
     */
    @Test
    public void realHgSeesHg4jNarrowCloneRespectsPathComponentBoundary(@TempDir Path tempDir) throws Exception {
        File srcRepoDir = tempDir.resolve("src2").toFile();
        File destRepoDir = tempDir.resolve("narrow_dest2").toFile();

        HgRepository srcRepo = Hg.init().setDirectory(srcRepoDir).call();
        try (Hg hgSrc = Hg.wrap(srcRepo)) {
            writeFile(srcRepoDir, "srcdir/A.java", "class A");
            writeFile(srcRepoDir, "srcdirextra/x.txt", "extra");
            hgSrc.add().addFile("srcdir/A.java").addFile("srcdirextra/x.txt").call();
            hgSrc.commit().setAuthor("Tester").setMessage("init commit").call();
        }

        Hg.narrowClone()
                .setSource(srcRepoDir.getAbsolutePath())
                .setDirectory(destRepoDir)
                .addIncludePath("srcdir")
                .call();

        assertEquals("I path:srcdir", hgNarrow(destRepoDir, "tracked"));
        assertEquals("srcdir/A.java", hgNarrow(destRepoDir, "files"),
                "real hg must NOT see srcdirextra/x.txt -- include=srcdir must not match the sibling srcdirextra");
    }

    /**
     * 시나리오 3 (리버스 방향): 실제 hg CLI로 narrow clone을 만들고, 그 결과물인
     * {@code .hg/store/narrowspec} 텍스트를 hg4j의 {@link HgTreeFilter#normalizeNarrowPattern}/
     * {@link HgTreeFilter#createNarrowSpecFilter}로 파싱/매칭했을 때, 실제 hg가 체크아웃한
     * 워킹 카피의 파일 목록과 정확히 같은 판정을 내리는지 확인한다.
     *
     * <p>hg4j는 narrowspec을 다른 명령(pull/status/update 등)에서 다시 읽어들이는 통합 코드가
     * 없으므로(narrow clone을 한 번 수행할 때만 그 자리에서 필터를 만들어 쓴다), 이 테스트는
     * "실제로 존재하는" 두 프리미티브({@code normalizeNarrowPattern}, {@code createNarrowSpecFilter})
     * 가 실제 hg의 narrowspec 텍스트와 매칭 판정 양쪽 모두에 대해 정확히 일치함을 확인하는 선에서
     * 검증한다.
     */
    @Test
    public void hg4jNarrowSpecFilterMatchesRealHgNarrowCloneDecisions(@TempDir Path tempDir) throws Exception {
        File srcRepoDir = tempDir.resolve("src3").toFile();
        HgTestUtils.nativeRepo(srcRepoDir, dir -> {
            try {
                writeFile(dir, "srcdir/A.java", "class A");
                writeFile(dir, "srcdir/sub/B.java", "class B");
                writeFile(dir, "docs/readme.txt", "doc");
                writeFile(dir, "other/o.txt", "other");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        HgTestUtils.hg(srcRepoDir, "add");
        HgTestUtils.hg(srcRepoDir, "commit", "-u", "T", "-m", "init");

        File destRepoDir = tempDir.resolve("narrow_dest3").toFile();
        hgNarrow(tempDir.toFile(), "clone", "--narrow", "--include", "srcdir", "--exclude", "srcdir/sub",
                srcRepoDir.getAbsolutePath(), destRepoDir.getAbsolutePath());

        File narrowSpecFile = new File(destRepoDir, ".hg/store/narrowspec");
        assertTrue(narrowSpecFile.exists());
        String specText = Files.readString(narrowSpecFile.toPath(), StandardCharsets.UTF_8);

        List<HgTreeFilter.NarrowPattern> includes = new ArrayList<>();
        List<HgTreeFilter.NarrowPattern> excludes = new ArrayList<>();
        List<HgTreeFilter.NarrowPattern> target = null;
        for (String line : specText.split("\n")) {
            if (line.isBlank()) continue;
            if (line.equals("[include]")) {
                target = includes;
            } else if (line.equals("[exclude]")) {
                target = excludes;
            } else {
                assertNotNull(target, "narrowspec pattern line found before any section header: " + line);
                target.add(HgTreeFilter.normalizeNarrowPattern(line));
            }
        }
        assertFalse(includes.isEmpty());

        HgTreeFilter filter = HgTreeFilter.createNarrowSpecFilter(includes, excludes);

        // Ground truth: what real hg actually checked out.
        try (Stream<Path> walk = Files.walk(destRepoDir.toPath())) {
            List<String> realHgCheckedOutFiles = walk.filter(Files::isRegularFile)
                    .map(p -> destRepoDir.toPath().relativize(p).toString().replace(File.separatorChar, '/'))
                    .filter(p -> !p.startsWith(".hg/"))
                    .sorted()
                    .collect(Collectors.toList());
            assertEquals(List.of("srcdir/A.java"), realHgCheckedOutFiles);
        }

        // Every candidate path from the FULL (unnarrowed) source tree must get the same
        // accept/reject verdict from hg4j's matcher as real hg's actual checkout demonstrates.
        assertTrue(filter.accept("srcdir/A.java"), "must accept the file real hg actually checked out");
        assertFalse(filter.accept("srcdir/sub/B.java"), "excluded by real hg's narrowspec");
        assertFalse(filter.accept("docs/readme.txt"), "outside the include set real hg used");
        assertFalse(filter.accept("other/o.txt"), "outside the include set real hg used");
    }

    private static void writeFile(File repoDir, String relPath, String content) throws Exception {
        File f = new File(repoDir, relPath);
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), content);
    }
}
