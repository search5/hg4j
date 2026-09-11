package io.github.search5.hg4j.docs.examples;

import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.treewalk.ManifestTreeIterator;
import io.github.search5.hg4j.treewalk.ManifestWalk;
import io.github.search5.hg4j.treewalk.TreeWalk;
import io.github.search5.hg4j.treewalk.WorkingDirWalk;

import java.io.IOException;

/**
 * Snippets for the "Walking Trees Directly (Advanced)" chapter of the hg4j manual. If you have
 * used JGit's own {@code TreeWalk}, this is the same idea: a shared, sorted, multi-tree
 * comparison engine that porcelain commands (and your own tooling) can drive directly. Compiled
 * by the {@code docExamples} source set so the manual's example code can never silently drift out
 * of sync with the real API.
 */
final class TreeWalkExamples {

    private TreeWalkExamples() {
    }

    void walkManifestOfARevision() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::walk-manifest[]
            ManifestWalk walk = hg.walkManifest("tip");
            while (walk.next()) {
                ManifestWalk.Entry entry = walk.getEntry();
                System.out.println(entry.getPath() + " -> " + entry.getNodeIdHex()
                        + (entry.isExecutable() ? " (executable)" : "")
                        + (entry.isSymlink() ? " (symlink)" : ""));
            }
            // end::walk-manifest[]
        }
    }

    void walkWorkingDirectory() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::walk-workingdir[]
            // Merges tracked (dirstate) and untracked paths into one sorted walk, so every
            // relevant working-copy path is visited regardless of whether it is already tracked.
            WorkingDirWalk walk = hg.walkWorkingDir();
            while (walk.next()) {
                WorkingDirWalk.Entry entry = walk.getEntry();
                // State is a dirstate character: 'n' (normal), 'a' (added), 'r' (removed),
                // 'm' (merged), or '?' for an untracked path.
                System.out.println(entry.getState() + " " + entry.getPath()
                        + " (" + entry.getSize() + " bytes)");
            }
            // end::walk-workingdir[]
        }
    }

    void compareTwoRevisionsWithTreeWalk() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::walk-tree-compare[]
            HgRepository repository = hg.getRepository();

            // Each added tree is indexed in add order -- that index is what isTracked()/
            // getNodeId()/getState() take below. ManifestTreeIterator resolves "tip" or a
            // revision number/hex node id, exactly like walkManifest(String).
            TreeWalk tw = hg.walkTree();
            tw.addTree(new ManifestTreeIterator(repository, "0")); // tree 0: revision 0
            tw.addTree(new ManifestTreeIterator(repository, "tip")); // tree 1: tip

            while (tw.next()) {
                String path = tw.getPath();
                boolean inOld = tw.isTracked(0);
                boolean inNew = tw.isTracked(1);

                if (inOld && !inNew) {
                    System.out.println("removed: " + path);
                } else if (!inOld && inNew) {
                    System.out.println("added: " + path);
                } else if (tw.getState(1) != tw.getState(0)
                        || !java.util.Arrays.equals(tw.getNodeId(0), tw.getNodeId(1))) {
                    System.out.println("modified: " + path);
                }
            }
            // end::walk-tree-compare[]
        }
    }

    void restrictTreeWalkWithAFilter() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            HgRepository repository = hg.getRepository();
            // tag::walk-tree-filter[]
            TreeWalk tw = hg.walkTree();
            tw.addTree(new ManifestTreeIterator(repository, "tip"));

            // Restricts the walk to one subtree.
            tw.setFilter(path -> path.equals("services/billing") || path.startsWith("services/billing/"));

            while (tw.next()) {
                System.out.println(tw.getPath());
            }
            // end::walk-tree-filter[]
        }
    }

    void listTopLevelEntriesOnly() throws IOException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            HgRepository repository = hg.getRepository();
            // tag::walk-tree-non-recursive[]
            // With no filter set, setRecursive(false) restricts the walk to paths with no "/"
            // at all -- i.e. files directly at the repository root, not inside any subdirectory.
            TreeWalk tw = hg.walkTree();
            tw.addTree(new ManifestTreeIterator(repository, "tip"));
            tw.setRecursive(false);

            while (tw.next()) {
                System.out.println(tw.getPath()); // e.g. "README.md", never "src/Main.java"
            }
            // end::walk-tree-non-recursive[]
        }
    }
}
