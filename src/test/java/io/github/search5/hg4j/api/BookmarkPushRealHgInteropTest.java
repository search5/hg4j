package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code hg push -B}: real hg and hg4j push the same bookmark situations to equal destinations (a local path and an HTTP server
 * run by real hg) and must agree on the bookmarks and changesets the destination ends up with, on the lines printed
 * ({@code updating bookmark}, {@code exporting bookmark}, {@code deleting remote bookmark}, refusals) and on the exit status
 * of the bookmark part.
 */
@Tag("interop")
class BookmarkPushRealHgInteropTest {

    @TempDir
    Path tempDir;

    private Process serve;

    private record Run(int exit, String output) {
    }

    private static Run realHg(File dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("hg", "--config", "format.usezstd=false", "--config", "ui.username=t"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        return new Run(p.waitFor(), out);
    }

    private static String hg(File dir, String... args) throws Exception {
        Run run = realHg(dir, args);
        assertEquals(0, run.exit(), String.join(" ", args) + ":\n" + run.output());
        return run.output();
    }

    static Stream<String[]> cases() {
        List<String[]> all = new ArrayList<>();
        for (String transport : new String[]{"local", "http"}) {
            for (String[] c : new String[][]{
                    {"push everything, no -B"}, {"-B new"}, {"-B adv"}, {"-B gone"}, {"-B div"}, {"-B same"}, {"-B nothere"},
                    {"-B new -B adv"}}) {
                all.add(new String[]{c[0], transport});
            }
        }
        return all.stream();
    }

    @BeforeEach
    void requireHg() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled());
    }

    @AfterEach
    void stop() throws Exception {
        if (serve != null) {
            serve.destroy();
            serve.waitFor();
        }
    }

    private void write(File dir, String name) throws Exception {
        Files.writeString(new File(dir, name).toPath(), name + "\n");
    }

    /**
     * Source: c1, c2, c3 (child of c2), other (child of c1... see below), extra (child of other); bookmarks adv=c3, same=c2,
     * new=c3, div=c3. Destination: c1, c2, other; bookmarks adv=c1, same=c2, gone=c2, div=other.
     */
    private File[] build(String name) throws Exception {
        File src = tempDir.resolve(name + "-src").toFile();
        File dst = tempDir.resolve(name + "-dst").toFile();
        src.mkdirs();
        dst.mkdirs();
        hg(src, "init");
        write(src, "c1");
        hg(src, "add");
        hg(src, "commit", "-m", "c1");
        write(src, "c2");
        hg(src, "add");
        hg(src, "commit", "-m", "c2");
        write(src, "c3");
        hg(src, "add");
        hg(src, "commit", "-m", "c3");
        hg(src, "update", "-r", "desc(c2)");
        write(src, "other");
        hg(src, "add");
        hg(src, "commit", "-m", "other");
        write(src, "extra");
        hg(src, "add");
        hg(src, "commit", "-m", "extra");
        hg(src, "bookmark", "-r", "desc(c3)", "adv");
        hg(src, "bookmark", "-r", "desc(c2)", "same");
        hg(src, "bookmark", "-r", "desc(c3)", "new");
        hg(src, "bookmark", "-r", "desc(c3)", "div");
        hg(dst, "init");
        Files.writeString(new File(dst, ".hg/hgrc").toPath(), "[web]\npush_ssl = false\nallow-push = *\n");
        hg(src, "push", "-r", "desc(other)", dst.getAbsolutePath());
        hg(dst, "bookmark", "-r", "desc(c1)", "adv");
        hg(dst, "bookmark", "-r", "desc(c2)", "same");
        hg(dst, "bookmark", "-r", "desc(c2)", "gone");
        hg(dst, "bookmark", "-r", "desc(other)", "div");
        return new File[]{src, dst};
    }

    private String serveUrl(File dst) throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        serve = new ProcessBuilder("hg", "serve", "-p", String.valueOf(port), "--config", "web.push_ssl=false",
                "--config", "web.allow-push=*").directory(dst).redirectErrorStream(true).start();
        for (int i = 0; i < 100; i++) {
            try (Socket s = new Socket("127.0.0.1", port)) {
                break;
            } catch (java.io.IOException e) {
                Thread.sleep(100);
            }
        }
        return "http://127.0.0.1:" + port;
    }

    private static Map<String, String> bookmarksOf(File dir) throws Exception {
        Map<String, String> map = new TreeMap<>();
        for (String line : hg(dir, "bookmarks", "-T", "{bookmark}={desc}\n", "--config", "ui.verbose=false").split("\n")) {
            if (line.contains("=")) {
                map.put(line.substring(0, line.indexOf('=')), line.substring(line.indexOf('=') + 1).strip());
            }
        }
        return map;
    }

    private static TreeSet<String> changesetsOf(File dir) throws Exception {
        TreeSet<String> set = new TreeSet<>();
        for (String line : hg(dir, "log", "-T", "{desc}\n").split("\n")) {
            if (!line.isBlank()) {
                set.add(line.strip());
            }
        }
        return set;
    }

    private static List<String> bookmarkLines(String output) {
        List<String> lines = new ArrayList<>();
        for (String line : output.split("\n")) {
            String t = line.strip();
            if (t.startsWith("updating bookmark") || t.startsWith("exporting bookmark") || t.startsWith("deleting remote bookmark")
                    || t.startsWith("cannot push") || (t.startsWith("bookmark ") && t.contains("does not exist"))) {
                lines.add(t);
            }
        }
        return lines;
    }

    @ParameterizedTest(name = "{0} over {1}")
    @MethodSource("cases")
    void bothClientsLeaveTheDestinationAlikeAndReportTheSame(String flags, String transport) throws Exception {
        String key = (flags + transport).replaceAll("[^a-z]", "");
        File[] real = build(key + "r");
        File[] own = build(key + "o");
        List<String> names = new ArrayList<>();
        List<String> args = new ArrayList<>(List.of("push"));
        if (flags.startsWith("-B")) {
            for (String part : flags.split(" ")) {
                if (!part.equals("-B")) {
                    names.add(part);
                    args.add("-B");
                    args.add(part);
                }
            }
        }
        String realTarget = transport.equals("http") ? serveUrl(real[1]) : real[1].getAbsolutePath();
        args.add(realTarget);
        Run realRun = realHg(real[0], args.toArray(String[]::new));
        if (serve != null) {
            serve.destroy();
            serve.waitFor();
            serve = null;
        }

        String ownTarget = transport.equals("http") ? serveUrl(own[1]) : own[1].getAbsolutePath();
        PushCommand push = new PushCommand(new HgRepository(own[0])).setDestination(ownTarget);
        if (!names.isEmpty()) {
            push.setBookmarks(names.toArray(String[]::new));
        }
        String ownOutput;
        try {
            ownOutput = push.call();
        } catch (Exception e) {
            ownOutput = "EXCEPTION " + e;
        }

        // Known difference: when nothing is pushed ("-B" of a name that is missing locally), Mercurial still moves the advanced
        // bookmark "adv" to its changeset, which the destination does not have, and so stores a dangling bookmark. hg4j leaves
        // it where it was.
        boolean danglingQuirk = flags.equals("-B gone") || flags.equals("-B nothere");
        Map<String, String> realBookmarks = new TreeMap<>(bookmarksOf(real[1]));
        Map<String, String> ownBookmarks = new TreeMap<>(bookmarksOf(own[1]));
        List<String> realLines = bookmarkLines(realRun.output());
        List<String> ownLines = bookmarkLines(ownOutput);
        if (danglingQuirk) {
            realBookmarks.remove("adv");
            ownBookmarks.remove("adv");
            realLines.removeIf(l -> l.contains(" adv"));
            ownLines.removeIf(l -> l.contains(" adv"));
        }
        assertEquals(realBookmarks, ownBookmarks, "bookmarks of the destination after: " + flags + " over " + transport
                + "\nreal output:\n" + realRun.output() + "\nours: " + ownOutput);
        assertEquals(changesetsOf(real[1]), changesetsOf(own[1]), "changesets of the destination after: " + flags);
        assertEquals(realLines, ownLines, "bookmark lines printed after: " + flags);
        int realBookmarkStatus = realRun.exit() == 2 ? 2 : 0;
        assertEquals(realBookmarkStatus, push.getBookmarkResult(), "bookmark status after: " + flags + "\nreal output:\n" + realRun.output());
        if (serve != null) {
            serve.destroy();
            serve.waitFor();
            serve = null;
        }
    }
}
