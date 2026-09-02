package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.phase.PhaseRoots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Additional coverage for {@link SummaryCommand}, focused on the defensive branches that
 * {@code SummaryCommandInteropTest}'s always-consistent repositories never exercise: the
 * changelog-missing fallback while the dirstate still points at a parent, and the two
 * swallowed-exception paths (description parsing, phase lookup) that only trigger against a
 * corrupted store.
 */
public class SummaryCommandCoverageTest {

    @Test
    public void testMissingChangelogFallsBackToUnresolvedParentInfo(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);
        Files.writeString(new File(repoDir, "a.txt").toPath(), "one");
        hg.add().addFile("a.txt").call();
        hg.commit().setAuthor("T").setMessage("c1").call();

        // dirstate는 여전히 커밋된 부모를 가리키지만 00changelog.i 자체를 제거해, changelog가
        // 없는데도 ParentsCommand는 부모 hex를 돌려주는 상황(SummaryCommand.call()의
        // changelog == null 분기)을 재현한다.
        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Files.delete(clIdx.toPath());
        Files.deleteIfExists(clDat.toPath());

        SummaryCommand.SummaryInfo info = new SummaryCommand(repo).call();
        assertEquals(1, info.parents().size());
        SummaryCommand.ParentInfo p = info.parents().get(0);
        assertEquals(-1, p.revision());
        assertEquals("", p.description());
        // phase 계산은 changelog != null을 요구하므로 changelog가 없을 때는 기본값(PUBLIC)으로
        // 남는다.
        assertEquals(PhaseRoots.Phase.PUBLIC, info.currentPhase());
    }

    @Test
    public void testCorruptedChangelogDataIgnoredDuringDescriptionParse(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);
        Files.writeString(new File(repoDir, "a.txt").toPath(), "one");
        hg.add().addFile("a.txt").call();
        hg.commit().setAuthor("T").setMessage("c1").call();

        // 인덱스(00changelog.i)는 그대로 두어 findRevision은 성공하지만, 데이터 파일
        // (00changelog.d)을 제거해 getRevisionContent()가 실패하도록 store를 손상시킨다 —
        // 커밋 설명(description)을 채우는 try/catch의 catch 분기를 재현한다.
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Files.delete(clDat.toPath());

        SummaryCommand.SummaryInfo info = new SummaryCommand(repo).call();
        assertEquals(1, info.parents().size());
        SummaryCommand.ParentInfo p = info.parents().get(0);
        assertEquals(0, p.revision());
        assertEquals("", p.description(), "설명 파싱이 실패하면 빈 문자열로 남아야 함");
    }

    @Test
    public void testCorruptedPhaserootsIgnoredDuringPhaseLookup(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);
        Files.writeString(new File(repoDir, "a.txt").toPath(), "one");
        hg.add().addFile("a.txt").call();
        hg.commit().setAuthor("T").setMessage("c1").call();

        // phaseroots 파일에 잘못된(비UTF-8) 바이트를 써서 PhaseRoots 로딩이 IOException을
        // 던지게 만든다 — SummaryCommand.call()의 phase 계산 try/catch가 이를 삼키는지
        // 재현한다.
        File phaseroots = new File(repo.getStoreDir(), "phaseroots");
        Files.write(phaseroots.toPath(), new byte[] {(byte) 0x80, (byte) 0xC1, 0x01});

        SummaryCommand.SummaryInfo info = new SummaryCommand(repo).call();
        assertEquals(1, info.parents().size());
        // 예외가 삼켜지므로 phase는 초기값(PUBLIC)으로 남는다.
        assertEquals(PhaseRoots.Phase.PUBLIC, info.currentPhase());
        assertTrue(true);
    }

    @Test
    public void testDirstateParentNotInChangelogYieldsUnresolvedRevision(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);
        Files.writeString(new File(repoDir, "a.txt").toPath(), "one");
        hg.add().addFile("a.txt").call();
        hg.commit().setAuthor("T").setMessage("c1").call();

        // changelog는 그대로 두되, dirstate의 parent1을 changelog에 존재하지 않는 가짜
        // 20바이트 노드로 덮어써 changelog.findRevision()이 -1을 반환하는 상황(SummaryCommand
        // .call()의 `if (rev != -1)` false 분기)을 재현한다.
        byte[] bogusNode = new byte[20];
        for (int i = 0; i < bogusNode.length; i++) {
            bogusNode[i] = (byte) (0xAA + i);
        }
        Dirstate dirstate = repo.getDirstate();
        dirstate.setParents(bogusNode, new byte[20]);
        repo.writeDirstate(dirstate);

        SummaryCommand.SummaryInfo info = new SummaryCommand(repo).call();
        assertEquals(1, info.parents().size());
        SummaryCommand.ParentInfo p = info.parents().get(0);
        assertEquals(-1, p.revision(), "changelog에 없는 부모는 rev -1로 남아야 함");
        assertEquals("", p.description());
    }

    @Test
    public void testParseChangelogHeaderWithoutBlankLineYieldsNoDescription() throws Exception {
        // parseChangelogHeader()는 실제 커밋이라면 항상 "\n\n" 구분자가 있는 콘텐츠만 받지만,
        // 방어적으로 그 구분자가 없는(손상된) 콘텐츠도 처리한다 — private 메서드를 리플렉션으로
        // 직접 호출해 `blank != -1`이 false인 분기를 재현한다.
        Method m = SummaryCommand.class.getDeclaredMethod("parseChangelogHeader", byte[].class);
        m.setAccessible(true);
        SummaryCommand cmd = new SummaryCommand(null);
        byte[] noBlankLine = "manifestnode\nuser\ndate\nfiles-with-no-blank-separator"
                .getBytes(StandardCharsets.UTF_8);

        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) m.invoke(cmd, (Object) noBlankLine);
        assertTrue(result.isEmpty(), "\\n\\n 구분자가 없으면 desc 키가 채워지지 않아야 함");
    }
}
