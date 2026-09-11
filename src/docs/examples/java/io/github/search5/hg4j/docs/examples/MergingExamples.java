package io.github.search5.hg4j.docs.examples;

import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.MergeCommand.MergeResult;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgMergeConflictException;
import io.github.search5.hg4j.lib.NodeId;

import java.io.IOException;
import java.util.List;

/**
 * Snippets for the "Merging &amp; Rebasing" chapter.
 */
final class MergingExamples {

    private MergingExamples() {
    }

    void mergeAnotherHead() throws IOException, HgLockException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::merge-branches[]
            List<String> headHexes = hg.heads().call();
            // Merge some other head into the working copy's current parent.
            byte[] otherHead = NodeId.fromHex(headHexes.get(1)).getBytes();
            MergeResult result = hg.merge().setNodeId(otherHead).call();

            if (result.isConflicted()) {
                for (String conflictedPath : result.getConflicts()) {
                    // Each conflicted file already has <<<<<<< / ======= / >>>>>>> markers
                    // written to disk -- resolve them, then commit like a normal merge commit.
                    System.out.println("Unresolved: " + conflictedPath);
                }
            } else {
                hg.commit().setMessage("Merge").call();
            }
            // end::merge-branches[]
        }
    }

    void rebaseOntoAnotherRevision() throws IOException, HgLockException, HgMergeConflictException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::rebase-commits[]
            List<String> headHexes = hg.heads().call();
            byte[] source = NodeId.fromHex(headHexes.get(0)).getBytes();
            byte[] target = NodeId.fromHex(headHexes.get(1)).getBytes();

            // Cherry-picks `source` (and its descendants) onto `target`. The original revisions
            // stay readable via `hg log --hidden`; only an obsolescence marker records that
            // they were superseded -- nothing is physically stripped.
            hg.rebase().setSource(source).setTarget(target).call();
            // end::rebase-commits[]
        }
    }
}
