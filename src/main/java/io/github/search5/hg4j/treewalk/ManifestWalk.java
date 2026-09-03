package io.github.search5.hg4j.treewalk;

import io.github.search5.hg4j.lib.HgRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import io.github.search5.hg4j.util.NodeIdUtil;
import java.util.AbstractList;
import java.util.Iterator;

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
            return NodeIdUtil.toHex(nodeId);
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
        if (cachedIndex < 0) {
            throw new NoSuchElementException("No current entry");
        }
        return cachedEntries.get(cachedIndex);
    }

    public List<Entry> getEntries() throws IOException {
        return new AbstractList<Entry>() {
            private final List<Entry> cache = new ArrayList<>();
            private final Iterator<Entry> it = lazyEntries();

            @Override
            public Entry get(int index) {
                while (cache.size() <= index && it.hasNext()) {
                    cache.add(it.next());
                }
                if (index < cache.size()) {
                    return cache.get(index);
                }
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size());
            }

            @Override
            public int size() {
                while (it.hasNext()) {
                    cache.add(it.next());
                }
                return cache.size();
            }

            @Override
            public Iterator<Entry> iterator() {
                return lazyEntries();
            }
        };
    }

    /**
     * JGit TreeWalk 스타일의 lazy streaming 탐색을 제공합니다.
     * 메모리를 선적재하지 않고, 필요한 요소를 순차적으로 스트리밍합니다 (힙 압박 해결).
     */
    public Iterator<Entry> lazyEntries() {
        return new Iterator<Entry>() {
            private final ManifestTreeIterator it = iterator;
            private boolean initialized = false;

            private void init() {
                if (!initialized) {
                    try {
                        it.reset();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    initialized = true;
                }
            }

            @Override
            public boolean hasNext() {
                init();
                try {
                    return it.next();
                } catch (IOException e) {
                    return false;
                }
            }

            @Override
            public Entry next() {
                init();
                return new Entry(
                    it.getEntryPath(),
                    it.getEntryNodeId(),
                    it.isExecutable(),
                    it.isSymlink()
                );
            }
        };
    }
}
