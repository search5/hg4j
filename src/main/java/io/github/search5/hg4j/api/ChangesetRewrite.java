package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.phase.PhaseRoots;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What commands that replace a changeset by a changed copy of it ({@code copy --at-rev}, {@code branch --rev}) share, following real
 * hg's {@code rewriteutil.precheck} and {@code cmdutil.cleanup_nodes}. The caller holds the working copy lock and the store lock (real
 * hg: {@code with repo.wlock(), repo.lock(), ...}); everything here, the checks included, runs under them.
 */
final class ChangesetRewrite {

    private ChangesetRewrite() {
    }

    /** A changelog entry: the manifest, the committer, the date, the extra fields, the files and the message. */
    static final class Entry {
        String manifestHex;
        String user;
        String time;
        String offset;
        final TreeMap<String, String> extra = new TreeMap<>();
        List<String> files = new ArrayList<>();
        String message;

        static Entry parse(byte[] content) {
            String text = new String(content, StandardCharsets.UTF_8);
            Entry e = new Entry();
            int i0 = text.indexOf('\n');
            int i1 = text.indexOf('\n', i0 + 1);
            int i2 = text.indexOf('\n', i1 + 1);
            e.manifestHex = text.substring(0, i0);
            e.user = text.substring(i0 + 1, i1);
            String[] date = text.substring(i1 + 1, i2).split(" ", 3);
            e.time = date[0];
            e.offset = date[1];
            if (date.length > 2 && !date[2].isEmpty()) {
                for (String part : date[2].split("\0", -1)) {
                    int colon = part.indexOf(':');
                    if (colon >= 0) {
                        e.extra.put(CommitCommand.decodeExtraKey(part.substring(0, colon)),
                                CommitCommand.decodeExtraKey(part.substring(colon + 1)));
                    }
                }
            }
            String rest = text.substring(i2 + 1);
            if (rest.startsWith("\n")) {
                e.message = rest.substring(1);
            } else {
                int sep = rest.indexOf("\n\n");
                if (sep < 0) {
                    e.files = new ArrayList<>(List.of(rest.split("\n")));
                    e.message = "";
                } else {
                    e.files = new ArrayList<>(List.of(rest.substring(0, sep).split("\n")));
                    e.message = rest.substring(sep + 2);
                }
            }
            return e;
        }

