package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;

import java.io.File;
import java.util.List;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixVerifyDockerRoundTripTest}.
 * {@link VerifyCommand} itself never writes anything (it is a pure read/hash-check), so unlike
 * {@link RequirementMatrixCensorHelperMain} it has no data-corruption motive for running out of
 * process -- this exists purely so the Docker test class follows the same "hg4j runs in a
 * dedicated subprocess, never inline in a JVM that also spawns docker exec/docker run child
 * processes" convention as every write-side matrix test (see {@link
 * RequirementMatrixCommitHelperMain} for the original root-cause writeup), so a future edit that
 * adds a write-based scenario here does not have to remember to introduce one retroactively.
 *
 * <p>Args: {@code repoDir}. Prints each error on its own line to stdout (nothing at all when the
 * repository verifies clean), exactly mirroring {@link VerifyCommand#call()}'s return value.
 */
public final class RequirementMatrixVerifyHelperMain {
    private RequirementMatrixVerifyHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        HgRepository repo = new HgRepository(repoDir);
        List<String> errors = new VerifyCommand(repo).call();
        for (String e : errors) {
            System.out.println(e);
        }
    }
}
