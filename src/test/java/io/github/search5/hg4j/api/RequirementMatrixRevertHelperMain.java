package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;

import java.io.File;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixRevertDockerRoundTripTest},
 * mirroring {@link RequirementMatrixMergeHelperMain}'s reason for existing -- see its javadoc
 * (which itself defers to {@link RequirementMatrixCommitHelperMain}) for the full root-cause
 * writeup on why hg4j's own dirstate/working-copy-writing commands must run in a dedicated
 * subprocess rather than inline in a JVM that also repeatedly spawns {@code docker exec}/{@code
 * docker run} child processes.
 *
 * <p>Args: {@code repoDir mode [revisionHex]}, where {@code mode} is:
 * <ul>
 *   <li>{@code mar}: reverts {@code base.txt} (modified), {@code new.txt} (added, never
 *   committed) and {@code keep.txt} (removed) all back to the working copy's parent.</li>
 *   <li>{@code older}: reverts {@code a.txt} and {@code later.txt} to the explicit revision
 *   {@code revisionHex}.</li>
 * </ul>
 */
public final class RequirementMatrixRevertHelperMain {
    private RequirementMatrixRevertHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        String mode = args[1];
        HgRepository repo = new HgRepository(repoDir);

        if ("mar".equals(mode)) {
            new RevertCommand(repo).setFile("base.txt").call();
            new RevertCommand(repo).setFile("new.txt").call();
            new RevertCommand(repo).setFile("keep.txt").call();
        } else if ("older".equals(mode)) {
            String revisionHex = args[2];
            new RevertCommand(repo).setFile("a.txt").setRevision(revisionHex).call();
            new RevertCommand(repo).setFile("later.txt").setRevision(revisionHex).call();
        } else {
            throw new IllegalArgumentException("Unknown mode: " + mode);
        }
        System.out.println("OK");
    }
}
