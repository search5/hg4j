package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.util.List;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixUnbundleDockerRoundTripTest},
 * mirroring {@link RequirementMatrixBundleHelperMain}'s reason for existing (see {@link
 * RequirementMatrixCommitHelperMain}'s javadoc for the full root-cause writeup on why hg4j's own
 * revlog-writing commands must run in a dedicated subprocess rather than inline in a JVM that also
 * repeatedly spawns {@code docker exec}/{@code docker run} child processes).
 *
 * <p>Unlike {@link RequirementMatrixBundleHelperMain} (which creates the commits AND writes the
 * bundle files), this helper only ever APPLIES two already-written bundle files (produced by real
 * {@code hg bundle} inside the Docker container, against the SAME bind-mounted directory tree this
 * process also reads/writes -- no docker exec calls happen in this process at all) to an
 * hg4j-managed destination repository. Args: {@code destRepoDir bundleFile1 bundleFile2}. Prints
 * {@code node1Hex node2Hex} (space-separated) -- the last imported node from each of the two
 * {@link UnbundleCommand} calls.
 */
public final class RequirementMatrixUnbundleHelperMain {
    private RequirementMatrixUnbundleHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File destRepoDir = new File(args[0]);
        File bundleFile1 = new File(args[1]);
        File bundleFile2 = new File(args[2]);
        HgRepository dest = new HgRepository(destRepoDir);

        List<byte[]> imported1 = new UnbundleCommand(dest).setBundleFile(bundleFile1).call();
        dest.clearRevlogCache();
        List<byte[]> imported2 = new UnbundleCommand(dest).setBundleFile(bundleFile2).call();

        String node1Hex = imported1.isEmpty() ? "" : NodeIdUtil.toHex(imported1.get(imported1.size() - 1));
        String node2Hex = imported2.isEmpty() ? "" : NodeIdUtil.toHex(imported2.get(imported2.size() - 1));
        System.out.println(node1Hex + " " + node2Hex);
    }
}
