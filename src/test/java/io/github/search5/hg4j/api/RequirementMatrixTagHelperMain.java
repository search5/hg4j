package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixTagDockerRoundTripTest},
 * mirroring {@link RequirementMatrixAmendHelperMain}'s reason for existing -- {@link TagCommand}
 * delegates its (non-local, non-remove) commit to {@link CommitCommand}, which touches revlogs, so
 * it needs the same subprocess isolation from this test's repeated {@code docker exec}/
 * {@code docker run} child-process spawning (see {@link RequirementMatrixCommitHelperMain}'s
 * javadoc for the full root-cause writeup).
 *
 * <p>Usage: {@code <repoDir> <tagName> <nodeHexOrEmpty> <local:true|false> <remove:true|false>
 * <force:true|false>}.
 */
public final class RequirementMatrixTagHelperMain {
    private RequirementMatrixTagHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        String tagName = args[1];
        String nodeHex = args[2];
        boolean local = Boolean.parseBoolean(args[3]);
        boolean remove = Boolean.parseBoolean(args[4]);
        boolean force = Boolean.parseBoolean(args[5]);

        HgRepository repo = new HgRepository(repoDir);
        TagCommand cmd = new TagCommand(repo).setTagName(tagName).setLocal(local).setRemove(remove).setForce(force);
        if (nodeHex != null && !nodeHex.isEmpty()) {
            cmd.setNodeId(NodeIdUtil.fromHex(nodeHex));
        }
        cmd.call();
    }
}
