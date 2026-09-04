package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixPushDockerRoundTripTest},
 * mirroring {@link RequirementMatrixCommitHelperMain}'s reason for existing -- see its javadoc for
 * the full root-cause writeup on why hg4j's own revlog-writing commands (here: two add+commit
 * cycles plus two {@link PushCommand} pushes, into a SEPARATE destination repository) must run in
 * a dedicated subprocess rather than inline in a JVM that also repeatedly spawns {@code docker
 * exec}/{@code docker run} child processes.
 *
 * <p>Args: {@code sourceRepoDir destRepoDir}. Prints {@code node1Hex node2Hex} (space-separated).
 */
public final class RequirementMatrixPushHelperMain {
    private RequirementMatrixPushHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File sourceRepoDir = new File(args[0]);
        File destRepoDir = new File(args[1]);
        HgRepository source = new HgRepository(sourceRepoDir);

        Files.writeString(Path.of(sourceRepoDir.getPath(), "a.txt"), "one");
        new AddCommand(source).call();
        byte[] node1 = new CommitCommand(source).setAuthor("hg4j").setMessage("c0").call();

        new PushCommand(source).setDestination(destRepoDir.getAbsolutePath()).call();

        Files.createDirectories(Path.of(sourceRepoDir.getPath(), "dir"));
        Files.writeString(Path.of(sourceRepoDir.getPath(), "dir", "b.txt"), "two");
        new AddCommand(source).call();
        byte[] node2 = new CommitCommand(source).setAuthor("hg4j").setMessage("c1").call();

        new PushCommand(source).setDestination(destRepoDir.getAbsolutePath()).call();

        System.out.println(NodeIdUtil.toHex(node1) + " " + NodeIdUtil.toHex(node2));
    }
}
