package io.github.search5.hg4j.dirstate;

import java.io.UncheckedIOException;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * The entries of a dirstate: what is on disk, read in place from a {@link DirstateV2Tree} (nothing is loaded up front), and on top of that
 * the changes made since, which are all that the memory holds. Reading a path looks at the changes first, then at the tree. An entry that was
 * removed is remembered as removed even though the tree still has it.
 */
final class LazyEntryMap extends AbstractMap<String, Dirstate.Entry> {

    private DirstateV2Tree base;
    private final Map<String, Dirstate.Entry> overlay = new LinkedHashMap<>();
    private final Set<String> removed = new HashSet<>();

    /** Makes {@code tree} the content (nothing changed yet). */
    void attach(DirstateV2Tree tree) {
        this.base = tree;
        overlay.clear();
        removed.clear();
    }

    /** The paths changed since the content was attached: the entries to write when the dirstate is appended to. */
    Map<String, Dirstate.Entry> changed() {
        return overlay;
    }

    Set<String> removedPaths() {
        return removed;
    }

    DirstateV2Tree base() {
        return base;
    }

    private Dirstate.Entry fromBase(Object key) {
        if (base == null || !(key instanceof String path) || removed.contains(path)) {
            return null;
        }
        try {
            return base.get(path);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Dirstate.Entry get(Object key) {
        Dirstate.Entry changed = overlay.get(key);
        return changed != null ? changed : fromBase(key);
    }

    @Override
    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    @Override
    public Dirstate.Entry put(String path, Dirstate.Entry entry) {
        Dirstate.Entry old = get(path);
        overlay.put(path, entry);
        removed.remove(path);
        return old;
    }

    @Override
    public Dirstate.Entry remove(Object key) {
        Dirstate.Entry old = get(key);
        overlay.remove(key);
        if (old != null && base != null && key instanceof String path) {
            try {
                if (base.contains(path)) {
                    removed.add(path);
                }
            } catch (java.io.IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return old;
    }

    @Override
    public void clear() {
        base = null;
        overlay.clear();
        removed.clear();
    }

    @Override
    public int size() {
        int size = base == null ? 0 : base.entryCount() - removed.size();
        for (String path : overlay.keySet()) {
            try {
                if (base == null || removed.contains(path) || !base.contains(path)) {
                    size++;
                }
            } catch (java.io.IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return size;
    }

    @Override
    public Set<Map.Entry<String, Dirstate.Entry>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public Iterator<Map.Entry<String, Dirstate.Entry>> iterator() {
                return new MergedIterator();
            }

            @Override
            public int size() {
                return LazyEntryMap.this.size();
            }
        };
    }

    /** The tree's entries that were not changed or removed, then the changed ones. */
    private final class MergedIterator implements Iterator<Map.Entry<String, Dirstate.Entry>> {
        private final Iterator<Map.Entry<String, Dirstate.Entry>> fromBase = base == null ? java.util.Collections.emptyIterator() : base.entries();
        private final Iterator<Map.Entry<String, Dirstate.Entry>> fromOverlay = new java.util.ArrayList<>(overlay.entrySet()).iterator();
        private Map.Entry<String, Dirstate.Entry> pending;
        private Map.Entry<String, Dirstate.Entry> last;

        MergedIterator() {
            advance();
        }

        private void advance() {
            pending = null;
            while (pending == null && fromBase.hasNext()) {
                Map.Entry<String, Dirstate.Entry> e = fromBase.next();
                if (!removed.contains(e.getKey()) && !overlay.containsKey(e.getKey())) {
                    pending = e;
                }
            }
            if (pending == null && fromOverlay.hasNext()) {
                pending = fromOverlay.next();
            }
        }

        @Override
        public boolean hasNext() {
            return pending != null;
        }

        @Override
        public Map.Entry<String, Dirstate.Entry> next() {
            if (pending == null) {
                throw new NoSuchElementException();
            }
            Map.Entry<String, Dirstate.Entry> result = pending;
            last = result;
            advance();
            return new AbstractMap.SimpleEntry<>(result.getKey(), result.getValue()) {
                @Override
                public Dirstate.Entry setValue(Dirstate.Entry value) {
                    LazyEntryMap.this.put(getKey(), value);
                    return super.setValue(value);
                }
            };
        }

        @Override
        public void remove() {
            if (last == null) {
                throw new IllegalStateException();
            }
            LazyEntryMap.this.remove(last.getKey());
            last = null;
        }
    }
}
