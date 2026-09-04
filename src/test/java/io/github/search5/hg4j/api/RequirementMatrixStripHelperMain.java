package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixStripDockerRoundTripTest},
 * mirroring {@link RequirementMatrixCommitHelperMain}'s reason for existing: running hg4j's own
 * write commands (here, {@link CommitCommand} x4 followed by {@link StripCommand}) inline in the
 * SAME JVM process that is also repeatedly spawning {@code docker exec}/{@code docker run} child
 * processes non-deterministically corrupts the writes -- see {@link RequirementMatrixCommitHelperMain}'s
 * javadoc for the full root-cause writeup. All four commits plus the strip happen in ONE
 * subprocess invocation (rather than one subprocess per hg4j operation) purely to keep the
 * Docker matrix's per-combo wall-clock cost down; the corruption is about interleaving with
 * docker-exec spawns in the TEST's own JVM, not about how many hg4j operations share one
 * subprocess.
 */
public final class RequirementMatrixStripHelperMain {
    private RequirementMatrixStripHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        HgRepository repo = new HgRepository(repoDir);

        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "x".repeat(50));
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("hg4j").setMessage("rev0-small").call();

        Files.writeString(f.toPath(), "y".repeat(50_000));
        new CommitCommand(repo).setAuthor("hg4j").setMessage("rev1-large").call();

        Files.writeString(f.toPath(), "z".repeat(60));
        byte[] rev2Node = new CommitCommand(repo).setAuthor("hg4j").setMessage("rev2-small-to-keep").call();

        Files.writeString(f.toPath(), "w".repeat(70));
        new CommitCommand(repo).setAuthor("hg4j").setMessage("rev3-to-strip").call();

        new StripCommand(repo).setRevision("3").call();

        System.out.println(NodeIdUtil.toHex(rev2Node));
    }
}
