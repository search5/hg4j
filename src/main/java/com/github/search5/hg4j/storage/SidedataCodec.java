package com.github.search5.hg4j.storage;

import com.github.search5.hg4j.errors.HgCorruptDataException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Codec for the outer "sidedata container" byte format real hg uses inside a revlog-v2
 * revision's (decompressed) sidedata chunk — see {@code mercurial/revlogutils/sidedata.py}
 * {@code serialize_sidedata()}/{@code deserialize_sidedata()}. This is one layer below {@link
 * Revlog#getSidedata(int)} (which handles locating/decompressing the chunk on disk) and one
 * layer above the per-key payload formats (e.g. {@link com.github.search5.hg4j.api.ChangingFiles}
 * for the {@link #SD_FILES} key).
 *
 * <p>Wire format, verified byte-for-byte against a real {@code hg}-generated repository (see
 * {@code src/test/resources/fixtures/sidedata-copytracing/README.md}):
 * <pre>
 *   header:  count:uint16-be
 *   repeated `count` times, in key order: key:uint16-be  length:uint32-be  sha1(value):20 bytes
 *   then, in that same order: the `count` raw payload byte strings, back to back
 * </pre>
 * Each payload's SHA-1 is verified against its stored digest — a real corruption check real hg
 * itself performs ({@code error.SidedataHashError} on mismatch), not just a format nicety.
 */
public final class SidedataCodec {
    private SidedataCodec() {
    }

    /**
     * Sidedata key holding a changeset's per-revision "files" record (which paths were
     * added/removed/merged/salvaged/touched, and which destinations were copied from which
     * parent-relative source). Real hg's {@code SD_FILES} constant
     * (mercurial/revlogutils/sidedata.py) — currently the only sidedata key any shipped hg
     * version actually writes (the older {@code SD_P1COPIES}/{@code SD_P2COPIES}/{@code
     * SD_FILESADDED}/{@code SD_FILESREMOVED} constants are defined but dead code upstream, never
     * produced or consumed by any current code path — confirmed by grepping the installed
     * Mercurial 7.2 source tree).
     */
    public static final int SD_FILES = 12;

    private static final int HEADER_SIZE = 2;
    private static final int ENTRY_SIZE = 2 + 4 + 20; // key + length + sha1 digest
    private static final int SHA1_SIZE = 20;

    /**
     * Decodes one revision's already-decompressed sidedata chunk into its key -&gt; payload map.
     * Returns an empty map for a null/empty chunk (no sidedata).
     */
    public static Map<Integer, byte[]> deserialize(byte[] blob) throws HgCorruptDataException {
        Map<Integer, byte[]> result = new LinkedHashMap<>();
        if (blob == null || blob.length == 0) {
            return result;
        }
        if (blob.length < HEADER_SIZE) {
            throw new HgCorruptDataException("Truncated sidedata container: missing header");
        }
        ByteBuffer buf = ByteBuffer.wrap(blob);
        int count = buf.getShort() & 0xFFFF;

        long entryTableBytes = (long) count * ENTRY_SIZE;
        if (buf.remaining() < entryTableBytes) {
            throw new HgCorruptDataException("Truncated sidedata container: entry table shorter than declared count " + count);
        }

        int[] keys = new int[count];
        int[] lengths = new int[count];
        byte[][] digests = new byte[count][];
        for (int i = 0; i < count; i++) {
            keys[i] = buf.getShort() & 0xFFFF;
            lengths[i] = buf.getInt();
            byte[] digest = new byte[SHA1_SIZE];
            buf.get(digest);
            digests[i] = digest;
        }

        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }

        for (int i = 0; i < count; i++) {
            if (lengths[i] < 0 || buf.remaining() < lengths[i]) {
                throw new HgCorruptDataException("Truncated sidedata container: payload for key " + keys[i] + " shorter than declared length " + lengths[i]);
            }
            byte[] payload = new byte[lengths[i]];
            buf.get(payload);
            byte[] actualDigest = sha1.digest(payload);
            if (!Arrays.equals(actualDigest, digests[i])) {
                throw new HgCorruptDataException("Sidedata SHA-1 mismatch for key " + keys[i]);
            }
            result.put(keys[i], payload);
        }
        return result;
    }

    /**
     * Encodes a key -&gt; payload map into the outer sidedata container format (the inverse of
     * {@link #deserialize}), for {@link com.github.search5.hg4j.storage.Revlog#appendRevision}'s
     * new {@code sidedataContainer} parameter. Keys are written in ascending order (real hg's own
     * {@code serialize_sidedata()} iterates {@code sorted(sidedata.items())} — matters for
     * byte-for-byte compatibility with a real hg reader, not just for determinism here).
     *
     * @return an empty (zero-length) array if {@code payloadsByKey} is null/empty — matches
     *         {@link #deserialize}'s treatment of an empty/null blob as "no sidedata".
     */
    public static byte[] serialize(Map<Integer, byte[]> payloadsByKey) {
        if (payloadsByKey == null || payloadsByKey.isEmpty()) {
            return new byte[0];
        }
        Map<Integer, byte[]> sorted = new TreeMap<>(payloadsByKey);
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }

        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE + sorted.size() * ENTRY_SIZE);
        header.putShort((short) sorted.size());
        for (Map.Entry<Integer, byte[]> e : sorted.entrySet()) {
            byte[] payload = e.getValue();
            header.putShort((short) (int) e.getKey());
            header.putInt(payload.length);
            header.put(sha1.digest(payload));
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(header.capacity()
                + sorted.values().stream().mapToInt(v -> v.length).sum());
        try {
            out.write(header.array());
            for (byte[] payload : sorted.values()) {
                out.write(payload);
            }
        } catch (IOException e) {
            throw new IllegalStateException("ByteArrayOutputStream never throws", e);
        }
        return out.toByteArray();
    }
}
