package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;

import java.io.File;

/**
 * Standalone JVM entry point used by {@link RequirementMatrixPurgeDockerRoundTripTest}.
 *
 * <p>Unlike most other requirement-matrix Docker suites in this package, {@link PurgeCommand}
 * never touches store/changelog/manifest data at all (see its class javadoc) -- only the working
 * directory -- so running it via a dedicated subprocess isn't needed to route around the
 * docker-exec-interleaving revlog corruption {@link RequirementMatrixCommitHelperMain} documents.
 * This helper exists purely for pattern parity with the rest of the campaign.
 *
 * <p>Args: {@code repoDir}. Runs {@link PurgeCommand} with default settings; prints nothing on
 * success.
 */
public final class RequirementMatrixPurgeHelperMain {
    private RequirementMatrixPurgeHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        HgRepository repo = new HgRepository(repoDir);
        new PurgeCommand(repo).call();
    }
}
