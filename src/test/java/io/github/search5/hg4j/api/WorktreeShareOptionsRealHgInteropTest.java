package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.testsupport.LocalHg;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code hg share} of real hg against {@link WorktreeCommand}: the files that the two write in the new {@code .hg} (sharedpath, requires,
 * shared, hgrc), the checkout, and the bookmarks that a {@code -B} share shares.
 */
@Tag("interop")
public class WorktreeShareOptionsRealHgInteropTest {

    @BeforeAll
    static void checkLocalHg() {
        Assumptions.assumeTrue(LocalHg.isAvailable(), LocalHg::unavailableReason);
    }

    private static String hg(Path cwd, String... args) throws Exception {
        String[] all = new String[args.length + 2];
        all[0] = "--config";
        all[1] = "extensions.share=";
        System.arraycopy(args, 0, all, 2, args.length);
        return LocalHg.run(cwd, all);
    }

    /** A source with a commit on default and a later commit on branch b2, so the tip is not on default. */
    private static Path makeSource(Path root, String name, boolean shareSafe) throws Exception {
        Path src = root.resolve(name);
        Files.createDirectories(src);
        hg(root, "init", "--config", "format.use-share-safe=" + shareSafe, name);
        Files.writeString(src.resolve("a"), "a\n");
        hg(src, "commit", "-Aqm", "c1", "-u", "t", "-d", "0 0");
        hg(src, "branch", "-q", "b2");
        Files.writeString(src.resolve("b"), "b\n");
        hg(src, "commit", "-Aqm", "c2", "-u", "t", "-d", "1 0");
        hg(src, "update", "-q", "default");
        return src;
    }

    private static String read(Path p) throws Exception {
        return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "<missing>";
    }

    private static String listing(Path hgDir) throws Exception {
        try (var s = Files.list(hgDir)) {
            return String.join(" ", new TreeSet<>(s.map(x -> x.getFileName().toString()).toList()));
        }
    }

    private static void assertSameShare(Path real, Path mine) throws Exception {
        for (String f : new String[]{"sharedpath", "requires", "shared", "hgrc"}) {
            assertEquals(read(real.resolve(".hg").resolve(f)), read(mine.resolve(".hg").resolve(f)), ".hg/" + f);
        }
        assertEquals(listing(real.resolve(".hg")), listing(mine.resolve(".hg")), "the files of .hg");
        assertEquals(hg(real, "identify", "-b").trim(), hg(mine, "identify", "-b").trim(), "the checked out branch");
        assertEquals(hg(real, "status", "-A").trim(), hg(mine, "status", "-A").trim(), "the working copy");
    }

    private static HgRepository open(Path p) {
        return new HgRepository(p.toFile());
    }

    @Test
    void plainShareMatches(@TempDir Path tmp) throws Exception {
        Path src = makeSource(tmp, "src", true);
        hg(tmp, "share", "src", "real");
        new WorktreeCommand(open(src)).setNewWorktreeDir(tmp.resolve("mine").toFile()).call();
        assertSameShare(tmp.resolve("real"), tmp.resolve("mine"));
        assertEquals("a", hg(tmp.resolve("mine"), "status", "-A").replaceAll("(?s)C ", "").trim().split("\\s+")[0]);
    }

    @Test
    void shareOfNotShareSafeSourceMatches(@TempDir Path tmp) throws Exception {
        Path src = makeSource(tmp, "src", false);
        hg(tmp, "share", "src", "real");
        new WorktreeCommand(open(src)).setNewWorktreeDir(tmp.resolve("mine").toFile()).call();
        assertSameShare(tmp.resolve("real"), tmp.resolve("mine"));
    }

    @Test
    void relativeShareMatches(@TempDir Path tmp) throws Exception {
        Path src = makeSource(tmp, "src", true);
        hg(tmp, "share", "--relative", "src", "real");
        new WorktreeCommand(open(src)).setNewWorktreeDir(tmp.resolve("mine").toFile()).setRelative(true).call();
        assertSameShare(tmp.resolve("real"), tmp.resolve("mine"));
        assertTrue(read(tmp.resolve("mine/.hg/requires")).contains("relshared"));
        // the relative share opens again, in hg4j and in real hg
        HgRepository reopened = open(tmp.resolve("mine"));
        assertEquals(src.resolve(".hg/store").toRealPath(), reopened.getStoreDir().toPath().toRealPath());
        assertEquals("default", hg(tmp.resolve("mine"), "identify", "-b").trim());
    }

