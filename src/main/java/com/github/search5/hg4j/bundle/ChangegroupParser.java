package com.github.search5.hg4j.bundle;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Parser for unpackaging and applying Mercurial changegroup (Bundle) payload
 * to local repositories with robust error boundaries.
 */
public class ChangegroupParser {
    private static final Logger LOGGER = Logger.getLogger(ChangegroupParser.class.getName());

    /**
     * Reads a single chunk from the stream.
     * Each chunk starts with a 4-byte big-endian length field.
     * Length of 0 or {@code < 4} indicates end of chunk collection.
     */
    public static byte[] readChunk(InputStream in) throws IOException {
        byte[] lenBytes = new byte[4];
        int read = in.read(lenBytes);
        if (read < 4) {
            return null;
        }
        int len = ((lenBytes[0] & 0xFF) << 24) |
                  ((lenBytes[1] & 0xFF) << 16) |
                  ((lenBytes[2] & 0xFF) << 8)  |
                  (lenBytes[3] & 0xFF);

        if (len <= 4) {
            return null;
        }
        int payloadLen = len - 4;
        if (payloadLen > 20 * 1024 * 1024) { // 20MB guard limit to prevent DoS OOM
            throw new com.github.search5.hg4j.errors.HgCorruptDataException("Security Guard: Changegroup chunk size exceeds maximum allowed limit (20MB): " + payloadLen);
        }
        byte[] payload = new byte[payloadLen];
        int offset = 0;
        while (offset < payloadLen) {
            int count = in.read(payload, offset, payloadLen - offset);
            if (count == -1) {
                throw new com.github.search5.hg4j.errors.HgCorruptDataException("Unexpected EOF while reading changegroup chunk payload of size: " + payloadLen);
            }
            offset += count;
        }
        return payload;
    }

    /**
     * Structure representing a single delta/revision entry in a changegroup.
     */
    public static class ChangeGroupEntry {
        public byte[] node;
        public byte[] p1;
        public byte[] p2;
        public byte[] cs;
        public byte[] deltabase; // null if cg1, 20-bytes if cg2/cg3
        public int flags;        // 0 if not cg3
        public byte[] delta;
    }

    /**
     * Parses chunks belonging to a single revlog group until a terminal chunk {@code (len <= 4)} is found.
     */
    public static List<ChangeGroupEntry> parseGroup(InputStream in) throws IOException {
        return parseGroup(in, "01");
    }

    /**
     * Parses chunks belonging to a single revlog group with a specific changegroup version.
     */
    public static List<ChangeGroupEntry> parseGroup(InputStream in, String version) throws IOException {
        return parseGroup(in, version, null);
    }

    /**
     * Parses chunks belonging to a single revlog group with a specific changegroup version and reports the detected version.
     */
    public static List<ChangeGroupEntry> parseGroup(InputStream in, String version, String[] outVersion) throws IOException {
        List<ChangeGroupEntry> entries = new ArrayList<>();
        boolean first = true;
        String detectedVersion = version;
        int headerSize = 80;
        if ("02".equals(version)) {
            headerSize = 100;
        } else if ("03".equals(version)) {
            headerSize = 102;
        }

        while (true) {
            byte[] chunk = readChunk(in);
            if (chunk == null) {
                break;
            }
            
            if (first) {
                first = false;
                if ("01".equals(version)) {
                    detectedVersion = autoDetectVersion(chunk);
                    if ("02".equals(detectedVersion)) {
                        headerSize = 100;
                    } else if ("03".equals(detectedVersion)) {
                        headerSize = 102;
                    }
                }
                if (outVersion != null) {
                    outVersion[0] = detectedVersion;
                }
            }

            if (chunk.length < headerSize) {
                throw new com.github.search5.hg4j.errors.HgCorruptDataException("Malformed changegroup header chunk. Length too small: " + chunk.length + " for version: " + detectedVersion);
            }

            ChangeGroupEntry entry = new ChangeGroupEntry();
            entry.node = new byte[20];
            entry.p1 = new byte[20];
            entry.p2 = new byte[20];
            entry.cs = new byte[20];

            System.arraycopy(chunk, 0, entry.node, 0, 20);
            System.arraycopy(chunk, 20, entry.p1, 0, 20);
            System.arraycopy(chunk, 40, entry.p2, 0, 20);
            System.arraycopy(chunk, 60, entry.cs, 0, 20);

            if (headerSize >= 100) {
                entry.deltabase = new byte[20];
                System.arraycopy(chunk, 80, entry.deltabase, 0, 20);
            }
            if (headerSize >= 102) {
                entry.flags = ((chunk[100] & 0xFF) << 8) | (chunk[101] & 0xFF);
            }

            int deltaLen = chunk.length - headerSize;
            entry.delta = new byte[deltaLen];
            System.arraycopy(chunk, headerSize, entry.delta, 0, deltaLen);

            entries.add(entry);
        }
        return entries;
    }

