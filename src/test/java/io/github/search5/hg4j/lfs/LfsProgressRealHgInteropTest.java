package io.github.search5.hg4j.lfs;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.InitCommand;
import io.github.search5.hg4j.api.PullCommand;
import io.github.search5.hg4j.api.PushCommand;
import io.github.search5.hg4j.api.UpdateCommand;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.ProgressMonitor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 의도적 차이 포팅 5번: an LFS transfer reports like Mercurial does. Real hg prints {@code lfs: uploaded N files (SIZE)} /
 * {@code lfs: downloaded ...} after an HTTP transfer; hg4j hands the same line to the {@link ProgressMonitor} (and the bytes
 * as progress of the topic {@code lfs uploading} / {@code lfs downloading}). The size text (Mercurial's {@code bytecount}) is
 * compared for sizes in every unit.
 */
@Tag("interop")
class LfsProgressRealHgInteropTest {

    @TempDir
    Path tempDir;

    private Process serve;
    private static final String[] LFS = {"--config", "extensions.lfs=", "--config", "lfs.track=size(\">100\")",
            "--config", "experimental.lfs.disableusercache=true"};
    private static final Pattern LINE = Pattern.compile("lfs: (uploaded|downloaded) \\d+ files \\([^)]*\\)");

    private static String[] concat(String[] head, String... tail) {
        String[] all = new String[head.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }

    @BeforeEach
    void requireHg() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled());
    }

    @org.junit.jupiter.api.AfterEach
    void stop() throws Exception {
        if (serve != null) {
            serve.destroy();
            serve.waitFor();
        }
    }

    private static final class Recorder implements ProgressMonitor {
        final List<String> titles = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        int done;
        int total = -2;

        @Override public void start(String title, int totalWork) { titles.add(title); total = totalWork; done = 0; }
        @Override public void update(int completed) { done += completed; }
        @Override public void end() { }
        @Override public boolean isCancelled() { return false; }
        @Override public void message(String text) { messages.add(text); }
    }

    /** An empty lfs server run by real hg (accepting pushes); returns its URL. */
    private String emptyServer(String name) throws Exception {
        File server = tempDir.resolve(name).toFile();
        server.mkdirs();
        HgTestUtils.hg(server, concat(LFS, "init"));
        Files.writeString(new File(server, ".hg/hgrc").toPath(),
                "[extensions]\nlfs =\n[web]\npush_ssl = false\nallow-push = *\n[experimental]\nlfs.disableusercache = true\n");
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        if (serve != null) {
            serve.destroy();
            serve.waitFor();
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
        return "http://127.0.0.1:" + port;
    }

    private static byte[] bytes(int size) {
        byte[] data = new byte[size];
        new Random(System.nanoTime() + size).nextBytes(data);
        return data;
    }

    private static String line(String output) {
        Matcher matcher = LINE.matcher(output);
        assertTrue(matcher.find(), "no lfs summary line in:\n" + output);
        return matcher.group();
    }

    @Test
    void theUploadLineAndItsSizeTextMatchRealHgInEveryUnit() throws Exception {
        for (int[] sizes : new int[][]{{600}, {1500}, {11000}, {200000}, {1200000}, {300, 300, 300}, {1152}, {1024 * 1024 * 3 / 2}}) {
            byte[][] files = new byte[sizes.length][];
            for (int i = 0; i < sizes.length; i++) {
                files[i] = bytes(sizes[i]);
            }
            // real hg
            String realUrl = emptyServer("real-srv-" + sizes[0] + "-" + sizes.length);
            File realSrc = tempDir.resolve("real-src-" + sizes[0] + "-" + sizes.length).toFile();
            realSrc.mkdirs();
            HgTestUtils.hg(realSrc, concat(LFS, "init"));
            for (int i = 0; i < files.length; i++) {
                Files.write(new File(realSrc, "f" + i + ".bin").toPath(), files[i]);
            }
            HgTestUtils.hg(realSrc, concat(LFS, "add"));
            HgTestUtils.hg(realSrc, concat(LFS, "commit", "-m", "x", "-u", "t"));
            String expected = line(HgTestUtils.hg(realSrc, concat(LFS, "push", realUrl)));

            // hg4j
            String ourUrl = emptyServer("our-srv-" + sizes[0] + "-" + sizes.length);
            File dir = tempDir.resolve("our-src-" + sizes[0] + "-" + sizes.length).toFile();
            new InitCommand().setDirectory(dir).call();
            Files.writeString(new File(dir, ".hg/hgrc").toPath(),
                    "[extensions]\nlfs =\n[lfs]\ntrack = size(\">100\")\n[experimental]\nlfs.disableusercache = true\n");
            HgRepository repo = new HgRepository(dir);
            for (int i = 0; i < files.length; i++) {
                Files.write(new File(dir, "f" + i + ".bin").toPath(), files[i]);
            }
            new AddCommand(repo).call();
            new CommitCommand(repo).setMessage("x").setAuthor("t").call();
            Recorder monitor = new Recorder();
            new PushCommand(new HgRepository(dir)).setDestination(ourUrl).setProgressMonitor(monitor).call();
            assertEquals(List.of(expected), monitor.messages, "sizes " + java.util.Arrays.toString(sizes));
            assertEquals(List.of("lfs uploading"), monitor.titles);
            long sum = 0;
            for (int s : sizes) {
                sum += s;
            }
            assertEquals(sum, monitor.total);
            assertEquals(sum, monitor.done, "every byte was reported as progress");
        }
    }

    @Test
    void theDownloadLineMatchesRealHg() throws Exception {
        byte[] data = bytes(1500);
        String url = emptyServer("srv");
        File src = tempDir.resolve("src").toFile();
        src.mkdirs();
        HgTestUtils.hg(src, concat(LFS, "init"));
        Files.write(new File(src, "big.bin").toPath(), data);
        HgTestUtils.hg(src, concat(LFS, "add"));
        HgTestUtils.hg(src, concat(LFS, "commit", "-m", "x", "-u", "t"));
        HgTestUtils.hg(src, concat(LFS, "push", url));

        File realDst = tempDir.resolve("real-dst").toFile();
        String expected = line(HgTestUtils.hg(tempDir.toFile(), concat(LFS, "clone", url, realDst.getAbsolutePath())));

        File dir = tempDir.resolve("our-dst").toFile();
        new InitCommand().setDirectory(dir).call();
        Files.writeString(new File(dir, ".hg/hgrc").toPath(),
                "[extensions]\nlfs =\n[experimental]\nlfs.disableusercache = true\n[paths]\ndefault = " + url + "\n");
        HgRepository repo = new HgRepository(dir);
        new PullCommand(repo).call();
        Recorder monitor = new Recorder();
        new UpdateCommand(new HgRepository(dir)).setProgressMonitor(monitor).call();
        assertEquals(List.of(expected), monitor.messages);
        assertEquals(List.of("lfs downloading"), monitor.titles);
        assertArrayEquals(data, Files.readAllBytes(new File(dir, "big.bin").toPath()));
    }
}
