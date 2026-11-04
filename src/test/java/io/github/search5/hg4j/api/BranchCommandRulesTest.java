package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code hg branch}: the names, messages and effects below were observed with real hg 7.2 (commands.branch, scmutil.checknewlabel).
 */
class BranchCommandRulesTest {

    @TempDir
    Path tempDir;

    /** default: base; branch "other": ob. The working directory is back on default. */
    private HgRepository repositoryWithAnotherBranch() throws Exception {
        File dir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(dir).call();
        Files.writeString(new File(dir, "a").toPath(), "a\n");
        new AddCommand(repo).call();
        byte[] base = new CommitCommand(repo).setMessage("base").setAuthor("t").call();
        new BranchCommand(repo).setBranchName("other").call();
        Files.writeString(new File(dir, "b").toPath(), "b\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("ob").setAuthor("t").call();
        new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(base)).call();
        return repo;
    }

    private static String refused(HgRepository repo, String name) {
        return assertThrows(HgValidationException.class, () -> new BranchCommand(repo).setBranchName(name).call()).getMessage();
    }

    @Test
    void namesRealHgRefuses() throws Exception {
        HgRepository repo = repositoryWithAnotherBranch();
        assertEquals("the name 'tip' is reserved", refused(repo, "tip"));
        assertEquals("the name '.' is reserved", refused(repo, "."));
        assertEquals("the name 'null' is reserved", refused(repo, "null"));
        assertEquals("':' cannot be used in a name", refused(repo, "a:b"));
        assertEquals("'\\n' cannot be used in a name", refused(repo, "a\nb"));
        assertEquals("cannot use an integer as a name", refused(repo, "12"));
        assertEquals("cannot use an integer as a name", refused(repo, "+5"));
        assertEquals("cannot use an integer as a name", refused(repo, " 7 "));
        assertEquals("default", repo.getBranch(), "a refused name changes nothing");
    }

    @Test
    void namesRealHgAccepts() throws Exception {
        HgRepository repo = repositoryWithAnotherBranch();
        assertEquals("x", new BranchCommand(repo).setBranchName(" x").call(), "the label is stripped first");
        assertEquals("x", repo.getBranch());
        assertEquals("1_0", new BranchCommand(repo).setBranchName("1_0").call(), "an underscore keeps it from being an integer");
        assertEquals("default", new BranchCommand(repo).setBranchName("default").call(), "the branch the working directory is on");
    }

    @Test
    void aBranchThatAlreadyExistsNeedsForceOrHavingBeenUpdatedTo() throws Exception {
        HgRepository repo = repositoryWithAnotherBranch();
        assertEquals("a branch of the same name already exists", refused(repo, "other"));
        assertEquals("other", new BranchCommand(repo).setBranchName("other").setForce(true).call());
        assertEquals("other", repo.getBranch());
    }

    @Test
    void cleanGoesBackToTheBranchOfTheParent() throws Exception {
        HgRepository repo = repositoryWithAnotherBranch();
        new BranchCommand(repo).setBranchName("other").setForce(true).call();
        assertEquals("default", new BranchCommand(repo).setClean(true).call());
        assertEquals("default", repo.getBranch());
        // showing the branch is the default without a name
        assertEquals("default", new BranchCommand(repo).call());
    }

    @Test
    void settingTheBranchWaitsForTheWorkingCopyLock() throws Exception {
        HgRepository repo = repositoryWithAnotherBranch();
        Files.writeString(new File(tempDir.toFile(), ".hg/hgrc").toPath(), "[ui]\ntimeout = 1\n");
        try (HgRepository holder = new HgRepository(tempDir.toFile())) {
            Thread other = new Thread(() -> {
                try (io.github.search5.hg4j.lib.HgLock l = holder.lockWorkingCopy()) {
                    Thread.sleep(3000);
                } catch (Exception ignored) {
                }
            });
            other.start();
            Thread.sleep(300);
            assertThrows(io.github.search5.hg4j.errors.HgLockException.class,
                    () -> new BranchCommand(repo).setBranchName("zzz").call());
            assertEquals("default", new BranchCommand(repo).call(), "showing the branch needs no lock");
            other.join();
        }
    }
}
