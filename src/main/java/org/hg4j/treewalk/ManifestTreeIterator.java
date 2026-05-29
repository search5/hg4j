package org.hg4j.treewalk;

import org.hg4j.core.HgRepository;
import org.hg4j.core.NodeIdUtil;
import org.hg4j.core.Revlog;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * TreeIterator implementation that traverses historical repository manifests.
 */
public class ManifestTreeIterator implements TreeIterator {

    private final HgRepository repository;
    private final String revision;
    private final List<Entry> entries = new ArrayList<>();
    private int index = -1;

    private static class Entry {
        final String path;
        final byte[] nodeId;
        final boolean executable;

        Entry(String path, byte[] nodeId, boolean executable) {
            this.path = path;
            this.nodeId = nodeId;
            this.executable = executable;
        }
    }

    public ManifestTreeIterator(HgRepository repository, String revision) {
        this.repository = repository;
        this.revision = revision;
    }

    @Override
    public void reset() throws IOException {
        entries.clear();
        index = -1;
        loadEntries();
    }

    private void loadEntries() throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        byte[] targetNodeId = NodeIdUtil.resolveRevision(changelog, revision);
        if (targetNodeId == null) {
            return;
        }

        java.util.Map<String, String> manifestMap = repository.getManifestAtCommit(targetNodeId);
        for (java.util.Map.Entry<String, String> entry : manifestMap.entrySet()) {
            String path = entry.getKey();
            String nodeWithFlags = entry.getValue();
            String hex = nodeWithFlags.substring(0, 40);
            boolean executable = nodeWithFlags.length() > 40 && nodeWithFlags.substring(40).contains("x");
            entries.add(new Entry(path, NodeIdUtil.fromHex(hex), executable));
        }
        
        entries.sort((e1, e2) -> NodeIdUtil.UTF8_STRING_COMPARATOR.compare(e1.path, e2.path));
    }



    @Override
    public String getEntryPath() {
        if (index >= 0 && index < entries.size()) {
            return entries.get(index).path;
        }
        return null;
    }

    @Override
    public byte[] getEntryNodeId() {
        if (index >= 0 && index < entries.size()) {
            return entries.get(index).nodeId;
        }
        return null;
    }

    @Override
    public boolean isExecutable() {
        if (index >= 0 && index < entries.size()) {
            return entries.get(index).executable;
        }
        return false;
    }

    @Override
    public char getEntryState() {
        // Manifest is tracked, so normally active 'n'
        return 'n';
    }

    @Override
    public boolean next() throws IOException {
        if (index < entries.size() - 1) {
            index++;
            return true;
        }
        index = entries.size(); // mark as finished
        return false;
    }
}
