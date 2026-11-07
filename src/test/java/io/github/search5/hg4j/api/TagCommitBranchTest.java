package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The tag commit of a temporary working copy (clone, update, {@link TagCommand} without commit, {@link CommitCommand}) stays on the
 * branch of the changeset the working copy was updated to, as {@code hg tag} does (checked against real hg 7.2: the tag commit made
 * from a head of branch {@code feature} is on {@code feature}, and from the head of {@code default} it is on {@code default}).
 */
class TagCommitBranchTest {

    @TempDir
    Path tempDir;

    private String branchOfTip(HgRepository repo) throws Exception {
        var log = new LogCommand(repo).call();
        return log.stream().max(java.util.Comparator.comparingInt(HgCommit::getRevision)).orElseThrow().getBranch();
    }

    private void tagFrom(HgRepository work, byte[] target, String revisionToUpdateTo) throws Exception {
        new UpdateCommand(work).setRevision(revisionToUpdateTo).call();
        new TagCommand(work).setTagName("v1").setNodeId(target).setCommit(false).call();
        new CommitCommand(work).setAuthor("t").setMessage("Added tag v1").call();
    }

    @Test
    void theTagCommitStaysOnTheBranchOfTheUpdatedChangeset() throws Exception {
        File srcDir = tempDir.resolve("src").toFile();
        HgRepository src = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "a\n");
        new AddCommand(src).call();
        byte[] c0 = new CommitCommand(src).setMessage("c0").setAuthor("t").call();
        new BranchCommand(src).setBranchName("feature").call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "b\n");
        byte[] c1 = new CommitCommand(src).setMessage("c1").setAuthor("t").call();

        File featureDir = tempDir.resolve("w1").toFile();
        HgRepository w1 = new CloneCommand().setSource(srcDir.getAbsolutePath()).setDirectory(featureDir).call();
        tagFrom(w1, c0, io.github.search5.hg4j.util.NodeIdUtil.toHex(c1));
        assertEquals("feature", branchOfTip(w1));

        File defaultDir = tempDir.resolve("w2").toFile();
        HgRepository w2 = new CloneCommand().setSource(srcDir.getAbsolutePath()).setDirectory(defaultDir).call();
        tagFrom(w2, c0, io.github.search5.hg4j.util.NodeIdUtil.toHex(c0));
        assertEquals("default", branchOfTip(w2));
    }
}
