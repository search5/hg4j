package com.github.search5.hg4j.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class EscapeAndMetadataTest {

    @Test
    public void testFileStartingWithMetaMarker(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("meta.i").toFile();
        File datFile = tempDir.resolve("meta.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);

        // Content starting with \x01\n
        byte[] originalContent = "\u0001\nHello World starting with meta marker\n".getBytes(StandardCharsets.UTF_8);
        byte[] pNode = new byte[20];

        byte[] node = revlog.appendRevision(originalContent, -1, -1, pNode, pNode, 0);
        assertNotNull(node);

        // Read it back
        byte[] readBack = revlog.getRevisionContent(0);
        assertArrayEquals(originalContent, readBack, "Content starting with \\x01\\n must be correctly unescaped to match the original bytes.");

        // Verify raw content on disk indeed has escaping: prefixed with \x01\n\x01\n
        byte[] rawContent = revlog.getRawRevisionContent(0);
        assertTrue(rawContent.length >= 4);
        assertEquals('\u0001', (char) rawContent[0]);
        assertEquals('\n', (char) rawContent[1]);
        assertEquals('\u0001', (char) rawContent[2]);
        assertEquals('\n', (char) rawContent[3]);
        
        // Check that the rest is the original content
        byte[] restOfRaw = Arrays.copyOfRange(rawContent, 4, rawContent.length);
        assertArrayEquals(originalContent, restOfRaw);
    }

    @Test
    public void testMetadataParsing(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("meta.i").toFile();
        File datFile = tempDir.resolve("meta.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);

        byte[] content = "File content".getBytes(StandardCharsets.UTF_8);
        Map<String, String> metadata = Map.of(
            "copy", "src/original.txt",
            "copyrev", "1a2b3c4d5e6f1a2b3c4d5e6f1a2b3c4d5e6f1a2b"
        );
        byte[] pNode = new byte[20];

        byte[] node = revlog.appendRevision(content, metadata, -1, -1, pNode, pNode, 0);
        assertNotNull(node);

        // Verify normal content is returned
        byte[] readBack = revlog.getRevisionContent(0);
        assertArrayEquals(content, readBack);

        // Verify metadata map is returned correctly
        Map<String, String> readMeta = revlog.getRevisionMetadata(0);
        assertEquals("src/original.txt", readMeta.get("copy"));
        assertEquals("1a2b3c4d5e6f1a2b3c4d5e6f1a2b3c4d5e6f1a2b", readMeta.get("copyrev"));
    }

    @Test
    public void testGcCommandWithMetadataFiles(@TempDir Path tempDir) throws Exception {
        // 1. 임시 리포지토리 생성
        File repoDir = tempDir.resolve("repo").toFile();
        com.github.search5.hg4j.core.HgRepository repo = com.github.search5.hg4j.api.Hg.init().setDirectory(repoDir).call();

        // 2. 메타데이터(복사 등)를 가지는 파일의 Revlog 생성 및 리비전 추가
        File idxFile = new File(repo.getStoreDir(), "data/test.i");
        File datFile = new File(repo.getStoreDir(), "data/test.d");
        idxFile.getParentFile().mkdirs();

        Revlog revlog = new Revlog(idxFile, datFile);
        byte[] content1 = "First version content".getBytes(StandardCharsets.UTF_8);
        Map<String, String> metadata1 = Map.of(
            "copy", "source_file.txt",
            "copyrev", "1234567890123456789012345678901234567890"
        );
        byte[] pNode = new byte[20];
        byte[] node1 = revlog.appendRevision(content1, metadata1, -1, -1, pNode, pNode, 0);

        byte[] content2 = "Second version content".getBytes(StandardCharsets.UTF_8);
        byte[] node2 = revlog.appendRevision(content2, null, 0, -1, node1, pNode, 1);

        // 3. Compaction(GcCommand) 실행 전 상태 검증
        assertArrayEquals(content1, revlog.getRevisionContent(0));
        assertEquals("source_file.txt", revlog.getRevisionMetadata(0).get("copy"));
        assertArrayEquals(content2, revlog.getRevisionContent(1));

        // fncache에 경로 등록 (GcCommand가 fncache를 빌드할 수 있도록)
        File fncacheFile = new File(repo.getStoreDir(), "fncache");
        java.nio.file.Files.writeString(fncacheFile.toPath(), "data/test.i\n");


        // 4. GcCommand 실행
        com.github.search5.hg4j.api.GcCommand gc = new com.github.search5.hg4j.api.GcCommand(repo);
        String report = gc.call();
        assertTrue(report.contains("GC / Compaction complete"));

        // 5. Compaction 실행 후 상태 검증 - 데이터 손상이나 델타 불일치 없이 정확히 불러와지는가?
        // Revlog 캐시 클리어 후 다시 로드
        repo.clearRevlogCache();
        Revlog compactedRevlog = new Revlog(idxFile, datFile);

        assertEquals(2, compactedRevlog.getRevisionCount());
        assertArrayEquals(content1, compactedRevlog.getRevisionContent(0));
        assertEquals("source_file.txt", compactedRevlog.getRevisionMetadata(0).get("copy"));
        assertArrayEquals(content2, compactedRevlog.getRevisionContent(1));
    }
}

