package com.github.search5.hg4j.treewalk;

import com.github.search5.hg4j.lib.HgRepository;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.AbstractList;
import java.util.Iterator;

public class WorkingDirWalk {

    private final WorkingDirTreeIterator iterator;
    private final HgRepository repository;
    private final List<Entry> cachedEntries = new ArrayList<>();
    private int cachedIndex = -1;

    public static class Entry {
        private final String path;
        private final File file;
        private final char state;
        private final boolean executable;
        private final long size;
        private final long lastModified;

        public Entry(String path, File file, char state, boolean executable, long size, long lastModified) {
            this.path = path;
            this.file = file;
            this.state = state;
            this.executable = executable;
            this.size = size;
            this.lastModified = lastModified;
        }

        public String getPath() {
            return path;
        }

        public File getFile() {
            return file;
        }

        public char getState() {
            return state;
        }

        public boolean isExecutable() {
            return executable;
        }

        public long getSize() {
            return size;
        }

        public long getLastModified() {
            return lastModified;
        }
    }

    public WorkingDirWalk(HgRepository repository) {
        this.repository = repository;
        this.iterator = new WorkingDirTreeIterator(repository);
    }

    public void reset() throws IOException {
        cachedIndex = -1;
    }

    private void loadAll() throws IOException {
        if (cachedEntries.isEmpty()) {
            iterator.reset();
            while (iterator.next()) {
                String path = iterator.getEntryPath();
                File diskFile = new File(repository.getDirectory(), path);
                cachedEntries.add(new Entry(
                    path,
                    diskFile,
                    iterator.getEntryState(),
                    iterator.isExecutable(),
                    diskFile.exists() ? diskFile.length() : 0,
                    diskFile.exists() ? diskFile.lastModified() / 1000 : 0
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
            private final WorkingDirTreeIterator it = iterator;
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
                String path = it.getEntryPath();
                File diskFile = new File(repository.getDirectory(), path);
                return new Entry(
                    path,
                    diskFile,
                    it.getEntryState(),
                    it.isExecutable(),
                    diskFile.exists() ? diskFile.length() : 0,
                    diskFile.exists() ? diskFile.lastModified() / 1000 : 0
                );
            }
        };
    }
}
