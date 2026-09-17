package io.github.search5.hg4j.lfs.server;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.transport.HgSshWireServer;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SSH counterpart of {@link HgLfsServerRealHgInteropTest} -- verifies real hg's actual topology
 * for LFS over an SSH remote (see {@code llm-wiki/decisions/lfs-server-side-batch-api-plan.md}
 * step 7): the changegroup itself can travel over SSH, but the LFS blob channel cannot -- real
 * hg's {@code hgext/lfs/blobstore.py}'s {@code remote()} only derives a {@code .git/info/lfs} URL
 * for an {@code http}/{@code https} remote, never for {@code ssh}, so an {@code ssh://} push of an
 * LFS-tracked revision with no explicit {@code [lfs] url} aborts client-side before ever reaching
 * the wire protocol's blob transfer.
 */
@Tag("interop")
public class HgLfsServerSshRealHgInteropTest {

    private SshServer sshServer;
    private int sshPort;
    private File sshKeyFile;

    @BeforeEach
    public void setUp(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
        Assumptions.assumeTrue(isLfsExtensionAvailable(), "Native hg's lfs extension is not available. Skipping.");
        Assumptions.assumeTrue(isSshKeygenAvailable(), "ssh-keygen is not available. Skipping.");

        sshKeyFile = tempDir.resolve("id_test").toFile();
        runProcess("ssh-keygen", "-t", "rsa", "-b", "2048", "-f", sshKeyFile.getAbsolutePath(), "-N", "");

        sshServer = SshServer.setUpDefaultServer();
        sshServer.setPort(0);
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempDir.resolve("host_key")));
        sshServer.setPublickeyAuthenticator(AcceptAllPublickeyAuthenticator.INSTANCE);
        sshServer.setCommandFactory((channel, command) -> new HgLfsWireCommand(command));
        sshServer.start();
        sshPort = sshServer.getPort();
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (sshServer != null) {
            sshServer.stop(true);
        }
    }

    private static boolean isLfsExtensionAvailable() {
        try {
            return new ProcessBuilder("hg", "--config", "extensions.lfs=", "version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isSshKeygenAvailable() {
        try {
            return new ProcessBuilder("ssh-keygen", "-?").start().waitFor() >= 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void runProcess(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        int code = p.waitFor();
        if (code != 0) {
            throw new AssertionError(String.join(" ", cmd) + " failed (" + code + "): " + new String(out));
        }
    }

    /** Same leading/double-slash convention as {@code HgSshWireServerRealHgInteropTest}. */
    private String sshUrl(File repoDir) {
        return "ssh://127.0.0.1:" + sshPort + "/" + repoDir.getAbsolutePath();
    }

    private String remoteCmdForTest(Path tempDir) {
        return "ssh -i " + sshKeyFile.getAbsolutePath()
                + " -o StrictHostKeyChecking=no -o UserKnownHostsFile=" + tempDir.resolve("known_hosts")
                + " -o IdentitiesOnly=yes -p " + sshPort;
    }

    /**
     * Negative case: an SSH remote alone can never carry an LFS blob -- real hg's own {@code
     * blobstore.remote()} only derives a {@code .git/info/lfs} URL for an http(s) scheme; for
     * anything else (including plain {@code ssh://}) it resolves to {@code _storemap[None]},
     * {@code _promptremote}, whose first actual use (the prepush hook's blob upload attempt)
     * aborts with "lfs.url needs to be configured" -- confirmed here against the real hg CLI
     * (not just the source), never reaching {@link HgLfsServer}, which this test deliberately
     * does not mount.
     */
    @Test
    public void pushingAnLfsFileOverSshAloneAbortsWithUnknownUrlScheme(@TempDir Path tempDir) throws Exception {
        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        Hg.init().setDirectory(serverRepoDir).call();

        File sourceDir = tempDir.resolve("source_repo").toFile();
        HgTestUtils.hg(tempDir.toFile(), "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                "clone", sshUrl(serverRepoDir), sourceDir.getAbsolutePath());
        Files.writeString(new File(sourceDir, ".hg/hgrc").toPath(),
                "[extensions]\nlfs =\n[lfs]\nthreshold = 10\n[experimental]\nlfs.disableusercache = True\n",
                StandardOpenOption.APPEND);

        byte[] content = new byte[4096];
        new Random(2026).nextBytes(content);
        Files.write(new File(sourceDir, "big.bin").toPath(), content);
        HgTestUtils.hg(sourceDir, "add", "big.bin");
        HgTestUtils.hg(sourceDir, "commit", "-u", "tester", "-m", "add big lfs file");

        AssertionError failure = assertThrows(AssertionError.class, () ->
                HgTestUtils.hg(sourceDir, "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                        "push", "--new-branch", sshUrl(serverRepoDir)));

        assertTrue(failure.getMessage().contains("lfs.url needs to be configured"),
                "real hg must abort client-side trying to resolve an LFS blob endpoint for a bare ssh:// remote: "
                        + failure.getMessage());
    }

    /**
     * Positive case: real hg's actual supported topology -- the changegroup travels over SSH,
     * while the LFS blob travels over a separately configured HTTP(S) {@code [lfs] url} pointing
     * at {@link HgLfsServer} mounted over plain HTTP for the SAME repository directory. Confirms
     * hg4j fully supports this hybrid transport, matching real hg's own documented limitation
     * (SSH never carries the blob itself, by design -- not an hg4j gap).
     */
    @Test
    public void pushingAnLfsFileOverSshWithAnExplicitHttpLfsUrlSucceeds(@TempDir Path tempDir) throws Exception {
        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgRepository serverRepo = Hg.init().setDirectory(serverRepoDir).call();

        Server lfsHttpServer = HgTestUtils.startServlets(Map.of(
                "/.git/info/lfs/*", new HgLfsServer(serverRepo),
                "/.hg/lfs/*", new HgLfsServer(serverRepo)));
        try {
            String lfsUrl = "http://127.0.0.1:" + HgTestUtils.port(lfsHttpServer) + "/.git/info/lfs";

            File sourceDir = tempDir.resolve("source_repo").toFile();
            HgTestUtils.hg(tempDir.toFile(), "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                    "clone", sshUrl(serverRepoDir), sourceDir.getAbsolutePath());
            Files.writeString(new File(sourceDir, ".hg/hgrc").toPath(),
                    "[extensions]\nlfs =\n[lfs]\nthreshold = 10\nurl = " + lfsUrl
                            + "\n[experimental]\nlfs.disableusercache = True\n",
                    StandardOpenOption.APPEND);

            byte[] originalContent = new byte[4096];
            new Random(99).nextBytes(originalContent);
            Files.write(new File(sourceDir, "big.bin").toPath(), originalContent);
            HgTestUtils.hg(sourceDir, "add", "big.bin");
            HgTestUtils.hg(sourceDir, "commit", "-u", "tester", "-m", "add big lfs file");

            HgTestUtils.hg(sourceDir, "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                    "push", "--new-branch", sshUrl(serverRepoDir));

            File destDir = tempDir.resolve("dest_repo").toFile();
            HgTestUtils.hg(tempDir.toFile(), "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                    "--config", "extensions.lfs=", "--config", "lfs.url=" + lfsUrl,
                    "--config", "experimental.lfs.disableusercache=True",
                    "clone", sshUrl(serverRepoDir), destDir.getAbsolutePath());

            byte[] restored = Files.readAllBytes(new File(destDir, "big.bin").toPath());
            assertArrayEquals(originalContent, restored,
                    "a real hg client cloning over SSH (changegroup) with an explicit HTTP [lfs] url "
                            + "(blob) must receive the exact original bytes");

            String verifyOutput = HgTestUtils.hg(destDir, "--config", "extensions.lfs=",
                    "--config", "lfs.url=" + lfsUrl, "verify");
            assertFalse(verifyOutput.toLowerCase().contains("error:"),
                    "real hg verify (lfs-aware) must find no errors: " + verifyOutput);
        } finally {
            HgTestUtils.stop(lfsHttpServer);
        }
    }

    /** Mirrors {@code HgSshWireServerRealHgInteropTest}'s own bridging command, except the
     * dispatched {@link HgSshWireServer} has {@link HgSshWireServer#enableLfsCapability()} turned
     * on -- otherwise real hg's {@code exchange.push()} requirement check would already reject the
     * push before ever reaching the LFS-specific behavior under test here. */
    private static class HgLfsWireCommand implements Command, Runnable {
        private static final Pattern REPO_PATH = Pattern.compile("-R\\s+'?([^'\\s]+)'?");

        private final String command;
        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback callback;
        private Thread thread;

        HgLfsWireCommand(String command) {
            this.command = command;
        }

        @Override public void setInputStream(InputStream in) { this.in = in; }
        @Override public void setOutputStream(OutputStream out) { this.out = out; }
        @Override public void setErrorStream(OutputStream err) { this.err = err; }
        @Override public void setExitCallback(ExitCallback callback) { this.callback = callback; }

        @Override
        public void start(ChannelSession session, org.apache.sshd.server.Environment env) {
            thread = new Thread(this, "hg-lfs-ssh-wire-test");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void destroy(ChannelSession session) {
            if (thread != null) {
                thread.interrupt();
            }
        }

        @Override
        public void run() {
            try {
                Matcher m = REPO_PATH.matcher(command == null ? "" : command);
                if (!m.find()) {
                    err.write(("bad command: " + command + "\n").getBytes());
                    err.flush();
                    callback.onExit(1);
                    return;
                }
                HgRepository repo = new HgRepository(new File(m.group(1)));
                new HgSshWireServer(repo).enableLfsCapability().handleConnection(in, out);
                callback.onExit(0);
            } catch (Exception e) {
                try {
                    err.write((e + "\n").getBytes());
                    err.flush();
                } catch (IOException ignored) {
                }
                callback.onExit(1);
            }
        }
    }
}
