package io.github.search5.hg4j.lfs;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.PullCommand;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A repository with an LFS file, made by real hg, can be pulled by hg4j into an empty repository: the node covers the real content, not the pointer. */
@Tag("interop")
class LfsPullLocalRealHgInteropTest {

    @TempDir
    Path tempDir;

    @Test
    void pullingAnLfsRepositoryFromALocalPathKeepsTheNodes() throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "hg is not installed");
        File src = tempDir.resolve("src").toFile();
        HgTestUtils.hg(tempDir.toFile(), "init", src.getAbsolutePath());
        Files.writeString(new File(src, ".hg/hgrc").toPath(), "[extensions]\nlfs =\n[experimental]\nlfs.disableusercache = true\n");
        Files.writeString(new File(src, "small.txt").toPath(), "small\n");
        Files.writeString(new File(src, "big.bin").toPath(), "0123456789".repeat(70));
        HgTestUtils.hg(src, "--config", "lfs.track=size('>100')", "add");
        HgTestUtils.hg(src, "--config", "lfs.track=size('>100')", "commit", "-m", "lfs", "-u", "t");
        Files.writeString(new File(src, "big.bin").toPath(), "9876543210".repeat(70));
        HgTestUtils.hg(src, "--config", "lfs.track=size('>100')", "commit", "-m", "lfs2", "-u", "t");

        File dest = tempDir.resolve("dest").toFile();
        HgRepository repo = Hg.init().setDirectory(dest).call();
        assertEquals(2, new PullCommand(repo).setSource(src.getAbsolutePath()).call().size());
        assertEquals(HgTestUtils.hg(src, "log", "-T", "{node}\n"), HgTestUtils.hg(dest, "log", "-T", "{node}\n"));
        // like real hg, the repository that received an LFS revision requires lfs from now on (the extension then loads by itself)
        assertTrue(Files.readString(new File(dest, ".hg/requires").toPath()).contains("lfs"));
        // the pointers are stored with the lfs flag, so the manifests agree too (the blobs themselves are not part of a pull here)
        assertEquals(HgTestUtils.hg(src, "manifest", "--debug", "-r", "tip"), HgTestUtils.hg(dest, "manifest", "--debug", "-r", "tip"));
        assertEquals(HgTestUtils.hg(src, "debugindex", "big.bin"), HgTestUtils.hg(dest, "debugindex", "big.bin"));
    }

    @Test
    void aBundleOfAnLfsRepositoryUnbundlesIntoAnEmptyRepository() throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "hg is not installed");
        File src = tempDir.resolve("src2").toFile();
        HgTestUtils.hg(tempDir.toFile(), "init", src.getAbsolutePath());
        Files.writeString(new File(src, ".hg/hgrc").toPath(), "[extensions]\nlfs =\n[experimental]\nlfs.disableusercache = true\n");
        Files.writeString(new File(src, "big.bin").toPath(), "0123456789".repeat(70));
        HgTestUtils.hg(src, "--config", "lfs.track=size('>100')", "add");
        HgTestUtils.hg(src, "--config", "lfs.track=size('>100')", "commit", "-m", "lfs", "-u", "t");

        // like real hg, bundling uploads the blobs first, to lfs.url (here a local directory)
        File dest = tempDir.resolve("dest2").toFile();
        HgRepository repo = Hg.init().setDirectory(dest).call();
        Files.createDirectories(tempDir.resolve("blobs"));
        Files.writeString(new File(src, ".hg/hgrc").toPath(), "[lfs]\nurl = file://" + tempDir.resolve("blobs").toAbsolutePath() + "\n", java.nio.file.StandardOpenOption.APPEND);
        File bundle = tempDir.resolve("all.hg").toFile();
        new io.github.search5.hg4j.api.BundleCommand(new HgRepository(src)).setAll(true).setType(io.github.search5.hg4j.api.BundleCommand.BundleType.GZIP_V3).setOutputFile(bundle).call();
        assertEquals(1, new io.github.search5.hg4j.api.UnbundleCommand(repo).setBundleFile(bundle).call().size());
        assertEquals(HgTestUtils.hg(src, "log", "-T", "{node}\n"), HgTestUtils.hg(dest, "log", "-T", "{node}\n"));
        assertEquals(HgTestUtils.hg(src, "debugindex", "big.bin"), HgTestUtils.hg(dest, "debugindex", "big.bin"));
    }
}
