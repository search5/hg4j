package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.transport.HgHttpWireServer;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@code hg pull -r REV}: only the looked up revisions and their ancestors come over (exchange._pullchangeset). */
class PullRevisionsTest {

    @TempDir
    Path tempDir;

    private HgRepository server;
    private Server http;
    private final String[] nodes = new String[4];

    @BeforeEach
    void start() throws Exception {
        File dir = tempDir.resolve("server").toFile();
        server = Hg.init().setDirectory(dir).call();
        for (int i = 0; i < 4; i++) {
            Files.writeString(new File(dir, "f.txt").toPath(), "v" + i + "\n");
            new AddCommand(server).call();
            nodes[i] = NodeIdUtil.toHex(new CommitCommand(server).setMessage("c" + i).setAuthor("t").call());
        }
        new BookmarkCommand(server).setBookmarkName("mark").setRevision(nodes[1]).call();
        http = HgTestUtils.startServlet(new HgHttpWireServer(server));
    }

    @AfterEach
    void stop() {
        HgTestUtils.stop(http);
    }

    private String url() {
        return "http://127.0.0.1:" + HgTestUtils.port(http);
    }

    private HgRepository emptyClient(String name) throws Exception {
        return Hg.init().setDirectory(tempDir.resolve(name).toFile()).call();
    }

    private static int revisions(HgRepository repo) throws Exception {
        return repo.getRevlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d")).getRevisionCount();
    }

    @Test
    void aFullHashAndItsAncestorsOnly() throws Exception {
        try (HgRepository c = emptyClient("full")) {
            new PullCommand(c).setSource(url()).setRevisions(List.of(nodes[1])).call();
            assertEquals(2, revisions(c));
        }
    }

    @Test
    void aShortHashAndABookmarkAreLookedUpOnTheRemote() throws Exception {
        try (HgRepository c = emptyClient("short")) {
            new PullCommand(c).setSource(url()).setRevisions(List.of(nodes[0].substring(0, 12))).call();
            assertEquals(1, revisions(c));
            new PullCommand(c).setSource(url()).setRevisions(List.of("mark")).call();
            assertEquals(2, revisions(c));
        }
    }

    @Test
    void severalRevisionsAndThenEverything() throws Exception {
        try (HgRepository c = emptyClient("several")) {
            new PullCommand(c).setSource(url()).setRevisions(List.of(nodes[0], nodes[2])).call();
            assertEquals(3, revisions(c));
            new PullCommand(c).setSource(url()).call();
            assertEquals(4, revisions(c));
        }
    }

    @Test
    void anUnknownRevisionIsReportedWithTheRemotesMessage() throws Exception {
        try (HgRepository c = emptyClient("unknown")) {
            HgValidationException e = assertThrows(HgValidationException.class,
                    () -> new PullCommand(c).setSource(url()).setRevisions(List.of("nonexistent")).call());
            assertTrue(e.getMessage().contains("nonexistent"), e.getMessage());
            assertEquals(0, revisions(c), "nothing came over");
        }
    }

    @Test
    void aLocalSourceLooksUpToo() throws Exception {
        try (HgRepository c = emptyClient("local")) {
            new PullCommand(c).setSource(server.getDirectory().getAbsolutePath()).setRevisions(List.of(nodes[2])).call();
            assertEquals(3, revisions(c));
        }
    }
}
