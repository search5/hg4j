package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;

import java.io.File;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixBookmarkDockerRoundTripTest},
 * mirroring {@link RequirementMatrixCommitHelperMain}'s reason for existing -- see its javadoc for
 * the full root-cause writeup on why hg4j's own write commands must run in a dedicated subprocess
 * rather than inline in a JVM that also repeatedly spawns {@code docker exec}/{@code docker run}
 * child processes. Supports the handful of {@link BookmarkCommand} operations the Docker matrix
 * scenario needs, one per subprocess invocation (real hg commands run between each one, so each
 * needs its own fresh process the same way {@code RequirementMatrixAmendHelperMain} et al. do).
 *
 * <p>Usage: {@code <repoDir> create-active <name>} | {@code <repoDir> create-explicit <name>
 * <nodeHex> [force]} | {@code <repoDir> delete <name>}.
 */
public final class RequirementMatrixBookmarkHelperMain {
    private RequirementMatrixBookmarkHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        String op = args[1];
        HgRepository repo = new HgRepository(repoDir);
        BookmarkCommand cmd = new BookmarkCommand(repo);
        switch (op) {
            case "create-active":
                cmd.setBookmarkName(args[2]).call();
                break;
            case "create-explicit":
                cmd.setBookmarkName(args[2]).setRevision(args[3]);
                if (args.length > 4 && Boolean.parseBoolean(args[4])) {
                    cmd.setForce(true);
                }
                cmd.call();
                break;
            case "delete":
                cmd.setDelete(true).setBookmarkName(args[2]).call();
                break;
            default:
                throw new IllegalArgumentException("Unknown bookmark op: " + op);
        }
    }
}
