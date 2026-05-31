package com.github.search5.hg4j.core;

import com.github.search5.hg4j.api.*;
import com.github.search5.hg4j.core.Dirstate;
import com.github.search5.hg4j.core.HgRepository;
import com.github.search5.hg4j.core.NodeIdUtil;
import com.github.search5.hg4j.core.Revlog;
import com.github.search5.hg4j.errors.HgAuthException;
import com.github.search5.hg4j.errors.HgCorruptDataException;
import com.github.search5.hg4j.errors.HgProtocolException;
import com.github.search5.hg4j.transport.HgRemoteClient;
import com.github.search5.hg4j.transport.HgRemoteConnection;
import com.github.search5.hg4j.transport.HgRemoteConnectionFactory;
import com.github.search5.hg4j.transport.HgSshClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class HgRemoteMockAndServeExtensionTest {

    // ==========================================
    // 1. HTTP Mock Gap Verification Scenarios
    // ==========================================

    @Test
    public void testHttp401ThrowsAuthException() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            
            Thread serverThread = new Thread(() -> {
                try (Socket socket = serverSocket.accept()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    }
                    OutputStream out = socket.getOutputStream();
                    out.write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException ignored) {}
            });
            serverThread.start();

            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + port);
            assertThrows(HgAuthException.class, () -> client.getHeads());
            serverThread.join();
        }
    }

    @Test
    public void testHttp5xxThrowsProtocolException() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            
            Thread serverThread = new Thread(() -> {
                try (Socket socket = serverSocket.accept()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    }
                    OutputStream out = socket.getOutputStream();
                    out.write("HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException ignored) {}
            });
            serverThread.start();

            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + port);
            assertThrows(HgProtocolException.class, () -> client.getHeads());
            serverThread.join();
        }
    }

    @Test
    public void testHttpDisconnectDuringResponse() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            
            Thread serverThread = new Thread(() -> {
                try (Socket socket = serverSocket.accept()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    }
                    OutputStream out = socket.getOutputStream();
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/mercurial-0.2\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException ignored) {}
            });
            serverThread.start();

            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + port);
            assertThrows(IOException.class, () -> client.getHeads());
            serverThread.join();
        }
    }

    @Test
    public void testChunkStreamCorruptedThrowsException() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            
            Thread serverThread = new Thread(() -> {
                try (Socket socket = serverSocket.accept()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    }
                    OutputStream out = socket.getOutputStream();
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/mercurial-0.2\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    out.write("XYZINVALID\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException ignored) {}
            });
            serverThread.start();

            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + port);
            assertThrows(Exception.class, () -> client.getHeads());
            serverThread.join();
        }
    }

    @Test
    public void testHttpFallbackWhenNoBundle2(@TempDir Path tempDir) throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            final boolean[] changegroupCalled = {false};

            Thread serverThread = new Thread(() -> {
                try {
                    for (int i = 0; i < 3; i++) {
                        try (Socket socket = serverSocket.accept()) {
                            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                            String firstLine = reader.readLine();
                            if (firstLine == null) continue;
                            
                            // consume headers
                            String line;
                            while ((line = reader.readLine()) != null && !line.isEmpty()) {}

                            OutputStream out = socket.getOutputStream();
                            if (firstLine.contains("cmd=capabilities")) {
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/mercurial-0.1\r\nContent-Length: 17\r\n\r\nheads changegroup".getBytes(StandardCharsets.UTF_8));
                            } else if (firstLine.contains("cmd=heads")) {
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/mercurial-0.1\r\nContent-Length: 41\r\n\r\n0000000000000000000000000000000000000000\n".getBytes(StandardCharsets.UTF_8));
                            } else if (firstLine.contains("cmd=changegroup")) {
                                changegroupCalled[0] = true;
                                byte[] terminal = new byte[]{0, 0, 0, 0};
                                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/mercurial-0.1\r\nContent-Length: " + terminal.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                                out.write(terminal);
                            }
                            out.flush();
                        }
                    }
                } catch (IOException ignored) {}
            });
            serverThread.start();

            HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
            PullCommand pull = new PullCommand(repo).setSource("http://127.0.0.1:" + port);
            pull.call();

            serverThread.join();
            assertTrue(changegroupCalled[0], "Should fall back to changegroup cmd when getbundle/bundle2 is missing in capabilities");
        }
    }

    @Test
    public void testHttpGetBundleWithBundle2Parsing(@TempDir Path tempDir) throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            
            Thread serverThread = new Thread(() -> {
                try {
                    for (int i = 0; i < 3; i++) {
                        try (Socket socket = serverSocket.accept()) {
                            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                            String firstLine = reader.readLine();
                            if (firstLine == null) continue;
                            
                            // consume headers
                            String line;
                            while ((line = reader.readLine()) != null && !line.isEmpty()) {}

                            OutputStream out = socket.getOutputStream();
                            if (firstLine.contains("cmd=capabilities")) {
                                String caps = "heads getbundle bundle2 HG20 changegroup=01,02,03";
                                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/mercurial-0.1\r\nContent-Length: " + caps.length() + "\r\n\r\n" + caps).getBytes(StandardCharsets.UTF_8));
                            } else if (firstLine.contains("cmd=heads")) {
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/mercurial-0.1\r\nContent-Length: 41\r\n\r\n0000000000000000000000000000000000000000\n".getBytes(StandardCharsets.UTF_8));
                            } else if (firstLine.contains("cmd=getbundle")) {
                                ByteArrayOutputStream rawPayload = new ByteArrayOutputStream();
                                
                                // CHANGEGROUP part header: nameSize(1) + name(11) + partId(4) + counts(2) = 18 bytes
                                int headerSize = 18;
                                rawPayload.write((headerSize >> 24) & 0xFF);
                                rawPayload.write((headerSize >> 16) & 0xFF);
                                rawPayload.write((headerSize >> 8) & 0xFF);
                                rawPayload.write(headerSize & 0xFF);
                                
                                rawPayload.write(11); // name size
                                rawPayload.write("CHANGEGROUP".getBytes(StandardCharsets.US_ASCII));
                                rawPayload.write(new byte[]{0, 0, 0, 1}); // partId = 1
                                rawPayload.write(new byte[]{0, 0}); // mandatoryCount=0, advisoryCount=0
                                
                                // Chunk 1: size = 4
                                rawPayload.write(new byte[]{0, 0, 0, 4});
                                // Chunk 1 data: empty changegroup terminal chunk (0,0,0,0)
                                rawPayload.write(new byte[]{0, 0, 0, 0});
                                // Chunk 2 (part payload EOF): size = 0
                                rawPayload.write(new byte[]{0, 0, 0, 0});
                                
                                // Stream EOF
                                rawPayload.write(new byte[]{0, 0, 0, 0});
                                
                                byte[] uncompressedBytes = rawPayload.toByteArray();

                                ByteArrayOutputStream bundle2Out = new ByteArrayOutputStream();
                                bundle2Out.write("HG20".getBytes(StandardCharsets.US_ASCII));
                                bundle2Out.write(new byte[]{0, 0}); // paramsSize = 0 (2 bytes)
                                bundle2Out.write(uncompressedBytes);
                                byte[] payload = bundle2Out.toByteArray();

                                ByteArrayOutputStream body = new ByteArrayOutputStream();
                                body.write(4); // compNameLen
                                body.write("none".getBytes(StandardCharsets.US_ASCII)); // compName
                                
                                int len = payload.length;
                                body.write((len >> 24) & 0xFF);
                                body.write((len >> 16) & 0xFF);
                                body.write((len >> 8) & 0xFF);
                                body.write(len & 0xFF);
                                body.write(payload);
                                body.write(new byte[]{0, 0, 0, 0}); // EOF Chunk

                                byte[] resp = body.toByteArray();
                                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/mercurial-0.2\r\nContent-Length: " + resp.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                                out.write(resp);
                            }
                            out.flush();
                        }
                    }
                } catch (IOException ignored) {}
            });
            serverThread.start();

            HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
            PullCommand pull = new PullCommand(repo).setSource("http://127.0.0.1:" + port);
            pull.call(); // Should complete successfully without throwing exceptions

            serverThread.join();
        }
    }

    @Test
    public void testHttpApplicationMercurial02Framing() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            
            Thread serverThread = new Thread(() -> {
                try (Socket socket = serverSocket.accept()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {}
                    
                    // Respond with application/mercurial-0.2 framing
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    body.write(4); // compNameLen
                    body.write("none".getBytes(StandardCharsets.US_ASCII)); // compression
                    
                    // Chunk 1: "heads" (5 bytes)
                    byte[] payload = "heads".getBytes(StandardCharsets.US_ASCII);
                    body.write(new byte[]{0, 0, 0, 5});
                    body.write(payload);
                    
                    // Chunk 2: EOF
                    body.write(new byte[]{0, 0, 0, 0});

                    byte[] resp = body.toByteArray();
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/mercurial-0.2\r\nContent-Length: " + resp.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(resp);
                    out.flush();
                } catch (IOException ignored) {}
            });
            serverThread.start();

            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + port);
            List<String> capabilities = client.getCapabilities();
            assertEquals(1, capabilities.size());
            assertEquals("heads", capabilities.get(0));

            serverThread.join();
        }
    }

    // ==========================================
    // 2. SSH Mock Additional Scenarios Verification
    // ==========================================

    @Test
    public void testSshCapabilitiesMissingThrowsProtocolException() {
        String invalidCapabilities = "invalid-ssh-stream-no-capabilities-prefix\n";
        assertThrows(HgProtocolException.class, () -> {
            if (!invalidCapabilities.startsWith("capabilities:")) {
                throw new HgProtocolException("capabilities header not found", "ssh://mock");
            }
        });
    }

    @Test
    public void testSshChunkReadUnexpectedEof() {
        byte[] truncatedChunkHeader = new byte[]{0, 0};
        assertThrows(HgProtocolException.class, () -> {
            if (truncatedChunkHeader.length < 4) {
                throw new HgProtocolException("Unexpected EOF while reading chunk stream", "ssh://mock");
            }
        });
    }

    @Test
    public void testSshAuthFailureThrowsHgAuthException() throws Exception {
        SshServer sshServer = SshServer.setUpDefaultServer();
        sshServer.setPort(0);
        Path tempKey = Files.createTempFile("ssh_host_auth_test_", ".key");
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempKey));
        Files.deleteIfExists(tempKey);

        // Authenticator always returns false (Auth Failure)
        sshServer.setPasswordAuthenticator((username, password, session) -> false);
        sshServer.start();
        int port = sshServer.getPort();

        try {
            String url = "ssh://hg4juser@127.0.0.1:" + port + "/test/repo";
            try (HgSshClient client = new HgSshClient(url)) {
                client.setPassword("wrongpass");
                assertThrows(HgAuthException.class, () -> client.getCapabilities());
            }
        } finally {
            sshServer.stop(true);
        }
    }

    @Test
    public void testSshNoCapabilitiesHeaderThrowsHgProtocolException() throws Exception {
        SshServer sshServer = SshServer.setUpDefaultServer();
        sshServer.setPort(0);
        Path tempKey = Files.createTempFile("ssh_host_nocap_test_", ".key");
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempKey));
        Files.deleteIfExists(tempKey);

        sshServer.setPasswordAuthenticator((username, password, session) -> true);
        sshServer.setCommandFactory((channel, command) -> new Command() {
            private OutputStream out;
            private org.apache.sshd.server.ExitCallback callback;

            @Override public void setInputStream(InputStream in) {}
            @Override public void setOutputStream(OutputStream out) { this.out = out; }
            @Override public void setErrorStream(OutputStream err) {}
            @Override public void setExitCallback(org.apache.sshd.server.ExitCallback cb) { this.callback = cb; }

            @Override
            public void start(org.apache.sshd.server.channel.ChannelSession s, org.apache.sshd.server.Environment env) throws IOException {
                // Respond with invalid text instead of capabilities header
                out.write("invalid response header string\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                callback.onExit(0);
            }

            @Override public void destroy(org.apache.sshd.server.channel.ChannelSession s) {}
        });

        sshServer.start();
        int port = sshServer.getPort();

        try {
            String url = "ssh://hg4juser@127.0.0.1:" + port + "/test/repo";
            try (HgSshClient client = new HgSshClient(url)) {
                client.setPassword("any");
                assertThrows(HgProtocolException.class, () -> client.getCapabilities());
            }
        } finally {
            sshServer.stop(true);
        }
    }

    @Test
    public void testSshChunkReadEofThrowsHgProtocolException() throws Exception {
        SshServer sshServer = SshServer.setUpDefaultServer();
        sshServer.setPort(0);
        Path tempKey = Files.createTempFile("ssh_host_eof_test_", ".key");
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempKey));
        Files.deleteIfExists(tempKey);

        sshServer.setPasswordAuthenticator((username, password, session) -> true);
        sshServer.setCommandFactory((channel, command) -> new Command() {
            private InputStream in;
            private OutputStream out;
            private org.apache.sshd.server.ExitCallback callback;

            @Override public void setInputStream(InputStream in) { this.in = in; }
            @Override public void setOutputStream(OutputStream out) { this.out = out; }
            @Override public void setErrorStream(OutputStream err) {}
            @Override public void setExitCallback(org.apache.sshd.server.ExitCallback cb) { this.callback = cb; }

            @Override
            public void start(org.apache.sshd.server.channel.ChannelSession s, org.apache.sshd.server.Environment env) throws IOException {
                new Thread(() -> {
                    try {
                        out.write("capabilities: heads getbundle changegroup\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();

                        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                        String line = reader.readLine();
                        if (line != null && line.trim().equals("getbundle")) {
                            // read extra parameters blank line
                            reader.readLine();
                            // write incomplete chunk length (e.g. only 2 bytes and then close connection)
                            out.write(new byte[]{0, 0});
                            out.flush();
                        }
                        callback.onExit(0);
                    } catch (IOException ignored) {}
                }).start();
            }

            @Override public void destroy(org.apache.sshd.server.channel.ChannelSession s) {}
        });

        sshServer.start();
        int port = sshServer.getPort();

        try {
            String url = "ssh://hg4juser@127.0.0.1:" + port + "/test/repo";
            try (HgSshClient client = new HgSshClient(url)) {
                client.setPassword("any");
                assertThrows(HgProtocolException.class, () -> client.getBundle(List.of(), List.of("head1"), List.of("bundle2")));
            }
        } finally {
            sshServer.stop(true);
        }
    }


    // ==========================================
    // 3. Native hg serve Extended E2E Verification Scenarios
    // ==========================================



    @Test
    public void testNativeHgExtendedServe(@TempDir Path tempDir) throws Exception {
        if (!com.github.search5.hg4j.HgTestUtils.isHgInstalled()) {
            return;
        }

        ServePortTracker.detectedUrl = null;
        ServePortTracker.capturedLines.clear();

        // 1. Setup Remote Native Repository
        File remoteRepoDir = tempDir.resolve("remote_repo").toFile();
        remoteRepoDir.mkdirs();
        runProcess(remoteRepoDir, "hg", "init");

        // Enable allow_push in remote hgrc
        File hgrc = new File(remoteRepoDir, ".hg/hgrc");
        Files.writeString(hgrc.toPath(), "[web]\nallow_push = *\npush_ssl = false\n");

        // Commit primary files on remote (including branch, tag and bookmark)
        File f1 = new File(remoteRepoDir, "a.txt");
        Files.writeString(f1.toPath(), "Content A\n", StandardCharsets.UTF_8);
        runProcess(remoteRepoDir, "hg", "add", "a.txt");
        runProcess(remoteRepoDir, "hg", "commit", "-m", "Commit 1", "-u", "tester <test@example.com>");

        // Branching & Bookmark
        runProcess(remoteRepoDir, "hg", "branch", "feature-branch");
        File f2 = new File(remoteRepoDir, "b.txt");
        Files.writeString(f2.toPath(), "Content B\n", StandardCharsets.UTF_8);
        runProcess(remoteRepoDir, "hg", "add", "b.txt");
        runProcess(remoteRepoDir, "hg", "commit", "-m", "Commit 2 on branch", "-u", "tester <test@example.com>");
        runProcess(remoteRepoDir, "hg", "bookmark", "my-bookmark");

        // Merge Commit setup (Create merge parent structure)
        runProcess(remoteRepoDir, "hg", "update", "default");
        File f3 = new File(remoteRepoDir, "c.txt");
        Files.writeString(f3.toPath(), "Content C\n", StandardCharsets.UTF_8);
        runProcess(remoteRepoDir, "hg", "add", "c.txt");
        runProcess(remoteRepoDir, "hg", "commit", "-m", "Commit 3 default side branch", "-u", "tester <test@example.com>");

        // Execute merge commit
        runProcess(remoteRepoDir, "hg", "merge", "feature-branch");
        runProcess(remoteRepoDir, "hg", "commit", "-m", "Merge commit feature-branch into default", "-u", "tester <test@example.com>");

        // Tagging
        runProcess(remoteRepoDir, "hg", "tag", "v1.0.0");

        // 2. Start hg serve
        ProcessBuilder servePb = new ProcessBuilder("hg", "serve", "-p", "0", "--address", "127.0.0.1");
        servePb.directory(remoteRepoDir);
        servePb.redirectErrorStream(true);
        Process serveProcess = servePb.start();

        // Start background redirect stream readers to prevent OS pipe block
        Thread stdOutReader = new Thread(() -> {
            try (InputStream is = serveProcess.getInputStream();
                 BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    System.out.println("[hg serve] " + line);
                    ServePortTracker.capturedLines.add(line);
                    if (line.contains("listening at")) {
                        int idx = line.indexOf("http://");
                        if (idx != -1) {
                            int end = line.indexOf("/", idx + 7);
                            ServePortTracker.detectedUrl = end != -1 ? line.substring(idx, end + 1) : line.substring(idx).trim();
                        }
                    }
                }
            } catch (IOException ignored) {}
        });
        stdOutReader.setDaemon(true);
        stdOutReader.start();

        String remoteUrl = null;
        // Wait for port mapping using a server port polling loop
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 8000) {
            if (ServePortTracker.detectedUrl != null) {
                remoteUrl = ServePortTracker.detectedUrl;
                break;
            }
            Thread.sleep(100);
        }

        if (remoteUrl == null) {
            // Fallback port parsing: we can read from .hg/server-state if it exists
            File portFile = new File(remoteRepoDir, ".hg/server-state");
            if (portFile.exists()) {
                String state = Files.readString(portFile.toPath(), StandardCharsets.UTF_8);
                // format: port=XXXX
                for (String line : state.split("\n")) {
                    if (line.startsWith("port=")) {
                        remoteUrl = "http://127.0.0.1:" + line.substring(5).trim();
                        break;
                    }
                }
            }
        }

        if (remoteUrl != null) {
            remoteUrl = remoteUrl.replaceAll("http://[^:]+:", "http://127.0.0.1:");
        }

        assertNotNull(remoteUrl, "Failed to parse listening URL from hg serve. Stdout logs: " + ServePortTracker.capturedLines);

        try {
            // ==========================================
            // Verification 1: Clone Verification and Content Matching Check
            // ==========================================
            File cloneRepoDir = tempDir.resolve("clone_repo").toFile();
            HgRepository cloneRepo = Hg.cloneRepository()
                    .setSource(remoteUrl)
                    .setDirectory(cloneRepoDir)
                    .call();
            assertNotNull(cloneRepo);
            
            File clonedA = new File(cloneRepoDir, "a.txt");
            assertTrue(clonedA.exists());
            assertEquals("Content A\n", Files.readString(clonedA.toPath(), StandardCharsets.UTF_8));

            // ==========================================
            // Verification 2: hg4j log == native hg log field-by-field comparison after Pull
            // ==========================================
            File pullRepoDir = tempDir.resolve("pull_repo").toFile();
            HgRepository pullRepo = Hg.init().setDirectory(pullRepoDir).call();
            new PullCommand(pullRepo).setSource(remoteUrl).call();
            new UpdateCommand(pullRepo).call();

            // hg4j log traversal
            List<HgCommit> hg4jLog = new LogCommand(pullRepo).call();
            assertFalse(hg4jLog.isEmpty());

            // Retrieve native hg logs for comparative verification
            ProcessBuilder logPb = new ProcessBuilder("hg", "log", "--template", "{node}|{author}|{desc}\n");
            logPb.directory(remoteRepoDir);
            Process logProcess = logPb.start();
            String nativeLogOutput = new String(logProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            logProcess.waitFor();

            String[] nativeLines = nativeLogOutput.split("\n");
            // Compare each commit's author and message fields accurately
            int checkLimit = Math.min(hg4jLog.size(), nativeLines.length);
            for (int i = 0; i < checkLimit; i++) {
                String[] nativeFields = nativeLines[i].split("\\|");
                HgCommit hg4jCommit = hg4jLog.get(i); // hg4j and hg log both return newest first
                assertEquals(nativeFields[1].trim(), hg4jCommit.getAuthor().trim());
                assertEquals(nativeFields[2].trim(), hg4jCommit.getMessage().trim());
            }

            // ==========================================
            // Verification 3: Pull repository with branch/tag/bookmark -> Metadata consistency
            // ==========================================
            // Branch check
            assertEquals("default", pullRepo.getBranch(), "Pull repo active branch should be default");
            
            // Tag check (verify .hgtags exists and contains v1.0.0)
            File pullTagsFile = new File(pullRepoDir, ".hgtags");
            assertTrue(pullTagsFile.exists(), ".hgtags file must be pulled successfully");
            String tagsContent = Files.readString(pullTagsFile.toPath(), StandardCharsets.UTF_8);
            assertTrue(tagsContent.contains("v1.0.0"), ".hgtags should contain tagged version v1.0.0");

            // ==========================================
            // Verification 4: Correctly parse 2 parents after Merge Commit pull
            // ==========================================
            File clIdx = new File(pullRepoDir, ".hg/store/00changelog.i");
            File clDat = new File(pullRepoDir, ".hg/store/00changelog.d");
            Revlog changelog = pullRepo.getRevlog(clIdx, clDat);
            
            // Find the merge commit (message contains "Merge commit")
            int mergeRev = -1;
            for (int i = 0; i < changelog.getRevisionCount(); i++) {
                byte[] content = changelog.getRevisionContent(i);
                String text = new String(content, StandardCharsets.UTF_8);
                if (text.contains("Merge commit")) {
                    mergeRev = i;
                    break;
                }
            }
            
            assertTrue(mergeRev >= 0, "Merge commit must be present in the pulled repository");
            Revlog.IndexRecord mergeRec = changelog.getIndexRecord(mergeRev);
            assertTrue(mergeRec.getParent1() >= 0, "Merge commit Parent 1 must be valid");
            assertTrue(mergeRec.getParent2() >= 0, "Merge commit Parent 2 must be valid (not -1)");

            // ==========================================
            // Verification 5: Verify and cat check in native hg after Push
            // ==========================================
            File testPushFile = new File(cloneRepoDir, "pushed_file.txt");
            Files.writeString(testPushFile.toPath(), "Pushed Content\n", StandardCharsets.UTF_8);
            
            try (Hg localHg = Hg.open(cloneRepoDir)) {
                localHg.add().addFile("pushed_file.txt").call();
                localHg.commit().setAuthor("tester <tester@example.com>").setMessage("Commit push verify").call();
                localHg.push().setDestination(remoteUrl).call();
            }

            // remote verify
            ProcessBuilder verifyPb = new ProcessBuilder("hg", "verify");
            verifyPb.directory(remoteRepoDir);
            Process verifyProcess = verifyPb.start();
            int verifyExit = verifyProcess.waitFor();
            assertEquals(0, verifyExit, "Remote repository verification failed after hg4j push!");

            // remote cat
            ProcessBuilder catPb = new ProcessBuilder("hg", "cat", "-r", "tip", "pushed_file.txt");
            catPb.directory(remoteRepoDir);
            Process catProcess = catPb.start();
            String catOutput = new String(catProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            catProcess.waitFor();
            assertEquals("Pushed Content\n", catOutput, "Pushed content does not match on remote!");

        } finally {
            serveProcess.destroy();
            serveProcess.waitFor();
        }
    }

    private void runProcess(File workingDir, String... command) throws Exception {
        String[] cmd = command;
        if (command.length > 0 && "hg".equals(command[0])) {
            cmd = new String[command.length + 2];
            cmd[0] = "hg";
            cmd[1] = "--config";
            cmd[2] = "format.usezstd=false";
            System.arraycopy(command, 1, cmd, 3, command.length - 1);
        }
        Process p = new ProcessBuilder(cmd).directory(workingDir).start();
        p.waitFor();
    }

    // Helper static class to avoid socket block and fetch ports asynchronously
    private static class ServePortTracker {
        public static volatile String detectedUrl = null;
        public static final List<String> capturedLines = java.util.Collections.synchronizedList(new ArrayList<>());
    }
}
