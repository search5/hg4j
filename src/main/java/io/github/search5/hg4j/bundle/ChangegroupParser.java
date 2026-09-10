package io.github.search5.hg4j.bundle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import io.github.search5.hg4j.errors.HgCorruptDataException;
import java.nio.charset.StandardCharsets;

/**
 * Parser for unpackaging and applying Mercurial changegroup (Bundle) payload
 * to local repositories with robust error boundaries.
 *
 * @apiNote Decodes the changegroup versions (cg1-cg5) real hg actually produces, used by every
 *     exchange path: {@code HgRemoteClientV2}/{@code HgLocalClient} (wire pull/push), {@code
 *     Bundle2Parser} (bundle2 payload), and porcelain commands {@code PullCommand}, {@code
 *     FetchCommand}, {@code PushCommand}, {@code BundleCommand}, {@code UnbundleCommand}, {@code
 *     IncomingCommand}, {@code HisteditCommand}, and {@code ShelveCommand}. {@link
 *     io.github.search5.hg4j.storage.Revlog#appendChangeGroupEntry} is where a decoded {@link
 *     ChangeGroupEntry} actually gets written to local storage.
 */
public class ChangegroupParser {
    private static final Logger LOGGER = Logger.getLogger(ChangegroupParser.class.getName());

    // Real spec (mercurial/utils/storageutil.py, confirmed against Mercurial 7.2.2): bit values
    // used in the per-entry protocol_flags field of a cg4/cg5 delta header.
    private static final int CG_FLAG_SIDEDATA = 1;
    private static final int CG_FLAG_FULL_TEXT = 2;

