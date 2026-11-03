package io.github.search5.hg4j.lfs;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.BundleCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.InitCommand;
import io.github.search5.hg4j.api.PushCommand;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LFS plan Phase 4: the destination of the blobs is chosen by {@code lfs.url} (or the push address) exactly like real hg. Real hg
 * and hg4j push the same LFS commit to a local repository under each setting and must agree: refuse with the same words
 * (no address, unknown scheme), upload nothing ({@code null:}), or copy the blobs into a directory ({@code file:}).
 */
@Tag("interop")
class LfsPushDestinationsRealHgInteropTest {

    @TempDir
    Path tempDir;

    private byte[] payload;

    @BeforeEach
    void requireHg() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled());
        payload = new byte[600];
        new Random(System.nanoTime()).nextBytes(payload);
    }

    private String[] lfsConfig(String... extra) {
        String[] base = {"--config", "extensions.lfs=", "--config", "lfs.track=size(\">100\")",
                "--config", "experimental.lfs.disableusercache=true"};
        String[] all = new String[base.length + extra.length * 2];
        System.arraycopy(base, 0, all, 0, base.length);
        for (int i = 0; i < extra.length; i++) {
            all[base.length + i * 2] = "--config";
            all[base.length + i * 2 + 1] = extra[i];
        }
        return all;
    }

    private static String[] concat(String[] head, String... tail) {
        String[] all = new String[head.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }

    /** A repository made by real hg holding one LFS file; the same bytes are used for the hg4j twin. */
    private File realSource(String name) throws Exception {
        File src = tempDir.resolve(name).toFile();
        src.mkdirs();
        HgTestUtils.hg(src, concat(lfsConfig(), "init"));
        Files.write(new File(src, "big.bin").toPath(), payload);
        HgTestUtils.hg(src, concat(lfsConfig(), "add"));
        HgTestUtils.hg(src, concat(lfsConfig(), "commit", "-m", "one", "-u", "t"));
        return src;
    }

    private HgRepository hg4jSource(String name, String lfsUrl) throws Exception {
        File dir = tempDir.resolve(name).toFile();
        new InitCommand().setDirectory(dir).call();
        Files.writeString(new File(dir, ".hg/hgrc").toPath(),
                "[extensions]\nlfs =\n[lfs]\ntrack = size(\">100\")\n" + (lfsUrl == null ? "" : "url = " + lfsUrl + "\n")
                        + "[experimental]\nlfs.disableusercache = true\n");
        HgRepository repo = new HgRepository(dir);
        Files.write(new File(dir, "big.bin").toPath(), payload);
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("one").setAuthor("t").call();
        return new HgRepository(dir);
    }

    private File emptyDestination(String name) throws Exception {
        File dst = tempDir.resolve(name).toFile();
        dst.mkdirs();
        HgTestUtils.hg(dst, concat(lfsConfig(), "init"));
        return dst;
    }

    @Test
    void withoutAnAddressTheUploadIsRefused() throws Exception {
        File realDst = emptyDestination("real-dst");
        AssertionError real = assertThrows(AssertionError.class,
                () -> HgTestUtils.hg(realSource("real-src"), concat(lfsConfig(), "push", realDst.getAbsolutePath())));
        assertTrue(real.getMessage().contains("lfs.url needs to be configured"), real.getMessage());

        File dst = emptyDestination("dst");
        HgRepository src = hg4jSource("src", null);
        IOException ours = assertThrows(IOException.class, () -> new PushCommand(src).setDestination(dst.getAbsolutePath()).call());
        assertTrue(ours.getMessage().contains("lfs.url needs to be configured"), ours.getMessage());
    }

    @Test
    void anUnknownSchemeIsRefused() throws Exception {
        File realDst = emptyDestination("real-dst");
        AssertionError real = assertThrows(AssertionError.class, () -> HgTestUtils.hg(realSource("real-src"),
                concat(lfsConfig("lfs.url=ftp://example.invalid/x"), "push", realDst.getAbsolutePath())));
        assertTrue(real.getMessage().contains("lfs: unknown url scheme: ftp"), real.getMessage());

        File dst = emptyDestination("dst");
        HgRepository src = hg4jSource("src", "ftp://example.invalid/x");
        IOException ours = assertThrows(IOException.class, () -> new PushCommand(src).setDestination(dst.getAbsolutePath()).call());
        assertTrue(ours.getMessage().contains("lfs: unknown url scheme: ftp"), ours.getMessage());
    }

    @Test
    void theNullStoreUploadsNothingButTheChangesetsArrive() throws Exception {
        File realDst = emptyDestination("real-dst");
        HgTestUtils.hg(realSource("real-src"), concat(lfsConfig("lfs.url=null:"), "push", realDst.getAbsolutePath()));
        assertEquals(1, HgTestUtils.hg(realDst, concat(lfsConfig(), "log", "--template", "{rev}\n")).lines().count());

        File dst = emptyDestination("dst");
        new PushCommand(hg4jSource("src", "null:")).setDestination(dst.getAbsolutePath()).call();
        assertEquals(1, HgTestUtils.hg(dst, concat(lfsConfig(), "log", "--template", "{rev}\n")).lines().count(),
                "the changeset arrived although no blob was uploaded");
    }

    @Test
    void aFileStoreReceivesTheBlobsInTheSameLayout() throws Exception {
        File realStore = tempDir.resolve("real-store").toFile();
        File realDst = emptyDestination("real-dst");
        HgTestUtils.hg(realSource("real-src"),
                concat(lfsConfig("lfs.url=file://" + realStore.getAbsolutePath()), "push", realDst.getAbsolutePath()));

        File store = tempDir.resolve("store").toFile();
        File dst = emptyDestination("dst");
        new PushCommand(hg4jSource("src", "file://" + store.getAbsolutePath())).setDestination(dst.getAbsolutePath()).call();

        String oid = HgLfsPointer.sha256Hex(payload);
        assertEquals(files(realStore), files(store), "same directory layout as real hg");
        assertTrue(files(store).contains(oid.substring(0, 2) + "/" + oid.substring(2)), files(store).toString());
        assertArrayEquals(payload, Files.readAllBytes(new File(store, oid.substring(0, 2) + "/" + oid.substring(2)).toPath()));
    }

    @Test
    void aBundleUploadsLikeAPushAndWithoutAnAddressIsRefused() throws Exception {
        File realBundle = tempDir.resolve("real.hg").toFile();
        AssertionError real = assertThrows(AssertionError.class, () -> HgTestUtils.hg(realSource("real-src"),
                concat(lfsConfig(), "bundle", "--all", "-t", "none-v3", realBundle.getAbsolutePath())));
        assertTrue(real.getMessage().contains("lfs.url needs to be configured"), real.getMessage());

        File bundle = tempDir.resolve("ours.hg").toFile();
        HgRepository src = hg4jSource("src", null);
        IOException ours = assertThrows(IOException.class,
                () -> new BundleCommand(src).setOutputFile(bundle).setType("none-v3").setBaseRevision("null").call());
        assertTrue(ours.getMessage().contains("lfs.url needs to be configured"), ours.getMessage());
        assertFalse(bundle.exists(), "no bundle file may be left behind");
    }

    @Test
    void aVersion1BundleOfAnLfsRepositoryIsRefusedLikeRealHg() throws Exception {
        File realBundle = tempDir.resolve("real.hg").toFile();
        AssertionError real = assertThrows(AssertionError.class, () -> HgTestUtils.hg(realSource("real-src"),
                concat(lfsConfig("lfs.url=null:"), "bundle", "--all", "-t", "v1", realBundle.getAbsolutePath())));
        assertTrue(real.getMessage().contains("repository does not support bundle version 01"), real.getMessage());

        File bundle = tempDir.resolve("ours.hg").toFile();
        HgRepository src = hg4jSource("src", "null:");
        for (String type : new String[] {"none-v1", "gzip-v1", "bzip2-v1"}) {
            IllegalStateException ours = assertThrows(IllegalStateException.class,
                    () -> new BundleCommand(src).setOutputFile(bundle).setType(type).setBaseRevision("null").call());
            assertTrue(ours.getMessage().contains("repository does not support bundle version 01"), ours.getMessage());
        }
    }

    @Test
    void aVersion3BundleOfAnLfsRepositoryIsAcceptedByRealHg() throws Exception {
        File bundle = tempDir.resolve("ours.hg").toFile();
        HgRepository src = hg4jSource("src", "null:");
        assertEquals(1, new BundleCommand(src).setOutputFile(bundle).setType("none-v3").setBaseRevision("null").call());

        File dst = emptyDestination("dst");
        HgTestUtils.hg(dst, concat(lfsConfig(), "unbundle", bundle.getAbsolutePath()));
        // the destination has no blob, so real hg's verify has to be told to skip LFS revisions (verify.skipflags = EXTSTORED)
        assertTrue(HgTestUtils.hg(dst, concat(lfsConfig("verify.skipflags=8192"), "verify")).contains("checked 1 changesets"));
        assertTrue(HgTestUtils.hg(dst, concat(lfsConfig(), "debugdata", "big.bin", "0")).contains("git-lfs.github.com/spec"),
                "the file arrived as an LFS pointer, flag and all");
    }

    private static TreeSet<String> files(File root) throws IOException {
        try (Stream<Path> walk = Files.walk(root.toPath())) {
            TreeSet<String> names = new TreeSet<>();
            walk.filter(Files::isRegularFile).forEach(p -> names.add(root.toPath().relativize(p).toString().replace(File.separatorChar, '/')));
            return names;
        }
    }
}
