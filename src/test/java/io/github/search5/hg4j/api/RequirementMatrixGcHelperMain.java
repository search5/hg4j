package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;

import java.io.File;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixGcDockerRoundTripTest},
 * mirroring {@link RequirementMatrixBackoutHelperMain}'s reason for existing -- see its javadoc
 * (which itself defers to {@link RequirementMatrixCommitHelperMain}) for the full root-cause
 * writeup on why hg4j's own revlog-writing commands must run in a dedicated subprocess rather than
 * inline in a JVM that also repeatedly spawns {@code docker exec}/{@code docker run} child
 * processes.
 *
 * <p>Args: {@code repoDir}. Runs {@link GcCommand} against the already-committed repository and
 * prints its report line.
 */
public final class RequirementMatrixGcHelperMain {
    private RequirementMatrixGcHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        HgRepository repo = new HgRepository(repoDir);
        String report = new GcCommand(repo).call();
        System.out.println(report);
    }
}
