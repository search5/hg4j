package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixDockerRoundTripTest} to run a
 * single hg4j {@code add}+{@code commit} in a <b>fresh, isolated process</b> instead of inline in
 * the test's own JVM.
 *
 * <p><b>Why this exists (2026-09-04, empirically root-caused)</b>: running hg4j's
 * {@link CommitCommand} (which goes through {@code Revlog}'s zstd compression path) in the SAME
 * JVM process that is also repeatedly spawning {@code docker exec}/{@code docker run} child
 * processes via {@code ProcessBuilder} causes non-deterministic on-disk corruption of the SECOND
 * and later commits written that way -- confirmed reproducible with fresh containers, fresh host
 * directories, no shared state, no timing sensitivity (retries/delays did not help), and confirmed
 * to disappear completely once the commit itself runs in its own subprocess. The corruption never
 * reproduces when {@link CommitCommand} runs alone (no interleaved {@code ProcessBuilder} process
 * spawning in the same JVM) -- e.g. every {@code *RealHgInteropTest} elsewhere in this suite that
 * shells out to a bare {@code hg} CLI (not through Docker) never exhibits this, because those don't
 * spawn nearly as many child processes back-to-back. The most likely mechanism is an interaction
 * between the zstd-jni native library's internal state and the JVM's process-spawning/reaping
 * machinery, though the exact fork/exec interaction was not pinned down further -- what matters
 * operationally is that it is 100% avoided by giving the commit its own process.
 */
public final class RequirementMatrixCommitHelperMain {
    private RequirementMatrixCommitHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        String author = args[1];
        String message = args[2];
        HgRepository repo = new HgRepository(repoDir);
        new AddCommand(repo).call();
        byte[] node = new CommitCommand(repo).setAuthor(author).setMessage(message).call();
        System.out.println(NodeIdUtil.toHex(node));
    }
}
