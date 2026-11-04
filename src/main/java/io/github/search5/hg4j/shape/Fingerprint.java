package io.github.search5.hg4j.shape;

import io.github.search5.hg4j.errors.HgValidationException;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identifies a set of files in the store ({@code shape.py Fingerprint}, {@code narrow/shape.rs Fingerprint}): two semantically
 * equivalent shapes (or shards) have the same fingerprint. It carries the version of the computation that made it, which fixes the size of
 * the digest; a version this code does not know is valid with any non-empty digest, it just cannot match anything computed here.
 *
 * <p>Two forms: the binary one ({@code version byte + digest}) for storage and the wire, and the text one
 * ({@code shape00-<hex>} / {@code shard00-<hex>}) for users.</p>
 */
public final class Fingerprint {

    /** What a fingerprint identifies. */
    public enum Kind {
        SHAPE("shape"), SHARD("shard");

        private final String prefix;

        Kind(String prefix) {
            this.prefix = prefix;
        }

        /**
         * Returns the word the text form of this kind starts with.
         *
         * @return {@code "shape"} or {@code "shard"}
         */
        public String prefix() {
            return prefix;
        }
    }

    /** The version of the computation produced here ({@code FINGERPRINT_VERSION}). */
    public static final int CURRENT_VERSION = 0;
    private static final Map<Integer, Integer> DIGEST_SIZES = Map.of(0, 32);
    private static final Pattern TEXT = Pattern.compile("(shape|shard)([0-9]{2,3})-((?:[0-9a-f]{2})+)");

    private final Kind kind;
    private final int version;
    private final byte[] digest;

    /**
     * Creates a fingerprint.
     *
     * @param kind what the fingerprint identifies
     * @param version the version of the computation that produced the digest; version 0 requires a 32-byte digest, other versions accept any non-empty digest
     * @param digest the digest bytes, copied; must not be {@code null}
     * @throws IllegalArgumentException if the digest is empty or has the wrong size for a known version
     */
    public Fingerprint(Kind kind, int version, byte[] digest) {
        Integer expected = DIGEST_SIZES.get(version);
        if (digest.length == 0 || expected != null && digest.length != expected) {
            throw new IllegalArgumentException("invalid version " + version + " fingerprint digest: " + HexFormat.of().formatHex(digest));
        }
        this.kind = kind;
        this.version = version;
        this.digest = digest.clone();
    }

    /**
     * Returns what this fingerprint identifies.
     *
     * @return the kind
     */
    public Kind kind() {
        return kind;
    }

    /**
     * Returns the version of the computation that produced the digest.
     *
     * @return the version
     */
    public int version() {
        return version;
    }

    /**
     * Returns the digest.
     *
     * @return a copy of the digest bytes
     */
    public byte[] digest() {
        return digest.clone();
    }

    /** The same digest as a fingerprint of a standalone shard ({@code as_shard}). */
    public Fingerprint asShard() {
        return new Fingerprint(Kind.SHARD, version, digest);
    }

    /** The binary form: one byte of version, then the digest. */
    public byte[] toBytes() {
        byte[] out = new byte[1 + digest.length];
        out[0] = (byte) version;
        System.arraycopy(digest, 0, out, 1, digest.length);
        return out;
    }

    /** @throws IllegalArgumentException when the digest is invalid for that version, or the data is empty */
    public static Fingerprint fromBytes(Kind kind, byte[] data) {
        if (data.length == 0) {
            throw new IllegalArgumentException("invalid fingerprint: no data");
        }
        return new Fingerprint(kind, data[0] & 0xFF, Arrays.copyOfRange(data, 1, data.length));
    }

    /** {@code shapeXY-<hex>} / {@code shardXY-<hex>}, XY the version as a decimal padded to two digits. */
    public String toText() {
        return String.format("%s%02d-%s", kind.prefix, version, HexFormat.of().formatHex(digest));
    }

    /**
     * Parses the text form. The version must be written the way {@code %02d} writes it and be at most 255; the kind must be {@code kind}.
     *
     * @throws HgValidationException with {@code not a shape fingerprint: ...} otherwise
     */
    public static Fingerprint fromText(Kind kind, String text) throws HgValidationException {
        Matcher m = TEXT.matcher(text);
        if (m.matches() && m.group(1).equals(kind.prefix)) {
            String rawVersion = m.group(2);
            int version = Integer.parseInt(rawVersion);
            if (version <= 255 && rawVersion.equals(String.format("%02d", version))) {
                try {
                    return new Fingerprint(kind, version, HexFormat.of().parseHex(m.group(3)));
                } catch (IllegalArgumentException invalid) {
                    // fall through to the common message
                }
            }
        }
        throw new HgValidationException("not a " + kind.prefix + " fingerprint: " + text);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Fingerprint f && f.kind == kind && f.version == version && Arrays.equals(f.digest, digest);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * kind.hashCode() + version) + Arrays.hashCode(digest);
    }

    @Override
    public String toString() {
        return toText();
    }
}
