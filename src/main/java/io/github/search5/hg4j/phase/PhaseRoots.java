package io.github.search5.hg4j.phase;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.util.SafeFileIO;

import io.github.search5.hg4j.lib.NodeId;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Function;

/**
 * Class responsible for managing and parsing Mercurial Phase metadata (.hg/store/phaseroots).
 *
 * @apiNote Obtained via {@link io.github.search5.hg4j.lib.HgRepository#getPhaseRoots()}. {@code
 *     PhaseCommand}/{@code SummaryCommand} query phases for display, {@code CommitCommand} sets
 *     a new commit's phase, and {@code PushCommand}/{@code FetchCommand}/{@code
 *     HgLocalClient}/{@code Wire2Commands} synchronize phases across push/pull, matching real
 *     hg's phase-boundary interop. Only non-{@link Phase#PUBLIC} nodes are ever written to disk
 *     — a node's absence from the file means public, exactly like real hg.
 */
public class PhaseRoots {

    /**
     * The three Mercurial commit phases, ordered from most to least shareable.
     */
    public enum Phase {
        /** Immutable, shareable phase; the default for nodes with no recorded root. */
        PUBLIC(0),
        /** Mutable, shareable phase used for in-progress work not yet made public. */
        DRAFT(1),
        /** Mutable, non-shareable phase excluded from normal push/pull exchange. */
        SECRET(2);

        private final int value;

        Phase(int value) {
            this.value = value;
        }

        /**
         * Returns the numeric phase code used in the on-disk {@code phaseroots} file.
         *
         * @return the phase's integer encoding ({@code 0} for {@link #PUBLIC}, {@code 1} for
         *     {@link #DRAFT}, {@code 2} for {@link #SECRET})
         */
        public int getValue() {
            return value;
        }

        /**
         * Resolves a numeric phase code, as stored in the {@code phaseroots} file, back to its
         * {@link Phase} constant.
         *
         * @param value the integer phase code read from disk
         * @return the matching {@link Phase}, or {@link #PUBLIC} if {@code value} does not match
         *     any known phase
         */
        public static Phase fromValue(int value) {
            for (Phase p : values()) {
                if (p.value == value) {
                    return p;
                }
            }
            return PUBLIC;
        }
    }

    private final File phaserootsFile;
    private final Map<NodeId, Phase> rootsMap = new HashMap<>();

    /**
     * Loads phase roots from the given {@code .hg/store/phaseroots} file, if it exists.
     *
     * @param phaserootsFile the {@code phaseroots} file to read; a missing file is treated as
     *     having no non-public roots
     * @throws IOException if the file exists but cannot be read
     */
    public PhaseRoots(File phaserootsFile) throws IOException {
        this.phaserootsFile = phaserootsFile;
        if (phaserootsFile.exists()) {
            load();
        }
    }

