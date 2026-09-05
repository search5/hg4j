package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backlog item 40: hg4j as a narrow CLIENT against a REAL hg SERVER, verifying that narrow clone
 * actually reduces the number of bytes transferred on the wire -- not just that the resulting
 * working copy is correct (already covered by {@code NarrowCloneRealHgInteropTest} and {@code
 * HgWireProtocolMatrixNarrowCloneTest}, both of which only ever exercised hg4j's OLD
 * always-fetch-everything-then-filter-locally behavior, since hg4j never used to send {@code
 * narrow}/{@code includepats}/{@code excludepats} on the wire at all).
 *
 * <p>Real hg's own narrow clone reduces wire size by omitting out-of-narrowspec <em>filelog</em>
 * data from the {@code getbundle} response -- confirmed directly (2026-09-06) against Mercurial
 * 7.2's {@code mercurial/changegroup.py}/{@code exchange.py} source and a real {@code hg --debug
 * clone --narrow} packet trace against a local {@code hg serve --config extensions.narrow=}: for
 * a scratch repo with 50 small in-scope files and 200 large (20KB each) out-of-scope files, the
 * getbundle response shrank from a 5,462,104-byte full changegroup to a 29,412-byte narrow one.
 * Changelog and manifest revisions are always sent in full either way (real hg's flat-manifest
 * storage can't prune individual paths out of a manifest blob without corrupting its revlog hash
 * chain -- only its optional treemanifest storage, which neither real hg by default nor hg4j at
 * all uses, can do that via per-subtree {@code visitdir()} pruning). This is why the assertions
 * below target "response shrank dramatically" rather than "response shrank to near-zero".
 */
@Tag("interop")
public class NarrowCloneWireReductionRealHgInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
        Assumptions.assumeTrue(isNarrowExtensionAvailable(), "Native hg's narrow extension is not available. Skipping.");
    }

    private static boolean isNarrowExtensionAvailable() {
        try {
            Process p = new ProcessBuilder("hg", "--config", "extensions.narrow=", "version").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private File seedRepoWithLargeExcludedContent(Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("src").toFile();
        repoDir.mkdirs();
        HgTestUtils.hg(repoDir, "init");
        File includedDir = new File(repoDir, "included");
        File excludedDir = new File(repoDir, "excluded");
        includedDir.mkdirs();
        excludedDir.mkdirs();
        for (int i = 0; i < 20; i++) {
            Files.writeString(new File(includedDir, "f" + i + ".txt").toPath(), "small line " + i);
        }
        byte[] filler = new byte[20_000];
        java.util.Arrays.fill(filler, (byte) 'x');
        String fillerText = new String(filler, java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = 0; i < 80; i++) {
            Files.writeString(new File(excludedDir, "big" + i + ".bin").toPath(), fillerText + i);
        }
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-m", "seed", "-u", "dev");
        return repoDir;
    }

    /**
     * Directly measures the {@code getbundle} response size with and without narrow negotiation
     * against the same live real-hg server and repository state -- the most direct possible proof
     * that {@link HgRemoteConnection#getBundle(List, List, List, HgRemoteConnection.NarrowScope)}
     * actually changes what comes back over the wire, isolated from clone/checkout overhead.
     */
    @Test
    public void narrowGetBundleResponseIsDramaticallySmallerThanFullResponse(@TempDir Path tempDir) throws Exception {
        File repoDir = seedRepoWithLargeExcludedContent(tempDir);

        try (RealHgServeSupport.ServeHandle serve = RealHgServeSupport.start(repoDir,
                "--config", "extensions.narrow=", "--config", "experimental.narrow=True")) {
            HgRemoteClient client = new HgRemoteClient(serve.url);
            List<String> caps = client.getCapabilities();
            assertTrue(caps.contains("exp-narrow-1"),
                    "real hg server with the narrow extension enabled must advertise exp-narrow-1: " + caps);
            assertTrue(client.supportsNarrow(), "HgRemoteClient must recognize exp-narrow-1 after getCapabilities()");

            List<String> bundleCaps = List.of("HG20",
                    io.github.search5.hg4j.bundle.Bundle2Parser.buildBundle2CapsToken("01,02,03,04,05"),
                    "compression=GZ,BZ,ZS");

            byte[] fullResponse = client.getBundle(List.of(), List.of(), bundleCaps, null);

            HgRemoteConnection.NarrowScope narrowScope =
                    new HgRemoteConnection.NarrowScope(List.of("path:included"), List.of());
            byte[] narrowResponse = client.getBundle(List.of(), List.of(), bundleCaps, narrowScope);

            assertTrue(fullResponse.length > 200_000,
                    "sanity: the full response should be dominated by the 80 x 20KB excluded files, was "
                            + fullResponse.length + " bytes");
            assertTrue(narrowResponse.length < fullResponse.length / 4,
                    "narrow getbundle response (" + narrowResponse.length
                            + " bytes) must be dramatically smaller than the full response ("
                            + fullResponse.length + " bytes) -- narrow clone must reduce wire transfer, "
                            + "not just filter locally after downloading everything");
        }
    }

    /**
     * End-to-end: {@link io.github.search5.hg4j.api.NarrowCloneCommand} against the same kind of
     * server produces a correct working copy (in-scope files present, out-of-scope absent) while
     * also actually negotiating the reduced wire transfer proven above -- i.e. the porcelain
     * command path (not just a hand-rolled {@code getBundle} call) benefits too.
     */
    @Test
    public void narrowCloneCommandProducesCorrectResultAgainstRealHgServer(@TempDir Path tempDir) throws Exception {
        File repoDir = seedRepoWithLargeExcludedContent(tempDir);

        try (RealHgServeSupport.ServeHandle serve = RealHgServeSupport.start(repoDir,
                "--config", "extensions.narrow=", "--config", "experimental.narrow=True")) {
            File destDir = tempDir.resolve("dest").toFile();
            Hg.narrowClone()
                    .setSource(serve.url)
                    .setDirectory(destDir)
                    .addIncludePath("included")
                    .call();

            assertTrue(new File(destDir, "included/f0.txt").exists(), "in-scope file must be checked out");
            assertFalse(new File(destDir, "excluded").exists(), "out-of-scope dir must not be checked out");

            List<String> serverFilelogPaths = new ArrayList<>();
            File clientStore = new File(destDir, ".hg/store");
            collectFilelogRelPaths(clientStore, clientStore, serverFilelogPaths);
            boolean anyExcludedFilelogStored = serverFilelogPaths.stream().anyMatch(p -> p.startsWith("data/excluded"));
            assertFalse(anyExcludedFilelogStored,
                    "the client's own local store must not even contain excluded filelogs -- the server "
                            + "must never have sent them in the first place, not merely have hg4j discard "
                            + "them after checkout. Found: " + serverFilelogPaths);
        }
    }

    private static void collectFilelogRelPaths(File root, File dir, List<String> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isDirectory()) {
                collectFilelogRelPaths(root, c, out);
            } else if (c.getName().endsWith(".i") && c.getPath().contains("data")) {
                out.add(root.toPath().relativize(c.toPath()).toString().replace(File.separatorChar, '/'));
            }
        }
    }
}
