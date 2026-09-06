package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgRepository;
import java.io.File;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixCensorDockerRoundTripTest},
 * mirroring {@link RequirementMatrixBackoutHelperMain}'s reason for existing -- see its javadoc
 * (which itself defers to {@link RequirementMatrixCommitHelperMain}) for the full root-cause
 * writeup on why hg4j's own revlog-writing commands must run in a dedicated subprocess rather than
 * inline in a JVM that also repeatedly spawns {@code docker exec}/{@code docker run} child
 * processes -- {@link CensorCommand} edits a filelog revlog in place, so it is squarely in scope.
 *
 * <p>Args: {@code repoDir mode path nodeHex}, where {@code mode} is {@code censor} (expects the
 * censor to succeed; prints nothing on success) or {@code refuse} (expects {@link
 * HgValidationException} from the check-heads/working-directory guard; prints the exception
 * message, and throws {@link IllegalStateException} if the call unexpectedly succeeds instead).
 */
public final class RequirementMatrixCensorHelperMain {
    private RequirementMatrixCensorHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        String repoDir = args[0];
        String mode = args[1];
        String path = args[2];
        String nodeHex = args[3];
        HgRepository repo = new HgRepository(new File(repoDir));

        if ("refuse".equals(mode)) {
            try {
                new CensorCommand(repo).setFile(path).setRevision(nodeHex).call();
                throw new IllegalStateException("Expected CensorCommand to refuse, but it succeeded");
            } catch (HgValidationException e) {
                System.out.println(e.getMessage());
            }
        } else {
            new CensorCommand(repo).setFile(path).setRevision(nodeHex).call();
        }
    }
}
