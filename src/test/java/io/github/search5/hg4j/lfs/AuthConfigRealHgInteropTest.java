package io.github.search5.hg4j.lfs;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.InitCommand;
import io.github.search5.hg4j.api.PullCommand;
import io.github.search5.hg4j.api.UpdateCommand;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 의도적 차이 포팅 2번: the {@code [auth]} section supplies the credentials of an HTTP(S) address, for the wire protocol and
 * for the LFS blob transfer. Real hg and hg4j pull the same LFS repository through a proxy that insists on one exact Basic
 * credential and must agree on which {@code [auth]} settings let the pull through.
 */
@Tag("interop")
class AuthConfigRealHgInteropTest {

    @TempDir
    Path tempDir;

    private Process serve;
    private LfsTestProxy proxy;
    private byte[] payload;
    private static final String[] LFS = {"--config", "extensions.lfs=", "--config", "lfs.track=size(\">100\")",
            "--config", "experimental.lfs.disableusercache=true"};

    private static String[] concat(String[] head, String... tail) {
        String[] all = new String[head.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }

    @BeforeEach
    void startServer() throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled());
        payload = new byte[500];
        new Random(System.nanoTime()).nextBytes(payload);
        File src = tempDir.resolve("src").toFile();
        src.mkdirs();
        HgTestUtils.hg(src, concat(LFS, "init"));
        Files.write(new File(src, "big.bin").toPath(), payload);
        HgTestUtils.hg(src, concat(LFS, "add"));
        HgTestUtils.hg(src, concat(LFS, "commit", "-m", "one", "-u", "t"));
        File server = tempDir.resolve("srv").toFile();
        server.mkdirs();
        HgTestUtils.hg(server, concat(LFS, "init"));
        Files.writeString(new File(server, ".hg/hgrc").toPath(),
                "[extensions]\nlfs =\n[web]\npush_ssl = false\nallow-push = *\n[experimental]\nlfs.disableusercache = true\n");
        String store = "file://" + new File(server, ".hg/store/lfs/objects").getAbsolutePath();
        HgTestUtils.hg(src, concat(LFS, "--config", "lfs.url=" + store, "push", server.getAbsolutePath()));
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        serve = new ProcessBuilder("hg", "serve", "-p", String.valueOf(port), "--config", "web.push_ssl=false")
                .directory(server).redirectErrorStream(true).start();
        for (int i = 0; i < 100; i++) {
            try (java.net.Socket s = new java.net.Socket("127.0.0.1", port)) {
                break;
            } catch (java.io.IOException e) {
                Thread.sleep(100);
            }
        }
        proxy = new LfsTestProxy(port);
        proxy.requiredAuthorization = "Basic " + Base64.getEncoder().encodeToString("user:secret".getBytes());
    }

    @AfterEach
    void stop() throws Exception {
        if (proxy != null) {
            proxy.close();
        }
        if (serve != null) {
            serve.destroy();
            serve.waitFor();
        }
    }

    private static String authSection(String settings) {
        return "[auth]\n" + settings;
    }

    /** Real hg clones with the given [auth] lines; returns whether the file arrived with the right content. */
    private boolean realHgPulls(String name, String authLines) throws Exception {
        File dst = tempDir.resolve(name).toFile();
        dst.mkdirs();
        HgTestUtils.hg(dst, concat(LFS, "init"));
        Files.writeString(new File(dst, ".hg/hgrc").toPath(),
                "[extensions]\nlfs =\n[experimental]\nlfs.disableusercache = true\n[paths]\ndefault = " + proxy.url() + "\n"
                        + authSection(authLines));
        try {
            HgTestUtils.hg(dst, concat(LFS, "pull", "-u"));
        } catch (AssertionError e) {
            return false;
        }
        return Files.exists(new File(dst, "big.bin").toPath()) && java.util.Arrays.equals(payload, Files.readAllBytes(new File(dst, "big.bin").toPath()));
    }

    private boolean ourPull(String name, String authLines) throws Exception {
        File dir = tempDir.resolve(name).toFile();
        new InitCommand().setDirectory(dir).call();
        Files.writeString(new File(dir, ".hg/hgrc").toPath(),
                "[extensions]\nlfs =\n[experimental]\nlfs.disableusercache = true\n[paths]\ndefault = " + proxy.url() + "\n"
                        + authSection(authLines));
        try {
            HgRepository repo = new HgRepository(dir);
            new PullCommand(repo).call();
            new UpdateCommand(repo).call();
        } catch (Exception e) {
            return false;
        }
        File big = new File(dir, "big.bin");
        return big.exists() && java.util.Arrays.equals(payload, Files.readAllBytes(big.toPath()));
    }

    private void bothAgree(boolean expected, String authLines) throws Exception {
        proxy.refusals.set(0);
        boolean real = realHgPulls("real-" + System.nanoTime(), authLines);
        int realRefusals = proxy.refusals.getAndSet(0);
        boolean ours = ourPull("ours-" + System.nanoTime(), authLines);
        int ourRefusals = proxy.refusals.get();
        if (expected) {
            // the credentials go out only after a 401: the same number of refused requests as real hg
            assertEquals(realRefusals, ourRefusals, "refused requests, real hg vs hg4j, with:\n" + authLines);
            assertTrue(ourRefusals > 0, "the first request carried no credentials");
        }
        assertEquals(expected, real, "real hg with:\n" + authLines);
        assertEquals(expected, ours, "hg4j with:\n" + authLines);
    }

    @Test
    void withoutAnAuthSectionTheServerRefuses() throws Exception {
        bothAgree(false, "");
    }

    @Test
    void aMatchingGroupSuppliesUserAndPassword() throws Exception {
        bothAgree(true, "x.prefix = 127.0.0.1\nx.schemes = http\nx.username = user\nx.password = secret\n");
    }

    @Test
    void theDefaultSchemeIsHttpsSoAPlainHttpAddressIsNotMatched() throws Exception {
        bothAgree(false, "x.prefix = 127.0.0.1\nx.username = user\nx.password = secret\n");
    }

    @Test
    void aWildcardPrefixMatchesEveryHost() throws Exception {
        bothAgree(true, "x.prefix = *\nx.schemes = http\nx.username = user\nx.password = secret\n");
    }

    @Test
    void aPrefixOfAnotherHostIsNotMatched() throws Exception {
        bothAgree(false, "x.prefix = other.example\nx.schemes = http\nx.username = user\nx.password = secret\n");
    }

    @Test
    void theLongestMatchingPrefixWins() throws Exception {
        bothAgree(true, "short.prefix = *\nshort.schemes = http\nshort.username = nobody\nshort.password = wrong\n"
                + "long.prefix = 127.0.0.1\nlong.schemes = http\nlong.username = user\nlong.password = secret\n");
    }

    @Test
    void aPasswordMissingFromTheGroupLeavesTheServerRefusing() throws Exception {
        bothAgree(false, "x.prefix = 127.0.0.1\nx.schemes = http\nx.username = user\n");
    }
}
