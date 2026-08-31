package com.github.search5.hg4j.obsolete;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses Mercurial's obsolescence store (obsstore) binary format.
 * Enables integration with the evolve revision history system.
 */
public final class HgObsolescenceParser {

    /**
     * Decodes the raw obsstore binary payload into a list of obsolescence markers.
     *
     * @param bytes raw binary contents of obsstore
     * @return list of parsed markers
     * @throws IOException if parsing fails or invalid format is detected
     */
    public static List<HgObsMarker> parse(byte[] bytes) throws IOException {
        List<HgObsMarker> markers = new ArrayList<>();
        if (bytes == null || bytes.length == 0) {
            return markers;
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        try {
            while (buffer.hasRemaining()) {
                if (buffer.remaining() < 20) {
                    throw new com.github.search5.hg4j.errors.HgCorruptDataException("Truncated obsstore content: predecessor node mismatch");
                }

                // 1. Read Predecessor NodeId (20 bytes)
                byte[] predecessor = new byte[20];
                buffer.get(predecessor);

                // 2. Read Successors count (1 byte)
                if (!buffer.hasRemaining()) {
                    throw new com.github.search5.hg4j.errors.HgCorruptDataException("Truncated obsstore content: missing successor count");
                }
                int successorsCount = buffer.get() & 0xFF;

                // 3. Read Successor NodeIds (successorsCount * 20 bytes)
                if (buffer.remaining() < successorsCount * 20) {
                    throw new com.github.search5.hg4j.errors.HgCorruptDataException("Truncated obsstore content: successor nodes block overflow");
                }
                List<byte[]> successors = new ArrayList<>(successorsCount);
                for (int i = 0; i < successorsCount; i++) {
                    byte[] succ = new byte[20];
                    buffer.get(succ);
                    successors.add(succ);
                }

                // 4. Read Flags (1 byte)
                if (!buffer.hasRemaining()) {
                    throw new com.github.search5.hg4j.errors.HgCorruptDataException("Truncated obsstore content: missing flags");
                }
                int flags = buffer.get() & 0xFF;

                // 5. Read Metadata Block Size (2 bytes)
                if (buffer.remaining() < 2) {
                    throw new com.github.search5.hg4j.errors.HgCorruptDataException("Truncated obsstore content: missing metadata length");
                }
                int metaLen = buffer.getShort() & 0xFFFF;

                // 6. Read Metadata (metaLen bytes, formatted as null-separated key-value pairs)
                if (buffer.remaining() < metaLen) {
                    throw new com.github.search5.hg4j.errors.HgCorruptDataException("Truncated obsstore content: metadata segment overflow");
                }

                Map<String, String> metadata = new LinkedHashMap<>();
                if (metaLen > 0) {
                    byte[] metaBytes = new byte[metaLen];
                    buffer.get(metaBytes);

                    // Parse null-separated pairs
                    int start = 0;
                    String key = null;
                    for (int i = 0; i < metaLen; i++) {
                        if (metaBytes[i] == 0) {
                            String token = new String(metaBytes, start, i - start, StandardCharsets.UTF_8);
                            if (key == null) {
                                key = token;
                            } else {
                                metadata.put(key, token);
                                key = null;
                            }
                            start = i + 1;
                        }
                    }
                    // Handle trailing token if not null-terminated
                    if (start < metaLen) {
                        String token = new String(metaBytes, start, metaLen - start, StandardCharsets.UTF_8);
                        if (key != null) {
                            metadata.put(key, token);
                        }
                    }
                }

                markers.add(new HgObsMarker(predecessor, successors, flags, metadata));
            }
        } catch (com.github.search5.hg4j.errors.HgCorruptDataException e) {
            throw e;
        } catch (Exception e) {
            throw new com.github.search5.hg4j.errors.HgCorruptDataException("Failed to parse obsstore binary content", e);
        }

        return markers;
    }
}
