package io.github.search5.hg4j.treewalk;

import io.github.search5.hg4j.lib.HgRepository;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.AbstractList;
import java.util.Iterator;

/**
 * Walks the working copy: every file on disk and every path in the dirstate, in ascending UTF-8 path order,
 * with size and modification time read from disk.
 * <p>Like {@link ManifestWalk}, it offers a cursor over cached entries and a lazy streaming view that share
 * one underlying reader, so do not interleave them. Instances are not thread-safe.
 */
public class WorkingDirWalk {

    private final WorkingDirTreeIterator iterator;
    private final HgRepository repository;
    private final List<Entry> cachedEntries = new ArrayList<>();
    private int cachedIndex = -1;

    /**
     * Immutable description of one working copy path.
     */
    public static class Entry {
        private final String path;
        private final File file;
        private final char state;
        private final boolean executable;
        private final long size;
        private final long lastModified;

        /**
         * Creates an entry.
         * @param path the repository-relative path using {@code /} separators
         * @param file the file on disk
         * @param state the dirstate state character, or {@code '?'} for an untracked file
         * @param executable whether the file is executable on disk
         * @param size the file length in bytes, 0 if the file does not exist
         * @param lastModified the modification time in whole seconds since the epoch, 0 if the file does not exist
         */
        public Entry(String path, File file, char state, boolean executable, long size, long lastModified) {
            this.path = path;
            this.file = file;
            this.state = state;
            this.executable = executable;
            this.size = size;
            this.lastModified = lastModified;
        }

        /**
         * Returns the repository-relative path using {@code /} separators.
         */
        public String getPath() {
            return path;
        }

        /**
         * Returns the file on disk (which may not exist, for example for a removed path).
         */
        public File getFile() {
            return file;
        }

        /**
         * Returns the dirstate state character, or {@code '?'} for an untracked file.
         */
        public char getState() {
            return state;
        }

        /**
         * Returns whether the file is executable on disk.
         */
        public boolean isExecutable() {
            return executable;
        }

        /**
         * Returns the file length in bytes, 0 if the file does not exist.
         */
        public long getSize() {
            return size;
        }

        /**
         * Returns the modification time in whole seconds since the epoch, 0 if the file does not exist.
         */
        public long getLastModified() {
            return lastModified;
        }
    }

    /**
     * Creates a walk over the working copy of the repository. No I/O happens until the first traversal.
     * @param repository the repository whose working copy is scanned; must not be null
     */
    public WorkingDirWalk(HgRepository repository) {
        this.repository = repository;
        this.iterator = new WorkingDirTreeIterator(repository);
    }

    /**
     * Rewinds the cursor used by {@link #next()} so that the next call returns the first entry again.
     * The entries already loaded are kept and not re-scanned.
     */
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

    /**
     * Advances the cursor to the next entry. The first call scans the working copy and caches all entries.
     * @return true if the cursor now points at an entry, false if there are no more entries
     * @throws IOException if the working copy or dirstate cannot be read
     */
    public boolean next() throws IOException {
        loadAll();
        if (cachedIndex < cachedEntries.size() - 1) {
            cachedIndex++;
            return true;
        }
        return false;
    }

    /**
     * Returns the entry the cursor currently points at.
     * @return the current entry
     * @throws NoSuchElementException if {@link #next()} has not been called since construction or the last
     *         {@link #reset()}
     */
    public Entry getEntry() {
        if (cachedIndex < 0 || cachedIndex >= cachedEntries.size()) {
            throw new NoSuchElementException("No current entry");
        }
        return cachedEntries.get(cachedIndex);
    }

    /**
     * Returns a read-only list view of all entries in path order, filled lazily from {@link #lazyEntries()}
     * as it is accessed. Calling {@code size()} scans the whole working copy.
     * <p>The list drives the shared underlying reader, so do not use it together with {@link #next()}.
     * Read errors surfaced while populating the list are treated as the end of the data.
     * @return a new list view; never null
     * @throws IOException declared for I/O failures; the view itself performs its reads lazily
     */
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
