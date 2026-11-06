package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A repository filled by hg4j's pull or {@code clone -U} looks like the one real hg makes: no dirstate, null parent, {@code hg verify} passes. */
@Tag("interop")
class PullLeavesDirstateRealHgInteropTest {

    @TempDir
    Path tempDir;

    private File source() throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "hg is not installed");
        File dir = tempDir.resolve("source").toFile();
        HgRepository source = Hg.init().setDirectory(dir).call();
        Files.writeString(new File(dir, "a.txt").toPath(), "a\n");
        new AddCommand(source).call();
        new CommitCommand(source).setMessage("a").setAuthor("t").call();
        Files.writeString(new File(dir, "b.txt").toPath(), "b\n");
        new AddCommand(source).call();
        new CommitCommand(source).setMessage("b").setAuthor("t").call();
        return dir;
    }

    @Test
    void realHgVerifiesARepositoryFilledByPullAndByCloneWithoutUpdate() throws Exception {
        File src = source();
        File pulled = tempDir.resolve("pulled").toFile();
        new PullCommand(Hg.init().setDirectory(pulled).call()).setSource(src.getAbsolutePath()).call();
        File cloned = tempDir.resolve("cloned").toFile();
        new CloneCommand().setSource(src.getAbsolutePath()).setDirectory(cloned).setUpdate(false).call();
        for (File repo : new File[] {pulled, cloned}) {
            assertFalse(new File(repo, ".hg/dirstate").exists(), repo.getName());
            assertEquals("-1", HgTestUtils.hg(repo, "log", "-r", ".", "-T", "{rev}").trim(), repo.getName());
            String verify = HgTestUtils.hg(repo, "verify");
            assertTrue(verify.contains("checked 2 changesets"), verify);
            assertFalse(new File(repo, "a.txt").exists());
        }
    }
}
