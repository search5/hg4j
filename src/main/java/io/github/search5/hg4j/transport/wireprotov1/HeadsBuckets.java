package io.github.search5.hg4j.transport.wireprotov1;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Heads fingerprints for discovery: the {@code heads_buckets} wire command, the {@code heads_fingerprints}
 * capability and the SSH {@code hello} line, a port of {@code mercurial/exchanges/heads.py} (development tree after
 * 7.2.4; <b>this is not in a released Mercurial yet</b>, it is verified against an oracle built from that tree).
 *
 * <p>A server lists, for tiers of revision numbers, how many of its heads lie at or below the tier and a CRC32
 * fingerprint of those heads, so a client that remembers an earlier answer can tell without a round trip which heads
 * it already knows. The strategy is the server's to choose; this one is the reference one (revision-number tiers, CRC32 of
 * the native 32-bit revision numbers).</p>
 */
final class HeadsBuckets {

    static final String CAPABILITY = "heads_fingerprints";
    private static final String HELLO_HEADER = "heads-buckets-info-v01:";
    private static final int FINGERPRINT_SIZE = 4;
    private static final int NODE_SIZE = 20;

    /** @param count number of heads at or below this tier (cumulative), @param fingerprint 4 bytes */
    record Bucket(int count, byte[] fingerprint) {
    }

    private HeadsBuckets() {
    }

