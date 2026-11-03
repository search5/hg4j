package io.github.search5.hg4j.lfs;

import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.treewalk.ManifestWalk;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Which files of a changeset are stored in LFS. This is what real hg's {@code lfs()} fileset ({@link #inManifest}) and
 * {@code lfs_files} template keyword ({@link #changedBy}) compute from {@code pointerfromctx} and {@code pointersfromctx};
 * hg4j has no fileset or template language to attach them to, so they are offered as a plain API.
 */
public final class HgLfsFiles {

    private HgLfsFiles() {
    }

    /**
     * The LFS files among those a changeset modified, added or removed ({@code lfs_files}): path to pointer. With
     * {@code includeRemoved}, an LFS file the changeset removed is listed with a {@code null} pointer (the original shows an empty
     * dict for it).
     */
    public static SortedMap<String, HgLfsPointer> changedBy(HgRepository repository, int changesetRev, boolean includeRemoved)
            throws IOException {
        Revlog changelog = changelog(repository);
        String[] lines = new String(changelog.getRevisionContent(changesetRev), StandardCharsets.UTF_8).split("\n");
        Map<String, String> own = manifestOf(repository, changelog, changesetRev);
        Revlog.IndexRecord record = changelog.getIndexRecord(changesetRev);
        Map<String, String> parent1 = record.getParent1() >= 0 ? manifestOf(repository, changelog, record.getParent1()) : Map.of();
        Map<String, String> parent2 = record.getParent2() >= 0 ? manifestOf(repository, changelog, record.getParent2()) : Map.of();

        SortedMap<String, HgLfsPointer> result = new TreeMap<>();
        for (int i = 3; i < lines.length; i++) {
            String file = lines[i].trim();
            if (file.isEmpty()) {
                break;
            }
            if (own.containsKey(file)) {
                HgLfsPointer pointer = pointerAt(repository, file, own.get(file));
                if (pointer != null) {
                    result.put(file, pointer);
                }
            } else if (includeRemoved) {
                String node = parent1.containsKey(file) ? parent1.get(file) : parent2.get(file);
                if (node != null && pointerAt(repository, file, node) != null) {
                    result.put(file, null);
                }
            }
        }
        return result;
    }

    /** Every file of the changeset's manifest that is stored in LFS ({@code lfs()}): path to pointer. */
    public static SortedMap<String, HgLfsPointer> inManifest(HgRepository repository, int changesetRev) throws IOException {
        SortedMap<String, HgLfsPointer> result = new TreeMap<>();
        for (Map.Entry<String, String> entry : manifestOf(repository, changelog(repository), changesetRev).entrySet()) {
            HgLfsPointer pointer = pointerAt(repository, entry.getKey(), entry.getValue());
            if (pointer != null) {
                result.put(entry.getKey(), pointer);
            }
        }
        return result;
    }

    private static Revlog changelog(HgRepository repository) throws IOException {
        return repository.getRevlog(new File(repository.getStoreDir(), "00changelog.i"),
                new File(repository.getStoreDir(), "00changelog.d"));
    }

    /** path to the node of the file in the manifest of {@code changesetRev}. */
    private static Map<String, String> manifestOf(HgRepository repository, Revlog changelog, int changesetRev) throws IOException {
        Map<String, String> entries = new HashMap<>();
        ManifestWalk walk = new ManifestWalk(repository, String.valueOf(changesetRev));
        while (walk.next()) {
            ManifestWalk.Entry entry = walk.getEntry();
            entries.put(entry.getPath(), NodeIdUtil.toHex(entry.getNodeId()));
        }
        return entries;
    }

    private static HgLfsPointer pointerAt(HgRepository repository, String path, String nodeHex) throws IOException {
        File index = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
        if (!index.exists()) {
            return null;
        }
        Revlog filelog = repository.getRevlog(index, new File(index.getPath().substring(0, index.getPath().length() - 2) + ".d"));
        int rev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(nodeHex.substring(0, 40)));
        if (rev == -1 || !filelog.isExtStored(rev)) {
            return null;
        }
        return HgLfsPointer.parse(filelog.getRawRevisionContent(rev));
    }
}
