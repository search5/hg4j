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
 */
public class ChangegroupParser {
    private static final Logger LOGGER = Logger.getLogger(ChangegroupParser.class.getName());

    // 실제 스펙(mercurial/utils/storageutil.py, Mercurial 7.2.2 실측): cg4/cg5 델타 헤더의
    // per-entry protocol_flags 필드에서 쓰는 비트 값.
    private static final int CG_FLAG_SIDEDATA = 1;
    private static final int CG_FLAG_FULL_TEXT = 2;

    // 실제 스펙(mercurial/revlogutils/constants.py 실측): cg4의 델타 헤더 flags 필드에서
    // REVIDX_DELTA_INFO_FLAGS로 마스킹해 제거하는 비트들(REVIDX_DELTA_IS_SNAPSHOT=0x400 |
    // REVIDX_DELTA_HAS_QUALITY=0x200 | REVIDX_DELTA_IS_GOOD=0x100 | REVIDX_DELTA_P1_IS_SMALL=0x80
    // | REVIDX_DELTA_P2_IS_SMALL=0x40) — sparse-revlog 델타 체인 최적화 힌트일 뿐 revlogv1
    // 콘텐츠 의미와 무관하고, hg4j의 기존 REVIDX_ISCENSORED(0x8000) 등 flags 비트와도 겹치지
    // 않는다(0x40~0x400 범위, ISCENSORED 등은 0x800 이상).
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

        // cg4-only (실제 스펙: mercurial/changegroup.py의 _CHANGEGROUPV4_DELTA_HEADER 실측).
        // cg5 헤더에는 이 필드들이 없으므로 cg5 파싱/패킹 시엔 관여하지 않는다(기본값 유지).
        /** {@code protocol_flags & CG_FLAG_FULL_TEXT}: true면 {@link #delta}는 bdiff 델타가
         * 아니라 압축되지 않은 원문 그대로("raw full text")다 — {@code deltabase}와 무관하게
         * 콘텐츠를 그대로 사용해야 한다. cg1/cg2/cg3/cg5는 항상 false(이 조합 자체가 없음). */
        public boolean fullText;
        /** 델타 스냅샷 깊이(sparse-revlog 힌트). {@code Integer.MIN_VALUE}는 "미설정"(패킹 시
         * 와이어에 -2 "no info" 센티널로 씀), 그 외엔 실제 hg가 보낸 원시 값(-1 이하는 실제
         * hg 쪽에서도 "정보 없음"으로 취급됨). */
        public int snapshotLevel = Integer.MIN_VALUE;
        /** 이 리비전의 복원된 전체 텍스트 크기(바이트). */
        public int rawTextSize;
        /** {@code WireDeltaCompression} 열거값(0=NO_COMPRESSION). hg4j는 델타 페이로드에 별도
         * 압축을 얹지 않으므로 파싱 시 값과 무관하게 항상 원시 bdiff/원문으로 취급한다. */
        public int encodedCompression;
        /** 소스 저장소의 델타 베이스(스토리지 최적화 힌트, 20바이트, null이면 all-zero로 취급). */
        public byte[] storageDeltaBase;
        /** 소스 저장소의 스냅샷 레벨 힌트. */
        public int storageSnapshotLevel = -1;

        // cg5-only (실제 스펙: _CHANGEGROUPV5_DELTA_HEADER 실측). cg4 파싱/패킹 시엔 관여하지
        // 않는다.
        /** 와이어 {@code protocol_flags} 원시값(cg4/cg5 공통 위치는 다르지만 의미는 같음:
         * bit0=CG_FLAG_SIDEDATA, bit1=CG_FLAG_FULL_TEXT — cg5는 sidedata만 사용). */
        public int protocolFlags;
        /** {@code protocol_flags & CG_FLAG_SIDEDATA}가 설정된 cg5 엔트리에 한해, 델타 청크
         * 바로 뒤에 오는 별도의 길이-프리픽스 청크로 전달되는 원시 sidedata 바이트.
         * revlogv2 sidedata 저장소 자체를 hg4j가 아직 쓰지 못하므로(별도 백로그) 여기서는
         * 손실 없이 보관만 하고 로컬 revlog에는 반영하지 않는다. */
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
                // 실제 스펙(mercurial/changegroup.py의 _CHANGEGROUPV4_DELTA_HEADER, Mercurial
                // 7.2.2 실측 — 로컬 hg 7.2로 직접 만든 cg4 바이트와 대조 완료): node(20) p1(20)
                // p2(20) deltabase(20) cs(20) flags(H,2) snapshot_level(b,1,signed)
                // raw_size(I,4) encoded_comp(B,1) protocol_flags(B,1) storage_delta_base(20)
                // storage_snapshot_level(b,1,signed) = 130바이트. cg2/cg3와 필드 순서는 같지만
                // (deltabase가 cs보다 앞) 그 뒤에 6개 필드가 더 붙는다.
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

