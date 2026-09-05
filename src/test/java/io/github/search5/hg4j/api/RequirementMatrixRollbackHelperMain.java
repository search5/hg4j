package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixRollbackDockerRoundTripTest},
 * mirroring {@link RequirementMatrixBackoutHelperMain}'s reason for existing -- see its javadoc
 * (which itself defers to {@link RequirementMatrixCommitHelperMain}) for the full root-cause
 * writeup on why hg4j's own revlog-writing commands must run in a dedicated subprocess rather than
 * inline in a JVM that also repeatedly spawns {@code docker exec}/{@code docker run} child
 * processes.
 *
 * <p>Args: {@code repoDir}. The repository must already contain a single committed revision c0
 * with a root {@code a.txt} and a nested {@code sub/a.txt}. This commits c1 (changing {@code
 * sub/a.txt}) via hg4j, then immediately rolls it back via {@link RollbackCommand} -- fully
 * organic, unlike {@link RequirementMatrixRecoverHelperMain} (no journal fabrication needed:
 * {@link CommitCommand} already writes everything {@link RollbackCommand} needs after every
 * successful commit).
 */
public final class RequirementMatrixRollbackHelperMain {
    private RequirementMatrixRollbackHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        HgRepository repo = new HgRepository(repoDir);

        Files.writeString(repoDir.toPath().resolve("sub/a.txt"), "sub-changed-by-c1\n", StandardCharsets.UTF_8);
        new CommitCommand(repo).setAuthor("hg4j").setMessage("c1").call();

        new RollbackCommand(repo).call();
        System.out.println("rollback-done");
    }
}
