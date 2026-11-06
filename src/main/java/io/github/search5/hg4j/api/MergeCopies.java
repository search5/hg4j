package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Finds the files that one side of a merge added as a copy or rename of a file of the merge ancestor, which {@code hg merge} follows
 * ({@code copies.mergecopies}): when the other side changed the source, the change is merged into the new name.
 *
 * <p>The origin of a file is found like the original's {@code _tracefile} does: from the file revision in the side's manifest, through
 * the parents of the file revision and through the {@code copy}/{@code copyrev} metadata (which acts as a parent), the first file
 * revision that is the very revision the ancestor has under some path names that path as the source.</p>
 */
final class MergeCopies {
    /** Bounds the walk through the history of one file. */
    private static final int MAX_VISITED = 20000;

    private final HgRepository repository;

    MergeCopies(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns the copies and renames that {@code side} made since {@code base}.
     * @param side the manifest of the side (path to 40-character node hex plus flag)
     * @param base the manifest of the merge ancestor
     * @return new path to the path in {@code base} it was copied or renamed from, ordered by new path
     */
    Map<String, String> trace(Map<String, String> side, Map<String, String> base) throws IOException {
        Map<String, String> copies = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : side.entrySet()) {
            if (base.containsKey(e.getKey())) {
                continue;
            }
            String source = origin(e.getKey(), NodeIdUtil.fromHex(e.getValue().substring(0, 40)), base);
            if (source != null) {
                copies.put(e.getKey(), source);
            }
        }
        return copies;
    }

    private Revlog filelog(String path) throws IOException {
        File index = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
        if (!index.exists()) {
            return null;
        }
        File data = new File(index.getPath().substring(0, index.getPath().length() - 2) + ".d");
        return repository.getRevlog(index, data);
    }

    private static boolean isBaseRevision(Map<String, String> base, String path, byte[] node) {
        String entry = base.get(path);
        return entry != null && Arrays.equals(NodeIdUtil.fromHex(entry.substring(0, 40)), node);
    }

    private String origin(String start, byte[] startNode, Map<String, String> base) throws IOException {
        Deque<Object[]> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(new Object[] {start, startNode});
        seen.add(start + "@" + NodeIdUtil.toHex(startNode));
        int visited = 0;
        while (!queue.isEmpty() && visited++ < MAX_VISITED) {
            Object[] current = queue.poll();
            String path = (String) current[0];
            byte[] node = (byte[]) current[1];
            Revlog filelog = filelog(path);
            if (filelog == null) {
                continue;
            }
            int rev = filelog.findRevision(node);
            if (rev < 0) {
                continue;
            }
            Revlog.IndexRecord record = filelog.getIndexRecord(rev);
            for (int parent : new int[] {record.getParent1(), record.getParent2()}) {
                if (parent < 0) {
                    continue;
                }
                byte[] parentNode = Arrays.copyOf(filelog.getIndexRecord(parent).getNodeId(), 20);
                if (isBaseRevision(base, path, parentNode) && !path.equals(start)) {
                    return path;
                }
                if (seen.add(path + "@" + NodeIdUtil.toHex(parentNode))) {
                    queue.add(new Object[] {path, parentNode});
                }
            }
            Map<String, String> meta = filelog.getRevisionMetadata(rev);
            if (meta != null && meta.get("copy") != null && meta.get("copyrev") != null) {
                String copyPath = meta.get("copy");
                byte[] copyNode = NodeIdUtil.fromHex(meta.get("copyrev"));
                if (isBaseRevision(base, copyPath, copyNode)) {
                    return copyPath;
                }
                if (seen.add(copyPath + "@" + meta.get("copyrev"))) {
                    queue.add(new Object[] {copyPath, copyNode});
                }
            }
        }
        return null;
    }
}
