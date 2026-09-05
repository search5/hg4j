package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;

import java.io.File;

/**
 * Standalone JVM entry point used by {@link RequirementMatrixUpdateDockerRoundTripTest}.
 *
 * <p>Unlike most other requirement-matrix Docker suites in this package, {@link UpdateCommand}
 * never appends a revlog revision (see its class javadoc) -- only reads the store and writes the
 * working directory/dirstate -- so running it via a dedicated subprocess isn't needed to route
 * around the docker-exec-interleaving revlog corruption {@link RequirementMatrixCommitHelperMain}
 * documents. This helper exists purely for pattern parity with the rest of the campaign.
 *
 * <p>Args: {@code repoDir revision}. Checks out {@code revision}; prints nothing on success.
 */
public final class RequirementMatrixUpdateHelperMain {
    private RequirementMatrixUpdateHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        String revision = args[1];
        HgRepository repo = new HgRepository(repoDir);
        new UpdateCommand(repo).setRevision(revision).call();
    }
}
