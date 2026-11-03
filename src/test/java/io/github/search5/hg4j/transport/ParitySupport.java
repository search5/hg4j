package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.testsupport.LocalHg;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Shared pieces of the tests that compare hg4j's wire servers with a real {@code hg} (the 7.2.4 oracle) on the same repository. */
final class ParitySupport {

    private ParitySupport() {
    }

    /** A server repository made by real hg: default branch (3 commits), a bookmark, a tag and a {@code feature} branch. */
    record Fixture(Path repo, String n0, String n1, String n2) {
    }

    static Fixture createRepo(Path tmp) throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("server"));
        LocalHg.hgIn(tmp, "server", "init", ".");
        for (int i = 0; i < 3; i++) {
            Files.writeString(repo.resolve("a.txt"), "a" + i + "\n");
            if (i == 0) {
                LocalHg.hgIn(tmp, "server", "add", "a.txt");
            }
            LocalHg.hgIn(tmp, "server", "commit", "-u", "dev", "-m", "c" + i);
        }
        LocalHg.hgIn(tmp, "server", "bookmark", "bm1", "-r", "1");
        LocalHg.hgIn(tmp, "server", "tag", "-u", "dev", "-r", "1", "v1");
        LocalHg.hgIn(tmp, "server", "update", "-r", "1");
        LocalHg.hgIn(tmp, "server", "branch", "feature");
        Files.writeString(repo.resolve("a.txt"), "f\n");
        LocalHg.hgIn(tmp, "server", "commit", "-u", "dev", "-m", "on feature");
        LocalHg.hgIn(tmp, "server", "update", "-r", "default");
        return new Fixture(repo,
                LocalHg.hgIn(tmp, "server", "log", "-r", "0", "--template", "{node}"),
                LocalHg.hgIn(tmp, "server", "log", "-r", "1", "--template", "{node}"),
                LocalHg.hgIn(tmp, "server", "log", "-r", "2", "--template", "{node}"));
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (java.util.stream.Stream<Path> walk = Files.walk(from)) {
            for (Path source : (Iterable<Path>) walk::iterator) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target);
                }
            }
        }
    }

    /** What a real hg or hg4j SSH server writes when it is fed {@code clientBytes} on stdin. */
    static byte[] sshSession(boolean real, Path repo, byte[] clientBytes) throws Exception {
        if (real) {
            ProcessBuilder pb = LocalHg.processBuilder(repo, "-R", repo.toString(), "serve", "--stdio");
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            try (java.io.OutputStream os = p.getOutputStream()) {
                os.write(clientBytes);
            }
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("hg serve --stdio did not finish");
            }
            return out;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new HgSshWireServer(new HgRepository(repo.toFile())).handleConnection(new ByteArrayInputStream(clientBytes), out);
        return out.toByteArray();
    }

    /** {@code unbundle} request bytes: the {@code heads} argument, then the bundle as one payload chunk, then the end marker. */
    static byte[] unbundleRequest(String heads, byte[] bundle) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("unbundle\nheads " + heads.length() + "\n" + heads).getBytes(StandardCharsets.US_ASCII));
        if (bundle.length > 0) {
            out.writeBytes((bundle.length + "\n").getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(bundle);
        }
        out.writeBytes("0\n".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes("\n".getBytes(StandardCharsets.US_ASCII)); // an empty command line ends the session
        return out.toByteArray();
    }

    /** A real {@code hg serve} on a free port (the repository must allow pushes if the test pushes). */
    static final class RealServe implements AutoCloseable {
        final Process process;
        final int port;

        RealServe(Path repo) throws Exception {
            ProcessBuilder pb = LocalHg.processBuilder(repo, "serve", "-p", "0", "--address", "127.0.0.1");
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONUNBUFFERED", "1");
            process = pb.start();
            CompletableFuture<Integer> found = new CompletableFuture<>();
            List<String> seen = new CopyOnWriteArrayList<>();
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        seen.add(line);
                        Matcher m = Pattern.compile("listening at http://[^:/]+:(\\d+)/").matcher(line);
                        if (m.find()) {
                            found.complete(Integer.parseInt(m.group(1)));
                        }
                    }
                } catch (IOException ignored) {
                    // the process was stopped
                }
            });
            reader.setDaemon(true);
            reader.start();
            try {
                port = found.get(30, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                process.destroy();
                throw new AssertionError("hg serve printed no listening banner; output=" + seen);
            }
        }

        @Override
        public void close() {
            process.destroy();
        }
    }

    static String hex(byte[] b, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, from); i < Math.min(to, b.length); i++) {
            sb.append(String.format("%02x", b[i])).append(' ');
        }
        return sb.toString();
    }

    static String text(byte[] b, int from, int to) {
        return new String(b, Math.max(0, from), Math.max(0, Math.min(to, b.length) - Math.max(0, from)), StandardCharsets.ISO_8859_1)
                .replace("\n", "\\n").replace("\0", "\\0");
    }
}
