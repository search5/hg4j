package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixAmendDockerRoundTripTest},
 * mirroring {@link RequirementMatrixCommitHelperMain}'s reason for existing -- see its javadoc
 * for the full root-cause writeup on why hg4j's own revlog-writing commands must run in a
 * dedicated subprocess rather than inline in a JVM that also repeatedly spawns {@code docker
 * exec}/{@code docker run} child processes. The working-copy edit itself (rewriting a.txt) is a
 * plain file write done by the caller BEFORE this subprocess starts -- only the hg4j {@link
 * AmendCommand} call (which touches revlogs) needs isolation.
 */
public final class RequirementMatrixAmendHelperMain {
    private RequirementMatrixAmendHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        String author = args[1];
        String message = args[2];
        HgRepository repo = new HgRepository(repoDir);
        byte[] amendedNode = new AmendCommand(repo).setAuthor(author).setMessage(message).call();
        System.out.println(NodeIdUtil.toHex(amendedNode));
    }
}
