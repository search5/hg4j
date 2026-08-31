package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ObsMarkerIntegrationTest {

    @Test
    public void testObsstoreWriteOnAmend(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);

        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "initial content");
        hg.add().addFile("a.txt").call();
        hg.commit().setMessage("initial").call();

        // Amend 실행
        Files.writeString(f.toPath(), "amended content");
        byte[] amendedTip = hg.amend().setMessage("amended msg").call();

        // obsstore 파일이 생성되었는지 확인
        File obsstore = new File(repo.getStoreDir(), "obsstore");
        assertTrue(obsstore.exists(), "obsstore file must be created on amend");
        assertTrue(obsstore.length() > 40, "obsstore file must contain binary marker bytes");
    }

    @Test
    public void testObsstoreWriteOnStrip(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);

        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "initial content");
        hg.add().addFile("a.txt").call();
        byte[] tipNode = hg.commit().setMessage("initial").call();
        String tipHex = toHex(tipNode).substring(0, 40);

        // Strip 실행
        new StripCommand(repo).setRevision(tipHex).call();

        // obsstore 파일에 prune 마커가 작성되었는지 확인
        File obsstore = new File(repo.getStoreDir(), "obsstore");
        assertTrue(obsstore.exists(), "obsstore file must be created on strip (prune)");
        assertTrue(obsstore.length() > 20, "obsstore file must contain binary marker bytes");
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(2 * bytes.length);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
