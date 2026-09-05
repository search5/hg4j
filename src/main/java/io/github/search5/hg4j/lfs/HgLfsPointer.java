package io.github.search5.hg4j.lfs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import io.github.search5.hg4j.errors.HgCorruptDataException;

/**
 * Represents a parsed Mercurial LFS (Large File Storage) pointer file.
 * Typically stored as a text file in the revlog:
 * <pre>
 * version https://git-lfs.github.com/spec/v1
 * oid sha256:7b1a2c3d...
 * size 123456
 * </pre>
 *
 * <p>Beyond the three required fields, real hg's {@code gitlfspointer} (confirmed 2026-09-06
 * against {@code hgext/lfs/pointer.py}) is really just a dict: it freely carries extra
 * {@code <key> <value>} lines. hg4j preserves those as {@link #getExtra()} -- most importantly
 * the {@code x-hg-copy}/{@code x-hg-copyrev} keys real hg's {@code hgext/lfs/wrapper.py}
 * ({@code writetostore}/{@code readfromstore}) uses to fold a file's rename/copy metadata INTO
 * the pointer itself (instead of the usual {@code \x01\n...\x01\n} filelog metadata block) when
 * a renamed file also happens to be LFS-tracked, and {@code x-is-binary} (present with value
 * {@code "0"} only when the real content is NOT binary, i.e. contains no NUL byte -- absence of
 * the key is real hg's implicit "assume binary" default for LFS content).
 */
public final class HgLfsPointer {
    private final String version;
    private final String oid;
    private final long size;
    private final Map<String, String> extra;

    public HgLfsPointer(String version, String oid, long size) {
        this(version, oid, size, Map.of());
    }

    /**
     * @param extra additional {@code <key> <value>} pointer fields beyond {@code version}/
     *     {@code oid}/{@code size} (e.g. {@code x-hg-copy}, {@code x-hg-copyrev},
     *     {@code x-is-binary}) -- may be {@code null}, treated the same as an empty map.
     */
    public HgLfsPointer(String version, String oid, long size, Map<String, String> extra) {
        if (version == null || oid == null || size < 0) {
            throw new IllegalArgumentException("Invalid LFS pointer arguments");
        }
        this.version = version;
        this.oid = oid;
        this.size = size;
        this.extra = extra == null || extra.isEmpty() ? Map.of() : Map.copyOf(extra);
    }

    public String getVersion() {
        return version;
    }

    public String getOid() {
        return oid;
    }

    public long getSize() {
        return size;
    }

    /** Extra pointer fields beyond {@code version}/{@code oid}/{@code size}, keyed exactly as
     * they appear in the serialized pointer text (e.g. {@code "x-hg-copy"}). Never {@code null}. */
    public Map<String, String> getExtra() {
        return extra;
    }

    /**
     * Serializes back to the exact text real hg's {@code gitlfspointer.serialize()} writes
     * (confirmed 2026-09-04/2026-09-06 against {@code hgext/lfs/pointer.py}): {@code version}
     * always first, then every other field (including {@code oid}/{@code size} and any
     * {@link #getExtra()} entries) sorted alphabetically together by key -- real hg's own sort
     * key is literally {@code (key != "version", key)}, so {@code oid}/{@code size} are NOT
     * grouped ahead of extra keys, they just happen to alphabetically sort before an
     * {@code x-hg-*}/{@code x-is-binary} key. Each line is {@code "<key> <value>\n"}.
     */
    public byte[] serialize() {
        Map<String, String> rest = new TreeMap<>(extra);
        rest.put("oid", "sha256:" + oid);
        rest.put("size", Long.toString(size));

        StringBuilder sb = new StringBuilder();
        sb.append("version ").append(version).append('\n');
        for (Map.Entry<String, String> entry : rest.entrySet()) {
            sb.append(entry.getKey()).append(' ').append(entry.getValue()).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Lowercase hex SHA-256 of {@code data} -- the LFS {@code oid} (git-lfs only supports sha256). */
    public static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 digest not available", e);
        }
    }

    /**
     * Parses the binary content of a candidate LFS pointer file, real hg's
     * {@code gitlfspointer.deserialize()}-equivalent: each non-blank line is split on its FIRST
     * space into a key/value pair (a line with no space at all -- i.e. not a recognized
     * {@code <key> <value>} pair -- is silently skipped rather than erroring, matching this
     * class's pre-existing tolerance for stray unrecognized lines). Any key beyond
     * {@code version}/{@code oid}/{@code size} is preserved verbatim in {@link #getExtra()}.
     *
     * @param content raw text content bytes
     * @return parsed pointer object
     * @throws IOException if parsing fails or layout is invalid
     */
    public static HgLfsPointer parse(byte[] content) throws IOException {
        if (content == null || content.length == 0) {
            throw new HgCorruptDataException("LFS pointer content is empty or null");
        }

        String text = new String(content, StandardCharsets.UTF_8);
        String[] lines = text.split("\n");
        String version = null;
        String oid = null;
        long size = -1;
        Map<String, String> extra = new LinkedHashMap<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int spaceIdx = trimmed.indexOf(' ');
            if (spaceIdx == -1) {
                // Not a "<key> <value>" line -- unrecognized, silently skipped.
                continue;
            }
            String key = trimmed.substring(0, spaceIdx);
            String value = trimmed.substring(spaceIdx + 1).trim();

            switch (key) {
                case "version" -> version = value;
                case "oid" -> {
                    if (value.startsWith("sha256:")) {
                        oid = value.substring("sha256:".length());
                    }
                    // A non-sha256 oid scheme is treated like any other unrecognized line --
                    // oid stays unset, surfaced below as "missing required fields".
                }
                case "size" -> {
                    try {
                        size = Long.parseLong(value);
                    } catch (NumberFormatException e) {
                        throw new HgCorruptDataException("Invalid size format in LFS pointer: " + trimmed, e);
                    }
                }
                default -> extra.put(key, value);
            }
        }

        if (version == null || oid == null || size == -1) {
            throw new HgCorruptDataException("Malformed LFS pointer file: missing required fields");
        }

        // Validate OID hex string (should be 64 characters for SHA-256)
        if (oid.length() != 64) {
            throw new HgCorruptDataException("Invalid LFS OID length. Expected 64 characters, got: " + oid.length());
        }

        return new HgLfsPointer(version, oid, size, extra);
    }
}
