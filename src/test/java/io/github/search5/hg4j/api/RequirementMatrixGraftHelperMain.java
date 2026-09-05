package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgMergeConflictException;
import io.github.search5.hg4j.lib.HgRepository;

import java.io.File;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixGraftDockerRoundTripTest},
 * mirroring {@link RequirementMatrixRebaseHelperMain}'s reason for existing -- see its javadoc
 * (which itself points to {@link RequirementMatrixCommitHelperMain}) for the full root-cause
 * writeup on why hg4j's own revlog-writing commands must run in a dedicated subprocess rather than
 * inline in a JVM that also repeatedly spawns {@code docker exec}/{@code docker run} child
 * processes.
 *
 * <p>Two modes, selected by {@code args[0]}:
 * <ul>
 *   <li>{@code call repoDir sourceRevisionHex}: runs {@link GraftCommand#call()}. Prints
 *       {@code OK <hex>} on a clean graft, or {@code CONFLICT <comma-separated-paths>} if it
 *       paused on a genuine conflict (not treated as a subprocess failure -- the Docker test then
 *       resolves the conflict on the shared-volume file itself and invokes {@code continue}).</li>
 *   <li>{@code continue repoDir}: runs {@link GraftCommand#continueGraft()} after the conflict was
 *       resolved on disk. Prints the resulting commit's hex.</li>
 * </ul>
 */
public final class RequirementMatrixGraftHelperMain {
    private RequirementMatrixGraftHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        File repoDir = new File(args[1]);
        HgRepository repo = new HgRepository(repoDir);

        if ("call".equals(mode)) {
            String sourceHex = args[2];
            try {
                String graftedHex = new GraftCommand(repo).setSource(sourceHex).call();
                System.out.println("OK " + graftedHex);
            } catch (HgMergeConflictException e) {
                System.out.println("CONFLICT " + String.join(",", e.getConflictPaths()));
            }
        } else if ("continue".equals(mode)) {
            String graftedHex = new GraftCommand(repo).continueGraft();
            System.out.println("OK " + graftedHex);
        } else {
            throw new IllegalArgumentException("Unknown mode: " + mode);
        }
    }
}