    private synchronized void load() throws IOException {
        List<String> lines = Files.readAllLines(phaserootsFile.toPath(), StandardCharsets.UTF_8);
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length >= 2) {
                try {
                    int val = Integer.parseInt(parts[0]);
                    Phase phase = Phase.fromValue(val);
                    NodeId node = NodeId.fromHex(parts[1]);
                    rootsMap.put(node, phase);
                } catch (IllegalArgumentException e) {
                    // Skip or ignore invalid lines (resilient recovery)
                }
            }
        }
    }

    private synchronized void save() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<NodeId, Phase> entry : rootsMap.entrySet()) {
            if (entry.getValue() != Phase.PUBLIC) {
                sb.append(entry.getValue().getValue())
                  .append(" ")
                  .append(entry.getKey().toHex())
                  .append("\n");
            }
        }
        SafeFileIO.writeStringAtomic(phaserootsFile, sb.toString());
    }

    /**
     * Checks the Phase of a specific node (functional interface based, supports FS-isolated testing).
     *
     * @param node the changeset node to resolve the phase for
     * @param parentLookup a function returning a node's parents, used to walk ancestors up to the
     *     nearest recorded phase root when {@code node} itself has no direct entry
     * @return the resolved {@link Phase}; {@link Phase#PUBLIC} if {@code node} is {@code null} or
     *     the null node, or if no ancestor carries a non-public phase root
     */
    public synchronized Phase getPhase(NodeId node, Function<NodeId, NodeId[]> parentLookup) {
        if (node == null || node.isNull()) {
            return Phase.PUBLIC;
        }

        // 1. Query the directly defined boundary (Root)
        Phase direct = rootsMap.get(node);
        if (direct != null) {
            return direct;
        }

        // 2. Ancestor search (BFS)
        Queue<NodeId> queue = new LinkedList<>();
        Set<NodeId> visited = new HashSet<>();
        queue.add(node);
        visited.add(node);

        Phase highestPhase = Phase.PUBLIC;

        while (!queue.isEmpty()) {
            NodeId curr = queue.poll();
            if (curr == null || curr.isNull()) {
                continue;
            }
            Phase currPhase = rootsMap.get(curr);

            if (currPhase != null) {
                if (currPhase.getValue() > highestPhase.getValue()) {
                    highestPhase = currPhase;
                }
                // Stop traversing up parents (reached the boundary)
                continue;
            }

            NodeId[] parents = parentLookup.apply(curr);
            if (parents != null) {
                for (NodeId parent : parents) {
                    if (parent != null && !parent.isNull() && visited.add(parent)) {
                        queue.add(parent);
                    }
                }
            }
        }

        return highestPhase;
    }

    /**
     * Checks the Phase of a specific node (dependent on Changelog Revlog).
     *
     * @param node the changeset node to resolve the phase for
     * @param changelog the changelog {@link Revlog} used to look up {@code node}'s parents when
     *     walking ancestors
     * @return the resolved {@link Phase}, per the same rules as {@link #getPhase(NodeId, Function)}
     * @throws IOException if the changelog cannot be read while resolving parent revisions
     */
    public Phase getPhase(NodeId node, Revlog changelog) throws IOException {
        return getPhase(node, n -> {
            if (n == null || n.isNull()) {
                return new NodeId[0];
            }
            int rev = changelog.findRevision(n.getBytes());
            if (rev == -1) return new NodeId[0];
            Revlog.IndexRecord rec = changelog.getIndexRecord(rev);
            int p1 = rec.getParent1();
            int p2 = rec.getParent2();

            List<NodeId> list = new ArrayList<>();
            if (p1 != -1) {
                list.add(new NodeId(changelog.getIndexRecord(p1).getNodeId()));
            }
            if (p2 != -1) {
                list.add(new NodeId(changelog.getIndexRecord(p2).getNodeId()));
            }
            return list.toArray(new NodeId[0]);
        });
    }

    /**
     * Updates the Phase of a specific node and synchronizes it to the file immediately. A phase
     * root is recorded/cleared directly by node identity -- it never needs to walk ancestors, so
     * {@code parentLookup} is accepted only for API symmetry with {@link #getPhase} and is unused.
     *
     * @param node the changeset node whose phase root is being recorded or cleared
     * @param phase the new phase for {@code node}; {@link Phase#PUBLIC} removes any existing root
     *     entry rather than recording one, matching the on-disk convention that public nodes are
     *     never written
     * @param parentLookup unused; accepted only for API symmetry with {@link #getPhase(NodeId, Function)}
     * @throws IOException if the updated phase roots cannot be written back to disk
     */
    public synchronized void setPhase(NodeId node, Phase phase, Function<NodeId, NodeId[]> parentLookup) throws IOException {
        if (node == null || node.isNull()) {
            return;
        }

        if (phase == Phase.PUBLIC) {
            rootsMap.remove(node);
        } else {
            rootsMap.put(node, phase);
        }
        save();
    }

    /**
     * Updates the Phase of a specific node and synchronizes it to the file immediately. {@code
     * changelog} is accepted only for API symmetry with {@link #getPhase(NodeId, Revlog)} (same
     * reason as the {@link #setPhase(NodeId, Phase, Function)} overload) and is unused.
     *
     * @param node the changeset node whose phase root is being recorded or cleared
     * @param phase the new phase for {@code node}
     * @param changelog unused; accepted only for API symmetry with {@link #getPhase(NodeId, Revlog)}
     * @throws IOException if the updated phase roots cannot be written back to disk
     */
    public void setPhase(NodeId node, Phase phase, Revlog changelog) throws IOException {
        setPhase(node, phase, (Function<NodeId, NodeId[]>) null);
    }

    /**
     * Checks whether a node is in the {@link Phase#PUBLIC} phase.
     *
     * @param node the changeset node to check
     * @param parentLookup a function returning a node's parents, used to walk ancestors as in
     *     {@link #getPhase(NodeId, Function)}
     * @return {@code true} if {@code node} resolves to {@link Phase#PUBLIC}
     */
    public boolean isPublic(NodeId node, Function<NodeId, NodeId[]> parentLookup) {
        return getPhase(node, parentLookup) == Phase.PUBLIC;
    }

    /**
     * Checks whether a node is in the {@link Phase#DRAFT} phase.
     *
     * @param node the changeset node to check
     * @param parentLookup a function returning a node's parents, used to walk ancestors as in
     *     {@link #getPhase(NodeId, Function)}
     * @return {@code true} if {@code node} resolves to {@link Phase#DRAFT}
     */
    public boolean isDraft(NodeId node, Function<NodeId, NodeId[]> parentLookup) {
        return getPhase(node, parentLookup) == Phase.DRAFT;
    }

    /**
     * Checks whether a node is in the {@link Phase#SECRET} phase.
     *
     * @param node the changeset node to check
     * @param parentLookup a function returning a node's parents, used to walk ancestors as in
     *     {@link #getPhase(NodeId, Function)}
     * @return {@code true} if {@code node} resolves to {@link Phase#SECRET}
     */
    public boolean isSecret(NodeId node, Function<NodeId, NodeId[]> parentLookup) {
        return getPhase(node, parentLookup) == Phase.SECRET;
    }

    /**
     * Checks whether a node is in the {@link Phase#PUBLIC} phase.
     *
     * @param node the changeset node to check
     * @param changelog the changelog {@link Revlog} used to look up parents, as in
     *     {@link #getPhase(NodeId, Revlog)}
     * @return {@code true} if {@code node} resolves to {@link Phase#PUBLIC}
     * @throws IOException if the changelog cannot be read while resolving parent revisions
     */
    public boolean isPublic(NodeId node, Revlog changelog) throws IOException {
        return getPhase(node, changelog) == Phase.PUBLIC;
    }

    /**
     * Checks whether a node is in the {@link Phase#DRAFT} phase.
     *
     * @param node the changeset node to check
     * @param changelog the changelog {@link Revlog} used to look up parents, as in
     *     {@link #getPhase(NodeId, Revlog)}
     * @return {@code true} if {@code node} resolves to {@link Phase#DRAFT}
     * @throws IOException if the changelog cannot be read while resolving parent revisions
     */
    public boolean isDraft(NodeId node, Revlog changelog) throws IOException {
        return getPhase(node, changelog) == Phase.DRAFT;
    }

    /**
     * Checks whether a node is in the {@link Phase#SECRET} phase.
     *
     * @param node the changeset node to check
     * @param changelog the changelog {@link Revlog} used to look up parents, as in
     *     {@link #getPhase(NodeId, Revlog)}
     * @return {@code true} if {@code node} resolves to {@link Phase#SECRET}
     * @throws IOException if the changelog cannot be read while resolving parent revisions
     */
    public boolean isSecret(NodeId node, Revlog changelog) throws IOException {
        return getPhase(node, changelog) == Phase.SECRET;
    }
}
