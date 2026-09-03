package io.github.search5.hg4j.merge;

import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Reads and writes {@code .hg/merge/state2} — the on-disk record of an in-progress (possibly
 * conflicted) merge, as produced/consumed by real Mercurial's {@code mergestate._readrecordsv2}/
 * {@code _writerecordsv2} (mercurial/mergestate.py). This lets a conflicted merge started by hg4j
 * be inspected/resolved by real {@code hg resolve}, and vice versa.
 *
 * <p>On-disk record framing: a flat sequence of {@code [1-byte type][4-byte big-endian unsigned
 * length][content]} records. Only {@code L} (local node), {@code O} (other node) and {@code F}
 * (merged-file entry) are understood by every v2 reader; any other type is wrapped as
 * {@code t <original-type-byte> <original content>} so that old clients treat it as an opaque
 * advisory record instead of aborting (real hg's {@code RECORD_OVERRIDE} mechanism).</p>
 *
 * <p>The null hex used for "no file" is Mercurial's null node (40 hex zeros), matching
 * {@code repo.nodeconstants.nullhex}.</p>
 */
public final class MergeState {
    private static final char RECORD_LOCAL = 'L';
    private static final char RECORD_OTHER = 'O';
    private static final char RECORD_LABELS = 'l';
    private static final char RECORD_FILE_VALUES = 'f';
    private static final char RECORD_MERGED = 'F';
    private static final char RECORD_CHANGEDELETE_CONFLICT = 'C';
    private static final char RECORD_PATH_CONFLICT = 'P';
    private static final char RECORD_OVERRIDE = 't';

    public static final String UNRESOLVED = "u";
    public static final String RESOLVED = "r";
    public static final String UNRESOLVED_PATH = "pu";
    public static final String RESOLVED_PATH = "pr";

    public static final String NULL_HEX = "0000000000000000000000000000000000000000";

    /** Local parent's changeset node (dirstate p1 at merge start). */
    public byte[] local;
    /** Other (merged-in) changeset node (dirstate p2 at merge start). */
    public byte[] other;

    /**
     * path -&gt; ordered field list, mirroring real hg's {@code self._state[path]}: for a normal
     * file conflict (record kind {@code F}/{@code C}) this is {@code [state, localkey, lfile,
     * afile, anode, ofile, onode, flags]}; for a path conflict (kind {@code P}) it is
     * {@code [state, frename, forigin]}. The concrete on-disk record type is derived from the
     * content on write, exactly like real hg's {@code _makerecords} — it is never stored
     * separately.
     */
    public final Map<String, List<String>> state = new LinkedHashMap<>();

    /** path -&gt; ordered extra key/value pairs (e.g. {@code ancestorlinknode}). */
    public final Map<String, Map<String, String>> stateExtras = new LinkedHashMap<>();

    /** Optional merge tool labels (local/other/base), written verbatim if non-empty. */
    public final List<String> labels = new ArrayList<>();

    public void addMergedFile(String path, String localKey, String localFile, String ancestorFile,
                               String ancestorNodeHex, String otherFile, String otherNodeHex, String flags) {
        List<String> fields = new ArrayList<>();
        fields.add(UNRESOLVED);
        fields.add(localKey);
        fields.add(localFile);
        fields.add(ancestorFile);
        fields.add(ancestorNodeHex);
        fields.add(otherFile);
        fields.add(otherNodeHex);
        fields.add(flags == null ? "" : flags);
        state.put(path, fields);
    }

    public void markResolved(String path) {
        setResolutionState(path, true);
    }

    public void markUnresolved(String path) {
        setResolutionState(path, false);
    }

    private void setResolutionState(String path, boolean resolved) {
        List<String> fields = state.get(path);
        if (fields == null || fields.isEmpty()) {
            return;
        }
        List<String> updated = new ArrayList<>(fields);
        boolean isPathConflict = UNRESOLVED_PATH.equals(fields.get(0)) || RESOLVED_PATH.equals(fields.get(0));
        updated.set(0, isPathConflict ? (resolved ? RESOLVED_PATH : UNRESOLVED_PATH) : (resolved ? RESOLVED : UNRESOLVED));
        state.put(path, updated);
    }

    /** Whether {@code path} is tracked as part of this merge at all (resolved or not). */
    public boolean hasFile(String path) {
        return state.containsKey(path);
    }

    /** Whether this represents an actual in-progress merge (a {@code state2} file existed). */
    public boolean isActive() {
        return local != null && other != null;
    }

    public boolean isEmpty() {
        return state.isEmpty();
    }

    public List<String> unresolvedFiles() {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : state.entrySet()) {
            List<String> fields = e.getValue();
            if (!fields.isEmpty() && (UNRESOLVED.equals(fields.get(0)) || UNRESOLVED_PATH.equals(fields.get(0)))) {
                result.add(e.getKey());
            }
        }
        return result;
    }

    /**
     * Hashes a working-copy file path the same way real hg does for its {@code .hg/merge/<key>}
     * pre-merge local-content backup files.
     */
    public static String getLocalKey(String path) {
        return NodeIdUtil.toHex(sha1(path.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static MergeState read(File stateFile) throws IOException {
        MergeState ms = new MergeState();
        if (stateFile == null || !stateFile.exists()) {
            return ms;
        }
        byte[] data = Files.readAllBytes(stateFile.toPath());
        int off = 0;
        int end = data.length;
        while (off < end) {
            char rtype = (char) (data[off] & 0xFF);
            off += 1;
            long length = ((long) (data[off] & 0xFF) << 24) | ((data[off + 1] & 0xFF) << 16)
                    | ((data[off + 2] & 0xFF) << 8) | (data[off + 3] & 0xFF);
            off += 4;
            byte[] record = new byte[(int) length];
            System.arraycopy(data, off, record, 0, (int) length);
            off += (int) length;

            if (rtype == RECORD_OVERRIDE) {
                rtype = (char) (record[0] & 0xFF);
                byte[] unwrapped = new byte[record.length - 1];
                System.arraycopy(record, 1, unwrapped, 0, unwrapped.length);
                record = unwrapped;
            }

            if (rtype == RECORD_LOCAL) {
                ms.local = NodeIdUtil.fromHex(new String(record, StandardCharsets.US_ASCII));
            } else if (rtype == RECORD_OTHER) {
                ms.other = NodeIdUtil.fromHex(new String(record, StandardCharsets.US_ASCII));
            } else if (rtype == RECORD_MERGED || rtype == RECORD_CHANGEDELETE_CONFLICT || rtype == RECORD_PATH_CONFLICT) {
                List<byte[]> parts = splitNul(record);
                String path = new String(parts.get(0), StandardCharsets.UTF_8);
                List<String> fields = new ArrayList<>();
                for (int i = 1; i < parts.size(); i++) {
                    fields.add(new String(parts.get(i), StandardCharsets.UTF_8));
                }
                ms.state.put(path, fields);
            } else if (rtype == RECORD_FILE_VALUES) {
                List<byte[]> parts = splitNul(record);
                String filename = new String(parts.get(0), StandardCharsets.UTF_8);
                Map<String, String> extras = new LinkedHashMap<>();
                for (int i = 1; i + 1 < parts.size(); i += 2) {
                    extras.put(new String(parts.get(i), StandardCharsets.UTF_8),
                            new String(parts.get(i + 1), StandardCharsets.UTF_8));
                }
                ms.stateExtras.put(filename, extras);
            } else if (rtype == RECORD_LABELS) {
                List<byte[]> parts = splitNul(record);
                for (byte[] p : parts) {
                    if (p.length > 0) {
                        ms.labels.add(new String(p, StandardCharsets.UTF_8));
                    }
                }
            }
            // 그 외 대문자(필수) 레코드 타입은 실제 hg라면 UnsupportedMergeRecords로 중단하지만,
            // 여기서는 알려지지 않은 레코드를 조용히 건너뛴다 — 이 클래스가 아직 다루지 않는
            // 레코드 종류(예: 레거시 머지 드라이버) 때문에 정상 파일 파싱이 막히지 않도록 한다.
        }
        return ms;
    }

    public void write(File stateFile) throws IOException {
        if (local == null || other == null) {
            throw new IllegalStateException("MergeState.local and .other must be set before writing");
        }
        List<Object[]> records = new ArrayList<>();
        records.add(new Object[]{RECORD_LOCAL, NodeIdUtil.toHex(local).getBytes(StandardCharsets.US_ASCII)});
        records.add(new Object[]{RECORD_OTHER, NodeIdUtil.toHex(other).getBytes(StandardCharsets.US_ASCII)});

        for (Map.Entry<String, List<String>> e : state.entrySet()) {
            List<String> fields = e.getValue();
            char kind = deriveKind(fields);
            List<String> parts = new ArrayList<>();
            parts.add(e.getKey());
            parts.addAll(fields);
            records.add(new Object[]{kind, joinNul(parts)});
        }

        for (Map.Entry<String, Map<String, String>> e : stateExtras.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            List<String> parts = new ArrayList<>();
            parts.add(e.getKey());
            for (Map.Entry<String, String> kv : e.getValue().entrySet()) {
                parts.add(kv.getKey());
                parts.add(kv.getValue());
            }
            records.add(new Object[]{RECORD_FILE_VALUES, joinNul(parts)});
        }

        if (!labels.isEmpty()) {
            records.add(new Object[]{RECORD_LABELS, joinNul(labels)});
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Object[] rec : records) {
            char type = (Character) rec[0];
            byte[] data = (byte[]) rec[1];
            if (type != RECORD_LOCAL && type != RECORD_OTHER && type != RECORD_MERGED) {
                byte[] wrapped = new byte[data.length + 1];
                wrapped[0] = (byte) type;
                System.arraycopy(data, 0, wrapped, 1, data.length);
                type = RECORD_OVERRIDE;
                data = wrapped;
            }
            out.write((byte) type);
            out.write((data.length >>> 24) & 0xFF);
            out.write((data.length >>> 16) & 0xFF);
            out.write((data.length >>> 8) & 0xFF);
            out.write(data.length & 0xFF);
            out.write(data);
        }

        stateFile.getParentFile().mkdirs();
        Files.write(stateFile.toPath(), out.toByteArray());
    }

    public static void clean(File stateFile) throws IOException {
        Files.deleteIfExists(stateFile.toPath());
    }

    /** Mirrors real hg's {@code _makerecords} record-kind inference from field content. */
    private static char deriveKind(List<String> fields) {
        if (!fields.isEmpty() && (UNRESOLVED_PATH.equals(fields.get(0)) || RESOLVED_PATH.equals(fields.get(0)))) {
            return RECORD_PATH_CONFLICT;
        }
        if (fields.size() >= 7 && (NULL_HEX.equals(fields.get(1)) || NULL_HEX.equals(fields.get(6)))) {
            return RECORD_CHANGEDELETE_CONFLICT;
        }
        return RECORD_MERGED;
    }

    private static List<byte[]> splitNul(byte[] data) {
        List<byte[]> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == 0) {
                byte[] seg = new byte[i - start];
                System.arraycopy(data, start, seg, 0, seg.length);
                parts.add(seg);
                start = i + 1;
            }
        }
        byte[] last = new byte[data.length - start];
        System.arraycopy(data, start, last, 0, last.length);
        parts.add(last);
        return parts;
    }

    private static byte[] joinNul(List<String> parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                out.write(0);
            }
            out.write(parts.get(i).getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }
}