                // 실제 스펙: flags &= ~REVIDX_DELTA_INFO_FLAGS — sparse-revlog 델타 체인
                // 힌트 비트는 revlogv1 콘텐츠 의미와 무관하므로 분리해서 걷어낸다.
                entry.flags &= ~REVIDX_DELTA_INFO_FLAGS_MASK;

                entry.fullText = (entry.protocolFlags & CG_FLAG_FULL_TEXT) != 0;

                int deltaLen = chunk.length - headerSize;
                entry.delta = new byte[deltaLen];
                System.arraycopy(chunk, headerSize, entry.delta, 0, deltaLen);
            } else if ("05".equals(detectedVersion)) {
                // 실제 스펙(_CHANGEGROUPV5_DELTA_HEADER 실측): protocol_flags(B,1) node(20)
                // p1(20) p2(20) deltabase(20) cs(20) flags(H,2) = 103바이트. cg2/cg3와 달리
                // protocol_flags가 맨 앞에 온다.
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

                // 실제 스펙(cg5unpacker.deltachunk): CG_FLAG_SIDEDATA 비트가 서 있으면 델타
                // 청크 바로 뒤에 별도 length-prefixed 청크로 sidedata가 온다.
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

                // 실제 스펙(mercurial/changegroup.py): cg1은 node,p1,p2,cs(4필드,
                // deltabase 없음 — 델타 베이스는 스트림상 "이전 항목"으로 암묵적으로
                // 결정된다: forcedeltaparentprev=True), cg2/cg3는 node,p1,p2,
                // deltabase,cs(5필드 — deltabase가 cs보다 앞에 옴)다. 기존 코드는
                // cg2/cg3에서도 cs를 60바이트 오프셋에서 읽고 deltabase를 80바이트
                // 오프셋에서 읽었는데, 이는 두 필드의 순서가 뒤바뀐 것이다. changelog
                // 그룹에서는 cs(linknode)가 자기 자신의 node와 같은 값이므로, 이 버그는
                // deltabase가 항상 자기 자신의 node와 같아지는 형태로 나타난다
                // (2026-09-01 발견·수정 — 실제 hg가 만든 번들의 unbundle 시
                // "Delta base revision not found" 오류로 발견).
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

    /** cg3/cg4/cg5 모두 treemanifest 봉투(루트 매니페스트 그룹 뒤에 선택적 서브디렉터리
     * 그룹들 + 종료 마커)를 쓴다(실제 스펙: changegroup.py의 {@code manifestsend} — cg1/cg2는
     * {@code b''}(추가 종료 마커 없음), cg3/cg4/cg5는 {@code closechunk()} 실측). */
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
            // 실제 스펙(changegroup.py의 generatemanifests(): "if tree: yield _fileheader(tree)")
            // — 루트 매니페스트 그룹(tree == b'')은 경로 청크 없이 델타 그룹이 바로 온다. 이전
            // 코드는 루프 첫 반복에서 무조건 readChunk()를 "경로 청크"로 해석해 루트 그룹의
            // 첫 델타 엔트리를 통째로 (엉뚱한) 경로 이름으로 먹어버리고 나머지 엔트리들을
            // 서브디렉터리로 잘못 분류하는 버그가 있었다 — 실제 hg 7.2로 만든 cg3/cg4/cg5
            // 번들(플랫 매니페스트, 서브디렉터리 없음)을 직접 바이트 단위로 대조해 발견
            // (2026-09-03). 루트 그룹을 먼저 무조건 bare로 파싱한 뒤, 있을 수 있는
            // 서브디렉터리 그룹들(경로 청크 + 델타 그룹 쌍, 실제 hg 7.2.2 기준 cg4는
            // treemanifest 서브디렉터리 전송을 아예 지원하지 않지만 방어적으로 동일하게
            // 처리)을 읽고, 전체를 끝맺는 별도의 {@code manifestsend} 종료 청크까지 소비한다.
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
                mg.path = new String(pathChunk, StandardCharsets.UTF_8);
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
    // Packing (all of cg1-cg5). Originally cg4/cg5-only -- HgLocalClient/PushCommand/BundleCommand
    // used to build cg1 "HG10UN" wire bytes ad hoc by hand instead of calling this. Since backlog
    // item 26 (2026-09-04), HgLocalClient#getBundle negotiates a version from the requester's
    // bundleCaps and calls writeBundle directly for whatever version (01-05) that negotiation
    // picks, so writeEntry/writeBundle had to grow real cg1/cg2/cg3 header-layout support
    // alongside the pre-existing cg4/cg5 one (mirroring parseGroup's read side, which already
    // handled all five). PushCommand/BundleCommand's own outbound paths are unaffected -- they
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
     * #writeGroup}) is immediately followed by the file groups, with no extra marker chunk (a bug
     * fixed 2026-09-04: this method used to always emit the extra terminator regardless of
     * version, which would have corrupted a cg1/cg2 stream the moment this method was wired to
     * versions below cg4).
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
                writePathChunk(out, mg.path);
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
