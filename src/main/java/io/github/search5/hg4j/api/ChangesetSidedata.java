package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.storage.SidedataCodec;
import io.github.search5.hg4j.treewalk.ManifestWalk;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The changed-files sidedata of the changesets a changegroup brought into a repository that keeps it ({@code exp-copies-sidedata-changeset}).
 * The changegroups hg4j applies carry no such sidedata, and the original computes it when it adds the changesets
 * ({@code sidedata.get_sidedata_adder}); here the changesets are added first and their sidedata is computed from the manifests afterwards, which
 * only exist then, so the tail of the changelog is written again, this time with the sidedata. The nodes do not change: the sidedata is not part
 * of the hash. Copy information is not derived (the changegroup does not say which files were copied).
 */
final class ChangesetSidedata {

    private ChangesetSidedata() {
    }

    /** Rewrites the changelog revisions from {@code firstRev} on with their changed-files sidedata. */
    static void rewriteTail(HgRepository repository, Revlog changelog, int firstRev) throws IOException {
        int count = changelog.getRevisionCount();
        if (firstRev >= count) {
            return;
        }
        List<byte[]> texts = new ArrayList<>();
        List<byte[]> sidedata = new ArrayList<>();
        List<Revlog.IndexRecord> records = new ArrayList<>();
        Map<Integer, Map<String, String>> manifests = new LinkedHashMap<>();
        for (int rev = firstRev; rev < count; rev++) {
            Revlog.IndexRecord record = changelog.getIndexRecord(rev);
            records.add(record);
            texts.add(changelog.getRevisionContent(rev));
            Map<String, String> now = manifestOf(repository, rev, manifests);
            Map<String, String> before = record.getParent1() < 0 ? Map.of() : manifestOf(repository, record.getParent1(), manifests);
            Set<String> added = new LinkedHashSet<>();
            Set<String> removed = new LinkedHashSet<>();
            Set<String> touched = new LinkedHashSet<>();
            for (Map.Entry<String, String> file : now.entrySet()) {
                String old = before.get(file.getKey());
                if (old == null) {
                    added.add(file.getKey());
                } else if (!old.equals(file.getValue())) {
                    touched.add(file.getKey());
                }
            }
            for (String path : before.keySet()) {
                if (!now.containsKey(path)) {
                    removed.add(path);
                }
            }
            byte[] encoded = ChangingFiles.encode(added, removed, Set.of(), Set.of(), touched, Map.of(), Map.of());
            sidedata.add(encoded.length == 0 ? null : SidedataCodec.serialize(Map.of(SidedataCodec.SD_FILES, encoded)));
        }
        changelog.truncate(firstRev);
        repository.clearRevlogCache();
        for (int i = 0; i < texts.size(); i++) {
            Revlog.IndexRecord record = records.get(i);
            byte[] p1Node = record.getParent1() < 0 ? new byte[20] : changelog.getIndexRecord(record.getParent1()).getNodeId();
            byte[] p2Node = record.getParent2() < 0 ? new byte[20] : changelog.getIndexRecord(record.getParent2()).getNodeId();
            changelog.appendRevision(texts.get(i), (Map<String, String>) null, record.getParent1(), record.getParent2(), p1Node, p2Node,
                    firstRev + i, sidedata.get(i));
        }
    }

    private static Map<String, String> manifestOf(HgRepository repository, int rev, Map<Integer, Map<String, String>> cache)
            throws IOException {
        Map<String, String> known = cache.get(rev);
        if (known != null) {
            return known;
        }
        Map<String, String> files = new TreeMap<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        ManifestWalk walk = new ManifestWalk(repository, String.valueOf(rev));
        while (walk.next()) {
            ManifestWalk.Entry entry = walk.getEntry();
            files.put(entry.getPath(), entry.getNodeIdHex() + (entry.isExecutable() ? "x" : entry.isSymlink() ? "l" : ""));
        }
        cache.put(rev, files);
        return files;
    }
}
