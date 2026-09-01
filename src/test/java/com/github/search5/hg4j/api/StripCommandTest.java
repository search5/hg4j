package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class StripCommandTest {

    @Test
    public void callThrowsWhenRevisionNotSet(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        assertThrows(IllegalArgumentException.class, () -> new StripCommand(repo).call());
    }

    @Test
    public void stripRemovesTheTargetRevisionAndItsDescendants(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        Files.writeString(new File(repoDir, "a.txt").toPath(), "v1");
        new CommitCommand(repo).setMessage("rev1").call();

        new StripCommand(repo).setRevision("1").call();

        assertEquals(1, new LogCommand(repo).call().size());
    }

    @Test
    public void stripRestoresTheWorkingBranchToMatchTheNewTip(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0 on default").call();

        new BranchCommand(repo).setBranchName("feature").call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v1");
        new CommitCommand(repo).setMessage("rev1 on feature").call();
        assertEquals("feature", repo.getBranch());

        new StripCommand(repo).setRevision("1").call();

        assertEquals("default", repo.getBranch(),
                "Stripping the feature-branch tip must restore the working branch to whatever the new tip (rev0) was committed on");
        assertEquals(NodeIdUtil.toHex(new LogCommand(repo).call().get(0).getNodeId().getBytes()),
                NodeIdUtil.toHex(repo.getDirstate().getParent1()));
    }
}