    // Real spec (confirmed against mercurial/revlogutils/constants.py): the bits masked off by
    // REVIDX_DELTA_INFO_FLAGS from a cg4 delta header's flags field (REVIDX_DELTA_IS_SNAPSHOT=
    // 0x400 | REVIDX_DELTA_HAS_QUALITY=0x200 | REVIDX_DELTA_IS_GOOD=0x100 |
    // REVIDX_DELTA_P1_IS_SMALL=0x80 | REVIDX_DELTA_P2_IS_SMALL=0x40) -- these are purely
    // sparse-revlog delta-chain optimization hints, unrelated to revlogv1 content semantics, and
    // they don't overlap with hg4j's existing flags bits such as REVIDX_ISCENSORED (0x8000)
    // either (this range is 0x40-0x400, while ISCENSORED etc. are 0x800 and above).
    private static final int REVIDX_DELTA_INFO_FLAGS_MASK = 0x7C0;

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
            throw new HgCorruptDataException("Security Guard: Changegroup chunk size exceeds maximum allowed limit (20MB): " + payloadLen);
        }
        byte[] payload = new byte[payloadLen];
        int offset = 0;
        while (offset < payloadLen) {
            int count = in.read(payload, offset, payloadLen - offset);
            if (count == -1) {
                throw new HgCorruptDataException("Unexpected EOF while reading changegroup chunk payload of size: " + payloadLen);
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
        public byte[] deltabase; // null if cg1, 20-bytes if cg2/cg3/cg4/cg5
        public int flags;        // 0 if not cg3/cg4/cg5 (cg4: REVIDX_DELTA_INFO_FLAGS already masked off)
        public byte[] delta;     // bdiff-encoded delta against deltabase, UNLESS fullText is true

        // cg4-only (real spec: confirmed against mercurial/changegroup.py's
        // _CHANGEGROUPV4_DELTA_HEADER). The cg5 header has no such fields, so these play no role
        // when parsing/packing cg5 (they keep their default values).
        /** {@code protocol_flags & CG_FLAG_FULL_TEXT}: when true, {@link #delta} is not a bdiff
         * delta but the uncompressed original text itself ("raw full text") -- the content must
         * be used as-is, regardless of {@code deltabase}. Always false for cg1/cg2/cg3/cg5 (this
         * combination doesn't exist there). */
        public boolean fullText;
        /** Delta snapshot depth (a sparse-revlog hint). {@code Integer.MIN_VALUE} means "not
         * set" (written to the wire as the -2 "no info" sentinel when packing); otherwise it is
         * the raw value real hg sent (a value of -1 or lower is also treated as "no info" by
         * real hg itself). */
        public int snapshotLevel = Integer.MIN_VALUE;
        /** This revision's reconstructed full text size, in bytes. */
        public int rawTextSize;
        /** The {@code WireDeltaCompression} enum value (0=NO_COMPRESSION). hg4j never applies
         * additional compression on top of the delta payload, so this value is ignored when
         * parsing -- the payload is always treated as raw bdiff/fulltext. */
        public int encodedCompression;
        /** The source repository's delta base (a storage-optimization hint, 20 bytes; {@code
         * null} is treated as all-zero). */
        public byte[] storageDeltaBase;
        /** The source repository's snapshot-level hint. */
        public int storageSnapshotLevel = -1;

        // cg5-only (real spec: confirmed against _CHANGEGROUPV5_DELTA_HEADER). Plays no role
        // when parsing/packing cg4.
        /** The raw wire {@code protocol_flags} value (cg4 and cg5 place it at different offsets
         * but it means the same thing: bit0=CG_FLAG_SIDEDATA, bit1=CG_FLAG_FULL_TEXT -- cg5 only
         * ever uses the sidedata bit). */
        public int protocolFlags;
        /** For a cg5 entry with {@code protocol_flags & CG_FLAG_SIDEDATA} set, the raw sidedata
         * bytes carried in a separate length-prefixed chunk immediately following the delta
         * chunk. Since hg4j cannot yet write to the revlogv2 sidedata store itself, this is only
         * kept here without loss, not applied to the local revlog. */
        public byte[] sidedata;
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
        } else if ("04".equals(version)) {
            headerSize = 130;
        } else if ("05".equals(version)) {
            headerSize = 103;
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
                throw new HgCorruptDataException("Malformed changegroup header chunk. Length too small: " + chunk.length + " for version: " + detectedVersion);
            }

            ChangeGroupEntry entry = new ChangeGroupEntry();

            if ("04".equals(detectedVersion)) {
                // Real spec (confirmed against mercurial/changegroup.py's
                // _CHANGEGROUPV4_DELTA_HEADER, Mercurial 7.2.2 -- cross-checked directly against
                // cg4 bytes produced by a local hg 7.2): node(20) p1(20) p2(20) deltabase(20)
                // cs(20) flags(H,2) snapshot_level(b,1,signed) raw_size(I,4) encoded_comp(B,1)
                // protocol_flags(B,1) storage_delta_base(20) storage_snapshot_level(b,1,signed) =
                // 130 bytes. Same field order as cg2/cg3 (deltabase comes before cs), with 6
                // more fields appended after that.
                entry.node = slice(chunk, 0);
                entry.p1 = slice(chunk, 20);
                entry.p2 = slice(chunk, 40);
                entry.deltabase = slice(chunk, 60);
                entry.cs = slice(chunk, 80);
                entry.flags = readU16(chunk, 100);
                entry.snapshotLevel = chunk[102]; // signed byte
                entry.rawTextSize = readI32(chunk, 103);
                entry.encodedCompression = chunk[107] & 0xFF;
                entry.protocolFlags = chunk[108] & 0xFF;
                entry.storageDeltaBase = slice(chunk, 109);
                entry.storageSnapshotLevel = chunk[129]; // signed byte

                // Real spec: flags &= ~REVIDX_DELTA_INFO_FLAGS -- the sparse-revlog delta-chain
                // hint bits are unrelated to revlogv1 content semantics, so they are masked off
                // separately.
                entry.flags &= ~REVIDX_DELTA_INFO_FLAGS_MASK;

                entry.fullText = (entry.protocolFlags & CG_FLAG_FULL_TEXT) != 0;

                int deltaLen = chunk.length - headerSize;
                entry.delta = new byte[deltaLen];
                System.arraycopy(chunk, headerSize, entry.delta, 0, deltaLen);
            } else if ("05".equals(detectedVersion)) {
                // Real spec (confirmed against _CHANGEGROUPV5_DELTA_HEADER): protocol_flags(B,1)
                // node(20) p1(20) p2(20) deltabase(20) cs(20) flags(H,2) = 103 bytes. Unlike
                // cg2/cg3, protocol_flags comes first.
                entry.protocolFlags = chunk[0] & 0xFF;
                entry.node = slice(chunk, 1);
                entry.p1 = slice(chunk, 21);
                entry.p2 = slice(chunk, 41);
                entry.deltabase = slice(chunk, 61);
                entry.cs = slice(chunk, 81);
                entry.flags = readU16(chunk, 101);

                int deltaLen = chunk.length - headerSize;
                entry.delta = new byte[deltaLen];
                System.arraycopy(chunk, headerSize, entry.delta, 0, deltaLen);

                // Real spec (cg5unpacker.deltachunk): when the CG_FLAG_SIDEDATA bit is set,
                // sidedata follows immediately after the delta chunk as a separate
                // length-prefixed chunk.
                if ((entry.protocolFlags & CG_FLAG_SIDEDATA) != 0) {
                    byte[] sd = readChunk(in);
                    entry.sidedata = sd != null ? sd : new byte[0];
                }
            } else {
                entry.node = new byte[20];
                entry.p1 = new byte[20];
                entry.p2 = new byte[20];
                entry.cs = new byte[20];

                System.arraycopy(chunk, 0, entry.node, 0, 20);
                System.arraycopy(chunk, 20, entry.p1, 0, 20);
                System.arraycopy(chunk, 40, entry.p2, 0, 20);

                // Real spec (mercurial/changegroup.py): cg1 has node,p1,p2,cs (4 fields, no
                // deltabase -- the delta base is implicitly determined by the stream as "the
                // previous entry": forcedeltaparentprev=True); cg2/cg3 have node,p1,p2,
                // deltabase,cs (5 fields -- deltabase comes before cs).
                if (headerSize >= 100) {
                    entry.deltabase = new byte[20];
                    System.arraycopy(chunk, 60, entry.deltabase, 0, 20);
                    System.arraycopy(chunk, 80, entry.cs, 0, 20);
                } else {
                    System.arraycopy(chunk, 60, entry.cs, 0, 20);
                }
                if (headerSize >= 102) {
                    entry.flags = ((chunk[100] & 0xFF) << 8) | (chunk[101] & 0xFF);
                }

                int deltaLen = chunk.length - headerSize;
                entry.delta = new byte[deltaLen];
                System.arraycopy(chunk, headerSize, entry.delta, 0, deltaLen);
            }

            entries.add(entry);
        }
        return entries;
    }

    private static byte[] slice(byte[] src, int offset) {
        byte[] out = new byte[20];
        System.arraycopy(src, offset, out, 0, 20);
        return out;
    }

    private static int readU16(byte[] src, int offset) {
        return ((src[offset] & 0xFF) << 8) | (src[offset + 1] & 0xFF);
    }

    private static int readI32(byte[] src, int offset) {
        return ((src[offset] & 0xFF) << 24) | ((src[offset + 1] & 0xFF) << 16)
                | ((src[offset + 2] & 0xFF) << 8) | (src[offset + 3] & 0xFF);
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
        public List<ChangeGroupEntry> manifestEntries; // null if cg3/cg4/cg5
        public List<ManifestGroup> manifestGroups;     // cg3/cg4/cg5 treemanifest-capable envelope
        public List<FileGroup> fileGroups;
    }

    /** cg3/cg4/cg5 all write a treemanifest envelope (the root manifest group followed by
     * optional subdirectory groups plus a terminator marker) -- real spec: confirmed against
     * changegroup.py's {@code manifestsend}, which is {@code b''} (no extra terminator) for
     * cg1/cg2 and {@code closechunk()} for cg3/cg4/cg5. */
    private static boolean isTreeCapableVersion(String version) {
        return "03".equals(version) || "04".equals(version) || "05".equals(version);
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

        if (isTreeCapableVersion(detectedVersion)) {
            // Real spec (changegroup.py's generatemanifests(): "if tree: yield
            // _fileheader(tree)") -- the root manifest group (tree == b'') has its delta group
            // arrive directly, with no path chunk in front of it. The root group is always parsed
            // bare first, then any subdirectory groups that may follow (path chunk + delta group
            // pairs -- real hg 7.2.2 doesn't actually support treemanifest subdirectory transfer
            // for cg4 at all, but this is handled defensively the same way regardless) are read,
            // and finally the separate {@code manifestsend} terminator chunk that closes out the
            // whole thing is consumed.
            bundle.manifestGroups = new ArrayList<>();
            ManifestGroup root = new ManifestGroup();
            root.path = "";
            root.entries = parseGroup(in, detectedVersion, versionHolder);
            bundle.manifestGroups.add(root);

            while (true) {
                byte[] pathChunk = readChunk(in);
                if (pathChunk == null) {
                    break;
                }
                ManifestGroup mg = new ManifestGroup();
                // Real hg's own wire format sends a treemanifest
                // subdirectory's path chunk WITH a trailing slash (mercurial/changegroup.py's
                // generatemanifests(): `subtree = tree + p + b'/'`, then `_fileheader(tree)`
                // writes that exact string as the chunk payload).
                // Every hg4j caller of `ManifestGroup.path` (BundleCommand/PushCommand's own
                // writers, FetchCommand#applyBundle's reader, HgRemoteClientV2's in-process
                // wireproto-v2 assembly) uses the NO-trailing-slash convention internally, so this
                // is normalized away right here at the wire deserialization boundary -- keeping
                // every other caller unchanged. See `writeBundle`'s symmetric write-side fix.
                mg.path = stripTrailingSlash(new String(pathChunk, StandardCharsets.UTF_8));
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
            fg.path = new String(pathChunk, StandardCharsets.UTF_8);
            fg.entries = parseGroup(in, detectedVersion, versionHolder);
            bundle.fileGroups.add(fg);
        }
        return bundle;
    }

    // ------------------------------------------------------------------
    // Packing (all of cg1-cg5). HgLocalClient#getBundle negotiates a version from the requester's
    // bundleCaps and calls writeBundle directly for whatever version (01-05) that negotiation
    // picks, so writeEntry/writeBundle support real cg1/cg2/cg3 header layouts
    // alongside cg4/cg5 (mirroring parseGroup's read side, which already
    // handles all five). PushCommand/BundleCommand's own outbound paths are unaffected -- they
    // still always produce cg1, since no known peer needs anything higher for push/local-bundle
    // purposes.
    // ------------------------------------------------------------------

    /** Writes a changegroup chunk: 4-byte big-endian length (including these 4 bytes) + payload. */
    public static void writeChunk(OutputStream out, byte[] payload) throws IOException {
        int len = payload.length + 4;
        out.write((len >>> 24) & 0xFF);
        out.write((len >>> 16) & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(payload);
    }

    /** Writes the zero-length terminal chunk that ends a group or a path-chunk loop. */
    public static void writeTerminalChunk(OutputStream out) throws IOException {
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(0);
    }

    private static void writePathChunk(OutputStream out, String path) throws IOException {
        writeChunk(out, path.getBytes(StandardCharsets.UTF_8));
    }

    /** Real hg's own treemanifest subdirectory path chunk, WITH the trailing slash real hg's wire
     * format requires (see {@link #parseBundle}'s read-side comment for the exact real-hg source
     * evidence). Used only for manifest subdirectory groups -- file group paths never get this
     * treatment (a plain filename like {@code "dir/b.txt"} is not a directory-tree path). */
    private static void writeManifestGroupPathChunk(OutputStream out, String path) throws IOException {
        writePathChunk(out, path.endsWith("/") ? path : path + "/");
    }

    /** Strips exactly one trailing {@code '/'} if present, else returns {@code s} unchanged. */
    private static String stripTrailingSlash(String s) {
        return (!s.isEmpty() && s.charAt(s.length() - 1) == '/') ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * Serializes a single delta entry as a cg1/cg2/cg3/cg4/cg5 wire chunk (header + delta/
     * full-text payload [+ sidedata chunk for cg5 when {@link ChangeGroupEntry#sidedata} is
     * set]). {@code version} must be one of {@code "01"}, {@code "02"}, {@code "03"}, {@code "04"}
     * or {@code "05"}.
     *
     * <p>cg1/cg2/cg3 header layouts mirror {@link #parseGroup}'s read side exactly (byte-for-byte
     * symmetric, verified against real hg 7.2.2 fixtures there): cg1 is {@code node(20) p1(20)
     * p2(20) cs(20)} = 80 bytes with NO explicit deltabase field (real hg's cg1 packer always
     * uses {@code forcedeltaparentprev=True} — the delta base is implicit, "whatever revision was
     * packed immediately before this one in the same group stream" — so {@link
     * ChangeGroupEntry#deltabase} is simply not written here, only used by the caller to decide
     * what content to diff against); cg2 is {@code node(20) p1(20) p2(20) deltabase(20) cs(20)} =
     * 100 bytes (deltabase now explicit, before cs); cg3 adds a trailing {@code flags(u16)} = 102
     * bytes total.
     */
    public static void writeEntry(OutputStream out, ChangeGroupEntry entry, String version) throws IOException {
        byte[] deltabase = entry.deltabase != null ? entry.deltabase : new byte[20];
        byte[] payload;
        if ("01".equals(version)) {
            payload = new byte[80 + entry.delta.length];
            System.arraycopy(entry.node, 0, payload, 0, 20);
            System.arraycopy(entry.p1, 0, payload, 20, 20);
            System.arraycopy(entry.p2, 0, payload, 40, 20);
            System.arraycopy(entry.cs, 0, payload, 60, 20);
            System.arraycopy(entry.delta, 0, payload, 80, entry.delta.length);
            writeChunk(out, payload);
        } else if ("02".equals(version)) {
            payload = new byte[100 + entry.delta.length];
            System.arraycopy(entry.node, 0, payload, 0, 20);
            System.arraycopy(entry.p1, 0, payload, 20, 20);
            System.arraycopy(entry.p2, 0, payload, 40, 20);
            System.arraycopy(deltabase, 0, payload, 60, 20);
            System.arraycopy(entry.cs, 0, payload, 80, 20);
            System.arraycopy(entry.delta, 0, payload, 100, entry.delta.length);
            writeChunk(out, payload);
        } else if ("03".equals(version)) {
            payload = new byte[102 + entry.delta.length];
            System.arraycopy(entry.node, 0, payload, 0, 20);
            System.arraycopy(entry.p1, 0, payload, 20, 20);
            System.arraycopy(entry.p2, 0, payload, 40, 20);
            System.arraycopy(deltabase, 0, payload, 60, 20);
            System.arraycopy(entry.cs, 0, payload, 80, 20);
            writeU16(payload, 100, entry.flags);
            System.arraycopy(entry.delta, 0, payload, 102, entry.delta.length);
            writeChunk(out, payload);
        } else if ("04".equals(version)) {
            payload = new byte[130 + entry.delta.length];
            System.arraycopy(entry.node, 0, payload, 0, 20);
            System.arraycopy(entry.p1, 0, payload, 20, 20);
            System.arraycopy(entry.p2, 0, payload, 40, 20);
            System.arraycopy(deltabase, 0, payload, 60, 20);
            System.arraycopy(entry.cs, 0, payload, 80, 20);
            writeU16(payload, 100, entry.flags & ~REVIDX_DELTA_INFO_FLAGS_MASK);
            int snapshotLevel = entry.snapshotLevel == Integer.MIN_VALUE ? -2 : entry.snapshotLevel;
            payload[102] = (byte) snapshotLevel;
            writeI32(payload, 103, entry.rawTextSize);
            payload[107] = (byte) entry.encodedCompression;
            int protocolFlags = entry.fullText ? CG_FLAG_FULL_TEXT : 0;
            payload[108] = (byte) protocolFlags;
            byte[] storageDeltaBase = entry.storageDeltaBase != null ? entry.storageDeltaBase : deltabase;
            System.arraycopy(storageDeltaBase, 0, payload, 109, 20);
            payload[129] = (byte) entry.storageSnapshotLevel;
            System.arraycopy(entry.delta, 0, payload, 130, entry.delta.length);
            writeChunk(out, payload);
        } else if ("05".equals(version)) {
            int protocolFlags = entry.sidedata != null ? CG_FLAG_SIDEDATA : 0;
            payload = new byte[103 + entry.delta.length];
            payload[0] = (byte) protocolFlags;
            System.arraycopy(entry.node, 0, payload, 1, 20);
            System.arraycopy(entry.p1, 0, payload, 21, 20);
            System.arraycopy(entry.p2, 0, payload, 41, 20);
            System.arraycopy(deltabase, 0, payload, 61, 20);
            System.arraycopy(entry.cs, 0, payload, 81, 20);
            writeU16(payload, 101, entry.flags);
            System.arraycopy(entry.delta, 0, payload, 103, entry.delta.length);
            writeChunk(out, payload);
            if (entry.sidedata != null) {
                writeChunk(out, entry.sidedata);
            }
        } else {
            throw new IllegalArgumentException("writeEntry only supports cg1-cg5, got: " + version);
        }
    }

    private static void writeU16(byte[] dst, int offset, int value) {
        dst[offset] = (byte) ((value >>> 8) & 0xFF);
        dst[offset + 1] = (byte) (value & 0xFF);
    }

    private static void writeI32(byte[] dst, int offset, int value) {
        dst[offset] = (byte) ((value >>> 24) & 0xFF);
        dst[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        dst[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        dst[offset + 3] = (byte) (value & 0xFF);
    }

    private static boolean isSupportedWriteVersion(String version) {
        return "01".equals(version) || "02".equals(version) || "03".equals(version)
                || "04".equals(version) || "05".equals(version);
    }

    /** Writes a whole group of entries (any cg1-cg5 version) followed by its terminal chunk. */
    public static void writeGroup(OutputStream out, List<ChangeGroupEntry> entries, String version) throws IOException {
        for (ChangeGroupEntry entry : entries) {
            writeEntry(out, entry, version);
        }
        writeTerminalChunk(out);
    }

    /**
     * Serializes a whole {@link ChangegroupBundle} as cg1-cg5 wire bytes (the raw changegroup
     * payload only — not wrapped in an HG20/bundle2 envelope). Mirrors {@link #parseBundle}'s
     * envelope structure exactly: for a tree-capable version ({@link #isTreeCapableVersion}, i.e.
     * cg3/cg4/cg5) the manifest section always ends with an extra {@code manifestsend} terminator
     * chunk (real hg emits this even for a flat/non-treemanifest repo, since cg3+'s envelope
     * always supports "possibly more manifest groups"), whereas cg1/cg2 have no such envelope —
     * the single flat manifest group's own end-of-group terminator (written by {@link
     * #writeGroup}) is immediately followed by the file groups, with no extra marker chunk --
     * emitting the extra terminator unconditionally would corrupt a cg1/cg2 stream.
     */
    public static void writeBundle(OutputStream out, ChangegroupBundle bundle, String version) throws IOException {
        if (!isSupportedWriteVersion(version)) {
            throw new IllegalArgumentException("writeBundle only supports cg1-cg5, got: " + version);
        }
        writeGroup(out, bundle.changelogEntries, version);

        boolean treeCapable = isTreeCapableVersion(version);
        if (bundle.manifestGroups != null && !bundle.manifestGroups.isEmpty()) {
            writeGroup(out, bundle.manifestGroups.get(0).entries, version); // bare root group
            for (int i = 1; i < bundle.manifestGroups.size(); i++) {
                ManifestGroup mg = bundle.manifestGroups.get(i);
                writeManifestGroupPathChunk(out, mg.path);
                writeGroup(out, mg.entries, version);
            }
            if (treeCapable) {
                writeTerminalChunk(out); // manifestsend
            }
        } else {
            writeGroup(out, bundle.manifestEntries, version);
            if (treeCapable) {
                writeTerminalChunk(out); // manifestsend
            }
        }

        for (FileGroup fg : bundle.fileGroups) {
            writePathChunk(out, fg.path);
            writeGroup(out, fg.entries, version);
        }
        writeTerminalChunk(out); // end of filelogs
    }
}