    @Test
    void shareOfShareMatches(@TempDir Path tmp) throws Exception {
        Path src = makeSource(tmp, "src", true);
        hg(tmp, "share", "src", "first");
        hg(tmp, "share", "first", "real");
        hg(tmp, "share", "--relative", "first", "realrel");
        new WorktreeCommand(open(tmp.resolve("first"))).setNewWorktreeDir(tmp.resolve("mine").toFile()).call();
        new WorktreeCommand(open(tmp.resolve("first"))).setNewWorktreeDir(tmp.resolve("minerel").toFile()).setRelative(true).call();
        assertSameShare(tmp.resolve("real"), tmp.resolve("mine"));
        assertSameShare(tmp.resolve("realrel"), tmp.resolve("minerel"));
        assertEquals(src.resolve(".hg").toRealPath().toString(), read(tmp.resolve("mine/.hg/sharedpath")));
    }

    @Test
    void noUpdateShareMatches(@TempDir Path tmp) throws Exception {
        Path src = makeSource(tmp, "src", true);
        hg(tmp, "share", "-U", "src", "real");
        new WorktreeCommand(open(src)).setNewWorktreeDir(tmp.resolve("mine").toFile()).setUpdate(false).call();
        assertEquals(listing(tmp.resolve("real/.hg")), listing(tmp.resolve("mine/.hg")));
        assertFalse(Files.exists(tmp.resolve("mine/a")));
        assertEquals(read(tmp.resolve("real/.hg/requires")), read(tmp.resolve("mine/.hg/requires")));
    }

    @Test
    void defaultPathOfSourceIsWritten(@TempDir Path tmp) throws Exception {
        Path src = makeSource(tmp, "src", true);
        Files.writeString(src.resolve(".hg/hgrc"), "[paths]\ndefault = ../other\nfoo = bar\n");
        hg(tmp, "share", "src", "real");
        new WorktreeCommand(open(src)).setNewWorktreeDir(tmp.resolve("mine").toFile()).call();
        assertSameShare(tmp.resolve("real"), tmp.resolve("mine"));
        assertEquals("[paths]\ndefault = ../other\n", read(tmp.resolve("mine/.hg/hgrc")));
    }

    @Test
    void checkoutRevisionOrderMatches(@TempDir Path tmp) throws Exception {
        Path src = makeSource(tmp, "src", true);
        hg(tmp, "share", "src#b2", "real");
        new WorktreeCommand(open(src)).setNewWorktreeDir(tmp.resolve("mine").toFile()).setCheckout("b2").call();
        assertSameShare(tmp.resolve("real"), tmp.resolve("mine"));
        assertEquals("b2", hg(tmp.resolve("mine"), "identify", "-b").trim());
    }

    @Test
    void sharedBookmarksMatchAndFollowTheSource(@TempDir Path tmp) throws Exception {
        Path src = makeSource(tmp, "src", true);
        hg(src, "bookmark", "-r", "1", "m1");
        hg(tmp, "share", "-B", "src", "real");
        new WorktreeCommand(open(src)).setNewWorktreeDir(tmp.resolve("mine").toFile()).setShareBookmarks(true).call();
        assertSameShare(tmp.resolve("real"), tmp.resolve("mine"));
        assertEquals("bookmarks\n", read(tmp.resolve("mine/.hg/shared")));

        // a bookmark made in the source is seen in the share
        hg(src, "bookmark", "-r", "1", "m2");  // the checked out revision 0 has no bookmark: update would activate one
        assertEquals(hg(tmp.resolve("real"), "bookmarks").trim(), hg(tmp.resolve("mine"), "bookmarks").trim());

        // a bookmark made by hg4j in the share goes to the source (and to the share's own file) like the original's share writes it
        HgRepository mine = open(tmp.resolve("mine"));
        new BookmarkCommand(mine).setBookmarkName("m3").setRevision(hg(src, "log", "-r", "1", "-T", "{node}").trim()).call();
        assertTrue(hg(src, "bookmarks").contains("m3"), hg(src, "bookmarks"));
        assertEquals(read(src.resolve(".hg/bookmarks")), read(tmp.resolve("mine/.hg/bookmarks")));
    }

