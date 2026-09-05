package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;

import java.io.File;

/**
 * Standalone JVM entry point used by {@link RequirementMatrixWorktreeDockerRoundTripTest}.
 *
 * <p>Unlike most other requirement-matrix Docker suites in this package, {@link WorktreeCommand}
 * never appends a revlog revision on its own (see its class javadoc) -- only reads the shared
 * store and writes the new worktree's own files/dirstate -- so running it via a dedicated
 * subprocess isn't needed to route around the docker-exec-interleaving revlog corruption {@link
 * RequirementMatrixCommitHelperMain} documents. This helper exists purely for pattern parity with
 * the rest of the campaign.
 *
 * <p>Args: {@code mainRepoDir newWorktreeDir}. Prints nothing on success.
 */
public final class RequirementMatrixWorktreeHelperMain {
    private RequirementMatrixWorktreeHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File mainRepoDir = new File(args[0]);
        File newWorktreeDir = new File(args[1]);
        HgRepository mainRepo = new HgRepository(mainRepoDir);
        new WorktreeCommand(mainRepo).setNewWorktreeDir(newWorktreeDir).call();
    }
}
