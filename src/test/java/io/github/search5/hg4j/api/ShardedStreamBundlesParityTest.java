package io.github.search5.hg4j.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The sharded streaming bundles of {@code hg debug::sharded-stream-bundles} (the Rust build of Mercurial, which has the shape code),
 * byte for byte: the same store, the same bundle group id, the same files.
 */
class ShardedStreamBundlesParityTest {

    private static final File RUST_HG = new File(System.getProperty("user.home"), ".cache/hg4j/hg-rust-dev/bin/hg");

    private static String run(File dir, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true);
        pb.environment().put("HGPLAIN", "1");
        pb.environment().put("HGUSER", "t");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS));
        return out;
    }

    private static String hg(File dir, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = RUST_HG.getPath();
        System.arraycopy(args, 0, cmd, 1, args.length);
        return run(dir, cmd);
    }

    private static void write(File dir, String name, String text) throws Exception {
        File f = new File(dir, name);
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), text);
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

    @Test
    void everyShardBundleIsTheSameAsMercurialsRustBuildWrites(@TempDir Path tmp) throws Exception {
        assumeTrue(RUST_HG.isFile(), "the Rust build of Mercurial is not installed");
        File repo = tmp.resolve("repo").toFile();
        assertTrue(repo.mkdirs());
        hg(repo, "init");
        write(repo, "foo/a.txt", "a\n");
        write(repo, "foo/deep/b.txt", "b\n");
        write(repo, "stone/s.txt", "s\n");
        write(repo, "top.txt", "top\n");
        hg(repo, "commit", "-A", "-m", "one", "-d", "1000 0");
        write(repo, "foo/a.txt", "a2\n");
        write(repo, "stone/s2.txt", "s2\n");
        hg(repo, "commit", "-A", "-m", "two", "-d", "2000 0");
        Files.writeString(new File(repo, ".hg/hgrc").toPath(),
                "[server]\npeer-bundle-cache-root = shardedcache\n[experimental]\nnarrow = true\nserver.stream-narrow-clones = true\n");
        write(repo, ".hg/store/server-shapes", "version = 0\n\n[[shards]]\nname = \"foo-files\"\npaths = [\"foo\"]\n\n"
                + "[[shards]]\nname = \"stone-files\"\npaths = [\"stone\"]\n");
        Files.createDirectories(repo.toPath().resolve(".hg/shardedcache"));
        copy(repo.toPath(), tmp.resolve("copy"));
        File copy = tmp.resolve("copy").toFile();
        Files.createDirectories(copy.toPath().resolve(".hg/shardedcache"));

        String out = hg(repo, "debug::sharded-stream-bundles");
        File[] oracle = new File(repo, ".hg/shardedcache").listFiles((d, n) -> n.endsWith(".hg"));
        assertNotNull(oracle, out);
        assertTrue(oracle.length >= 3, out);
        String group = oracle[0].getName().split("-")[2];

        List<File> ours = new ShardedStreamBundlesCommand(Hg.open(copy).getRepository()).setGroupId(group).call();
        assertEquals(oracle.length, ours.size());
        List<String> compared = new ArrayList<>();
        for (File o : oracle) {
            File mine = new File(copy, ".hg/shardedcache/" + o.getName());
            assertTrue(mine.isFile(), "missing " + o.getName() + " among " + ours);
            assertArrayEquals(Files.readAllBytes(o.toPath()), Files.readAllBytes(mine.toPath()), o.getName());
            compared.add(o.getName());
        }
        assertEquals(oracle.length, compared.size());
    }
}