    /** Whether this server advertises heads fingerprints: with {@code experimental.disco.heads-fingerprints}, as the original (default off). */
    static boolean advertised(HgRepository repo) {
        String raw = repo.getConfig().get("experimental", "disco.heads-fingerprints", "false");
        switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "1": case "yes": case "true": case "on": case "always":
                return true;
            default:
                return false;
        }
    }

    /** {@code _bucket_boundary}: the tiers for a repository whose highest revision is {@code maxValue}. */
    static List<Long> bucketBoundary(long maxValue) {
        if (maxValue <= 0) {
            return List.of(0L);
        }
        List<Long> tiers = new ArrayList<>();
        for (int idx = 0; idx < 32; idx++) {
            long mask = idx == 0 ? -1L : ~((1L << idx) - 1);
            long t = maxValue & mask;
            if (t == 0) {
                break;
            }
            long bit = 1L << idx;
            if ((maxValue & bit) == 0) {
                t -= bit;
            }
            tiers.add(t);
        }
        Collections.sort(tiers);
        return tiers;
    }

    /** {@code _bucket_fingerprints}: tier -> (cumulative head count, fingerprint). {@code heads} ascending. */
    static Map<Long, Bucket> bucketFingerprints(List<Long> tierList, int[] heads) {
        List<Long> tiers = new ArrayList<>(tierList);
        tiers.sort(Collections.reverseOrder()); // popped from the end = ascending
        Map<Long, Bucket> info = new TreeMap<>();
        byte[] all = new byte[heads.length * 4];
        ByteBuffer.wrap(all).order(ByteOrder.nativeOrder()).asIntBuffer().put(heads);

        long prevCrc = 0;
        int bucketStart = 0;
        int bucketEnd = 0;
        long currentTier = tiers.remove(tiers.size() - 1);
        for (int r : heads) {
            while (r > currentTier) {
                if (bucketStart == bucketEnd) {
                    info.put(currentTier, new Bucket(bucketEnd, pack(prevCrc)));
                } else {
                    long hash = crc32(all, bucketStart * 4, bucketEnd * 4, prevCrc);
                    info.put(currentTier, new Bucket(bucketEnd, pack(hash)));
                    prevCrc = hash;
                    bucketStart = bucketEnd;
                }
                currentTier = tiers.remove(tiers.size() - 1);
            }
            bucketEnd++;
        }
        if (bucketStart == bucketEnd) {
            info.put(currentTier, new Bucket(bucketEnd, pack(prevCrc)));
        } else {
            info.put(currentTier, new Bucket(bucketEnd, pack(crc32(all, bucketStart * 4, bucketEnd * 4, prevCrc))));
        }
        return info;
    }

    /** {@code buckets_info}: the buckets of this repository. */
    static Map<Long, Bucket> bucketsInfo(HgRepository repo) throws IOException {
        Revlog changelog = RepoSupport.changelog(repo);
        int count = changelog.getRevisionCount();
        return bucketFingerprints(bucketBoundary(count - 1L), headRevisions(changelog, count));
    }

    /** Topological heads as revision numbers, ascending ({@code changelog.headrevs()}); none for an empty repository. */
    static int[] headRevisions(Revlog changelog, int count) {
        boolean[] isParent = new boolean[count];
        for (int i = 0; i < count; i++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(i);
            if (rec.getParent1() >= 0) isParent[rec.getParent1()] = true;
            if (rec.getParent2() >= 0) isParent[rec.getParent2()] = true;
        }
        return java.util.stream.IntStream.range(0, count).filter(i -> !isParent[i]).toArray();
    }

    /**
     * {@code encoded_bucket_info}: {@code u8 buckets, u8 fingerprint size, u8 node size, u32 skipped heads}, then per bucket
     * {@code u32 id, i32 head count} + fingerprint, then the nodes of the heads not covered by a bucket the client already
     * has. {@code cached} maps a tier to the fingerprint the client remembers (null for none).
     */
    static byte[] encodedBucketInfo(HgRepository repo, Map<Long, byte[]> cached) throws IOException {
        Revlog changelog = RepoSupport.changelog(repo);
        int count = changelog.getRevisionCount();
        Map<Long, Bucket> info = bucketsInfo(repo);

        int skipped = 0;
        if (cached != null) {
            for (Bucket current : info.values()) {
                for (byte[] cachedFp : cached.values()) {
                    if (java.util.Arrays.equals(current.fingerprint(), cachedFp)) {
                        skipped = Math.max(skipped, current.count());
                    }
                }
            }
        }
        int[] heads = headRevisions(changelog, count);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(7).order(ByteOrder.BIG_ENDIAN);
        header.put((byte) info.size()).put((byte) FINGERPRINT_SIZE).put((byte) NODE_SIZE).putInt(skipped);
        out.write(header.array(), 0, 7);
        for (Map.Entry<Long, Bucket> e : info.entrySet()) {
            ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
            b.putInt((int) (long) e.getKey()).putInt(e.getValue().count());
            out.write(b.array(), 0, 8);
            out.write(e.getValue().fingerprint(), 0, FINGERPRINT_SIZE);
        }
        for (int i = skipped; i < heads.length; i++) {
            out.write(changelog.getIndexRecord(heads[i]).getNodeId(), 0, NODE_SIZE);
        }
        return out.toByteArray();
    }

    /** {@code encode_hello}: the SSH handshake line, or null when there is nothing to tell. */
    static byte[] encodeHello(HgRepository repo) throws IOException {
        Map<Long, Bucket> info = bucketsInfo(repo);
        if (info.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(HELLO_HEADER);
        for (Map.Entry<Long, Bucket> e : info.entrySet()) {
            sb.append(' ').append(e.getKey()).append(':').append(io.github.search5.hg4j.util.NodeIdUtil.toHex(e.getValue().fingerprint()));
        }
        return sb.append('\n').toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** Parses the {@code cached} argument: {@code <tier>:<hex fingerprint>;<tier>:<hex fingerprint>}. */
    static Map<Long, byte[]> parseCached(String raw) {
        if (raw == null) {
            return null;
        }
        Map<Long, byte[]> cached = new TreeMap<>();
        for (String piece : raw.split(";", -1)) {
            String[] parts = piece.split(":", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("malformed cached heads bucket: " + piece);
            }
            cached.put(Long.parseLong(parts[0]), io.github.search5.hg4j.util.NodeIdUtil.fromHex(parts[1]));
        }
        return cached;
    }

    /** The fingerprint is a native-endian unsigned 32-bit CRC (struct 'I'). */
    private static byte[] pack(long crc) {
        return ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt((int) crc).array();
    }

    /** {@code binascii.crc32(data, value)}: the standard CRC-32 continued from {@code value}. */
    static long crc32(byte[] data, int from, int to, long value) {
        long crc = ~value & 0xFFFFFFFFL;
        for (int i = from; i < to; i++) {
            crc ^= data[i] & 0xFF;
            for (int k = 0; k < 8; k++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xEDB88320L : crc >>> 1;
            }
        }
        return ~crc & 0xFFFFFFFFL;
    }
}
