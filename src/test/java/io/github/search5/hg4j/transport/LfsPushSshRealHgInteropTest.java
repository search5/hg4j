package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.InitCommand;
import io.github.search5.hg4j.api.PushCommand;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 의도적 차이 포팅 1번: SSH push가 서버의 번들2 응답(reply:changegroup, error:abort)을 해석한다.
 * 서버는 실제 hg(serve --stdio)이고 클라이언트는 hg4j다.
 */
@Tag("interop")
class LfsPushSshRealHgInteropTest {

    @TempDir
    Path tempDir;

    private SshMatrixServer server;

    @BeforeEach
    void start() throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled());
        Assumptions.assumeTrue(SshMatrixServer.isSshKeygenAvailable());
        server = SshMatrixServer.start(tempDir);
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private String[] lfs() {
        return new String[]{"--config", "extensions.lfs=", "--config", "experimental.lfs.disableusercache=true"};
    }

    private File serverRepo(String extraHgrc) throws Exception {
        File dir = tempDir.resolve("server").toFile();
        dir.mkdirs();
        HgTestUtils.hg(dir, "init");
        Files.writeString(new File(dir, ".hg/hgrc").toPath(),
                "[extensions]\nlfs =\n[lfs]\ndisableusercache = true\n[experimental]\nlfs.disableusercache = true\n" + extraHgrc);
        return dir;
    }

    private HgRepository client(File store) throws Exception {
        File dir = tempDir.resolve("client").toFile();
        new InitCommand().setDirectory(dir).call();
        Files.writeString(new File(dir, ".hg/hgrc").toPath(),
                "[extensions]\nlfs =\n[lfs]\ntrack = size(\">100\")\nurl = file://" + store.getAbsolutePath()
                        + "\n[experimental]\nlfs.disableusercache = true\n");
        HgRepository repo = new HgRepository(dir);
        byte[] payload = new byte[600];
        new Random(System.nanoTime()).nextBytes(payload);
        Files.write(new File(dir, "big.bin").toPath(), payload);
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("one").setAuthor("t").call();
        return new HgRepository(dir);
    }

    @Test
    void anLfsPushOverSshIsAnsweredWithABundle2ReplyThatIsRead() throws Exception {
        File dst = serverRepo("");
        File store = tempDir.resolve("store").toFile();
        HgRepository src = client(store);
        new PushCommand(src).setDestination(server.url(dst)).call();

        assertEquals("one", HgTestUtils.hg(dst, "log", "--template", "{desc}").trim());
        assertTrue(store.isDirectory() && store.list().length > 0, "the blob reached the file store");
    }

    @Test
    void aRefusalByTheServerSurfacesAsAnErrorNotASilentSuccess() throws Exception {
        File dst = serverRepo("[hooks]\npretxnchangegroup.deny = false\n");
        File store = tempDir.resolve("store").toFile();
        HgRepository src = client(store);
        IOException failure = assertThrows(IOException.class,
                () -> new PushCommand(src).setDestination(server.url(dst)).call());
        assertTrue(failure.getMessage().toLowerCase().contains("abort")
                        || failure.getMessage().contains("pretxnchangegroup"), failure.getMessage());
        assertEquals("", HgTestUtils.hg(dst, "log", "--template", "{desc}").trim(), "nothing was stored");
    }
}
