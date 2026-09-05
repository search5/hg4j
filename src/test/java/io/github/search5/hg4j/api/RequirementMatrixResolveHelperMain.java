package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixResolveDockerRoundTripTest},
 * mirroring {@link RequirementMatrixMergeHelperMain}'s reason for existing -- see its javadoc
 * (which itself defers to {@link RequirementMatrixCommitHelperMain}) for the full root-cause
 * writeup on why hg4j's own revlog-writing commands must run in a dedicated subprocess rather than
 * inline in a JVM that also repeatedly spawns {@code docker exec}/{@code docker run} child
 * processes.
 *
 * <p>Unlike the Core test (which interleaves each {@link ResolveCommand} step with a live real
 * {@code hg resolve --list} check, safe there since it never touches Docker), this runs the whole
 * conflict -&gt; list -&gt; mark -&gt; unmark -&gt; mark -&gt; commit lifecycle in one subprocess
 * call and only asserts real hg's view of the *final* state (see the Docker test itself) -- so
 * every intermediate {@link ResolveCommand} transition is still exercised, across every one of the
 * 30 Docker-only combos, just not independently re-verified against a live {@code docker exec}
 * mid-flight.
 *
 * <p>Args: {@code repoDir sourceHex}. The working copy must already be checked out at the target
 * revision before this runs. Prints the final merge commit's hex.
 */
public final class RequirementMatrixResolveHelperMain {
    private RequirementMatrixResolveHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        byte[] sourceNode = NodeIdUtil.fromHex(args[1]);
        HgRepository repo = new HgRepository(repoDir);

        MergeCommand.MergeResult result = new MergeCommand(repo).setNodeId(sourceNode).call();
        if (!result.isConflicted()) {
            throw new IllegalStateException("Expected a conflicted merge but got none");
        }

        Map<String, Boolean> before = new ResolveCommand(repo).list(true).call();
        if (!Map.of("conflict.txt", false).equals(before)) {
            throw new IllegalStateException("Expected conflict.txt unresolved before any mark, got: " + before);
        }

        Files.writeString(new File(repoDir, "conflict.txt").toPath(), "line1\nRESOLVED\nline3\n", StandardCharsets.UTF_8);
        Map<String, Boolean> afterMark = new ResolveCommand(repo).setFile("conflict.txt").markResolved(true).call();
        if (!Map.of("conflict.txt", true).equals(afterMark)) {
            throw new IllegalStateException("Expected conflict.txt resolved after mark, got: " + afterMark);
        }

        Map<String, Boolean> afterUnmark = new ResolveCommand(repo).setFile("conflict.txt").markUnresolved(true).call();
        if (!Map.of("conflict.txt", false).equals(afterUnmark)) {
            throw new IllegalStateException("Expected conflict.txt unresolved after unmark, got: " + afterUnmark);
        }

        Map<String, Boolean> finalMark = new ResolveCommand(repo).setFile("conflict.txt").markResolved(true).call();
        if (!Map.of("conflict.txt", true).equals(finalMark)) {
            throw new IllegalStateException("Expected conflict.txt resolved after final mark, got: " + finalMark);
        }

        byte[] mergeNode = new CommitCommand(repo).setAuthor("hg4j").setMessage("merge with conflict resolution").call();
        System.out.println(NodeIdUtil.toHex(mergeNode));
    }
}
