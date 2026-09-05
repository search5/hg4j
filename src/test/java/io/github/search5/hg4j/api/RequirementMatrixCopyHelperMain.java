package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;

import java.io.File;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixCopyDockerRoundTripTest},
 * mirroring {@link RequirementMatrixAddHelperMain}'s reason for existing -- see {@link
 * RequirementMatrixCommitHelperMain}'s javadoc for the full root-cause writeup on why hg4j's own
 * write commands must run in a dedicated subprocess rather than inline in a JVM that also
 * repeatedly spawns {@code docker exec}/{@code docker run} child processes.
 *
 * <p>Args: {@code repoDir sourcePath destinationPath}.
 */
public final class RequirementMatrixCopyHelperMain {
    private RequirementMatrixCopyHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        String source = args[1];
        String destination = args[2];
        HgRepository repo = new HgRepository(repoDir);
        new CopyCommand(repo).setSource(source).setDestination(destination).call();
    }
}
