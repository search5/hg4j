package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgMergeConflictException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixBackoutDockerRoundTripTest},
 * mirroring {@link RequirementMatrixMergeHelperMain}'s reason for existing -- see its javadoc
 * (which itself defers to {@link RequirementMatrixCommitHelperMain}) for the full root-cause
 * writeup on why hg4j's own revlog-writing commands must run in a dedicated subprocess rather than
 * inline in a JVM that also repeatedly spawns {@code docker exec}/{@code docker run} child
 * processes.
 *
 * <p>Args: {@code repoDir mode targetHex}, where {@code mode} is {@code clean} (backing out the
 * working copy's own parent -- expects success with no conflict) or {@code conflict} (backing out
 * an older ancestor whose own effect was independently re-touched by later history on the same
 * line -- expects {@link HgMergeConflictException}, which this then resolves to a fixed known
 * value via {@link ResolveCommand} and finishes with a plain {@link CommitCommand}, exactly
 * mirroring real hg's own manual resolve-then-commit workflow -- real hg has no
 * {@code backout --continue}). Prints the resulting commit's hex.
 */
public final class RequirementMatrixBackoutHelperMain {
    private RequirementMatrixBackoutHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        String mode = args[1];
        String targetHex = args[2];
        HgRepository repo = new HgRepository(repoDir);

        if ("conflict".equals(mode)) {
            try {
                new BackoutCommand(repo).setRevision(targetHex).setAuthor("hg4j").call();
                throw new IllegalStateException("Expected a conflicting backout but it succeeded");
            } catch (HgMergeConflictException e) {
                if (!e.getConflictPaths().contains("conflict.txt")) {
                    throw new IllegalStateException("Expected conflict.txt to conflict, got: " + e.getConflictPaths());
                }
            }
            Files.writeString(new File(repoDir, "conflict.txt").toPath(), "line1\nRESOLVED\nline3\n", StandardCharsets.UTF_8);
            new ResolveCommand(repo).setFile("conflict.txt").markResolved(true).call();
            byte[] finalNode = new CommitCommand(repo).setAuthor("hg4j").setMessage("manual backout resolution").call();
            System.out.println(NodeIdUtil.toHex(finalNode));
        } else {
            byte[] backoutNode = new BackoutCommand(repo).setRevision(targetHex).setAuthor("hg4j").call();
            System.out.println(NodeIdUtil.toHex(backoutNode));
        }
    }
}
