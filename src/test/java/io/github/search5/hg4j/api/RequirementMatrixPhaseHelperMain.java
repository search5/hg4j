package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;

import java.io.File;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixPhaseDockerRoundTripTest},
 * mirroring {@link RequirementMatrixTagHelperMain}'s reason for existing -- see {@link
 * RequirementMatrixCommitHelperMain}'s javadoc for the full docker-exec-interleaving root-cause
 * writeup this isolates {@link PhaseCommand}'s write path from.
 *
 * <p>A non-zero exit (uncaught exception, e.g. {@link java.io.IOException} for a move rejected
 * for lacking {@code --force}) mirrors real hg CLI's own non-zero exit for the same rejection,
 * so the caller can detect it the same way it detects a rejected native {@code hg phase} call.
 *
 * <p>Usage: {@code <repoDir> <revision> <targetPhase 0|1|2> <force:true|false>}.
 */
public final class RequirementMatrixPhaseHelperMain {
    private RequirementMatrixPhaseHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        String revision = args[1];
        int targetPhase = Integer.parseInt(args[2]);
        boolean force = Boolean.parseBoolean(args[3]);

        HgRepository repo = new HgRepository(repoDir);
        new PhaseCommand(repo).setRevision(revision).setPhase(targetPhase).setForce(force).call();
    }
}
