package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixHisteditDockerRoundTripTest},
 * mirroring {@link RequirementMatrixRebaseHelperMain}'s reason for existing -- see its javadoc
 * (which itself points to {@link RequirementMatrixCommitHelperMain}) for the full root-cause
 * writeup on why hg4j's own revlog-writing commands must run in a dedicated subprocess rather than
 * inline in a JVM that also repeatedly spawns {@code docker exec}/{@code docker run} child
 * processes.
 *
 * <p>Args: {@code repoDir} followed by one or more {@code ACTION:hex} rule specs (e.g.
 * {@code PICK:abcd1234...}), applied to a single {@link HisteditCommand} run in rule order.
 * Prints the resulting working-copy parent's hex once {@link HisteditCommand#call()} returns.
 */
public final class RequirementMatrixHisteditHelperMain {
    private RequirementMatrixHisteditHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        HgRepository repo = new HgRepository(repoDir);

        HisteditCommand cmd = new HisteditCommand(repo);
        for (int i = 1; i < args.length; i++) {
            String[] parts = args[i].split(":", 2);
            HisteditCommand.Action action = HisteditCommand.Action.valueOf(parts[0]);
            cmd.addRule(action, parts[1]);
        }
        cmd.call();

        System.out.println(NodeIdUtil.toHex(repo.getDirstate().getParent1()));
    }
}
