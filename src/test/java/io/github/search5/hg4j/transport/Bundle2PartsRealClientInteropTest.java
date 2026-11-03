package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.testsupport.LocalHg;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bundle2 parts hg4j's HTTP server advertises, exercised by a REAL hg client (the 7.2.4 oracle): {@code bookmarks},
 * {@code listkeys}, {@code phase-heads}, {@code hgtagsfnodes} and {@code stream2} when pulling or cloning, and
 * {@code check:*}, {@code pushkey}, {@code bookmarks}, {@code phase-heads} when pushing. The server repository is made by real
 * hg and is non-publishing so that phases are visible.
 */
@Tag("interop")
class Bundle2PartsRealClientInteropTest {

    @TempDir
    Path tmp;

    private Server server;
    private String url;

    private String hg(String dir, String... args) throws Exception {
        return LocalHg.hgIn(tmp, dir, args);
    }

    @BeforeEach
    void startServer() throws Exception {
        Assumptions.assumeTrue(LocalHg.isAvailable(), "no local hg (see scripts/build-local-hg.sh)");
        Path repo = Files.createDirectories(tmp.resolve("server"));
        hg("server", "init", ".");
        Files.writeString(repo.resolve(".hg/hgrc"), "[phases]\npublish = false\n[web]\nallow-push = *\npush_ssl = false\n");
        for (int i = 0; i < 3; i++) {
            Files.writeString(repo.resolve("a.txt"), "a" + i + "\n");
            if (i == 0) {
                hg("server", "add", "a.txt");
            }
            hg("server", "commit", "-u", "dev", "-m", "c" + i);
        }
        hg("server", "bookmark", "bm1", "-r", "1");
        hg("server", "tag", "-u", "dev", "-r", "1", "v1");
        hg("server", "phase", "--public", "-r", "1");
        server = HgTestUtils.startServlet(new HgHttpWireServer(new HgRepository(repo.toFile())));
        url = "http://127.0.0.1:" + HgTestUtils.port(server) + "/";
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            HgTestUtils.stop(server);
        }
    }

    /** `hg push -B` of only a bookmark exits with 1 ("no changes found") on a real server too; the result is checked by state. */
    private String pushBookmark(String dir, String name) throws Exception {
        return LocalHg.hgTolerantIn(tmp, dir, "push", "-B", name);
    }

    private static final String LOG = "{rev} {node|short} {phase} {bookmarks}\\n";

    @Test
    @DisplayName("clone 이 북마크와 phase 를 bundle2 부품으로 받아 서버와 같은 상태가 된다")
    void cloneGetsBookmarksAndPhases() throws Exception {
        hg(".", "clone", url, "client");
        assertEquals(hg("server", "log", "-T", LOG), hg("client", "log", "-T", LOG));
        assertEquals(hg("server", "bookmarks"), hg("client", "bookmarks"));
        assertTrue(hg("client", "log", "-r", "tip", "-T", "{phase}").contains("draft"));
        assertTrue(hg("client", "log", "-r", "1", "-T", "{phase}").contains("public"));
    }

    @Test
    @DisplayName("pull 이 새 커밋, 이동한 북마크, 바뀐 phase 를 반영한다")
    void pullFollowsBookmarkAndPhaseChanges() throws Exception {
        hg(".", "clone", url, "client");
        Files.writeString(tmp.resolve("server/a.txt"), "more\n");
        hg("server", "commit", "-u", "dev", "-m", "c3");
        hg("server", "bookmark", "bm1", "-f", "-r", "tip");
        hg("server", "bookmark", "bm2", "-r", "0");
        hg("server", "phase", "--public", "-r", "2");
        hg("client", "pull");
        assertEquals(hg("server", "log", "-T", LOG), hg("client", "log", "-T", LOG));
        assertEquals(hg("server", "bookmarks"), hg("client", "bookmarks"));
    }

    @Test
    @DisplayName("push 가 북마크를 만들고 옮기고 지운다 (bookmarks 와 check:bookmarks 부품)")
    void pushBookmarks() throws Exception {
        hg(".", "clone", url, "client");
        hg("client", "bookmark", "newbm");
        pushBookmark("client", "newbm");
        assertTrue(hg("server", "bookmarks").contains("newbm"), hg("server", "bookmarks"));

        Files.writeString(tmp.resolve("client/a.txt"), "client change\n");
        hg("client", "commit", "-u", "dev", "-m", "from client");
        hg("client", "push");
        hg("client", "bookmark", "-f", "newbm", "-r", "tip");
        pushBookmark("client", "newbm");
        assertEquals(hg("client", "log", "-r", "newbm", "-T", "{node}"), hg("server", "log", "-r", "newbm", "-T", "{node}"));

        hg("client", "bookmark", "-d", "newbm");
        pushBookmark("client", "newbm");
        assertFalse(hg("server", "bookmarks").contains("newbm"), hg("server", "bookmarks"));
    }

    @Test
    @DisplayName("push 한 changeset 을 public 으로 올리면 서버 phase 도 public 이 된다 (phase-heads 부품)")
    void pushPublishesPhases() throws Exception {
        hg(".", "clone", url, "client");
        Files.writeString(tmp.resolve("client/a.txt"), "client change\n");
        hg("client", "commit", "-u", "dev", "-m", "from client");
        hg("client", "phase", "--public", "-r", "tip");
        hg("client", "push");
        assertEquals("public", hg("server", "log", "-r", "tip", "-T", "{phase}").trim());
        assertEquals(hg("client", "log", "-T", LOG), hg("server", "log", "-T", LOG));
    }

    @Test
    @DisplayName("stream2 부품으로 --stream 복제가 되고 hg verify 가 통과한다")
    void streamClone() throws Exception {
        hg(".", "clone", "--stream", url, "streamed");
        assertEquals(hg("server", "log", "-T", "{node}\n"), hg("streamed", "log", "-T", "{node}\n"));
        assertTrue(hg("streamed", "verify").contains("checking files"));
        assertEquals(hg("server", "bookmarks"), hg("streamed", "bookmarks"));
    }
}
