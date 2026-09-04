package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;

import java.io.File;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixShelveDockerRoundTripTest},
 * mirroring {@link RequirementMatrixCommitHelperMain}'s reason for existing -- see its javadoc for
 * the full root-cause writeup on why hg4j's own revlog-writing commands must run in a dedicated
 * subprocess rather than inline in a JVM that also repeatedly spawns {@code docker exec}/
 * {@code docker run} child processes. The working-copy edits themselves (modify a.txt, create
 * b.txt) are plain file writes done by the caller BEFORE this subprocess starts -- only the hg4j
 * {@code add}+{@code shelve} commands (which touch revlogs) need isolation.
 */
public final class RequirementMatrixShelveHelperMain {
    private RequirementMatrixShelveHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        HgRepository repo = new HgRepository(repoDir);
        new AddCommand(repo).call();
        new ShelveCommand(repo).setName("default").call();
    }
}
