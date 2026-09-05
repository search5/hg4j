package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;

import java.io.File;

/**
 * Standalone JVM entry point used by {@link RequirementMatrixArchiveDockerRoundTripTest}.
 *
 * <p>Unlike most other requirement-matrix Docker suites in this package, {@link ArchiveCommand}
 * never mutates the repository (see its class javadoc), so running it via a dedicated subprocess
 * here isn't needed to route around the docker-exec-interleaving revlog corruption {@link
 * RequirementMatrixCommitHelperMain} documents -- this helper exists purely for pattern parity
 * with the rest of the campaign (one {@code java} subprocess call per combo instead of three
 * separate in-JVM {@link ArchiveCommand} invocations).
 *
 * <p>Args: {@code repoDir revision filesDestDir zipDestFile tarGzDestFile}. Archives {@code
 * revision} to all three destinations in one call; prints nothing on success.
 */
public final class RequirementMatrixArchiveHelperMain {
    private RequirementMatrixArchiveHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        String revision = args[1];
        File filesDest = new File(args[2]);
        File zipDest = new File(args[3]);
        File tarGzDest = new File(args[4]);

        HgRepository repo = new HgRepository(repoDir);
        new ArchiveCommand(repo).setRevision(revision).setDestination(filesDest).call();
        new ArchiveCommand(repo).setRevision(revision).setDestination(zipDest).call();
        new ArchiveCommand(repo).setRevision(revision).setDestination(tarGzDest).call();
    }
}
