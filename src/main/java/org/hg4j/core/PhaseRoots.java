package org.hg4j.core;

import org.hg4j.lib.NodeId;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Function;

/**
 * Mercurial의 Phase 메타데이터 (.hg/phaseroots)를 관리하고 파싱하는 클래스.
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
                    // 잘못된 라인은 스킵하거나 무시 (유연한 복구)
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
     * 특정 노드의 Phase를 확인합니다 (함수형 인터페이스 기반, FS 격리 테스트 지원).
     */
    public synchronized Phase getPhase(NodeId node, Function<NodeId, NodeId[]> parentLookup) {
        if (node == null || node.isNull()) {
            return Phase.PUBLIC;
        }

        // 1. 직접 정의된 경계선(Root) 조회
        Phase direct = rootsMap.get(node);
        if (direct != null) {
            return direct;
        }

        // 2. 조상 탐색 (BFS)
        Queue<NodeId> queue = new LinkedList<>();
        Set<NodeId> visited = new HashSet<>();
        queue.add(node);
        visited.add(node);

        Phase highestPhase = Phase.PUBLIC;

        while (!queue.isEmpty()) {
            NodeId curr = queue.poll();
            Phase currPhase = rootsMap.get(curr);

            if (currPhase != null) {
                if (currPhase.getValue() > highestPhase.getValue()) {
                    highestPhase = currPhase;
                }
                // 부모 탐색을 더 이상 올라가지 않음 (경계선에 도달했으므로)
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
     * 특정 노드의 Phase를 확인합니다 (Changelog Revlog 의존).
     */
    public Phase getPhase(NodeId node, Revlog changelog) throws IOException {
        return getPhase(node, n -> {
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
     * 특정 노드의 Phase를 업데이트하고 파일에 즉시 동기화합니다 (함수형 인터페이스 기반).
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
     * 특정 노드의 Phase를 업데이트하고 파일에 즉시 동기화합니다 (Changelog Revlog 의존).
     */
    public void setPhase(NodeId node, Phase phase, Revlog changelog) throws IOException {
        setPhase(node, phase, n -> {
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
