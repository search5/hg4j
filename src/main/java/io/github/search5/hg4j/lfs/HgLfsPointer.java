package io.github.search5.hg4j.lfs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import io.github.search5.hg4j.errors.HgCorruptDataException;

/**
 * Represents a parsed Mercurial LFS (Large File Storage) pointer file.
 * Typically stored as a text file in the revlog:
 * <pre>
 * version https://git-lfs.github.com/spec/v1
 * oid sha256:7b1a2c3d...
 * size 123456
 * </pre>
 */
public final class HgLfsPointer {
    private final String version;
    private final String oid;
    private final long size;

    public HgLfsPointer(String version, String oid, long size) {
        if (version == null || oid == null || size < 0) {
            throw new IllegalArgumentException("Invalid LFS pointer arguments");
        }
        this.version = version;
        this.oid = oid;
        this.size = size;
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

    /**
     * Serializes back to the exact 3-line text real hg's {@code gitlfspointer.serialize()} writes
     * (confirmed 2026-09-04 against {@code hgext/lfs/pointer.py}): sorted with {@code version}
     * always first, then the rest alphabetically ({@code oid} before {@code size}), each line
     * {@code "<key> <value>\n"}.
     */
    public byte[] serialize() {
        String text = "version " + version + "\n"
                + "oid sha256:" + oid + "\n"
                + "size " + size + "\n";
        return text.getBytes(StandardCharsets.UTF_8);
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
     * Parses the binary content of a candidate LFS pointer file.
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

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("version ")) {
                version = trimmed.substring("version ".length()).trim();
            } else if (trimmed.startsWith("oid sha256:")) {
                oid = trimmed.substring("oid sha256:".length()).trim();
            } else if (trimmed.startsWith("size ")) {
                try {
                    size = Long.parseLong(trimmed.substring("size ".length()).trim());
                } catch (NumberFormatException e) {
                    throw new HgCorruptDataException("Invalid size format in LFS pointer: " + trimmed, e);
                }
            }
        }

        if (version == null || oid == null || size == -1) {
            throw new HgCorruptDataException("Malformed LFS pointer file: missing required fields");
        }

        // Validate OID hex string (should be 64 characters for SHA-256)
        if (oid.length() != 64) {
            throw new HgCorruptDataException("Invalid LFS OID length. Expected 64 characters, got: " + oid.length());
        }

        return new HgLfsPointer(version, oid, size);
    }
}