    private static String autoDetectVersion(byte[] chunk) {
        LOGGER.log(Level.FINE, "[DEBUG AUTO] chunk length: {0}", chunk.length);
        if (chunk.length < 80) {
            return "01";
        }
        boolean v3Valid = chunk.length >= 102 + 12 && isValidDeltaHeader(chunk, 102);
        boolean v2Valid = chunk.length >= 100 + 12 && isValidDeltaHeader(chunk, 100);
        LOGGER.log(Level.FINE, "[DEBUG AUTO] v3Valid: {0}, v2Valid: {1}", new Object[]{v3Valid, v2Valid});
        if (v3Valid) {
            return "03";
        }
        if (v2Valid) {
            return "02";
        }
        return "01";
    }

    private static boolean isValidDeltaHeader(byte[] chunk, int offset) {
        int start = ((chunk[offset] & 0xFF) << 24) |
                    ((chunk[offset + 1] & 0xFF) << 16) |
                    ((chunk[offset + 2] & 0xFF) << 8) |
                    (chunk[offset + 3] & 0xFF);
        int end = ((chunk[offset + 4] & 0xFF) << 24) |
                    ((chunk[offset + 5] & 0xFF) << 16) |
                    ((chunk[offset + 6] & 0xFF) << 8) |
                    (chunk[offset + 7] & 0xFF);
        int len = ((chunk[offset + 8] & 0xFF) << 24) |
                    ((chunk[offset + 9] & 0xFF) << 16) |
                    ((chunk[offset + 10] & 0xFF) << 8) |
                    (chunk[offset + 11] & 0xFF);

        boolean valid = (start >= 0 && end >= 0 && len >= 0 && start <= end && len <= (chunk.length - (offset + 12)));
        LOGGER.log(Level.FINE, "[DEBUG AUTO] isValidDeltaHeader offset: {0}, start: {1}, end: {2}, len: {3}, remaining: {4} -> {5}", 
                new Object[]{offset, start, end, len, (chunk.length - (offset + 12)), valid});
        return valid;
    }

    public static class ManifestGroup {
        public String path;
        public List<ChangeGroupEntry> entries;
    }

    public static class FileGroup {
        public String path;
        public List<ChangeGroupEntry> entries;
    }

    public static class ChangegroupBundle {
        public List<ChangeGroupEntry> changelogEntries;
        public List<ChangeGroupEntry> manifestEntries; // null if cg3
        public List<ManifestGroup> manifestGroups;     // cg3 treemanifest
        public List<FileGroup> fileGroups;
    }

    /**
     * Parses a complete Mercurial changegroup v1 bundle from stream.
     */
    public static ChangegroupBundle parseBundle(InputStream in) throws IOException {
        return parseBundle(in, "01");
    }

    /**
     * Parses a complete Mercurial changegroup bundle of specific version from stream.
     */
    public static ChangegroupBundle parseBundle(InputStream in, String version) throws IOException {
        String[] versionHolder = new String[]{ version };
        ChangegroupBundle bundle = new ChangegroupBundle();
        bundle.changelogEntries = parseGroup(in, version, versionHolder);
        String detectedVersion = versionHolder[0];
        
        if ("03".equals(detectedVersion)) {
            bundle.manifestGroups = new ArrayList<>();
            while (true) {
                byte[] pathChunk = readChunk(in);
                if (pathChunk == null) {
                    break;
                }
                ManifestGroup mg = new ManifestGroup();
                mg.path = new String(pathChunk, java.nio.charset.StandardCharsets.UTF_8);
                mg.entries = parseGroup(in, detectedVersion, versionHolder);
                bundle.manifestGroups.add(mg);
            }
        } else {
            bundle.manifestEntries = parseGroup(in, detectedVersion, versionHolder);
        }

        bundle.fileGroups = new ArrayList<>();
        while (true) {
            byte[] pathChunk = readChunk(in);
            if (pathChunk == null) {
                break;
            }
            FileGroup fg = new FileGroup();
            fg.path = new String(pathChunk, java.nio.charset.StandardCharsets.UTF_8);
            fg.entries = parseGroup(in, detectedVersion, versionHolder);
            bundle.fileGroups.add(fg);
        }
        return bundle;
    }
}
