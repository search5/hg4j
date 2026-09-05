package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixExportImportDockerRoundTripTest},
 * mirroring {@link RequirementMatrixBundleHelperMain}'s reason for existing (see {@link
 * RequirementMatrixCommitHelperMain}'s javadoc for the full root-cause writeup on why hg4j's own
 * revlog-writing commands must run in a dedicated subprocess rather than inline in a JVM that also
 * repeatedly spawns {@code docker exec}/{@code docker run} child processes).
 *
 * <p>Two modes, matching {@link RequirementMatrixExportImportCoreRoundTripTest}'s two directions:
 * <ul>
 *   <li>{@code export <repoDir> <patchOutFile>} -- commits a two-file change (one root file, one
 *   in a subdirectory) into {@code repoDir} and writes {@link ExportCommand}'s patch text to
 *   {@code patchOutFile}. Prints the new commit's node hex.</li>
 *   <li>{@code import <repoDir> <patchInFile>} -- applies {@code patchInFile}'s contents to {@code
 *   repoDir} via {@link ImportCommand}. Prints the resulting tip's node hex.</li>
 * </ul>
 */
public final class RequirementMatrixExportImportHelperMain {
    private RequirementMatrixExportImportHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        File repoDir = new File(args[1]);
        HgRepository repo = new HgRepository(repoDir);

        if ("export".equals(mode)) {
            File patchOutFile = new File(args[2]);
            Files.writeString(repoDir.toPath().resolve("a.txt"), "hello from hg4j\n");
            Files.createDirectories(repoDir.toPath().resolve("dir"));
            Files.writeString(repoDir.toPath().resolve("dir").resolve("b.txt"), "nested content\n");
            new AddCommand(repo).call();
            byte[] commitNode = new CommitCommand(repo)
                    .setAuthor("hg4j export <export@example.com>")
                    .setDate(1700000000, 0)
                    .setMessage("hg4j export commit")
                    .call();
            String patch = new ExportCommand(repo).setRevision("0").call();
            Files.writeString(patchOutFile.toPath(), patch);
            System.out.println(NodeIdUtil.toHex(commitNode));
        } else if ("import".equals(mode)) {
            File patchInFile = new File(args[2]);
            String patchText = Files.readString(patchInFile.toPath());
            new ImportCommand(repo).setPatchText(patchText).call();

            File clIdx = new File(repo.getStoreDir(), "00changelog.i");
            File clDat = new File(repo.getStoreDir(), "00changelog.d");
            Revlog cl = repo.getRevlog(clIdx, clDat);
            byte[] tipNode = cl.getIndexRecord(cl.getRevisionCount() - 1).getNodeId();
            System.out.println(NodeIdUtil.toHex(tipNode));
        } else {
            throw new IllegalArgumentException("Unknown mode: " + mode);
        }
    }
}
