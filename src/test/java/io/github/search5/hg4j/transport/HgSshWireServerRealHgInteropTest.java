package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import org.apache.sshd.server.Environment;

/**
 * Verifies {@link HgSshWireServer} against the real {@code hg} CLI as an SSH client, using an
 * embedded Apache MINA SSHD {@link SshServer} (test-only — see the JGit-restructuring plan: real
 * production SSH serving is expected to be wired up outside hg4j the same way, by attaching
 * {@link HgSshWireServer} to whatever SSH server implementation the actual {@code hg
 * serve}-equivalent entry point uses; hg4j itself does not ship a production SSH server).
 *
 * <p>An earlier attempt at this used a hand-rolled shell-script {@code ui.ssh} override to bypass
 * a real SSH session entirely, which hit real hg misreading stray bytes as SSH banner text. A
 * genuine SSH channel (this test) avoids that: stdout/stderr are cleanly separated by the
 * protocol itself, exactly like a real deployment.</p>
 */
@Tag("interop")
public class HgSshWireServerRealHgInteropTest {

    private SshServer sshServer;
    private int port;
    private File sshKeyFile;

    @BeforeEach
    public void setUp(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
        Assumptions.assumeTrue(isSshKeygenAvailable(), "ssh-keygen is not available. Skipping.");

        sshKeyFile = tempDir.resolve("id_test").toFile();
        runProcess("ssh-keygen", "-t", "rsa", "-b", "2048", "-f", sshKeyFile.getAbsolutePath(), "-N", "");

        sshServer = SshServer.setUpDefaultServer();
        sshServer.setPort(0);
        Path hostKey = tempDir.resolve("host_key");
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
        // Test-only: accept any client key. A real deployment authenticates properly; that's the
        // separate production entry point's concern, not HgSshWireServer's.
        sshServer.setPublickeyAuthenticator(AcceptAllPublickeyAuthenticator.INSTANCE);
        sshServer.setCommandFactory((channel, command) -> new HgWireCommand(command));
        sshServer.start();
        port = sshServer.getPort();
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (sshServer != null) {
            sshServer.stop(true);
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

    /** Real hg's ssh:// URL path convention: a single leading slash is relative to the remote
     * user's home directory, a double slash makes it absolute -- needed since these test
     * repositories live under an arbitrary temp directory, not anyone's home. */
    private String sshUrl(File repoDir) {
        return "ssh://127.0.0.1:" + port + "/" + repoDir.getAbsolutePath();
    }

    private String remoteCmdForTest(Path tempDir) {
        return "ssh -i " + sshKeyFile.getAbsolutePath()
                + " -o StrictHostKeyChecking=no -o UserKnownHostsFile=" + tempDir.resolve("known_hosts")
                + " -o IdentitiesOnly=yes -p " + port;
    }

    @Test
    public void realHgClonesFromHg4jServedOverSsh(@TempDir Path tempDir) throws Exception {
        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgRepository serverRepo = Hg.init().setDirectory(serverRepoDir).call();
        File f = new File(serverRepoDir, "a.txt");
        Files.writeString(f.toPath(), "hello ssh interop");
        new AddCommand(serverRepo).call();
        byte[] commit = new CommitCommand(serverRepo).setMessage("v1").setAuthor("dev").call();

        File destDir = tempDir.resolve("client_repo").toFile();
        HgTestUtils.hg(tempDir.toFile(),
                "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                "clone", sshUrl(serverRepoDir), destDir.getAbsolutePath());

        String log = HgTestUtils.hg(destDir, "log", "-T", "{node}\n");
        assertEquals(NodeIdUtil.toHex(commit), log.trim());
        assertEquals("hello ssh interop", Files.readString(new File(destDir, "a.txt").toPath()));
    }

    @Test
    public void realHgPullsIncrementalChangesFromHg4jServedOverSsh(@TempDir Path tempDir) throws Exception {
        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgRepository serverRepo = Hg.init().setDirectory(serverRepoDir).call();
        Files.writeString(new File(serverRepoDir, "a.txt").toPath(), "hello ssh interop");
        new AddCommand(serverRepo).call();
        new CommitCommand(serverRepo).setMessage("v1").setAuthor("dev").call();

        File destDir = tempDir.resolve("client_repo").toFile();
        HgTestUtils.hg(tempDir.toFile(),
                "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                "clone", sshUrl(serverRepoDir), destDir.getAbsolutePath());

        Files.writeString(new File(serverRepoDir, "b.txt").toPath(), "second file");
        new AddCommand(serverRepo).call();
        byte[] secondCommit = new CommitCommand(serverRepo).setMessage("v2").setAuthor("dev").call();

        HgTestUtils.hg(destDir, "--config", "ui.ssh=" + remoteCmdForTest(tempDir), "pull");
        HgTestUtils.hg(destDir, "update");

        String log = HgTestUtils.hg(destDir, "log", "-T", "{node}\n", "-r", "tip");
        assertEquals(NodeIdUtil.toHex(secondCommit), log.trim());
        assertTrue(new File(destDir, "b.txt").exists());
    }

    @Test
    public void realHgPushesToHg4jServedOverSsh(@TempDir Path tempDir) throws Exception {
        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgRepository serverRepo = Hg.init().setDirectory(serverRepoDir).call();
        Files.writeString(new File(serverRepoDir, "a.txt").toPath(), "hello ssh interop");
        new AddCommand(serverRepo).call();
        new CommitCommand(serverRepo).setMessage("v1").setAuthor("dev").call();

        File destDir = tempDir.resolve("client_repo").toFile();
        HgTestUtils.hg(tempDir.toFile(),
                "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                "clone", sshUrl(serverRepoDir), destDir.getAbsolutePath());

        Files.writeString(new File(destDir, "c.txt").toPath(), "pushed file");
        HgTestUtils.hg(destDir, "add", "c.txt");
        HgTestUtils.hg(destDir, "commit", "-m", "pushed commit");

        HgTestUtils.hg(destDir, "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                "push", sshUrl(serverRepoDir));

        serverRepo.clearRevlogCache();
        File clIdx = new File(serverRepo.getStoreDir(), "00changelog.i");
        File clDat = new File(serverRepo.getStoreDir(), "00changelog.d");
        var cl = serverRepo.getRevlog(clIdx, clDat);
        assertEquals(2, cl.getRevisionCount(), "The pushed commit must be applied to the hg4j server repository");
    }

    @Test
    public void realHgSeesAnotherRealHgClientsPushImmediatelyOverSsh(@TempDir Path tempDir) throws Exception {
        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgRepository serverRepo = Hg.init().setDirectory(serverRepoDir).call();
        Files.writeString(new File(serverRepoDir, "a.txt").toPath(), "hello ssh interop");
        new AddCommand(serverRepo).call();
        new CommitCommand(serverRepo).setMessage("v1").setAuthor("dev").call();

        File clientA = tempDir.resolve("client_a").toFile();
        HgTestUtils.hg(tempDir.toFile(),
                "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                "clone", sshUrl(serverRepoDir), clientA.getAbsolutePath());
        Files.writeString(new File(clientA, "c.txt").toPath(), "pushed by client A");
        HgTestUtils.hg(clientA, "add", "c.txt");
        HgTestUtils.hg(clientA, "commit", "-m", "pushed commit");
        HgTestUtils.hg(clientA, "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                "push", sshUrl(serverRepoDir));
        String pushedNode = HgTestUtils.hg(clientA, "log", "-T", "{node}\n", "-r", "tip").trim();

        // Same self-consistency check as the HTTP equivalent: a second, independent real hg
        // client clones fresh from the SAME still-running SSH server session factory afterward.
        File clientB = tempDir.resolve("client_b").toFile();
        HgTestUtils.hg(tempDir.toFile(),
                "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                "clone", sshUrl(serverRepoDir), clientB.getAbsolutePath());

        String log = HgTestUtils.hg(clientB, "log", "-T", "{node}\n", "-r", "tip");
        assertEquals(pushedNode, log.trim(),
                "A second real-hg client must see the first client's push immediately, without the server needing a restart");
        assertTrue(new File(clientB, "c.txt").exists());
    }

    @Test
    public void realHgClonesMultipleBranchesBookmarksAndTagsFromHg4jServedOverSsh(@TempDir Path tempDir) throws Exception {
        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgRepository serverRepo = Hg.init().setDirectory(serverRepoDir).call();
        Files.writeString(new File(serverRepoDir, "a.txt").toPath(), "on default");
        new AddCommand(serverRepo).call();
        new CommitCommand(serverRepo).setMessage("default v1").setAuthor("dev").call();

        HgTestUtils.hg(serverRepoDir, "branch", "feature");
        Files.writeString(new File(serverRepoDir, "b.txt").toPath(), "on feature");
        HgTestUtils.hg(serverRepoDir, "add", "b.txt");
        HgTestUtils.hg(serverRepoDir, "commit", "-m", "feature v1");
        HgTestUtils.hg(serverRepoDir, "bookmark", "mybook");
        HgTestUtils.hg(serverRepoDir, "tag", "v1.0");

        File destDir = tempDir.resolve("client_repo").toFile();
        HgTestUtils.hg(tempDir.toFile(),
                "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                "clone", sshUrl(serverRepoDir), destDir.getAbsolutePath());

        String branches = HgTestUtils.hg(destDir, "branches");
        assertTrue(branches.contains("default"), "default branch missing: " + branches);
        assertTrue(branches.contains("feature"), "feature branch missing: " + branches);

        String bookmarks = HgTestUtils.hg(destDir, "bookmarks");
        assertTrue(bookmarks.contains("mybook"), "bookmark missing: " + bookmarks);

        String tags = HgTestUtils.hg(destDir, "tags");
        assertTrue(tags.contains("v1.0"), "tag missing: " + tags);
    }

    @Test
    public void realHgReceivesUnderstandableErrorForNonexistentRevisionOverSsh(@TempDir Path tempDir) throws Exception {
        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgRepository serverRepo = Hg.init().setDirectory(serverRepoDir).call();
        Files.writeString(new File(serverRepoDir, "a.txt").toPath(), "hello ssh interop");
        new AddCommand(serverRepo).call();
        new CommitCommand(serverRepo).setMessage("v1").setAuthor("dev").call();

        File destDir = tempDir.resolve("client_repo").toFile();
        String bogusRev = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef";

        AssertionError failure = assertThrows(AssertionError.class, () ->
                assertTimeoutPreemptively(java.time.Duration.ofSeconds(30), () ->
                        HgTestUtils.hg(tempDir.toFile(),
                                "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                                "clone", "-r", bogusRev, sshUrl(serverRepoDir), destDir.getAbsolutePath())));
        assertTrue(failure.getMessage().toLowerCase().contains("unknown revision")
                        || failure.getMessage().toLowerCase().contains("abort"),
                "Expected a real-hg-understood error message, got: " + failure.getMessage());
    }

    /**
     * Backlog item 40, server direction over SSH: same scenario as {@code
     * HgHttpWireServerNarrowInteropTest#realHgNarrowClonesFromHg4jServedOverHttp} but over the SSH
     * transport, proving {@link Wire1Commands#getbundle}'s narrow filtering (shared, transport-
     * agnostic code both {@link HgHttpWireServer} and {@link HgSshWireServer} delegate to) is
     * actually reached and applied when the request arrives framed as SSH v1 args rather than
     * HTTP query/header args.
     */
    @Test
    public void realHgNarrowClonesFromHg4jServedOverSsh(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(isNarrowExtensionAvailable(), "Native hg's narrow extension is not available. Skipping.");

        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgRepository serverRepo = Hg.init().setDirectory(serverRepoDir).call();
        File includedDir = new File(serverRepoDir, "included");
        File excludedDir = new File(serverRepoDir, "excluded");
        includedDir.mkdirs();
        excludedDir.mkdirs();
        for (int i = 0; i < 20; i++) {
            Files.writeString(new File(includedDir, "f" + i + ".txt").toPath(), "small line " + i);
        }
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < 80; i++) {
            byte[] filler = new byte[20_000];
            rnd.nextBytes(filler);
            Files.write(new File(excludedDir, "big" + i + ".bin").toPath(), filler);
        }
        new AddCommand(serverRepo).call();
        new CommitCommand(serverRepo).setMessage("seed").setAuthor("dev").call();

        File plainDestDir = tempDir.resolve("plain_client_repo").toFile();
        HgTestUtils.hg(tempDir.toFile(), "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                "clone", sshUrl(serverRepoDir), plainDestDir.getAbsolutePath());
        long plainStoreSize = directorySize(new File(plainDestDir, ".hg/store"));

        File narrowDestDir = tempDir.resolve("narrow_client_repo").toFile();
        ProcessBuilder pb = new ProcessBuilder("hg", "--config", "extensions.narrow=",
                "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                "clone", "--narrow", "--include=included",
                sshUrl(serverRepoDir), narrowDestDir.getAbsolutePath());
        pb.directory(tempDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        assertTrue(code == 0, "real hg --narrow clone over SSH against hg4j server failed: " + out);

        assertTrue(new File(narrowDestDir, "included/f0.txt").exists(), "in-scope file must be checked out");
        assertFalse(new File(narrowDestDir, "excluded").exists(), "out-of-scope dir must not be checked out");

        long narrowStoreSize = directorySize(new File(narrowDestDir, ".hg/store"));
        assertTrue(plainStoreSize > 1_000_000,
                "sanity: the plain clone's on-disk store should be dominated by the 80 x 20KB excluded files, was "
                        + plainStoreSize + " bytes");
        assertTrue(narrowStoreSize < plainStoreSize / 4,
                "hg4j's SSH server must actually send a reduced changegroup to a real hg narrow client "
                        + "(local store " + narrowStoreSize + " bytes) compared to a plain clone against the "
                        + "same repository (" + plainStoreSize + " bytes)");
    }

    private static long directorySize(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return 0;
        long total = 0;
        for (File c : children) {
            total += c.isDirectory() ? directorySize(c) : c.length();
        }
        return total;
    }

    private static boolean isNarrowExtensionAvailable() {
        try {
            Process p = new ProcessBuilder("hg", "--config", "extensions.narrow=", "version").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    public void realHgSeesExternalRepoChangesAcrossConnectionsOnALongLivedSshServer(@TempDir Path tempDir) throws Exception {
        // Every other test in this class opens a brand-new HgRepository per SSH connection
        // (see HgWireCommand.run() below), which incidentally always reads the repository fresh
        // and so can never exercise backlog 24 (a long-lived server reusing ONE HgRepository
        // across many connections/requests must notice out-of-band repository changes). This
        // test deliberately mirrors HgHttpWireServerRealHgInteropTest's persistent-server setup
        // instead: one HgRepository, one HgSshWireServer-backed command factory, reused across
        // two separate real-hg SSH sessions.
        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgRepository serverRepo = Hg.init().setDirectory(serverRepoDir).call();
        Files.writeString(new File(serverRepoDir, "a.txt").toPath(), "hello ssh interop");
        new AddCommand(serverRepo).call();
        new CommitCommand(serverRepo).setMessage("v1").setAuthor("dev").call();

        sshServer.setCommandFactory((channel, command) -> new SharedRepoHgWireCommand(command, serverRepo));

        File clientA = tempDir.resolve("client_a").toFile();
        HgTestUtils.hg(tempDir.toFile(), "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                "clone", sshUrl(serverRepoDir), clientA.getAbsolutePath());
        assertEquals("hello ssh interop", Files.readString(new File(clientA, "a.txt").toPath()));

        // Out-of-band mutation: a bare real hg CLI call against the server's repo directory,
        // not going through hg4j / serverRepo at all -- exactly the "another process touched the
        // repo while the server kept running" scenario backlog 24 is about.
        HgTestUtils.hg(serverRepoDir, "branch", "feature");
        Files.writeString(new File(serverRepoDir, "b.txt").toPath(), "on feature");
        HgTestUtils.hg(serverRepoDir, "add", "b.txt");
        HgTestUtils.hg(serverRepoDir, "commit", "-m", "feature v1");
        // No explicit serverRepo.clearRevlogCache() here on purpose -- HgSshWireServer's
        // handleConnection() loop now calls HgRepository.refreshIfChangedOnDisk() at the top of
        // every command, so the second, independent SSH session below must see this without it.

        File clientB = tempDir.resolve("client_b").toFile();
        HgTestUtils.hg(tempDir.toFile(), "--config", "ui.ssh=" + remoteCmdForTest(tempDir),
                "clone", sshUrl(serverRepoDir), clientB.getAbsolutePath());

        // NOTE: only the changelog/branch metadata is asserted here, not b.txt's *content* --
        // that's a separate, pre-existing bug (file content added on an externally-committed
        // revision doesn't reach the client, reproduces even with an explicit
        // clearRevlogCache() call, i.e. unrelated to backlog 24) tracked separately so this test
        // stays scoped to what backlog 24 is actually about: does the server's long-lived
        // HgRepository handle notice the external write at all.
        String log = HgTestUtils.hg(clientB, "log", "-T", "{rev}:{branch}\n");
        assertTrue(log.contains("1:feature"),
                "second SSH connection on the same long-lived server must see the externally-added commit, log was:\n" + log);
        String branches = HgTestUtils.hg(clientB, "branches");
        assertTrue(branches.contains("feature"), "feature branch missing: " + branches);
    }

    /** Like {@link HgWireCommand}, but wired to a single {@link HgRepository} instance shared
     * across every connection the factory creates -- for tests that need to reproduce a
     * long-lived server process, as opposed to every other test in this class re-opening the
     * repository fresh per connection. */
    private static class SharedRepoHgWireCommand implements Command, Runnable {
        private final String command;
        private final HgRepository repo;
        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback callback;
        private Thread thread;

        SharedRepoHgWireCommand(String command, HgRepository repo) {
            this.command = command;
            this.repo = repo;
        }

        @Override public void setInputStream(InputStream in) { this.in = in; }
        @Override public void setOutputStream(OutputStream out) { this.out = out; }
        @Override public void setErrorStream(OutputStream err) { this.err = err; }
        @Override public void setExitCallback(ExitCallback callback) { this.callback = callback; }

        @Override
        public void start(ChannelSession session, Environment env) {
            thread = new Thread(this, "hg-ssh-wire-shared-repo-test");
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
                new HgSshWireServer(repo).handleConnection(in, out);
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

    /** Server-side {@code Command} adapter -- exactly the shape a real production SSH server
     * entry point would use to attach {@link HgSshWireServer} to whatever SSH library it picks. */
    private static class HgWireCommand implements Command, Runnable {
        private static final Pattern REPO_PATH = Pattern.compile("-R\\s+'?([^'\\s]+)'?");

        private final String command;
        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback callback;
        private Thread thread;

        HgWireCommand(String command) {
            this.command = command;
        }

        @Override public void setInputStream(InputStream in) { this.in = in; }
        @Override public void setOutputStream(OutputStream out) { this.out = out; }
        @Override public void setErrorStream(OutputStream err) { this.err = err; }
        @Override public void setExitCallback(ExitCallback callback) { this.callback = callback; }

        @Override
        public void start(ChannelSession session, Environment env) {
            thread = new Thread(this, "hg-ssh-wire-test");
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
                new HgSshWireServer(repo).handleConnection(in, out);
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
