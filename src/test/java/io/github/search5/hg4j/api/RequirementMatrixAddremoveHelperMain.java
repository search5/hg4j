package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;

import java.io.File;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixAddremoveDockerRoundTripTest},
 * mirroring {@link RequirementMatrixAddHelperMain} -- see {@link
 * RequirementMatrixCommitHelperMain}'s javadoc for the full root-cause writeup on why hg4j's own
 * write commands must run in a dedicated subprocess rather than inline in a JVM that also
 * repeatedly spawns {@code docker exec}/{@code docker run} child processes.
 *
 * <p>Args: {@code repoDir}. Runs the whole-working-copy scan/reconcile in one call, matching
 * {@link AddremoveCommand}'s own no-argument porcelain contract.
 */
public final class RequirementMatrixAddremoveHelperMain {
    private RequirementMatrixAddremoveHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        HgRepository repo = new HgRepository(repoDir);
        new AddremoveCommand(repo).call();
    }
}
