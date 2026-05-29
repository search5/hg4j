package org.hg4j.api;

import org.hg4j.core.HgRepository;
import org.hg4j.core.Revlog;
import org.hg4j.core.NodeIdUtil;
import java.io.File;
import java.io.IOException;

/**
 * Identify command for summary status overview of active workspace node, branch and tag.
 */
public class IdentifyCommand {
    private final HgRepository repository;

    public IdentifyCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Identifies current workspace parent node ID, branch name and tags.
     * Output format mimics native hg: 'hexShort [tag] [branch]'.
     *
     * @return SCM identity summary string
     * @throws IOException if dirstate or changelog parsing fails
     */
    public String call() throws IOException {
        byte[] p1 = repository.getDirstate().getParent1();
        if (p1 == null || NodeIdUtil.isAllZero(p1)) {
            return "000000000000 default";
        }

        String hexShort = NodeIdUtil.toHex(p1).substring(0, 12);
        String branch = repository.getBranch();
        if (branch == null || branch.isEmpty()) {
            branch = "default";
        }

        // Fetch changelog tags if any matching
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);
        int rev = changelog.findRevision(p1);

        String tagPart = "";
        if (rev == changelog.getRevisionCount() - 1) {
            tagPart = " tip";
        }

        return hexShort + tagPart + " " + branch;
    }
}
