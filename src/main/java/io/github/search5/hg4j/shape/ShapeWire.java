package io.github.search5.hg4j.shape;

import io.github.search5.hg4j.errors.HgValidationException;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The blocks of the {@code store_shape} wire command ({@code shape.py _encode_fingerprints}, {@code _encode_shards_sets},
 * {@code wire_unknown_names_encode}, {@code wireprototypes.STORE_SHAPE_ENCODE}). All integers are big endian. See
 * {@code llm-wiki/concepts/store-shapes.md}, section 7.
 */
public final class ShapeWire {

    /** {@code ShapeReturnCode}: a shape of that name was found. */
    public static final int OK = 1;
    /** {@code ShapeReturnCode}: no shape of that name. */
    public static final int SHAPE_NOT_FOUND = 2;
    /** {@code STORE_SHAPE_ENCODE = struct ">BHII"}: code, fingerprints block size, shards sets block size, patterns block size. */
    public static final int HEADER_SIZE = 1 + 2 + 4 + 4;

    private ShapeWire() {
    }

    /** What a server answers to {@code store_shape}, decoded. */
    public record Reply(List<Fingerprint> fingerprints, List<Set<Fingerprint>> shardsSets, ShardTreeNode.Patterns patterns) {
    }

    /** The reply to a {@code store_shape} that names no known shape: the header, then the unknown names block in the first size slot. */
    public static byte[] notFound(List<String> unknownNames) {
        byte[] block = unknownNamesEncode(unknownNames);
        return ByteBuffer.allocate(HEADER_SIZE + block.length).put((byte) SHAPE_NOT_FOUND).putShort((short) block.length).putInt(0).putInt(0)
                .put(block).array();
    }

    /** The reply for a found shape: the header and the three blocks. */
    public static byte[] ok(Fingerprint shapeFingerprint, List<Set<Fingerprint>> shardsSets, byte[] patternsBlock) {
        byte[] fingerprints = encodeFingerprints(List.of(shapeFingerprint));
        byte[] sets = encodeShardsSets(shardsSets);
        return ByteBuffer.allocate(HEADER_SIZE + fingerprints.length + sets.length + patternsBlock.length)
                .put((byte) OK).putShort((short) fingerprints.length).putInt(sets.length).putInt(patternsBlock.length)
                .put(fingerprints).put(sets).put(patternsBlock).array();
    }

