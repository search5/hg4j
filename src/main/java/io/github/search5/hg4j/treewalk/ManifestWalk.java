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

/**
 * Walks the files recorded in one manifest, in ascending UTF-8 path order. Treemanifest subdirectories
 * are expanded, so only file entries are returned.
 * <p>Two access styles are offered: a cursor ({@link #next()} and {@link #getEntry()}) over entries cached
 * on first use, and a lazily streaming view ({@link #lazyEntries()}, {@link #getEntries()}). Both share one
 * underlying reader, so do not interleave them. Instances are not thread-safe.
 */
public class ManifestWalk {

    private final ManifestTreeIterator iterator;
    private final List<Entry> cachedEntries = new ArrayList<>();
    private int cachedIndex = -1;

    /**
     * Immutable description of a single file in a manifest.
     */
    public static class Entry {
        private final String path;
        private final byte[] nodeId;
        private final boolean executable;
        private final boolean symlink;

        /**
         * Creates an entry. The node hash array is stored as given, not copied.
         * @param path the repository-relative path using {@code /} separators
         * @param nodeId the 20-byte node hash of the file revision
         * @param executable whether the executable flag is set
         * @param symlink whether the symlink flag is set
         */
        public Entry(String path, byte[] nodeId, boolean executable, boolean symlink) {
            this.path = path;
            this.nodeId = nodeId;
            this.executable = executable;
            this.symlink = symlink;
        }

        /**
         * Returns the repository-relative path using {@code /} separators.
         */
        public String getPath() {
            return path;
        }

        /**
         * Returns the 20-byte node hash of the file revision. The internal array is returned, not a copy.
         */
        public byte[] getNodeId() {
            return nodeId;
        }

        /**
         * Returns the node hash as a lowercase 40-character hex string.
         */
        public String getNodeIdHex() {
            return NodeIdUtil.toHex(nodeId);
        }

        /**
         * Returns whether the executable flag ({@code x}) is set.
         */
        public boolean isExecutable() {
            return executable;
        }

        /**
         * Returns whether the symlink flag ({@code l}) is set.
         */
        public boolean isSymlink() {
            return symlink;
        }
    }

    /**
     * Creates a walk over the manifest of the given changeset. No I/O happens until the first traversal.
     * @param repository the repository to read from; must not be null
     * @param revision a changeset identifier resolved against the changelog; {@code null}, an empty string,
     *        {@code "-1"} or {@code "null"} yield an empty walk
     */
    public ManifestWalk(HgRepository repository, String revision) {
        this.iterator = new ManifestTreeIterator(repository, revision);
    }

    /**
     * Creates a walk over the manifest identified by its manifest node hash. No I/O happens until the
     * first traversal.
     * @param repository the repository to read from; must not be null
     * @param manifestNode the 20-byte node hash of the manifest revision (not of the changeset)
     */
    public ManifestWalk(HgRepository repository, byte[] manifestNode) {
        this.iterator = new ManifestTreeIterator(repository, manifestNode);
    }

    /**
     * Rewinds the cursor used by {@link #next()} so that the next call returns the first entry again.
     * The entries already loaded are kept and not re-read.
     */
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

    /**
     * Advances the cursor to the next entry. The first call loads the whole manifest into memory.
     * @return true if the cursor now points at an entry, false if there are no more entries
     * @throws IOException if the manifest or changelog cannot be read; this includes
     *         {@link io.github.search5.hg4j.errors.HgRevisionNotFoundException} when the revision or manifest
     *         does not exist
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
        if (cachedIndex < 0) {
            throw new NoSuchElementException("No current entry");
        }
        return cachedEntries.get(cachedIndex);
    }

    /**
     * Returns a read-only list view of all entries in path order, filled lazily from {@link #lazyEntries()}
     * as it is accessed. Calling {@code size()} reads the whole manifest.
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
