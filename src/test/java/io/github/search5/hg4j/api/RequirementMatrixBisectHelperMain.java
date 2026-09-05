package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixBisectDockerRoundTripTest},
 * mirroring {@link RequirementMatrixBackoutHelperMain}'s reason for existing -- see its javadoc
 * (which itself defers to {@link RequirementMatrixCommitHelperMain}) for the full root-cause
 * writeup on why hg4j's own repository-writing commands must run in a dedicated subprocess rather
 * than inline in a JVM that also repeatedly spawns {@code docker exec}/{@code docker run} child
 * processes. {@link BisectCommand#next()} writes the working directory and dirstate directly (a
 * full working-copy checkout), so it follows the same convention every other Docker-backed write
 * command in this suite does.
 *
 * <p>Args: {@code repoDir goodHex badHex}. Prints the resulting candidate node's hex.
 */
public final class RequirementMatrixBisectHelperMain {
    private RequirementMatrixBisectHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        byte[] good = NodeIdUtil.fromHex(args[1]);
        byte[] bad = NodeIdUtil.fromHex(args[2]);
        HgRepository repo = new HgRepository(repoDir);
        byte[] candidate = new BisectCommand(repo).setGood(good).setBad(bad).next();
        System.out.println(NodeIdUtil.toHex(candidate));
    }
}
