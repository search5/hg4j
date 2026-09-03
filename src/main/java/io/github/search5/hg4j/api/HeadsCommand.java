package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Heads command for querying SCM repository heads (revisions without children).
 */
public class HeadsCommand {
    private final HgRepository repository;

    public HeadsCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Traverses the changelog and finds all leaf nodes in SCM commit history.
     *
     * @return List of head node IDs in hex representation
     * @throws IOException if changelog IO fails
     */
    public List<String> call() throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        int count = changelog.getRevisionCount();
        List<String> headList = new ArrayList<>();
        if (count == 0) {
            return headList;
        }

        // Parent tracking: any revision that is a parent of another revision is not a head
        Set<Integer> parents = new HashSet<>();
        for (int i = 0; i < count; i++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(i);
            int p1 = rec.getParent1();
            int p2 = rec.getParent2();
            if (p1 != -1) parents.add(p1);
            if (p2 != -1) parents.add(p2);
        }

        for (int i = 0; i < count; i++) {
            if (!parents.contains(i)) {
                headList.add(NodeIdUtil.toHex(changelog.getIndexRecord(i).getNodeId()));
            }
        }
        return headList;
    }
}