        byte[] toBytes() {
            StringBuilder sb = new StringBuilder();
            sb.append(manifestHex).append('\n').append(user).append('\n').append(time).append(' ').append(offset);
            if (!extra.isEmpty()) {
                List<String> parts = new ArrayList<>();
                for (Map.Entry<String, String> x : extra.entrySet()) {
                    parts.add(CommitCommand.encodeExtraKey(x.getKey()) + ":" + CommitCommand.encodeExtraKey(x.getValue()));
                }
                sb.append(' ').append(String.join("\0", parts));
            }
            sb.append('\n');
            for (String f : files) {
                sb.append(f).append('\n');
            }
            sb.append('\n').append(message);
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    static Entry read(Revlog changelog, int rev) throws IOException {
        return Entry.parse(changelog.getRevisionContent(rev));
    }

    // ------------------------------------------------------------------ rewriteutil.precheck

    private static String formatRevs(Revlog changelog, List<Integer> revs) throws IOException {
        if (revs.size() < 4) {
            List<String> shorts = new ArrayList<>();
            for (int r : revs) {
                shorts.add(NodeIdUtil.toHex(changelog.getIndexRecord(r).getNodeId()).substring(0, 12));
            }
            return String.join(", ", shorts);
        }
        return NodeIdUtil.toHex(changelog.getIndexRecord(revs.get(0)).getNodeId()).substring(0, 12) + " and " + (revs.size() - 1)
                + " others";
    }

    /** Real hg's {@code rewriteutil.precheck(repo, revs, action)}; the caller holds the locks. */
    static void precheck(HgRepository repo, Revlog changelog, List<Integer> revs, String action) throws IOException {
        if (revs.contains(-1)) {
            throw new HgValidationException("cannot " + action + " the null revision");
        }
        byte[] p2 = repo.getDirstate().getParent2();
        if (p2 != null && !NodeIdUtil.isAllZero(p2)) {
            throw new HgValidationException("cannot " + action + " changesets while merging");
        }
        PhaseRoots phases = repo.getPhaseRoots();
        List<Integer> publicRevs = new ArrayList<>();
        for (int r : revs) {
            if (phases.getPhase(new NodeId(changelog.getIndexRecord(r).getNodeId()), changelog) == PhaseRoots.Phase.PUBLIC) {
                publicRevs.add(r);
            }
        }
        if (!publicRevs.isEmpty()) {
            throw new HgValidationException("cannot " + action + " public changesets: " + formatRevs(changelog, publicRevs));
        }
        // revs:: - revs : descendants that are not rewritten with them would be orphaned
        java.util.Set<Integer> rewritten = new java.util.HashSet<>(revs);
        java.util.Set<Integer> descendants = new java.util.HashSet<>(revs);
        int orphans = 0;
        int min = Collections.min(revs);
        for (int r = min + 1; r < changelog.getRevisionCount(); r++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(r);
            if (descendants.contains(rec.getParent1()) || descendants.contains(rec.getParent2())) {
                descendants.add(r);
                if (!rewritten.contains(r)) {
                    orphans++;
                }
            }
        }
        if (orphans > 0) {
            throw new HgValidationException("cannot " + action + " changeset, as that will orphan " + orphans + " descendants");
        }
    }


    // ------------------------------------------------------------------ logcmdutil.revrange

    /**
     * The revisions a set expression names, ascending: {@code +} (union), {@code a:b} (a range), {@code a::b} (descendants of a that are
     * ancestors of b), a single revision (number, hash, {@code .}, {@code tip}, a name), and anything else is left to the revset engine.
     */
    static java.util.TreeSet<Integer> revrange(HgRepository repo, Revlog changelog, String spec) throws IOException {
        java.util.TreeSet<Integer> result = new java.util.TreeSet<>();
        if (spec.contains("(") || spec.contains(" or ") || spec.contains(" and ") || spec.contains(" not ")) {
            result.addAll(new io.github.search5.hg4j.revset.HgRevsetEngine(repo).query(spec));
            return result;
        }
        for (String term : spec.split("\\+")) {
            term = term.trim();
            if (term.isEmpty()) {
                continue;
            }
            int dag = term.indexOf("::");
            int colon = term.indexOf(':');
            if (dag >= 0) {
                String left = term.substring(0, dag).trim();
                String right = term.substring(dag + 2).trim();
                int low = left.isEmpty() ? 0 : revision(repo, changelog, left);
                int high = right.isEmpty() ? changelog.getRevisionCount() - 1 : revision(repo, changelog, right);
                boolean[] descendant = new boolean[changelog.getRevisionCount()];
                descendant[low] = true;
                for (int r = low + 1; r <= high; r++) {
                    Revlog.IndexRecord rec = changelog.getIndexRecord(r);
                    descendant[r] = (rec.getParent1() >= 0 && descendant[rec.getParent1()])
                            || (rec.getParent2() >= 0 && descendant[rec.getParent2()]);
                }
                boolean[] ancestor = new boolean[changelog.getRevisionCount()];
                ancestor[high] = true;
                for (int r = high; r >= low; r--) {
                    if (ancestor[r]) {
                        Revlog.IndexRecord rec = changelog.getIndexRecord(r);
                        if (rec.getParent1() >= 0) {
                            ancestor[rec.getParent1()] = true;
                        }
                        if (rec.getParent2() >= 0) {
                            ancestor[rec.getParent2()] = true;
                        }
                    }
                }
                for (int r = low; r <= high; r++) {
                    if (descendant[r] && ancestor[r]) {
                        result.add(r);
                    }
                }
            } else if (colon >= 0) {
                String left = term.substring(0, colon).trim();
                String right = term.substring(colon + 1).trim();
                int a = left.isEmpty() ? 0 : revision(repo, changelog, left);
                int b = right.isEmpty() ? changelog.getRevisionCount() - 1 : revision(repo, changelog, right);
                for (int r = Math.min(a, b); r <= Math.max(a, b); r++) {
                    result.add(r);
                }
            } else {
                result.add(revision(repo, changelog, term));
            }
        }
        return result;
    }

    /** One revision: {@code .} is the working directory's parent, the rest is {@link NodeIdUtil#resolveRevision}. */
    static int revision(HgRepository repo, Revlog changelog, String name) throws IOException {
        byte[] node = ".".equals(name) ? repo.getDirstate().getParent1() : NodeIdUtil.resolveRevision(changelog, name);
        int rev = node == null || NodeIdUtil.isAllZero(node) ? -1 : changelog.findRevision(node);
        if (rev < 0) {
            throw new HgValidationException("unknown revision '" + name + "'");
        }
        return rev;
    }

    // ------------------------------------------------------------------ writing

    /** Appends {@code entry} as a new changeset with the given parents and returns its node. */
    static byte[] append(Revlog changelog, Entry entry, byte[] p1Node, byte[] p2Node) throws IOException {
        byte[] n1 = p1Node == null ? new byte[20] : p1Node;
        byte[] n2 = p2Node == null ? new byte[20] : p2Node;
        int p1 = NodeIdUtil.isAllZero(n1) ? -1 : changelog.findRevision(n1);
        int p2 = NodeIdUtil.isAllZero(n2) ? -1 : changelog.findRevision(n2);
        return changelog.appendRevision(entry.toBytes(), (Map<String, String>) null, p1, p2, n1, n2, changelog.getRevisionCount());
    }

    // ------------------------------------------------------------------ cmdutil.cleanup_nodes

    /**
     * Real hg's {@code cmdutil.cleanup_nodes} with obsolescence off: bookmarks on a replaced node follow to its replacement, the new
     * nodes get the phase of the old ones (not lower than their parents'), and the old nodes are stripped. Under both locks, as hg's
     * {@code with repo.lock()} around the commit and this call.
     *
     * @param replacements old node (hex) to new node (hex)
     */
    static List<File> cleanupNodes(HgRepository repo, Map<String, String> replacements, String operation) throws IOException, HgLockException {
        return cleanupNodes(repo, replacements, operation, java.util.Set.of());
    }

    /**
     * The same where the nodes in {@code withoutSuccessor} are dropped with no successor (a skipped changeset, in {@code
     * cleanup_nodes}: {@code replacements[(old,)] = ()}): their bookmarks still move to the mapped node, but it takes no phase from them.
     */
    static List<File> cleanupNodes(HgRepository repo, Map<String, String> allReplacements, String operation, java.util.Set<String> withoutSuccessor)
            throws IOException, HgLockException {
        List<File> backups = new ArrayList<>();
        if (allReplacements.isEmpty()) {
            return backups;
        }
        Map<String, String> replacements = new LinkedHashMap<>(allReplacements);
        replacements.keySet().removeAll(withoutSuccessor);
        Revlog changelog = repo.getRevlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"));
        // phases: max(phase of the old node, phase of the new parents), in revision order
        PhaseRoots phases = repo.getPhaseRoots();
        Map<String, PhaseRoots.Phase> oldPhases = new HashMap<>();
        for (String oldHex : replacements.keySet()) {
            oldPhases.put(oldHex, phases.getPhase(new NodeId(NodeIdUtil.fromHex(oldHex)), changelog));
        }
        List<String> newNodes = new ArrayList<>(replacements.values());
        Map<String, Integer> revOf = new HashMap<>();
        for (String hex : newNodes) {
            revOf.put(hex, changelog.findRevision(NodeIdUtil.fromHex(hex)));
        }
        newNodes.sort((a, b) -> Integer.compare(revOf.get(a), revOf.get(b)));
        Map<String, PhaseRoots.Phase> newPhases = new HashMap<>();
        for (String newHex : newNodes) {
            int rev = changelog.findRevision(NodeIdUtil.fromHex(newHex));
            Revlog.IndexRecord rec = changelog.getIndexRecord(rev);
            PhaseRoots.Phase phase = PhaseRoots.Phase.PUBLIC;
            for (Map.Entry<String, String> r : replacements.entrySet()) {
                if (r.getValue().equals(newHex) && oldPhases.get(r.getKey()).getValue() > phase.getValue()) {
                    phase = oldPhases.get(r.getKey());
                }
            }
            for (int parent : new int[] {rec.getParent1(), rec.getParent2()}) {
                if (parent >= 0) {
                    String parentHex = NodeIdUtil.toHex(changelog.getIndexRecord(parent).getNodeId());
                    PhaseRoots.Phase pp = newPhases.containsKey(parentHex) ? newPhases.get(parentHex)
                            : phases.getPhase(new NodeId(changelog.getIndexRecord(parent).getNodeId()), changelog);
                    if (pp.getValue() > phase.getValue()) {
                        phase = pp;
                    }
                }
            }
            newPhases.put(newHex, phase);
            if (phase != PhaseRoots.Phase.PUBLIC) {
                phases.setPhase(new NodeId(NodeIdUtil.fromHex(newHex)), phase, changelog);
            }
        }
        moveBookmarks(repo, allReplacements);
        replacements = new LinkedHashMap<>(allReplacements);
        // the old nodes go (the working copy has been moved already, so it is left alone); roots first, their descendants follow
        java.util.Set<String> oldSet = replacements.keySet();
        for (String oldHex : new ArrayList<>(oldSet)) {
            int rev = changelog.findRevision(NodeIdUtil.fromHex(oldHex));
            if (rev < 0) {
                continue;
            }
            Revlog.IndexRecord rec = changelog.getIndexRecord(rev);
            boolean hasOldParent = false;
            for (int parent : new int[] {rec.getParent1(), rec.getParent2()}) {
                if (parent >= 0 && oldSet.contains(NodeIdUtil.toHex(changelog.getIndexRecord(parent).getNodeId()))) {
                    hasOldParent = true;
                }
            }
            if (!hasOldParent) {
                StripCommand strip = new StripCommand(repo).setRevision(oldHex).setKeep(true).setTopic(operation);
                strip.call();
                if (strip.getBackupBundle() != null) {
                    backups.add(strip.getBackupBundle());
                }
                changelog = repo.getRevlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"));
            }
        }
        return backups;
    }

    /** Bookmarks on a replaced node follow to its replacement ({@code cleanup_nodes}' {@code moves}; also when the originals are kept). */
    static void moveBookmarks(HgRepository repo, Map<String, String> replacements) throws IOException, HgLockException {
        Map<String, String> bookmarks = new BookmarkCommand(repo).list();
        for (Map.Entry<String, String> bookmark : bookmarks.entrySet()) {
            String replacement = replacements.get(bookmark.getValue());
            if (replacement != null) {
                new BookmarkCommand(repo).setBookmarkName(bookmark.getKey()).setRevision(replacement).setForce(true).call();
            }
        }
    }

    /** {@code scmutil.movedirstate}: the working copy parent follows a replaced changeset. */
    static void moveWorkingCopyParent(HgRepository repo, byte[] oldNode, byte[] newNode) throws IOException {
        io.github.search5.hg4j.dirstate.Dirstate dirstate = repo.getDirstate();
        if (java.util.Arrays.equals(dirstate.getParent1(), oldNode)) {
            dirstate.setParents(newNode, new byte[20]);
            repo.writeDirstate(dirstate);
        }
    }

    static Map<String, String> orderedCopy(Map<String, String> m) {
        return new LinkedHashMap<>(m);
    }
}
