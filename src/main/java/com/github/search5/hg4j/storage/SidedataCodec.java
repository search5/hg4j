package com.github.search5.hg4j.storage;

import com.github.search5.hg4j.errors.HgCorruptDataException;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

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
}