    /** {@code _encode_fingerprints}: {@code count:u8}, {@code len:u8} each, then the binary fingerprints. */
    public static byte[] encodeFingerprints(List<Fingerprint> fingerprints) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(fingerprints.size());
        for (Fingerprint fp : fingerprints) {
            out.write(fp.toBytes().length);
        }
        for (Fingerprint fp : fingerprints) {
            out.writeBytes(fp.toBytes());
        }
        return out.toByteArray();
    }

    public static List<Fingerprint> decodeFingerprints(byte[] data, Fingerprint.Kind kind) {
        int count = data[0] & 0xFF;
        int cursor = 1 + count;
        List<Fingerprint> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            int size = data[i] & 0xFF;
            out.add(Fingerprint.fromBytes(kind, Arrays.copyOfRange(data, cursor, cursor + size)));
            cursor += size;
        }
        return out;
    }

    /**
     * {@code _encode_shards_sets}: {@code sets:u8}, per set {@code (id length:u8, count:u16)}, then each set's binary shard fingerprints, sorted.
     * A set has at least one member and all its ids have the same length.
     */
    public static byte[] encodeShardsSets(List<Set<Fingerprint>> sets) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(sets.size());
        for (Set<Fingerprint> set : sets) {
            if (set.isEmpty()) {
                throw new IllegalArgumentException("a set of shards needs at least one member");
            }
            int length = set.iterator().next().toBytes().length;
            for (Fingerprint fp : set) {
                if (fp.toBytes().length != length) {
                    throw new IllegalArgumentException("the ids of a set of shards have different lengths");
                }
            }
            out.write(length);
            out.writeBytes(ByteBuffer.allocate(2).putShort((short) set.size()).array());
        }
        for (Set<Fingerprint> set : sets) {
            List<byte[]> ids = new ArrayList<>();
            set.forEach(fp -> ids.add(fp.toBytes()));
            ids.sort(Arrays::compareUnsigned);
            ids.forEach(out::writeBytes);
        }
        return out.toByteArray();
    }

    public static List<Set<Fingerprint>> decodeShardsSets(byte[] data) {
        int sets = data[0] & 0xFF;
        int cursor = 1;
        int[][] info = new int[sets][2];
        for (int i = 0; i < sets; i++) {
            info[i][0] = data[cursor] & 0xFF;
            info[i][1] = ByteBuffer.wrap(data, cursor + 1, 2).getShort() & 0xFFFF;
            cursor += 3;
        }
        List<Set<Fingerprint>> out = new ArrayList<>();
        for (int[] set : info) {
            Set<Fingerprint> one = new LinkedHashSet<>();
            for (int i = 0; i < set[1]; i++) {
                one.add(Fingerprint.fromBytes(Fingerprint.Kind.SHARD, Arrays.copyOfRange(data, cursor, cursor + set[0])));
                cursor += set[0];
            }
            out.add(one);
        }
        if (cursor != data.length) {
            throw new IllegalArgumentException("trailing bytes in the shards sets block");
        }
        return out;
    }

    /** {@code wire_unknown_names_encode}: {@code count:u32}, {@code len:u32} each, then the names. */
    public static byte[] unknownNamesEncode(List<String> names) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ByteBuffer.allocate(4).putInt(names.size()).array());
        for (String name : names) {
            out.writeBytes(ByteBuffer.allocate(4).putInt(name.getBytes(StandardCharsets.UTF_8).length).array());
        }
        for (String name : names) {
            out.writeBytes(name.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    public static List<String> unknownNamesDecode(byte[] data) {
        int count = ByteBuffer.wrap(data, 0, 4).getInt();
        int cursor = 4 + 4 * count;
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int size = ByteBuffer.wrap(data, 4 + 4 * i, 4).getInt();
            names.add(new String(data, cursor, size, StandardCharsets.UTF_8));
            cursor += size;
        }
        if (cursor != data.length) {
            throw new IllegalArgumentException("trailing bytes in the unknown names block");
        }
        return names;
    }

    /**
     * Decodes a whole {@code store_shape} reply and checks it like {@code wireprotov1peer.store_shape}: the fingerprint computed from the
     * patterns must be one the server sent.
     *
     * @param names what was asked, for the messages
     * @throws HgValidationException {@code shape not found on remote: '...'}, {@code unknown shape error code: N},
     *                               {@code fingerprint mismatch for shape '...'}
     */
    public static Reply decodeReply(byte[] data, List<String> names) throws HgValidationException {
        ByteBuffer header = ByteBuffer.wrap(data, 0, HEADER_SIZE);
        int code = header.get() & 0xFF;
        int fingerprintsSize = header.getShort() & 0xFFFF;
        int setsSize = header.getInt();
        int patternsSize = header.getInt();
        int cursor = HEADER_SIZE;
        if (code == SHAPE_NOT_FOUND) {
            List<String> unknown = unknownNamesDecode(Arrays.copyOfRange(data, cursor, cursor + fingerprintsSize));
            throw new HgValidationException("shape not found on remote: '" + String.join(",", unknown) + "'");
        }
        if (code != OK) {
            throw new HgValidationException("unknown shape error code: " + code);
        }
        if (cursor + fingerprintsSize + setsSize + patternsSize != data.length) {
            throw new HgValidationException("unexpected response from remote server: the store_shape blocks do not add up");
        }
        List<Fingerprint> fingerprints = decodeFingerprints(Arrays.copyOfRange(data, cursor, cursor + fingerprintsSize), Fingerprint.Kind.SHAPE);
        cursor += fingerprintsSize;
        List<Set<Fingerprint>> sets = decodeShardsSets(Arrays.copyOfRange(data, cursor, cursor + setsSize));
        cursor += setsSize;
        ShardTreeNode.Patterns patterns = ShardTreeNode.deserializePatterns(Arrays.copyOfRange(data, cursor, cursor + patternsSize));
        Fingerprint computed = ShardTreeNode.fromPatterns(patterns.includes(), patterns.excludes()).fingerprint();
        if (!fingerprints.contains(computed)) {
            // only if the way fingerprints are computed changed
            throw new HgValidationException("fingerprint mismatch for shape '" + String.join(",", names) + "'\n(hint: server: ["
                    + String.join(", ", fingerprints.stream().map(Fingerprint::toText).toList()) + "], client: " + computed.toText() + ")");
        }
        return new Reply(fingerprints, sets, patterns);
    }
}
