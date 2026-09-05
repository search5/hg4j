package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;

import java.io.File;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixRenameDockerRoundTripTest},
 * mirroring {@link RequirementMatrixCopyHelperMain} -- see {@link
 * RequirementMatrixCommitHelperMain}'s javadoc for the full root-cause writeup on why hg4j's own
 * write commands must run in a dedicated subprocess rather than inline in a JVM that also
 * repeatedly spawns {@code docker exec}/{@code docker run} child processes.
 *
 * <p>Args: {@code repoDir sourcePath targetPath}.
 */
public final class RequirementMatrixRenameHelperMain {
    private RequirementMatrixRenameHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        String source = args[1];
        String target = args[2];
        HgRepository repo = new HgRepository(repoDir);
        new RenameCommand(repo).setSource(source).setTarget(target).call();
    }
}
