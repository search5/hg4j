package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;

import java.io.File;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixRemoveDockerRoundTripTest},
 * mirroring {@link RequirementMatrixAddHelperMain} -- see {@link
 * RequirementMatrixCommitHelperMain}'s javadoc for the full root-cause writeup on why hg4j's own
 * write commands must run in a dedicated subprocess rather than inline in a JVM that also
 * repeatedly spawns {@code docker exec}/{@code docker run} child processes.
 *
 * <p>Args: {@code repoDir file...} -- removes every listed path.
 */
public final class RequirementMatrixRemoveHelperMain {
    private RequirementMatrixRemoveHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        HgRepository repo = new HgRepository(repoDir);
        for (int i = 1; i < args.length; i++) {
            new RemoveCommand(repo).setFile(args[i]).call();
        }
    }
}
