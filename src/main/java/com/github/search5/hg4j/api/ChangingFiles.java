package com.github.search5.hg4j.api;

import com.github.search5.hg4j.errors.HgCorruptDataException;
import com.github.search5.hg4j.util.NodeIdUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decoded form of one changeset revision's {@code SD_FILES} sidedata payload (real hg's {@code
 * exp-copies-sidedata-changeset} changelog sidedata; {@code mercurial/metadata.py}
 * {@code encode_files_sidedata()}/{@code decode_files_sidedata()} and its {@code ChangingFiles}
 * class): which paths this revision touched, how (added/removed/merged/salvaged/touched), and
 * which destinations were copied from which parent-relative source path.
 *
 * <p>This is the read side of committed history's copy metadata. {@link CopyCommand} is its
 * working-copy counterpart: {@code CopyCommand} records "what did the user just copy, not yet
 * committed" into the dirstate; this class instead answers "what does an already-committed
 * revision's own changelog record as copied" by decoding sidedata that was written once, at
 * commit time — no walk over ancestor revisions needed. See {@link SidedataChangedFilesCommand}
 * for how to obtain an instance from a repository.
 */
public final class ChangingFiles {
    // Bit layout of each SD_FILES per-file `flag` byte (mercurial/metadata.py, verified against
    // a real hg-generated fixture -- see
    // src/test/resources/fixtures/sidedata-copytracing/README.md).
    private static final int ACTION_MASK = 0b11100;
    private static final int ADDED_FLAG = 0b00100;
    private static final int MERGED_FLAG = 0b01000;
    private static final int REMOVED_FLAG = 0b01100;
    private static final int SALVAGED_FLAG = 0b10000;
    private static final int TOUCHED_FLAG = 0b10100;
    private static final int COPIED_MASK = 0b11;
    private static final int COPIED_FROM_P1_FLAG = 0b10;
    private static final int COPIED_FROM_P2_FLAG = 0b11;

    private static final ChangingFiles EMPTY = new ChangingFiles(
            Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
            Collections.emptySet(), Collections.emptySet(),
            Collections.emptyMap(), Collections.emptyMap());

    private final Set<String> touched;
    private final Set<String> added;
    private final Set<String> removed;
    private final Set<String> merged;
    private final Set<String> salvaged;
    private final Map<String, String> copiedFromP1;
    private final Map<String, String> copiedFromP2;

    private ChangingFiles(Set<String> touched, Set<String> added, Set<String> removed,
                           Set<String> merged, Set<String> salvaged,
                           Map<String, String> copiedFromP1, Map<String, String> copiedFromP2) {
        this.touched = touched;
        this.added = added;
        this.removed = removed;
        this.merged = merged;
        this.salvaged = salvaged;
        this.copiedFromP1 = copiedFromP1;
        this.copiedFromP2 = copiedFromP2;
    }

    /** No files recorded — either the revision has no {@code SD_FILES} sidedata at all. */
    public static ChangingFiles empty() {
        return EMPTY;
    }

