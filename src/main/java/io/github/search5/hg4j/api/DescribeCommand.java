package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Describe command for describing the current changeset revision
 * relative to the nearest ancestral tag.
 */
public class DescribeCommand {
    private final HgRepository repository;

    public DescribeCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Traverses changelog history to locate the closest ancestral tag.
     * Formats output as 'tag-distance-gShortNode' similar to standard Git-describe.
     *
     * @return described revision string
     * @throws IOException if history parsing fails
     */
    public String call() throws IOException {
        // Backlog #39: a long-lived HgRepository handle whose changelog is changelog-v2
        // (docket-based, fixed .i file length -- content changes are in-place index_end/data_end
        // rewrites) silently served STALE cached revlog data after an external process (real hg
        // CLI, another hg4j writer) appended a revision, because RevlogIndex's own incremental
        // "did the file grow" check never fires for a docket whose length never changes -- caught
        // by RequirementMatrixDescribeCoreRoundTripTest's cl2/cl2+sidedata combos, which got back
        // a stale-but-plausible-looking WRONG answer (an exact tag match one revision too early)
        // rather than an error. repository.refreshIfChangedOnDisk() already existed for exactly
        // this class of problem (previously wired up only for the long-lived HgHttpWireServer/
        // HgSshWireServer handles) -- reusing it here at zero cost in the common case (a
        // freshly-opened HgRepository whose changelog never changed under it: two stat() calls).
        repository.refreshIfChangedOnDisk();
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        int count = changelog.getRevisionCount();
        if (count == 0) {
            return "empty-repository";
        }

        byte[] currentParent = repository.getDirstate().getParent1();
        int currentRev = changelog.findRevision(currentParent);
        if (currentRev == -1) {
            currentRev = count - 1;
            currentParent = changelog.getIndexRecord(currentRev).getNodeId();
        }

        // 1. Read tags from .hgtags
        Map<String, String> tagToNode = new HashMap<>();
        Map<String, String> nodeToTag = new HashMap<>();
        File hgtags = new File(repository.getDirectory(), ".hgtags");
        if (hgtags.exists()) {
            List<String> tagLines = Files.readAllLines(hgtags.toPath(), StandardCharsets.UTF_8);
            for (String line : tagLines) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    String nodeHex = parts[0].trim();
                    String tagName = parts[1].trim();
                    tagToNode.put(tagName, nodeHex);
                    nodeToTag.put(nodeHex.substring(0, 12), tagName);
                }
            }
        }

        // 2. Look for tag at current revision
        String currentHexShort = NodeIdUtil.toHex(currentParent).substring(0, 12);
        if (nodeToTag.containsKey(currentHexShort)) {
            return nodeToTag.get(currentHexShort);
        }

        // 3. Traversal backward to find closest tag
        int distance = 0;
        int curr = currentRev;
        while (curr >= 0) {
            byte[] node = changelog.getIndexRecord(curr).getNodeId();
            String hexShort = NodeIdUtil.toHex(node).substring(0, 12);
            if (nodeToTag.containsKey(hexShort)) {
                return nodeToTag.get(hexShort) + "-" + distance + "-g" + currentHexShort;
            }
            distance++;
            curr = changelog.getIndexRecord(curr).getParent1();
        }

        return "v0.0-" + distance + "-g" + currentHexShort;
    }
}
