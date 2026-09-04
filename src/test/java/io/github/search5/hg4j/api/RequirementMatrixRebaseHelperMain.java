package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixRebaseDockerRoundTripTest},
 * mirroring {@link RequirementMatrixCommitHelperMain}'s reason for existing -- see its javadoc for
 * the full root-cause writeup on why hg4j's own revlog-writing commands must run in a dedicated
 * subprocess rather than inline in a JVM that also repeatedly spawns {@code docker exec}/
 * {@code docker run} child processes.
 *
 * <p>Args: {@code repoDir sourceNodeHex targetNodeHex}. Prints the rebased node's hex.
 */
public final class RequirementMatrixRebaseHelperMain {
    private RequirementMatrixRebaseHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        byte[] sourceNode = NodeIdUtil.fromHex(args[1]);
        byte[] targetNode = NodeIdUtil.fromHex(args[2]);
        HgRepository repo = new HgRepository(repoDir);

        byte[] rebased = new RebaseCommand(repo).setSource(sourceNode).setTarget(targetNode).call();
        System.out.println(NodeIdUtil.toHex(rebased));
    }
}