    /**
     * Decodes a raw {@code SD_FILES} (sidedata key {@link
     * com.github.search5.hg4j.storage.SidedataCodec#SD_FILES}) payload, as returned by {@link
     * com.github.search5.hg4j.storage.Revlog#getSidedata(int)}.
     *
     * <p>Wire format (verified byte-for-byte against a real {@code hg}-generated fixture — see
     * {@code src/test/resources/fixtures/sidedata-copytracing/README.md}):
     * <pre>
     *   header:  totalFiles:uint32-be
     *   repeated `totalFiles` times: flag:signed-byte  fileEnd:uint32-be  copyIndex:uint32-be
     *   then, in that same order: the filenames' bytes, back to back — each file's slice runs
     *   from the previous file's end (or the start of the filename region for the first file) up
     *   to its own `fileEnd` (an offset relative to the start of the filename region)
     * </pre>
     * {@code copyIndex} is only meaningful when the entry's flag has a COPIED bit set; it then
     * indexes into this same file list (in encoding order, which is alphabetically sorted) to
     * name the copy source.
     *
     * @param raw the raw SD_FILES payload, or {@code null} if this revision had no such key
     *            (equivalent to {@link #empty()})
     */
    public static ChangingFiles decode(byte[] raw) throws HgCorruptDataException {
        if (raw == null || raw.length == 0) {
            return EMPTY;
        }
        if (raw.length < 4) {
            throw new HgCorruptDataException("Truncated SD_FILES payload: missing file count header");
        }
        ByteBuffer buf = ByteBuffer.wrap(raw);
        int totalFiles = buf.getInt();
        if (totalFiles < 0) {
            throw new HgCorruptDataException("Corrupt SD_FILES payload: negative file count");
        }

        final int entrySize = 1 + 4 + 4; // flag(byte) + fileEnd(uint32) + copyIndex(uint32)
        long entryTableBytes = (long) entrySize * totalFiles;
        int fileRegionStart = 4 + (int) entryTableBytes;
        if (raw.length < fileRegionStart) {
            throw new HgCorruptDataException("Truncated SD_FILES payload: entry table shorter than declared count " + totalFiles);
        }

        int[] flags = new int[totalFiles];
        int[] fileEndAbs = new int[totalFiles];
        int[] copyIndex = new int[totalFiles];
        for (int i = 0; i < totalFiles; i++) {
            flags[i] = buf.get();
            int fileEndRel = buf.getInt();
            copyIndex[i] = buf.getInt();
            fileEndAbs[i] = fileRegionStart + fileEndRel;
        }

        List<String> allFiles = new ArrayList<>(totalFiles);
        int prevEnd = fileRegionStart;
        for (int i = 0; i < totalFiles; i++) {
            int end = fileEndAbs[i];
            if (end < prevEnd || end > raw.length) {
                throw new HgCorruptDataException("Corrupt SD_FILES payload: filename bounds out of range for entry " + i);
            }
            allFiles.add(new String(raw, prevEnd, end - prevEnd, StandardCharsets.UTF_8));
            prevEnd = end;
        }

        Set<String> touched = new LinkedHashSet<>();
        Set<String> added = new LinkedHashSet<>();
        Set<String> removed = new LinkedHashSet<>();
        Set<String> merged = new LinkedHashSet<>();
        Set<String> salvaged = new LinkedHashSet<>();
        Map<String, String> copiedFromP1 = new LinkedHashMap<>();
        Map<String, String> copiedFromP2 = new LinkedHashMap<>();

        for (int i = 0; i < totalFiles; i++) {
            String name = allFiles.get(i);
            int action = flags[i] & ACTION_MASK;
            if (action == ADDED_FLAG) {
                added.add(name);
                touched.add(name);
            } else if (action == MERGED_FLAG) {
                merged.add(name);
                touched.add(name);
            } else if (action == REMOVED_FLAG) {
                removed.add(name);
                touched.add(name);
            } else if (action == SALVAGED_FLAG) {
                salvaged.add(name);
                touched.add(name);
            } else if (action == TOUCHED_FLAG) {
                touched.add(name);
            }
            // action == 0: untouched by this revision, present only as a copy source.

            int copied = flags[i] & COPIED_MASK;
            if (copied == COPIED_FROM_P1_FLAG) {
                copiedFromP1.put(name, allFiles.get(copyIndex[i]));
            } else if (copied == COPIED_FROM_P2_FLAG) {
                copiedFromP2.put(name, allFiles.get(copyIndex[i]));
            }
        }

        return new ChangingFiles(
                Collections.unmodifiableSet(touched),
                Collections.unmodifiableSet(added),
                Collections.unmodifiableSet(removed),
                Collections.unmodifiableSet(merged),
                Collections.unmodifiableSet(salvaged),
                Collections.unmodifiableMap(copiedFromP1),
                Collections.unmodifiableMap(copiedFromP2));
    }

    /** Every path this revision touched (added/removed/merged/salvaged/touched), NOT including untouched copy sources. */
    public Set<String> getTouched() { return touched; }
    public Set<String> getAdded() { return added; }
    public Set<String> getRemoved() { return removed; }
    public Set<String> getMerged() { return merged; }
    public Set<String> getSalvaged() { return salvaged; }
    /** Destination path -&gt; source path, for destinations copied from the first parent (p1). */
    public Map<String, String> getCopiedFromP1() { return copiedFromP1; }
    /** Destination path -&gt; source path, for destinations copied from the second parent (p2, merge only). */
    public Map<String, String> getCopiedFromP2() { return copiedFromP2; }

    /**
     * Where {@code path} was copied from in this revision, checking both parents, or {@code
     * null} if this revision does not record {@code path} as a copy destination. Matches real
     * hg's {@code hg debugchangedfiles}/{@code hg log --copies} output for a single revision.
     */
    public String getCopySource(String path) {
        String source = copiedFromP1.get(path);
        return source != null ? source : copiedFromP2.get(path);
    }

