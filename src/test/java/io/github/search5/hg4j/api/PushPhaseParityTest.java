package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What a push does to the phases of the source repository, and with its lock: as Mercurial's {@code exchange.push}. The pushed changesets
 * become public; when the source repository cannot be locked the push still goes through and says the phases were not updated.
 */
class PushPhaseParityTest {

    private static String hg(File dir, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "hg";
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true);
        pb.environment().put("HGPLAIN", "1");
        pb.environment().put("HGUSER", "t");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        return out;
    }

    private static boolean hgAvailable() {
        try {
            Process p = new ProcessBuilder("hg", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void write(File dir, String name, String text) throws Exception {
        Files.writeString(new File(dir, name).toPath(), text);
    }

    private static void copy(Path from, Path to) throws Exception {
        try (var walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path target = to.resolve(from.relativize(p));
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private File source(Path tmp) throws Exception {
        File src = tmp.resolve("src").toFile();
        assertTrue(src.mkdirs());
        hg(src, "init");
        write(src, "a.txt", "a\n");
        hg(src, "commit", "-A", "-m", "one", "-d", "1000 0");
        write(src, "b.txt", "b\n");
        hg(src, "commit", "-A", "-m", "two", "-d", "2000 0");
        File dest = tmp.resolve("dest").toFile();
        assertTrue(dest.mkdirs());
        hg(dest, "init");
        return src;
    }

    @Test
    void pushedChangesetsBecomePublicInTheSourceLikeMercurial(@TempDir Path tmp) throws Exception {
        assumeTrue(hgAvailable());
        File src = source(tmp);
        copy(src.toPath(), tmp.resolve("viahg"));
        copy(src.toPath(), tmp.resolve("viajava"));
        copy(tmp.resolve("dest"), tmp.resolve("desthg"));
        copy(tmp.resolve("dest"), tmp.resolve("destjava"));
        hg(tmp.resolve("viahg").toFile(), "push", tmp.resolve("desthg").toString());
        PushCommand push = new PushCommand(Hg.open(tmp.resolve("viajava").toFile()).getRepository()).setDestination(tmp.resolve("destjava").toString());
        push.call();
        String phases = "{rev} {phase}\n";
        assertEquals(hg(tmp.resolve("viahg").toFile(), "log", "-T", phases), hg(tmp.resolve("viajava").toFile(), "log", "-T", phases));
        assertEquals("1 public\n0 public\n", hg(tmp.resolve("viajava").toFile(), "log", "-T", phases));
        assertTrue(push.getMessages().isEmpty());
    }

    @Test
    void aSourceThatCannotBeLockedStillPushesAndKeepsItsPhases(@TempDir Path tmp) throws Exception {
        assumeTrue(hgAvailable());
        File src = source(tmp);
        HgRepository repo = Hg.open(src).getRepository();
        copy(tmp.resolve("dest"), tmp.resolve("destjava"));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            try (HgLock ignored = repo.lockStore()) {
                held.countDown();
                release.await();
            } catch (Throwable t) {
                failure.set(t);
                held.countDown();
            }
        });
        holder.start();
        assertTrue(held.await(10, TimeUnit.SECONDS));
        assertNull(failure.get());
        PushCommand push = new PushCommand(repo).setDestination(tmp.resolve("destjava").toString());
        try {
            push.call(); // ui.timeout is 0 in the tests: the lock is not available at once
        } finally {
            release.countDown();
            holder.join();
        }
        // the changesets arrived in the destination ...
        assertEquals("1", hg(tmp.resolve("destjava").toFile(), "log", "-r", "tip", "-T", "{rev}"));
        // ... and the source says that its phases were left alone, as Mercurial does
        assertEquals(java.util.List.of("cannot lock source repo, skipping local public phase update\n"), push.getMessages());
        assertEquals("1 draft\n0 draft\n", hg(src, "log", "-T", "{rev} {phase}\n"));
    }

    @Test
    void aNonPublishingRemoteIsToldWhichPushedChangesetsAreAlreadyPublicHere(@TempDir Path tmp) throws Exception {
        assumeTrue(hgAvailable());
        File src = tmp.resolve("src").toFile();
        assertTrue(src.mkdirs());
        hg(src, "init");
        write(src, "a.txt", "a\n");
        hg(src, "commit", "-A", "-m", "one", "-d", "1000 0");
        hg(src, "phase", "-p", "0");
        write(src, "b.txt", "b\n");
        hg(src, "commit", "-A", "-m", "two", "-d", "2000 0");
        File dest = tmp.resolve("dest").toFile();
        assertTrue(dest.mkdirs());
        hg(dest, "init");
        Files.writeString(new File(dest, ".hg/hgrc").toPath(), "[phases]\npublish = false\n");
        copy(src.toPath(), tmp.resolve("srchg"));
        copy(src.toPath(), tmp.resolve("srcjava"));
        copy(dest.toPath(), tmp.resolve("desthg"));
        copy(dest.toPath(), tmp.resolve("destjava"));

        hg(tmp.resolve("srchg").toFile(), "push", tmp.resolve("desthg").toString());
        PushCommand push = new PushCommand(Hg.open(tmp.resolve("srcjava").toFile()).getRepository()).setDestination(tmp.resolve("destjava").toString());
        push.call();

        String phases = "{rev} {phase}\n";
        assertEquals(hg(tmp.resolve("desthg").toFile(), "log", "-T", phases), hg(tmp.resolve("destjava").toFile(), "log", "-T", phases), "remote");
        assertEquals(hg(tmp.resolve("srchg").toFile(), "log", "-T", phases), hg(tmp.resolve("srcjava").toFile(), "log", "-T", phases), "source");
        assertEquals("1 draft\n0 public\n", hg(tmp.resolve("destjava").toFile(), "log", "-T", phases));
        assertTrue(push.getMessages().isEmpty(), push.getMessages().toString());
    }

    private void pushWithWorkingCopyLockHeld(Path tmp, boolean pushback) throws Exception {
        File src = source(tmp);
        if (pushback) {
            Files.writeString(new File(src, ".hg/hgrc").toPath(), "[experimental]\nbundle2.pushback = true\n");
        }
        HgRepository repo = Hg.open(src).getRepository();
        copy(tmp.resolve("dest"), tmp.resolve("destjava"));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            try (HgLock ignored = repo.lockWorkingCopy()) {
                held.countDown();
                release.await();
            } catch (Throwable t) {
                failure.set(t);
                held.countDown();
            }
        });
        holder.start();
        assertTrue(held.await(10, TimeUnit.SECONDS));
        assertNull(failure.get());
        PushCommand push = new PushCommand(repo).setDestination(tmp.resolve("destjava").toString());
        try {
            push.call();
        } finally {
            release.countDown();
            holder.join();
        }
        lastPush = push;
        lastSource = src;
    }

    private PushCommand lastPush;
    private File lastSource;

    @Test
    void withoutPushbackOnlyTheStoreLockIsTakenSoAHeldWorkingCopyLockDoesNotMatter(@TempDir Path tmp) throws Exception {
        assumeTrue(hgAvailable());
        pushWithWorkingCopyLockHeld(tmp, false);
        assertTrue(lastPush.getMessages().isEmpty(), lastPush.getMessages().toString());
        assertEquals("1 public\n0 public\n", hg(lastSource, "log", "-T", "{rev} {phase}\n"));
    }

    @Test
    void withPushbackTheWorkingCopyLockIsTakenFirstAndWhenItIsNotAvailableTheLocalPhasesAreLeftAlone(@TempDir Path tmp) throws Exception {
        assumeTrue(hgAvailable());
        pushWithWorkingCopyLockHeld(tmp, true);
        assertEquals(java.util.List.of("cannot lock source repo, skipping local public phase update\n"), lastPush.getMessages());
        assertEquals("1 draft\n0 draft\n", hg(lastSource, "log", "-T", "{rev} {phase}\n"));
        assertEquals("1", hg(tmp.resolve("destjava").toFile(), "log", "-r", "tip", "-T", "{rev}"), "the push itself went through");
    }

    @Test
    void anEmptyPushStillPublishesWhatIsPublicHereOnANonPublishingRemote(@TempDir Path tmp) throws Exception {
        assumeTrue(hgAvailable());
        File src = source(tmp);
        File dest = tmp.resolve("dest").toFile();
        Files.writeString(new File(dest, ".hg/hgrc").toPath(), "[phases]\npublish = false\n");
        hg(src, "push", dest.toString());              // both changesets arrive as draft
        hg(src, "phase", "-p", "-r", "0");              // the first one becomes public here only
        copy(src.toPath(), tmp.resolve("srchg"));
        copy(src.toPath(), tmp.resolve("srcjava"));
        copy(dest.toPath(), tmp.resolve("desthg"));
        copy(dest.toPath(), tmp.resolve("destjava"));
        hg(tmp.resolve("srchg").toFile(), "push", tmp.resolve("desthg").toString());
        new PushCommand(Hg.open(tmp.resolve("srcjava").toFile()).getRepository()).setDestination(tmp.resolve("destjava").toString()).call();
        String phases = "{rev} {phase}\n";
        assertEquals(hg(tmp.resolve("desthg").toFile(), "log", "-T", phases), hg(tmp.resolve("destjava").toFile(), "log", "-T", phases));
        assertEquals("1 draft\n0 public\n", hg(tmp.resolve("destjava").toFile(), "log", "-T", phases));
    }
}
