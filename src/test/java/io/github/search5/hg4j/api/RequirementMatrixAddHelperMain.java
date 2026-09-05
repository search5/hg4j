package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;

import java.io.File;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixAddDockerRoundTripTest},
 * mirroring {@link RequirementMatrixCommitHelperMain}'s reason for existing -- see its javadoc for
 * the full root-cause writeup on why hg4j's own write commands must run in a dedicated subprocess
 * rather than inline in a JVM that also repeatedly spawns {@code docker exec}/{@code docker run}
 * child processes. The working-copy edits themselves (writing the new files) are plain file writes
 * done by the caller BEFORE this subprocess starts -- only the hg4j {@link AddCommand} call needs
 * isolation.
 */
public final class RequirementMatrixAddHelperMain {
    private RequirementMatrixAddHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        HgRepository repo = new HgRepository(repoDir);
        AddCommand cmd = new AddCommand(repo);
        for (int i = 1; i < args.length; i++) {
            cmd.addFile(args[i]);
        }
        cmd.call();
    }
}
