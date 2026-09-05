package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixInitDockerRoundTripTest},
 * mirroring {@link RequirementMatrixBackoutHelperMain}'s reason for existing (see its javadoc,
 * which defers to {@link RequirementMatrixCommitHelperMain}, for the full root-cause writeup on
 * why hg4j's own revlog-writing commands must run in a dedicated subprocess rather than inline in
 * a JVM that also repeatedly spawns {@code docker exec}/{@code docker run} child processes).
 *
 * <p>Args: {@code repoDir dirstateV2 changelogV2 sidedataCopies treemanifest persistentNodemap
 * fileIndexV1 generalV2} (the last 7 are {@code "true"}/{@code "false"}). Runs hg4j's own {@link
 * InitCommand} with exactly those flags, adds+commits a nested file ({@code sub/a.txt}), then
 * prints the resulting commit's hex.
 */
public final class RequirementMatrixInitHelperMain {
    private RequirementMatrixInitHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        boolean dirstateV2 = Boolean.parseBoolean(args[1]);
        boolean changelogV2 = Boolean.parseBoolean(args[2]);
        boolean sidedataCopies = Boolean.parseBoolean(args[3]);
        boolean treemanifest = Boolean.parseBoolean(args[4]);
        boolean persistentNodemap = Boolean.parseBoolean(args[5]);
        boolean fileIndexV1 = Boolean.parseBoolean(args[6]);
        boolean generalV2 = Boolean.parseBoolean(args[7]);

        HgRepository repo = new InitCommand()
                .setDirectory(repoDir)
                .setDirstateV2(dirstateV2)
                .setChangelogV2(changelogV2)
                .setSidedataCopies(sidedataCopies)
                .setTreemanifest(treemanifest)
                .setPersistentNodemap(persistentNodemap)
                .setFileIndexV1(fileIndexV1)
                .setGeneralV2(generalV2)
                .call();

        Files.createDirectories(new File(repoDir, "sub").toPath());
        Files.writeString(new File(repoDir, "sub/a.txt").toPath(), "original\n", StandardCharsets.UTF_8);
        new AddCommand(repo).addFile("sub/a.txt").call();
        byte[] c0Node = new CommitCommand(repo).setAuthor("hg4j").setMessage("c0").call();
        System.out.println(NodeIdUtil.toHex(c0Node));
    }
}
