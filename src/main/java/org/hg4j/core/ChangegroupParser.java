package org.hg4j.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for unpackaging and applying Mercurial changegroup (Bundle) payload
 * to local repositories with robust error boundaries.
 */
public class ChangegroupParser {

    /**
     * Reads a single chunk from the stream.
     * Each chunk starts with a 4-byte big-endian length field.
     * Length of 0 or < 4 indicates end of chunk collection.
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
            throw new IOException("Security Guard: Changegroup chunk size exceeds maximum allowed limit (20MB): " + payloadLen);
        }
        byte[] payload = new byte[payloadLen];
        int offset = 0;
        while (offset < payloadLen) {
            int count = in.read(payload, offset, payloadLen - offset);
            if (count == -1) {
                throw new IOException("Unexpected EOF while reading changegroup chunk payload of size: " + payloadLen);
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
     * Parses chunks belonging to a single revlog group until a terminal chunk (len <= 4) is found.
     */
    public static List<ChangeGroupEntry> parseGroup(InputStream in) throws IOException {
        return parseGroup(in, "01");
    }

    /**
     * Parses chunks belonging to a single revlog group with a specific changegroup version.
     */
    public static List<ChangeGroupEntry> parseGroup(InputStream in, String version) throws IOException {
        List<ChangeGroupEntry> entries = new ArrayList<>();
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
            if (chunk.length < headerSize) {
                throw new IOException("Malformed changegroup header chunk. Length too small: " + chunk.length + " for version: " + version);
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
        ChangegroupBundle bundle = new ChangegroupBundle();
        bundle.changelogEntries = parseGroup(in, version);
        
        if ("03".equals(version)) {
            bundle.manifestGroups = new ArrayList<>();
            while (true) {
                byte[] pathChunk = readChunk(in);
                if (pathChunk == null) {
                    break;
                }
                ManifestGroup mg = new ManifestGroup();
                mg.path = new String(pathChunk, java.nio.charset.StandardCharsets.UTF_8);
                mg.entries = parseGroup(in, version);
                bundle.manifestGroups.add(mg);
            }
        } else {
            bundle.manifestEntries = parseGroup(in, version);
        }

        bundle.fileGroups = new ArrayList<>();
        while (true) {
            byte[] pathChunk = readChunk(in);
            if (pathChunk == null) {
                break;
            }
            FileGroup fg = new FileGroup();
            fg.path = new String(pathChunk, java.nio.charset.StandardCharsets.UTF_8);
            fg.entries = parseGroup(in, version);
            bundle.fileGroups.add(fg);
        }
        return bundle;
    }
}