    @Test
    void shareWithoutBookmarksKeepsItsOwn(@TempDir Path tmp) throws Exception {
        Path src = makeSource(tmp, "src", true);
        hg(src, "bookmark", "-r", "1", "m1");
        new WorktreeCommand(open(src)).setNewWorktreeDir(tmp.resolve("mine").toFile()).call();
        assertFalse(Files.exists(tmp.resolve("mine/.hg/shared")));
        assertEquals("no bookmarks set", hg(tmp.resolve("mine"), "bookmarks").trim());
        assertTrue(new BookmarkCommand(open(tmp.resolve("mine"))).list().isEmpty());
    }

    private static Path makeNarrowSource(Path tmp, String name) throws Exception {
        Path src = tmp.resolve(name);
        hg(tmp, "init", name);
        Files.writeString(src.resolve("a"), "a\n");
        hg(src, "commit", "-Aqm", "c1", "-u", "t", "-d", "0 0");
        Files.writeString(src.resolve(".hg/store/requires"), read(src.resolve(".hg/store/requires")) + "narrowhg-experimental\n");
        Files.writeString(src.resolve(".hg/store/narrowspec"), "[include]\npath:b\npath:a\n[exclude]\npath:a/x\n");
        return src;
    }

    /** A narrow source: the new working copy gets the narrowspec of the store, and the transaction leaves its rollback information. */
    @Test
    void narrowSourceGetsTheWorkingCopyNarrowspec(@TempDir Path tmp) throws Exception {
        Path realSrc = makeNarrowSource(tmp, "realsrc");
        Path mySrc = makeNarrowSource(tmp, "mysrc");
        LocalHg.run(tmp, "--config", "extensions.share=", "--config", "extensions.narrow=", "share", "realsrc", "real");
        new WorktreeCommand(open(mySrc)).setNewWorktreeDir(tmp.resolve("mine").toFile()).call();
        for (String f : new String[]{"narrowspec.dirstate", "requires", "undo.desc", "undo.backup.branch.bck"}) {
            assertEquals(read(tmp.resolve("real/.hg").resolve(f)), read(tmp.resolve("mine/.hg").resolve(f)), f);
        }
        assertEquals(listing(tmp.resolve("real/.hg")), listing(tmp.resolve("mine/.hg")));
        for (String f : new String[]{"undo", "undo.backupfiles"}) {
            assertEquals(read(realSrc.resolve(".hg/store").resolve(f)), read(mySrc.resolve(".hg/store").resolve(f)), "store/" + f);
        }
        assertEquals("[include]\npath:a\npath:b\n[exclude]\npath:a/x\n", read(tmp.resolve("mine/.hg/narrowspec.dirstate")));
    }

    /** A {@code paths.default} that only the user's hgrc has is written to the new .hg/hgrc as well. */
    @Test
    void defaultPathOfTheUserConfigurationIsWritten(@TempDir Path tmp) throws Exception {
        Path src = makeSource(tmp, "src", true);
        Path home = tmp.resolve("home");
        Files.createDirectories(home);
        Files.writeString(home.resolve(".hgrc"), "[paths]\ndefault = https://example.com/repo\n");

        ProcessBuilder pb = LocalHg.processBuilder(tmp, "--config", "extensions.share=", "share", "src", "real");
        pb.environment().put("HGRCPATH", home.resolve(".hgrc").toString());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);

        String previous = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            new WorktreeCommand(open(src)).setNewWorktreeDir(tmp.resolve("mine").toFile()).call();
        } finally {
            System.setProperty("user.home", previous);
        }
        assertEquals("[paths]\ndefault = https://example.com/repo\n", read(tmp.resolve("real/.hg/hgrc")));
        assertEquals(read(tmp.resolve("real/.hg/hgrc")), read(tmp.resolve("mine/.hg/hgrc")));
    }
}
