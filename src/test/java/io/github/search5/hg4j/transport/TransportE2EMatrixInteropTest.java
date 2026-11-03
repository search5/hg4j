package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CloneCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.InitCommand;
import io.github.search5.hg4j.api.PullCommand;
import io.github.search5.hg4j.api.PushCommand;
import io.github.search5.hg4j.api.UpdateCommand;
import io.github.search5.hg4j.lib.HgRepository;
import org.apache.sshd.server.Environment;
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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End to end matrix of the two implementations talking to each other: a client (real {@code hg} or hg4j) against a server
 * (real {@code hg serve} / {@code hg serve --stdio}, or hg4j's {@link HgHttpWireServer} / {@link HgSshWireServer}) over HTTP
 * and over SSH, 8 combinations. Each combination moves a plain history and an LFS history through clone (or init + pull),
 * a push of a new changeset and a later pull of a changeset made on the server, and afterwards real {@code hg verify} must
 * accept both repositories and the file contents must be the same byte for byte.
 */
@Tag("interop")
class TransportE2EMatrixInteropTest {

    enum Side { REAL, OWN }

    /** SSH_HTTP_LFS: the changesets travel over SSH while the LFS blobs go to the same server's HTTP LFS endpoints. */
    enum Wire { HTTP, SSH, SSH_HTTP_LFS }

    static boolean overSsh(Wire wire) {
        return wire != Wire.HTTP;
    }

    record Combo(Side client, Side server, Wire wire) {
        @Override
        public String toString() {
            return client + " client -> " + server + " server over " + wire;
        }
    }

    static Stream<Combo> combos() {
        return combos(Wire.HTTP, Wire.SSH);
    }

    static Stream<Combo> lfsCombos() {
        return combos(Wire.HTTP, Wire.SSH, Wire.SSH_HTTP_LFS);
    }

    static Stream<Combo> combos(Wire... wires) {
        List<Combo> all = new ArrayList<>();
        for (Wire wire : wires) {
            for (Side client : Side.values()) {
                for (Side server : Side.values()) {
                    all.add(new Combo(client, server, wire));
                }
            }
        }
        return all.stream();
    }

    @TempDir
    Path tempDir;

    private Process realHttp;
    private Server ownHttp;
    private SshServer ssh;
    private int sshPort;
    private File sshKey;
    private final byte[] bigData = new byte[600];
    /** The LFS endpoint of the HTTP server that accompanies an SSH wire (SSH_HTTP_LFS). */
    private String lfsHttpUrl;

    private static final String[] LFS = {"--config", "extensions.lfs=", "--config", "lfs.track=size(\">100\")",
            "--config", "experimental.lfs.disableusercache=true"};

    @BeforeEach
    void requirements() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled());
        new Random(System.nanoTime()).nextBytes(bigData);
    }

    @AfterEach
    void stopServers() throws Exception {
        if (realHttp != null) {
            realHttp.destroy();
            realHttp.waitFor();
        }
        if (ownHttp != null) {
            HgTestUtils.stop(ownHttp);
        }
        if (ssh != null) {
            ssh.stop(true);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String[] concat(String[] head, String... tail) {
        String[] all = new String[head.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void waitForPort(int port) throws Exception {
        for (int i = 0; i < 100; i++) {
            try (Socket s = new Socket("127.0.0.1", port)) {
                return;
            } catch (IOException e) {
                Thread.sleep(100);
            }
        }
        fail("nothing listens on port " + port);
    }

    private String hg(File dir, boolean lfs, String... args) throws Exception {
        return HgTestUtils.hg(dir, lfs ? concat(LFS, args) : args);
    }

    private String hgOver(File dir, Combo combo, boolean lfs, String... args) throws Exception {
        String[] full = args;
        if (overSsh(combo.wire())) {
            full = concat(new String[]{"--config", "ui.ssh=ssh -i " + sshKey.getAbsolutePath()
                    + " -o StrictHostKeyChecking=no -o UserKnownHostsFile=" + tempDir.resolve("known_hosts")
                    + " -o IdentitiesOnly=yes -p " + sshPort}, args);
        }
        return hg(dir, lfs, full);
    }

    private TreeSet<String> nodes(File dir, boolean lfs) throws Exception {
        TreeSet<String> set = new TreeSet<>();
        for (String line : hg(dir, lfs, "log", "-T", "{node}\n").split("\n")) {
            if (!line.isBlank()) {
                set.add(line.strip());
            }
        }
        return set;
    }

    /** The server's repository, made by real hg: three changesets, a binary file, an executable, a rename (and an LFS file). */
    private File serverRepo(boolean lfs) throws Exception {
        File src = tempDir.resolve("seed").toFile();
        src.mkdirs();
        hg(src, lfs, "init");
        Files.writeString(new File(src, "a.txt").toPath(), "first\n");
        byte[] binary = new byte[80];
        new Random(5).nextBytes(binary);
        Files.write(new File(src, "bin.dat").toPath(), binary);
        hg(src, lfs, "add");
        hg(src, lfs, "commit", "-m", "one", "-u", "t");
        new File(src, "sub").mkdirs();
        Files.writeString(new File(src, "sub/b.txt").toPath(), "second\n");
        File script = new File(src, "run.sh");
        Files.writeString(script.toPath(), "#!/bin/sh\n");
        script.setExecutable(true);
        if (lfs) {
            Files.write(new File(src, "big.bin").toPath(), bigData);
        }
        hg(src, lfs, "add");
        hg(src, lfs, "commit", "-m", "two", "-u", "t");
        hg(src, lfs, "mv", "a.txt", "a2.txt");
        hg(src, lfs, "commit", "-m", "three", "-u", "t");

        File server = tempDir.resolve("server").toFile();
        server.mkdirs();
        hg(server, lfs, "init");
        Files.writeString(new File(server, ".hg/hgrc").toPath(), (lfs ? "[extensions]\nlfs =\n" : "")
                + "[web]\npush_ssl = false\nallow-push = *\n[experimental]\nlfs.disableusercache = true\n");
        String store = "file://" + new File(server, ".hg/store/lfs/objects").getAbsolutePath();
        hg(src, lfs, lfs ? concat(new String[]{"--config", "lfs.url=" + store}, "push", server.getAbsolutePath())
                : new String[]{"push", server.getAbsolutePath()});
        return server;
    }

    private String startServer(Combo combo, File repo) throws Exception {
        if (combo.wire() == Wire.HTTP || combo.wire() == Wire.SSH_HTTP_LFS) {
            String http;
            if (combo.server() == Side.REAL) {
                int port = freePort();
                realHttp = new ProcessBuilder("hg", "serve", "-p", String.valueOf(port), "--config", "web.push_ssl=false",
                        "--config", "web.allow-push=*").directory(repo).redirectErrorStream(true).start();
                waitForPort(port);
                http = "http://127.0.0.1:" + port;
            } else {
                ownHttp = HgTestUtils.startServlet(new HgHttpWireServer(new HgRepository(repo)));
                http = "http://127.0.0.1:" + HgTestUtils.port(ownHttp);
            }
            if (combo.wire() == Wire.HTTP) {
                return http;
            }
            lfsHttpUrl = http + "/.git/info/lfs";
        }
        sshKey = tempDir.resolve("id_test").toFile();
        Process keygen = new ProcessBuilder("ssh-keygen", "-t", "rsa", "-b", "2048", "-f", sshKey.getAbsolutePath(), "-N", "")
                .redirectErrorStream(true).start();
        keygen.getInputStream().readAllBytes();
        Assumptions.assumeTrue(keygen.waitFor() == 0, "ssh-keygen is not available");
        ssh = SshServer.setUpDefaultServer();
        ssh.setPort(0);
        ssh.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempDir.resolve("host_key")));
        ssh.setPublickeyAuthenticator(AcceptAllPublickeyAuthenticator.INSTANCE);
        ssh.setPasswordAuthenticator((user, password, session) -> true);
        ssh.setCommandFactory((channel, command) -> combo.server() == Side.REAL ? new RealServe(command) : new OwnServe(command));
        ssh.start();
        sshPort = ssh.getPort();
        // real hg's client takes "//" for an absolute path, hg4j's client the path after the port
        return combo.client() == Side.REAL ? "ssh://127.0.0.1:" + sshPort + "/" + repo.getAbsolutePath()
                : "ssh://testuser@127.0.0.1:" + sshPort + repo.getAbsolutePath();
    }

    private void hgrcOf(File dir, String url, boolean lfs, Wire wire, File serverStore) throws Exception {
        String lfsUrl = wire == Wire.SSH ? "file://" + serverStore.getAbsolutePath()
                : wire == Wire.SSH_HTTP_LFS ? lfsHttpUrl : null;
        String text = (lfs ? "[extensions]\nlfs =\n[lfs]\ntrack = size(\">100\")\n"
                + (lfsUrl != null ? "url = " + lfsUrl + "\n" : "") : "")
                + "[experimental]\nlfs.disableusercache = true\n[paths]\ndefault = " + url + "\n";
        Files.writeString(new File(dir, ".hg/hgrc").toPath(), text);
    }

    // ---------------------------------------------------------------- the scenario

    @ParameterizedTest(name = "plain history: {0}")
    @MethodSource("combos")
    void aPlainHistoryTravelsBothWays(Combo combo) throws Exception {
        travel(combo, false);
    }

    @ParameterizedTest(name = "LFS history: {0}")
    @MethodSource("lfsCombos")
    void anLfsHistoryTravelsBothWays(Combo combo) throws Exception {
        travel(combo, true);
    }

    private void travel(Combo combo, boolean lfs) throws Exception {
        File server = serverRepo(lfs);
        String url = startServer(combo, server);
        File storeDir = new File(server, ".hg/store/lfs/objects");
        File client = tempDir.resolve("client").toFile();
        client.mkdirs();

        // 1. clone (an LFS client first needs its configuration, so it initializes, pulls and updates)
        if (combo.client() == Side.REAL) {
            if (lfs) {
                hg(client, true, "init");
                hgrcOf(client, url, true, combo.wire(), storeDir);
                hgOver(client, combo, true, "pull", "-u");
            } else {
                hgOver(tempDir.toFile(), combo, false, "clone", url, client.getAbsolutePath());
            }
        } else if (lfs) {
            new InitCommand().setDirectory(client).call();
            hgrcOf(client, url, true, combo.wire(), storeDir);
            HgRepository repo = new HgRepository(client);
            new PullCommand(repo).call();
            new UpdateCommand(repo).call();
        } else {
            new CloneCommand().setSource(url).setDirectory(client).call();
        }
        assertEquals(nodes(server, lfs), nodes(client, lfs), "the clone has the server's history");
        assertEquals("first\n", Files.readString(new File(client, "a2.txt").toPath()));
        assertEquals("second\n", Files.readString(new File(client, "sub/b.txt").toPath()));
        assertTrue(new File(client, "run.sh").canExecute(), "the executable bit survived");
        if (lfs) {
            assertArrayEquals(bigData, Files.readAllBytes(new File(client, "big.bin").toPath()), "the LFS file has its real content");
        }
        hg(client, lfs, "verify");

        // 2. push a new changeset (with a new LFS file when testing LFS)
        byte[] pushed = new byte[700];
        new Random(77).nextBytes(pushed);
        if (combo.client() == Side.REAL) {
            Files.writeString(new File(client, "pushed.txt").toPath(), "from the client\n");
            if (lfs) {
                Files.write(new File(client, "pushed.bin").toPath(), pushed);
            }
            hg(client, lfs, "add");
            hg(client, lfs, "commit", "-m", "client", "-u", "t");
            hgOver(client, combo, lfs, "push");
        } else {
            HgRepository repo = new HgRepository(client);
            Files.writeString(new File(client, "pushed.txt").toPath(), "from the client\n");
            if (lfs) {
                Files.write(new File(client, "pushed.bin").toPath(), pushed);
            }
            new AddCommand(repo).call();
            new CommitCommand(repo).setMessage("client").setAuthor("t").call();
            new PushCommand(new HgRepository(client)).setDestination(url).call();
        }
        assertEquals(nodes(client, lfs), nodes(server, lfs), "the server has the pushed changeset");
        hg(server, lfs, "verify");
        assertEquals("from the client", hg(server, lfs, "cat", "-r", "tip", "pushed.txt").strip());
        if (lfs) {
            // the blob reached the server's store, so real hg reads the content there
            File dump = tempDir.resolve("dump.bin").toFile();
            hg(server, true, "cat", "-r", "tip", "-o", dump.getAbsolutePath(), "pushed.bin");
            assertArrayEquals(pushed, Files.readAllBytes(dump.toPath()));
        }

        // 3. a changeset made on the server is pulled
        hg(server, lfs, "update", "-C", "tip"); // a push does not move the server's working directory
        Files.writeString(new File(server, "later.txt").toPath(), "from the server\n");
        hg(server, lfs, "add", "later.txt");
        hg(server, lfs, "commit", "-m", "server", "-u", "t");
        if (combo.client() == Side.REAL) {
            hgOver(client, combo, lfs, "pull", "-u");
        } else {
            HgRepository repo = new HgRepository(client);
            new PullCommand(repo).setSource(url).call();
            new UpdateCommand(new HgRepository(client)).call();
        }
        assertEquals(nodes(server, lfs), nodes(client, lfs), "the client has the changeset made on the server");
        assertEquals("from the server\n", Files.readString(new File(client, "later.txt").toPath()));
        hg(client, lfs, "verify");
    }

    // ---------------------------------------------------------------- history shapes

    private void write(File dir, String name, String text) throws Exception {
        File file = new File(dir, name);
        file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), text);
    }

    private String node(File dir, String revset) throws Exception {
        return hg(dir, false, "log", "-r", revset, "-T", "{node}").strip();
    }

    /**
     * A server with branches, a merge, a tag, bookmarks, two heads on {@code default}, drafts, a public part and a secret
     * changeset (which must never be served). Made by real hg directly in the server directory.
     */
    private File shapesServer() throws Exception {
        File srv = tempDir.resolve("server").toFile();
        srv.mkdirs();
        hg(srv, false, "init");
        Files.writeString(new File(srv, ".hg/hgrc").toPath(),
                "[web]\npush_ssl = false\nallow-push = *\n[phases]\npublish = false\n");
        write(srv, "c1.txt", "c1\n");
        hg(srv, false, "add");
        hg(srv, false, "commit", "-m", "c1", "-u", "t");
        write(srv, "c2.txt", "c2\n");
        hg(srv, false, "add");
        hg(srv, false, "commit", "-m", "c2", "-u", "t");
        hg(srv, false, "branch", "stable");
        write(srv, "s.txt", "s1\n");
        hg(srv, false, "add");
        hg(srv, false, "commit", "-m", "s1", "-u", "t");
        write(srv, "s.txt", "s1\ns2\n");
        hg(srv, false, "commit", "-m", "s2", "-u", "t");
        hg(srv, false, "update", "default");
        write(srv, "d.txt", "c3\n");
        hg(srv, false, "add");
        hg(srv, false, "commit", "-m", "c3", "-u", "t");
        hg(srv, false, "bookmark", "feature");
        hg(srv, false, "merge", "stable");
        hg(srv, false, "commit", "-m", "merge", "-u", "t");
        hg(srv, false, "bookmark", "release");
        hg(srv, false, "tag", "-r", "desc(c2)", "v1", "-u", "t");
        hg(srv, false, "update", "desc(c2)");
        write(srv, "h2.txt", "second head\n");
        hg(srv, false, "add");
        hg(srv, false, "commit", "-m", "second head", "-u", "t");
        hg(srv, false, "phase", "--public", "-r", "desc(c2)");
        hg(srv, false, "update", "desc(merge)");
        write(srv, "secret.txt", "secret\n");
        hg(srv, false, "add");
        hg(srv, false, "commit", "-m", "secret one", "-u", "t");
        hg(srv, false, "phase", "--secret", "--force", "-r", ".");
        return srv;
    }

    /** One line per changeset: node, branch, phase, bookmarks, tags (without "tip"); sorted. */
    private TreeSet<String> shape(File dir) throws Exception {
        TreeSet<String> lines = new TreeSet<>();
        for (String line : hg(dir, false, "log", "-T", "{node}|{branch}|{phase}|{bookmarks}|{tags}\n").split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.split("\\|", -1);
            String tags = String.join(" ", java.util.Arrays.stream(f[4].split(" ")).filter(t -> !t.isEmpty() && !t.equals("tip")).sorted().toList());
            String marks = String.join(" ", java.util.Arrays.stream(f[3].split(" ")).filter(t -> !t.isEmpty()).sorted().toList());
            lines.add(f[0] + "|" + f[1] + "|" + f[2] + "|" + marks + "|" + tags);
        }
        return lines;
    }

    @ParameterizedTest(name = "branches, merge, tags, bookmarks, phases: {0}")
    @MethodSource("combos")
    void aBranchyHistoryTravelsWithItsBookmarksTagsAndPhases(Combo combo) throws Exception {
        File server = shapesServer();
        String url = startServer(combo, server);
        File client = tempDir.resolve("client").toFile();
        client.mkdirs();
        if (combo.client() == Side.REAL) {
            hgOver(tempDir.toFile(), combo, false, "clone", url, client.getAbsolutePath());
        } else {
            new CloneCommand().setSource(url).setDirectory(client).call();
        }
        String secret = node(server, "desc('secret one')");
        TreeSet<String> expected = shape(server);
        expected.removeIf(line -> line.startsWith(secret));
        assertEquals(expected, shape(client), "branch, phase, bookmarks and tags of every served changeset; the secret one stays home");
        hg(client, false, "verify");

        // a changeset that adds a third head to default is refused, and accepted when forced
        hg(client, false, "update", "-C", "desc(c1)");
        write(client, "extra.txt", "a new head\n");
        hg(client, false, "add");
        hg(client, false, "commit", "-m", "third head", "-u", "t");
        String thirdHead = node(client, ".");
        assertThrows(Throwable.class, () -> push(combo, client, url, false, false), "a push that creates a head is refused");
        assertFalse(nodes(server, false).contains(thirdHead), "the refused push changed nothing");
        push(combo, client, url, true, false);
        assertTrue(nodes(server, false).contains(thirdHead), "the forced push arrived");

        // a new named branch is refused without the flag
        hg(client, false, "branch", "newbranch");
        write(client, "nb.txt", "on a new branch\n");
        hg(client, false, "add");
        hg(client, false, "commit", "-m", "on newbranch", "-u", "t");
        String onNewBranch = node(client, ".");
        assertThrows(Throwable.class, () -> push(combo, client, url, false, false), "a new branch needs the flag");
        assertFalse(nodes(server, false).contains(onNewBranch));
        push(combo, client, url, false, true);
        assertTrue(nodes(server, false).contains(onNewBranch), "the changeset of the new branch arrived");
        assertEquals("newbranch", node2branch(server, onNewBranch));
        hg(server, false, "verify");
        TreeSet<String> serverNow = shape(server);
        serverNow.removeIf(line -> line.startsWith(secret));
        assertEquals(serverNow, shape(client),
                "branch, phase, bookmarks and tags agree on both sides, also for the pushed changesets (they stay draft on a non-publishing server)");
    }

    private String node2branch(File dir, String node) throws Exception {
        return hg(dir, false, "log", "-r", node, "-T", "{branch}").strip();
    }

    private void push(Combo combo, File client, String url, boolean force, boolean newBranch) throws Exception {
        if (combo.client() == Side.REAL) {
            List<String> args = new ArrayList<>(List.of("push"));
            if (force) {
                args.add("-f");
            }
            if (newBranch) {
                args.add("--new-branch");
            }
            hgOver(client, combo, false, args.toArray(String[]::new));
        } else {
            new PushCommand(new HgRepository(client)).setDestination(url).setForce(force).setAllowNewBranch(newBranch).call();
        }
    }

    // ---------------------------------------------------------------- a long history with a big file

    @ParameterizedTest(name = "long history, 1.5 MB file: {0}")
    @MethodSource("combos")
    void aLongHistoryWithABigFileTravelsIntact(Combo combo) throws Exception {
        File server = tempDir.resolve("server").toFile();
        server.mkdirs();
        hg(server, false, "init");
        Files.writeString(new File(server, ".hg/hgrc").toPath(), "[web]\npush_ssl = false\nallow-push = *\n");
        byte[] big = new byte[1_500_000];
        new Random(11).nextBytes(big);
        Files.write(new File(server, "big.dat").toPath(), big);
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            lines.append("line ").append(i).append('\n');
            write(server, "log.txt", lines.toString());
            write(server, "f" + (i % 7) + ".txt", "version " + i + "\n");
            if (i == 20) {
                big[700_000] ^= 0x5a; // a small change of the big file: stored as a delta
                Files.write(new File(server, "big.dat").toPath(), big);
            }
            hg(server, false, "add");
            hg(server, false, "commit", "-m", "commit " + i, "-u", "t");
        }
        String url = startServer(combo, server);
        File client = tempDir.resolve("client").toFile();
        client.mkdirs();
        if (combo.client() == Side.REAL) {
            hgOver(tempDir.toFile(), combo, false, "clone", url, client.getAbsolutePath());
        } else {
            new CloneCommand().setSource(url).setDirectory(client).call();
        }
        assertEquals(nodes(server, false), nodes(client, false));
        assertArrayEquals(big, Files.readAllBytes(new File(client, "big.dat").toPath()), "the big file is intact");
        hg(client, false, "verify");

        // push a change of the big file back
        big[10] ^= 0x33;
        Files.write(new File(client, "big.dat").toPath(), big);
        if (combo.client() == Side.REAL) {
            hg(client, false, "commit", "-m", "big again", "-u", "t");
            hgOver(client, combo, false, "push");
        } else {
            new CommitCommand(new HgRepository(client)).setMessage("big again").setAuthor("t").call();
            new PushCommand(new HgRepository(client)).setDestination(url).call();
        }
        assertEquals(nodes(client, false), nodes(server, false));
        hg(server, false, "verify");
        hg(server, false, "update", "-C", "tip");
        assertArrayEquals(big, Files.readAllBytes(new File(server, "big.dat").toPath()));
    }

    // ---------------------------------------------------------------- SSH commands

    private abstract static class SshCommand implements Command {
        static final Pattern REPO_PATH = Pattern.compile("-R\\s+'?([^'\\s]+)'?");
        final String command;
        InputStream in;
        OutputStream out;
        OutputStream err;
        ExitCallback callback;

        SshCommand(String command) {
            this.command = command;
        }

        @Override public void setInputStream(InputStream in) { this.in = in; }
        @Override public void setOutputStream(OutputStream out) { this.out = out; }
        @Override public void setErrorStream(OutputStream err) { this.err = err; }
        @Override public void setExitCallback(ExitCallback callback) { this.callback = callback; }
        @Override public void destroy(ChannelSession session) { }

        String repoPath() throws IOException {
            Matcher m = REPO_PATH.matcher(command == null ? "" : command);
            if (!m.find()) {
                err.write(("bad command: " + command + "\n").getBytes());
                err.flush();
                callback.onExit(1);
                return null;
            }
            return m.group(1);
        }
    }

    /** hg4j's wire server on an SSH channel. */
    private static final class OwnServe extends SshCommand {
        OwnServe(String command) {
            super(command);
        }

        @Override
        public void start(ChannelSession session, Environment env) {
            Thread thread = new Thread(() -> {
                try {
                    String path = repoPath();
                    if (path == null) {
                        return;
                    }
                    new HgSshWireServer(new HgRepository(new File(path))).handleConnection(in, out);
                    callback.onExit(0);
                } catch (Exception e) {
                    try {
                        err.write((e + "\n").getBytes());
                        err.flush();
                    } catch (IOException ignored) {
                    }
                    callback.onExit(1);
                }
            }, "own-ssh-serve");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /** Real {@code hg serve --stdio} on an SSH channel. */
    private static final class RealServe extends SshCommand {
        RealServe(String command) {
            super(command);
        }

        @Override
        public void start(ChannelSession session, Environment env) throws IOException {
            String path = repoPath();
            if (path == null) {
                return;
            }
            Process proc = new ProcessBuilder("hg", "-R", path, "serve", "--stdio").redirectErrorStream(false).start();
            Thread inPump = new Thread(() -> pump(in, proc.getOutputStream(), true), "real-ssh-stdin");
            Thread outPump = new Thread(() -> pump(proc.getInputStream(), out, false), "real-ssh-stdout");
            Thread errPump = new Thread(() -> pump(proc.getErrorStream(), err, false), "real-ssh-stderr");
            for (Thread t : new Thread[]{inPump, outPump, errPump}) {
                t.setDaemon(true);
                t.start();
            }
            Thread waiter = new Thread(() -> {
                try {
                    int code = proc.waitFor();
                    outPump.join(2000);
                    errPump.join(2000);
                    callback.onExit(code);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }, "real-ssh-wait");
            waiter.setDaemon(true);
            waiter.start();
        }

        private static void pump(InputStream source, OutputStream target, boolean closeTarget) {
            byte[] buf = new byte[8192];
            try {
                int n;
                while ((n = source.read(buf)) != -1) {
                    target.write(buf, 0, n);
                    target.flush();
                }
                if (closeTarget) {
                    target.close();
                }
            } catch (IOException ignored) {
                // the peer went away
            }
        }
    }
}
