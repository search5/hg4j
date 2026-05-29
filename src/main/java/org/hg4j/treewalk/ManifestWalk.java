package org.hg4j.treewalk;

import org.hg4j.core.HgRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ManifestWalk {

    private final ManifestTreeIterator iterator;
    private final List<Entry> cachedEntries = new ArrayList<>();
    private int cachedIndex = -1;

    public static class Entry {
        private final String path;
        private final byte[] nodeId;
        private final boolean executable;
        private final boolean symlink;

        public Entry(String path, byte[] nodeId, boolean executable, boolean symlink) {
            this.path = path;
            this.nodeId = nodeId;
            this.executable = executable;
            this.symlink = symlink;
        }

        public String getPath() {
            return path;
        }

        public byte[] getNodeId() {
            return nodeId;
        }

        public String getNodeIdHex() {
            return org.hg4j.core.NodeIdUtil.toHex(nodeId);
        }

        public boolean isExecutable() {
            return executable;
        }

        public boolean isSymlink() {
            return symlink;
        }
    }

    public ManifestWalk(HgRepository repository, String revision) {
        this.iterator = new ManifestTreeIterator(repository, revision);
    }

    public ManifestWalk(HgRepository repository, byte[] manifestNode) {
        this.iterator = new ManifestTreeIterator(repository, manifestNode);
    }

    public void reset() throws IOException {
        cachedIndex = -1;
    }

    private void loadAll() throws IOException {
        if (cachedEntries.isEmpty()) {
            iterator.reset();
            while (iterator.next()) {
                cachedEntries.add(new Entry(
                    iterator.getEntryPath(),
                    iterator.getEntryNodeId(),
                    iterator.isExecutable(),
                    iterator.isSymlink()
                ));
            }
        }
    }

    public boolean next() throws IOException {
        loadAll();
        if (cachedIndex < cachedEntries.size() - 1) {
            cachedIndex++;
            return true;
        }
        return false;
    }

    public Entry getEntry() {
        if (cachedIndex < 0 || cachedIndex >= cachedEntries.size()) {
            throw new java.util.NoSuchElementException("No current entry");
        }
        return cachedEntries.get(cachedIndex);
    }

    public List<Entry> getEntries() throws IOException {
        loadAll();
        return Collections.unmodifiableList(cachedEntries);
    }
}
