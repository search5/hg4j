package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixBundleDockerRoundTripTest},
 * mirroring {@link RequirementMatrixCommitHelperMain}'s reason for existing -- see its javadoc for
 * the full root-cause writeup on why hg4j's own revlog-writing commands (here: two add+commit
 * cycles plus two {@link BundleCommand} bundle-file writes) must run in a dedicated subprocess
 * rather than inline in a JVM that also repeatedly spawns {@code docker exec}/{@code docker run}
 * child processes.
 *
 * <p>Args: {@code sourceRepoDir bundleFile1 bundleFile2 bundleTypeCliName}. Writes a first,
 * full-repository bundle ({@code --base null} equivalent) to {@code bundleFile1} after the first
 * commit, then a second, INCREMENTAL bundle ({@code --base <node1>} equivalent) to {@code
 * bundleFile2} after the second commit -- exercising both "bundle everything" and "bundle just the
 * new stuff" the same way {@link RequirementMatrixPushHelperMain} exercises an initial push and an
 * incremental push. Prints {@code node1Hex node2Hex} (space-separated).
 */
public final class RequirementMatrixBundleHelperMain {
    private RequirementMatrixBundleHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File sourceRepoDir = new File(args[0]);
        File bundleFile1 = new File(args[1]);
        File bundleFile2 = new File(args[2]);
        String bundleTypeCliName = args[3];
        HgRepository source = new HgRepository(sourceRepoDir);

        Files.writeString(Path.of(sourceRepoDir.getPath(), "a.txt"), "one");
        new AddCommand(source).call();
        byte[] node1 = new CommitCommand(source).setAuthor("hg4j").setMessage("c0").call();
        String node1Hex = NodeIdUtil.toHex(node1);

        new BundleCommand(source).setOutputFile(bundleFile1).setBaseRevision("null")
                .setType(bundleTypeCliName).call();

        Files.createDirectories(Path.of(sourceRepoDir.getPath(), "dir"));
        Files.writeString(Path.of(sourceRepoDir.getPath(), "dir", "b.txt"), "two");
        new AddCommand(source).call();
        byte[] node2 = new CommitCommand(source).setAuthor("hg4j").setMessage("c1").call();

        new BundleCommand(source).setOutputFile(bundleFile2).setBaseRevision(node1Hex)
                .setType(bundleTypeCliName).call();

        System.out.println(node1Hex + " " + NodeIdUtil.toHex(node2));
    }
}
