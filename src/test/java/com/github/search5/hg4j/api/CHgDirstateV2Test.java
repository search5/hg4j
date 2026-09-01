package com.github.search5.hg4j.api;

import com.github.search5.hg4j.HgTestUtils;
import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("interop")
public class CHgDirstateV2Test {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping CHgDirstateV2Test.");
    }

    private double getHgVersion() {
        try {
            String out = HgTestUtils.hg(new File("."), "--version");
            // Format example: "Mercurial Distributed SCM (version 6.5.1)"
            String prefix = "version ";
            int idx = out.indexOf(prefix);
            if (idx != -1) {
                String verStr = out.substring(idx + prefix.length());
                int spaceIdx = verStr.indexOf(')');
                if (spaceIdx != -1) {
                    verStr = verStr.substring(0, spaceIdx);
                }
                // Extract major.minor
                String[] parts = verStr.split("\\.");
                if (parts.length >= 2) {
                    return Double.parseDouble(parts[0] + "." + parts[1]);
                }
            }
        } catch (Exception ignored) {
        }
        return 0.0;
    }

    @Test
    public void testNativeDirstateV2Parsing(@TempDir Path tempDir) throws Exception {
        double ver = getHgVersion();
        Assumptions.assumeTrue(ver >= 6.0, "Mercurial version must be 6.0 or higher for dirstate-v2 tests. Current version: " + ver);

        File repoDir = tempDir.resolve("repo").toFile();

        // `format.use-dirstate-v2` only takes effect at `hg init` time (it decides whether the
        // `dirstate-v2` requirement gets written) -- setting it in `.hg/hgrc` afterwards, as
        // HgTestUtils.nativeRepo()'s plain `hg init` + setup-callback pattern would do, is too
        // late and silently keeps the repo on dirstate-v1. slow-path=allow is required because
        // this environment's hg has no Rust extension (HAS_FAST_DIRSTATE_V2 == False) -- without
        // it hg aborts with "accessing `dirstate-v2` repository without associated fast
        // implementation". Both gaps combined meant this test could never have actually created
        // a dirstate-v2 repo, and always silently skipped via the requires-file assumeTrue below
        // without ever exercising real interop.
        repoDir.mkdirs();
        HgTestUtils.hg(repoDir, "init",
                "--config", "format.use-dirstate-v2=true",
                "--config", "storage.dirstate-v2.slow-path=allow");
        File hgrc = new File(repoDir, ".hg/hgrc");
        Files.writeString(hgrc.toPath(),
                "[format]\nusezstd = false\nrevlog-compression = zlib\n"
                        + "[storage]\ndirstate-v2.slow-path = allow\n");

        File f1 = new File(repoDir, "f1.txt");
        Files.writeString(f1.toPath(), "Dirstate-v2 testing file 1");
        File f2 = new File(repoDir, "f2.txt");
        Files.writeString(f2.toPath(), "Dirstate-v2 testing file 2");

        HgTestUtils.hg(repoDir, "add", "f1.txt", "f2.txt");
        HgTestUtils.hg(repoDir, "commit", "-m", "Commit with dirstate-v2 format enabled");

        HgRepository repository = new HgRepository(repoDir);

        // 1. Verify that the repository has dirstate-v2 requirement
        File requirements = new File(repoDir, ".hg/requires");
        assertTrue(requirements.exists(), "Requirements file must exist");
        String reqsStr = Files.readString(requirements.toPath());
        Assumptions.assumeTrue(reqsStr.contains("dirstate-v2"), "Repository requires file must contain dirstate-v2 requirement");

        // 2. Parse using hg4j
        Dirstate dirstate = repository.getDirstate();
        assertTrue(dirstate.isV2(), "Dirstate parsed by hg4j must be detected as isV2 == true");

        // 3. Ensure the tracked files exist in our parsed entries
        var entries = dirstate.getEntries();
        assertNotNull(entries.get("f1.txt"), "f1.txt must be tracked in hg4j dirstate-v2");
        assertNotNull(entries.get("f2.txt"), "f2.txt must be tracked in hg4j dirstate-v2");
        assertEquals('n', entries.get("f1.txt").getState(), "f1.txt state must be 'n' (normal)");
        assertEquals('n', entries.get("f2.txt").getState(), "f2.txt state must be 'n' (normal)");
    }
}
