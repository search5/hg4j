package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixBranchDockerRoundTripTest},
 * mirroring {@link RequirementMatrixTagHelperMain}'s reason for existing -- {@link BranchCommand}
 * (writes {@code .hg/branch}) and {@link CommitCommand} (writes the revlogs/dirstate) both touch
 * repository state that must not be exercised interleaved with this test's repeated {@code docker
 * exec}/{@code docker run} child-process spawning (see {@code RequirementMatrixCommitHelperMain}'s
 * javadoc for the full root-cause writeup).
 *
 * <p>One invocation performs one atomic step: optionally set the current branch (via {@link
 * BranchCommand}), then add one file and commit it (optionally as a branch-closing commit).
 *
 * <p>Usage: {@code <repoDir> <branchNameOrEmpty> <fileName> <fileContent> <author> <message>
 * <closeBranch:true|false>}.
 */
public final class RequirementMatrixBranchHelperMain {
    private RequirementMatrixBranchHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        String branchName = args[1];
        String fileName = args[2];
        String fileContent = args[3];
        String author = args[4];
        String message = args[5];
        boolean closeBranch = Boolean.parseBoolean(args[6]);

        HgRepository repo = new HgRepository(repoDir);
        if (branchName != null && !branchName.isEmpty()) {
            new BranchCommand(repo).setBranchName(branchName).call();
        }
        Files.writeString(new File(repoDir, fileName).toPath(), fileContent, StandardCharsets.UTF_8);
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor(author).setMessage(message).setCloseBranch(closeBranch).call();
    }
}
