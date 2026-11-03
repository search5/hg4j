package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.phase.PhaseRoots;
import io.github.search5.hg4j.storage.Revlog;

import java.io.IOException;
import java.util.List;

/**
 * Mercurial's {@code served} repository view: what a peer (a server, or a local repository read by another one) shows to others.
 * Secret changesets, and every descendant of one, are hidden: they are not heads, not branch heads, not known, not found by
 * {@code lookup}, never part of a bundle, and bookmarks that point at them are not listed.
 */
public final class ServedView {

    private ServedView() {
    }

    /**
     * @return {@code hidden[rev]} for every revision of {@code changelog}, or {@code null} when nothing is hidden (the usual case)
     */
    public static boolean[] hidden(HgRepository repo, Revlog changelog) throws IOException {
        List<NodeId> secretRoots = repo.getPhaseRoots().getRoots(PhaseRoots.Phase.SECRET);
        if (secretRoots.isEmpty()) {
            return null;
        }
        int count = changelog.getRevisionCount();
        boolean[] hidden = new boolean[count];
        boolean any = false;
        java.util.Set<String> roots = new java.util.HashSet<>();
        for (NodeId root : secretRoots) {
            roots.add(io.github.search5.hg4j.util.NodeIdUtil.toHex(root.getBytes()));
        }
        for (int r = 0; r < count; r++) {
            Revlog.IndexRecord record = changelog.getIndexRecord(r);
            boolean h = roots.contains(io.github.search5.hg4j.util.NodeIdUtil.toHex(record.getNodeId()))
                    || (record.getParent1() >= 0 && hidden[record.getParent1()])
                    || (record.getParent2() >= 0 && hidden[record.getParent2()]);
            hidden[r] = h;
            any |= h;
        }
        return any ? hidden : null;
    }

    /** Whether the changeset is hidden in the served view (false for a node the repository does not have). */
    public static boolean isHidden(HgRepository repo, byte[] node) throws IOException {
        Revlog changelog = io.github.search5.hg4j.transport.wireprotov1.RepoSupport.changelog(repo);
        boolean[] hidden = hidden(repo, changelog);
        if (hidden == null) {
            return false;
        }
        int rev = changelog.findRevision(node);
        return rev >= 0 && hidden[rev];
    }
}
