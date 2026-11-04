package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@code admin::verify}; the expected output is what Mercurial 7.2 printed for the same situations. */
class AdminVerifyCommandTest {

    @TempDir
    Path tempDir;

    private HgRepository repo;
    private byte[] rev0;

    @BeforeEach
    void repository() throws Exception {
        File dir = tempDir.resolve("r").toFile();
        repo = Hg.init().setDirectory(dir).call();
        Files.writeString(new File(dir, "a").toPath(), "a\n");
        new AddCommand(repo).call();
        rev0 = new CommitCommand(repo).setMessage("c0").setAuthor("t").call();
        Files.writeString(new File(dir, "b").toPath(), "b\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("c1").setAuthor("t").call();
    }

    @Test
    void aConsistentDirstateHasNoFindings() throws Exception {
        AdminVerifyCommand verify = new AdminVerifyCommand(repo).addCheck("dirstate");
        assertEquals(Map.of(), verify.call());
        assertEquals(List.of("running 1 checks\n", "running working-copy.dirstate\n", "checking dirstate\n"), verify.getOutput());
        assertEquals(List.of(), verify.getWarnings());
    }

    @Test
    void aParentThatDoesNotMatchTheTrackedFilesIsReported() throws Exception {
        Dirstate dirstate = repo.getDirstate();
        dirstate.setParents(rev0, new byte[20]); // like `hg debugsetparents 0`
        repo.writeDirstate(dirstate);
        String short0 = NodeIdUtil.toHex(rev0).substring(0, 12);
        AdminVerifyCommand verify = new AdminVerifyCommand(repo).addCheck("working-copy");
        assertEquals(Map.of("working-copy.dirstate", 1), verify.call());
        assertEquals(List.of("b marked as tracked in p1 (" + short0 + ") but not in manifest1\n", "found 1 errors\n"), verify.getWarnings());
        assertEquals(List.of("running 1 checks\n", "running working-copy.dirstate\n", "checking dirstate\n"), verify.getOutput());
    }

    @Test
    void aFileOfTheParentManifestThatTheDirstateDoesNotKnowIsReported() throws Exception {
        Dirstate dirstate = repo.getDirstate();
        dirstate.removeEntry("a");
        repo.writeDirstate(dirstate);
        String short1 = NodeIdUtil.toHex(repo.getDirstate().getParent1()).substring(0, 12);
        AdminVerifyCommand verify = new AdminVerifyCommand(repo).addCheck("full");
        verify.call();
        assertEquals(List.of("a in manifest1, but not marked as tracked in p1 (" + short1 + ")\n", "found 1 errors\n"), verify.getWarnings());
    }

    @Test
    void anAddedFileThatTheParentAlreadyHasIsReported() throws Exception {
        Dirstate dirstate = repo.getDirstate();
        dirstate.addEntry("a", new Dirstate.Entry('a', 0644, 2, 0));
        repo.writeDirstate(dirstate);
        AdminVerifyCommand verify = new AdminVerifyCommand(repo).addCheck("dirstate");
        verify.call();
        // an added entry is not tracked in p1, so the second loop of dirstate.verify reports the same file again
        String short1 = NodeIdUtil.toHex(repo.getDirstate().getParent1()).substring(0, 12);
        assertEquals(List.of("a marked as added, but also in manifest1\n", "a in manifest1, but not marked as tracked in p1 (" + short1 + ")\n",
                "found 2 errors\n"), verify.getWarnings());
    }

    @Test
    void anAbandonedTransactionIsWarnedAbout() throws Exception {
        Files.writeString(new File(repo.getStoreDir(), "journal").toPath(), "");
        AdminVerifyCommand verify = new AdminVerifyCommand(repo).addCheck("dirstate");
        verify.call();
        assertEquals("abandoned transaction found - run hg recover\n", verify.getWarnings().get(0));
    }

    @Test
    void namesAliasesPrefixesAndFullSelectTheSameCheck() throws Exception {
        for (String name : List.of("working-copy.dirstate", "dirstate", "working-copy", "full")) {
            AdminVerifyCommand verify = new AdminVerifyCommand(repo).addCheck(name);
            verify.call();
            assertEquals("running 1 checks\n", verify.getOutput().get(0), name);
        }
    }

    @Test
    void refusalsUseHgsMessages() {
        assertEquals("unknown check nope", assertThrows(HgValidationException.class, () -> new AdminVerifyCommand(repo).addCheck("nope").call()).getMessage());
        assertEquals("`checks` required", assertThrows(HgValidationException.class, () -> new AdminVerifyCommand(repo).call()).getMessage());
        assertEquals("invalid option 'bad'\n(hint: syntax is 'check:option=value', eg. revlogs.changelog:copies=yes)",
                assertThrows(HgValidationException.class, () -> new AdminVerifyCommand(repo).addCheck("dirstate").addOption("bad").call()).getMessage());
        assertEquals("check 'working-copy.dirstate' has no option 'x'",
                assertThrows(HgValidationException.class, () -> new AdminVerifyCommand(repo).addCheck("dirstate").addOption("dirstate:x=1").call()).getMessage());
    }
}
