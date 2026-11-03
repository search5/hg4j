package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.testsupport.LocalHg;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * hg4j as a CLIENT against a real {@code hg serve}, next to the real {@code hg} client: after the same clone or pull both must
 * report the same phase for every changeset (exchange._pullapplyphases): a publishing server makes everything public, a
 * non-publishing one only what is not under one of its draft roots.
 */
@Tag("interop")
class PullPhasesParityInteropTest {

    @TempDir
    Path tmp;
    private ParitySupport.Fixture fx;
    private static final String VIEW = "{node} {phase} {bookmarks}\n";

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(LocalHg.isAvailable(), LocalHg::unavailableReason);
        fx = ParitySupport.createRepo(tmp);
    }

    private void server(String hgrc) throws Exception {
        Files.writeString(fx.repo().resolve(".hg/hgrc"), hgrc);
    }

    private String phases(String dir) throws Exception {
        return LocalHg.hgIn(tmp, dir, "log", "-T", VIEW);
    }

    private void cloneBoth(String url) throws Exception {
        LocalHg.hgIn(tmp, ".", "clone", url, "real-client");
        Hg.cloneRepository().setSource(url).setDirectory(new File(tmp.toFile(), "hg4j-client")).call();
    }

    @Test
    @DisplayName("게시형 서버에서 clone 하면 모든 변경셋이 public 이다 (서버에 draft 커밋이 있어도)")
    void cloneFromAPublishingServer() throws Exception {
        try (ParitySupport.RealServe serve = new ParitySupport.RealServe(fx.repo())) {
            cloneBoth("http://127.0.0.1:" + serve.port + "/");
        }
        String real = phases("real-client");
        assertTrue(real.contains("public") && !real.contains("draft"), real);
        assertEquals(real, phases("hg4j-client"));
    }

    @Test
    @DisplayName("비게시형 서버에서 clone 하면 draft 루트 아래만 draft 이다")
    void cloneFromANonPublishingServer() throws Exception {
        server("[phases]\npublish = false\n");
        LocalHg.hgIn(tmp, "server", "phase", "--public", "-r", "1");
        try (ParitySupport.RealServe serve = new ParitySupport.RealServe(fx.repo())) {
            cloneBoth("http://127.0.0.1:" + serve.port + "/");
        }
        String real = phases("real-client");
        assertTrue(real.contains("public") && real.contains("draft"), real);
        assertEquals(real, phases("hg4j-client"));
    }

    @Test
    @DisplayName("pull 이 새 커밋과 바뀐 phase 를 같게 반영한다 (게시형, 비게시형)")
    void pullFollowsPhaseChanges() throws Exception {
        for (boolean publishing : new boolean[]{true, false}) {
            Path root = Files.createDirectories(tmp.resolve(publishing ? "pub" : "nonpub"));
            ParitySupport.Fixture f = ParitySupport.createRepo(root);
            Files.writeString(f.repo().resolve(".hg/hgrc"), "[phases]\npublish = " + publishing + "\n");
            LocalHg.hgIn(root, "server", "phase", "--public", "-r", "0");
            try (ParitySupport.RealServe serve = new ParitySupport.RealServe(f.repo())) {
                String url = "http://127.0.0.1:" + serve.port + "/";
                LocalHg.hgIn(root, ".", "clone", url, "real-client");
                Hg.cloneRepository().setSource(url).setDirectory(new File(root.toFile(), "hg4j-client")).call();
                Files.writeString(f.repo().resolve("a.txt"), "after the clone\n");
                LocalHg.hgIn(root, "server", "commit", "-u", "dev", "-d", "2022-01-01 00:00 +0000", "-m", "after");
                LocalHg.hgIn(root, "server", "phase", "--public", "-r", "1");
                LocalHg.hgTolerantIn(root, "real-client", "pull");
                Hg.wrap(new io.github.search5.hg4j.lib.HgRepository(new File(root.toFile(), "hg4j-client"))).pull().setSource(url).call();
            }
            assertEquals(LocalHg.hgIn(root, "real-client", "log", "-T", VIEW), LocalHg.hgIn(root, "hg4j-client", "log", "-T", VIEW),
                    "publishing=" + publishing);
        }
    }
}
