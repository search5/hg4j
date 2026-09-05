package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixSubrepoDockerRoundTripTest},
 * mirroring {@link RequirementMatrixCommitHelperMain}'s reason for existing -- see its javadoc
 * for the full root-cause writeup on why hg4j's own revlog-writing commands (here, two {@link
 * CommitCommand} calls) must run in a dedicated subprocess rather than inline in a JVM that also
 * repeatedly spawns {@code docker exec}/{@code docker run} child processes. {@link
 * SubrepoCommand} itself never writes a revlog (only plain working-copy files, and the nested
 * subrepo's own dirstate/working copy via a recursive {@link UpdateCommand}/clone), so it is not
 * independently at risk of the corruption {@link CommitCommand} is, but the whole add/init/commit/
 * bump/update/commit sequence runs in ONE subprocess invocation purely to keep the Docker matrix's
 * per-combo wall-clock cost down, matching {@link RequirementMatrixStripHelperMain}.
 *
 * <p>Args: {@code parentDir subSourceDir subV1Hex subV2Hex}. Prints two lines: the first and
 * second parent commit hexes.
 */
public final class RequirementMatrixSubrepoHelperMain {
    private RequirementMatrixSubrepoHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File parentDir = new File(args[0]);
        File subSourceDir = new File(args[1]);
        String subV1 = args[2];
        String subV2 = args[3];

        HgRepository parentRepo = new HgRepository(parentDir);

        new SubrepoCommand(parentRepo)
                .setAction("add")
                .setSubrepoPath("sub")
                .setSubrepoUrl(subSourceDir.getAbsolutePath())
                .setRevision(subV1)
                .call();

        new SubrepoCommand(parentRepo).setAction("init").call();

        new AddCommand(parentRepo).call();
        byte[] parentC1 = new CommitCommand(parentRepo).setAuthor("dev").setMessage("add subrepo").call();

        Files.writeString(new File(parentDir, ".hgsubstate").toPath(), subV2 + " sub\n", StandardCharsets.UTF_8);
        new SubrepoCommand(parentRepo).setAction("update").call();

        byte[] parentC2 = new CommitCommand(parentRepo).setAuthor("dev").setMessage("bump sub to v2").call();

        System.out.println(NodeIdUtil.toHex(parentC1));
        System.out.println(NodeIdUtil.toHex(parentC2));
    }
}