    /**
     * Encodes a raw {@code SD_FILES} payload (the inverse of {@link #decode}) for {@link
     * com.github.search5.hg4j.api.CommitCommand} to attach as changelog sidedata when the
     * repository has {@code exp-copies-sidedata-changeset}. Every set/map here uses
     * repo-root-relative paths.
     *
     * <p><b>Scope note (documented, not a correctness gap):</b> {@code merged}/{@code salvaged}
     * are real hg concepts specific to merge-state bookkeeping (a file resolved during a merge
     * whose content happens to exactly match one parent, vs. one explicitly kept despite the
     * other side wanting it removed) that {@code CommitCommand} does not currently track
     * separately from an ordinary content change -- callers pass empty sets for those two today.
     * A file that is genuinely new, removed, or content-modified (and not new) is still encoded
     * correctly via {@code added}/{@code removed}/{@code touched}, and copy-tracing (the
     * headline use case for this sidedata key, per {@code mercurial-spec-compliance-requirement.md}
     * backlog item 19) is fully supported regardless.
     *
     * @param added newly-added paths this revision.
     * @param removed paths removed this revision.
     * @param merged paths resolved via merge with content matching a parent exactly (see scope
     *                note above -- may be empty).
     * @param salvaged paths explicitly kept during a merge despite a delete on the other side
     *                  (see scope note above -- may be empty).
     * @param touched paths with a genuine content change that are not in {@code added} (must not
     *                 overlap {@code added}/{@code removed}/{@code merged}/{@code salvaged} --
     *                 each path gets exactly one action classification, matching real hg's own
     *                 mutually-exclusive {@code ACTION_MASK} bit values).
     * @param copiedFromP1 destination -&gt; source, for destinations copied from the first parent.
     * @param copiedFromP2 destination -&gt; source, for destinations copied from the second
     *                     parent (merge commits only).
     */
    public static byte[] encode(Set<String> added, Set<String> removed, Set<String> merged, Set<String> salvaged,
                                 Set<String> touched, Map<String, String> copiedFromP1, Map<String, String> copiedFromP2) {
        TreeSet<String> allFiles = new TreeSet<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        allFiles.addAll(added);
        allFiles.addAll(removed);
        allFiles.addAll(merged);
        allFiles.addAll(salvaged);
        allFiles.addAll(touched);
        allFiles.addAll(copiedFromP1.keySet());
        allFiles.addAll(copiedFromP1.values());
        allFiles.addAll(copiedFromP2.keySet());
        allFiles.addAll(copiedFromP2.values());
        if (allFiles.isEmpty()) {
            return new byte[0];
        }

        List<String> ordered = new ArrayList<>(allFiles);
        Map<String, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            indexOf.put(ordered.get(i), i);
        }

        int totalFiles = ordered.size();
        ByteBuffer entryTable = ByteBuffer.allocate(9 * totalFiles); // flag(1) + fileEnd(4) + copyIndex(4)
        ByteArrayOutputStream nameBytesOut = new ByteArrayOutputStream();
        int cumulative = 0;
        for (String name : ordered) {
            int flag;
            if (added.contains(name)) {
                flag = ADDED_FLAG;
            } else if (merged.contains(name)) {
                flag = MERGED_FLAG;
            } else if (removed.contains(name)) {
                flag = REMOVED_FLAG;
            } else if (salvaged.contains(name)) {
                flag = SALVAGED_FLAG;
            } else if (touched.contains(name)) {
                flag = TOUCHED_FLAG;
            } else {
                flag = 0; // untouched, present only as a copy source
            }

            int copyIndex = 0;
            String p1Source = copiedFromP1.get(name);
            String p2Source = copiedFromP2.get(name);
            if (p1Source != null) {
                flag |= COPIED_FROM_P1_FLAG;
                copyIndex = indexOf.get(p1Source);
            } else if (p2Source != null) {
                flag |= COPIED_FROM_P2_FLAG;
                copyIndex = indexOf.get(p2Source);
            }

            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            cumulative += nameBytes.length;
            entryTable.put((byte) flag);
            entryTable.putInt(cumulative);
            entryTable.putInt(copyIndex);
            try {
                nameBytesOut.write(nameBytes);
            } catch (IOException e) {
                throw new IllegalStateException("ByteArrayOutputStream never throws", e);
            }
        }

        ByteBuffer out = ByteBuffer.allocate(4 + entryTable.capacity() + nameBytesOut.size());
        out.putInt(totalFiles);
        out.put(entryTable.array());
        out.put(nameBytesOut.toByteArray());
        return out.array();
    }
}
