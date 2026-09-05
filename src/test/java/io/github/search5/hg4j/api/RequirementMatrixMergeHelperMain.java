package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixMergeDockerRoundTripTest},
 * mirroring {@link RequirementMatrixCommitHelperMain}'s reason for existing -- see its javadoc for
 * the full root-cause writeup on why hg4j's own revlog-writing commands (here, {@link
 * CommitCommand} finalizing a merge) must run in a dedicated subprocess rather than inline in a
 * JVM that also repeatedly spawns {@code docker exec}/{@code docker run} child processes.
 * {@link MergeCommand} itself never writes a revlog (only the working copy, dirstate, and merge
 * state), so it is not independently at risk of the corruption {@code CommitCommand} is, but it
 * runs in the same subprocess as the finalizing commit purely to keep this a single subprocess
 * launch per combo.
 *
 * <p>Args: {@code repoDir mode sourceHex}, where {@code mode} is {@code clean} (expects the merge
 * to be conflict-free) or {@code conflict} (expects a conflict on {@code conflict.txt} -- both
 * branches having edited its middle line -- which this then resolves to a fixed known value and
 * marks resolved via {@link ResolveCommand} before committing). The working copy must already be
 * checked out at the target revision before this runs. Prints the merge commit's hex.
 */
public final class RequirementMatrixMergeHelperMain {
    private RequirementMatrixMergeHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        String mode = args[1];
        byte[] sourceNode = NodeIdUtil.fromHex(args[2]);
        HgRepository repo = new HgRepository(repoDir);

        MergeCommand.MergeResult result = new MergeCommand(repo).setNodeId(sourceNode).call();

        if ("conflict".equals(mode)) {
            if (!result.isConflicted()) {
                throw new IllegalStateException("Expected a conflicted merge but got none");
            }
            Files.writeString(new File(repoDir, "conflict.txt").toPath(), "line1\nRESOLVED\nline3\n", StandardCharsets.UTF_8);
            new ResolveCommand(repo).setFile("conflict.txt").markResolved(true).call();
        } else if (result.isConflicted()) {
            throw new IllegalStateException("Expected a clean merge but got conflicts: " + result.getConflicts());
        }

        byte[] mergeNode = new CommitCommand(repo).setAuthor("hg4j").setMessage("merge").call();
        System.out.println(NodeIdUtil.toHex(mergeNode));
    }
}
