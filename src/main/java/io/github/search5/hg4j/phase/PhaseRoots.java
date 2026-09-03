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
 */
public class PhaseRoots {

    public enum Phase {
        PUBLIC(0),
        DRAFT(1),
        SECRET(2);

        private final int value;

        Phase(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

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
     */
    public void setPhase(NodeId node, Phase phase, Revlog changelog) throws IOException {
        setPhase(node, phase, (Function<NodeId, NodeId[]>) null);
    }

    public boolean isPublic(NodeId node, Function<NodeId, NodeId[]> parentLookup) {
        return getPhase(node, parentLookup) == Phase.PUBLIC;
    }

    public boolean isDraft(NodeId node, Function<NodeId, NodeId[]> parentLookup) {
        return getPhase(node, parentLookup) == Phase.DRAFT;
    }

    public boolean isSecret(NodeId node, Function<NodeId, NodeId[]> parentLookup) {
        return getPhase(node, parentLookup) == Phase.SECRET;
    }

    public boolean isPublic(NodeId node, Revlog changelog) throws IOException {
        return getPhase(node, changelog) == Phase.PUBLIC;
    }

    public boolean isDraft(NodeId node, Revlog changelog) throws IOException {
        return getPhase(node, changelog) == Phase.DRAFT;
    }

    public boolean isSecret(NodeId node, Revlog changelog) throws IOException {
        return getPhase(node, changelog) == Phase.SECRET;
    }
}
