package com.github.search5.hg4j.api;

import com.github.search5.hg4j.core.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.util.NodeIdUtil;
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

        // Fetch actual tags matching from .hgtags file
        File hgTagsFile = new File(repository.getDirectory(), ".hgtags");
        if (hgTagsFile.exists()) {
            java.util.List<String> tagLines = java.nio.file.Files.readAllLines(hgTagsFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            String targetHex = NodeIdUtil.toHex(p1);
            for (String line : tagLines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\s+", 2);
                if (parts.length == 2) {
                    String tagHex = parts[0];
                    String tagName = parts[1];
                    if (targetHex.startsWith(tagHex) || tagHex.startsWith(targetHex)) {
                        tagPart = " " + tagName;
                        break;
                    }
                }
            }
        }

        return hexShort + tagPart + " " + branch;
    }
}
