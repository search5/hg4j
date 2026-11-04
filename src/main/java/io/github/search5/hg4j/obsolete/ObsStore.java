package io.github.search5.hg4j.obsolete;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.Base85;
import io.github.search5.hg4j.util.SafeFileIO;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The obsolescence marker store as {@code obsolete.py} handles it for pushkey exchange: markers in both on-disk formats (version 0
 * and 1), the {@code obsolete} pushkey namespace ({@code listmarkers}, {@code pushmarker}) and the merge of received markers into
 * {@code .hg/store/obsstore}.
 */
public final class ObsStore {

    private static final int USING_SHA256 = 1 << 1;
    private static final int FM1_PARENT_NONE = 3;
    /** {@code _maxpayload}: a pushkey value stays below the 5300 bytes a header may carry. */
    private static final int MAX_PAYLOAD = 5300;

    /** One marker; {@code tz} is in seconds, {@code parents} is {@code null} when none were recorded. */
    public record Marker(byte[] pre, List<byte[]> sucs, int flags, TreeMap<String, String> metadata, double date, int tz,
                         List<byte[]> parents) implements Comparable<Marker> {

        String key() {
            StringBuilder sb = new StringBuilder(HexFormat.of().formatHex(pre));
            for (byte[] s : sucs) {
                sb.append(',').append(HexFormat.of().formatHex(s));
            }
            sb.append('|').append(flags).append('|').append(metadata).append('|').append(Double.doubleToLongBits(date)).append('|').append(tz);
            sb.append('|');
            if (parents == null) {
                sb.append('-');
            } else {
                for (byte[] p : parents) {
                    sb.append(HexFormat.of().formatHex(p)).append(',');
                }
            }
            return sb.toString();
        }

        @Override
        public int compareTo(Marker other) {
            int c = Arrays.compareUnsigned(pre, other.pre);
            if (c != 0) {
                return c;
            }
            for (int i = 0; i < Math.min(sucs.size(), other.sucs.size()); i++) {
                c = Arrays.compareUnsigned(sucs.get(i), other.sucs.get(i));
                if (c != 0) {
                    return c;
                }
            }
            c = Integer.compare(sucs.size(), other.sucs.size());
            return c != 0 ? c : key().compareTo(other.key());
        }
    }

    private ObsStore() {
    }

    // ---------------------------------------------------------------- reading

    /** {@code _readmarkers}: the version byte, then the markers of that version. */
    public static List<Marker> read(byte[] data) throws IOException {
        if (data.length == 0) {
            return new ArrayList<>();
        }
        int version = data[0] & 0xFF;
        if (version == 0) {
            return readFm0(data, 1);
        } else if (version == 1) {
            return readFm1(data, 1);
        }
        throw new HgValidationException("parsing obsolete marker: unknown version " + version);
    }

    private static List<Marker> readFm0(byte[] data, int start) throws IOException {
        List<Marker> markers = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int off = start;
        while (off < data.length) {
            if (data.length - off < 26) {
                throw new HgValidationException("parsing obsolete marker: truncated header");
            }
            int numsuc = data[off] & 0xFF;
            long mdsize = buf.getInt(off + 1) & 0xFFFFFFFFL;
            int flags = data[off + 5] & 0xFF;
            byte[] pre = Arrays.copyOfRange(data, off + 6, off + 26);
            off += 26;
            List<byte[]> sucs = new ArrayList<>();
            for (int i = 0; i < numsuc; i++) {
                if (data.length - off < 20) {
                    throw new HgValidationException("parsing obsolete marker: truncated successors");
                }
                sucs.add(Arrays.copyOfRange(data, off, off + 20));
                off += 20;
            }
            if (data.length - off < mdsize) {
                throw new HgValidationException("parsing obsolete marker: metadata is too short, " + mdsize
                        + " bytes expected, got " + (data.length - off));
            }
            TreeMap<String, String> meta = new TreeMap<>();
            for (String piece : new String(data, off, (int) mdsize, StandardCharsets.ISO_8859_1).split("\0")) {
                if (!piece.isEmpty()) {
                    int colon = piece.indexOf(':');
                    meta.put(piece.substring(0, colon), piece.substring(colon + 1));
                }
            }
            off += (int) mdsize;
            double date = 0.0;
            int tz = 0;
            String when = meta.remove("date");
            if (when != null) {
                try {
                    String[] parts = when.split(" ");
                    date = Double.parseDouble(parts[0]);
                    tz = Integer.parseInt(parts[1]);
                } catch (RuntimeException e) {
                    date = 0.0;
                    tz = 0;
                }
            }
            List<String> parentHex = null;
            if (meta.containsKey("p2")) {
                parentHex = new ArrayList<>(Arrays.asList(meta.remove("p1"), meta.remove("p2")));
            } else if (meta.containsKey("p1")) {
                parentHex = new ArrayList<>(Arrays.asList(meta.remove("p1")));
            } else if (meta.containsKey("p0")) {
                parentHex = new ArrayList<>();
            }
            List<byte[]> parents = null;
            if (parentHex != null) {
                parents = new ArrayList<>();
                for (String hex : parentHex) {
                    if (hex == null || hex.length() != 40 || !hex.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                        parents = null; // content that is not a node id is dropped
                        break;
                    }
                    parents.add(HexFormat.of().parseHex(hex));
                }
            }
            markers.add(new Marker(pre, sucs, flags, meta, date, tz, parents));
        }
        return markers;
    }

    private static List<Marker> readFm1(byte[] data, int start) throws IOException {
        List<Marker> markers = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int off = start;
        while (off < data.length) {
            if (data.length - off < 19) {
                throw new HgValidationException("parsing obsolete marker: truncated header");
            }
            buf.position(off);
            int total = buf.getInt();
            double date = buf.getDouble();
            int tz = buf.getShort() * 60;
            int flags = buf.getShort() & 0xFFFF;
            int numsuc = buf.get() & 0xFF;
            int numpar = buf.get() & 0xFF;
            int nummeta = buf.get() & 0xFF;
            int nodeSize = (flags & USING_SHA256) != 0 ? 32 : 20;
            if (total < 19 || off + total > data.length) {
                throw new HgValidationException("parsing obsolete marker: record size mismatch");
            }
            byte[] pre = new byte[nodeSize];
            buf.get(pre);
            List<byte[]> sucs = new ArrayList<>();
            for (int i = 0; i < numsuc; i++) {
                byte[] s = new byte[nodeSize];
                buf.get(s);
                sucs.add(s);
            }
            List<byte[]> parents = null;
            if (numpar != FM1_PARENT_NONE) {
                parents = new ArrayList<>();
                for (int i = 0; i < numpar; i++) {
                    byte[] p = new byte[nodeSize];
                    buf.get(p);
                    parents.add(p);
                }
            }
            int[] lengths = new int[nummeta * 2];
            for (int i = 0; i < lengths.length; i++) {
                lengths[i] = buf.get() & 0xFF;
            }
            TreeMap<String, String> meta = new TreeMap<>();
            for (int i = 0; i < nummeta; i++) {
                byte[] k = new byte[lengths[i * 2]];
                buf.get(k);
                byte[] v = new byte[lengths[i * 2 + 1]];
                buf.get(v);
                meta.put(new String(k, StandardCharsets.ISO_8859_1), new String(v, StandardCharsets.ISO_8859_1));
            }
            if (buf.position() - off != total) {
                throw new HgValidationException("parsing obsolete marker: record size mismatch");
            }
            markers.add(new Marker(pre, sucs, flags, meta, date, tz, parents));
            off += total;
        }
        return markers;
    }

    // ---------------------------------------------------------------- writing

    /** Python's {@code repr(float)} for the dates a marker holds. */
    static String repr(double value) {
        String s = Double.toString(value);
        if (s.contains("E")) {
            s = new BigDecimal(s).toPlainString();
            if (!s.contains(".")) {
                s += ".0";
            }
        }
        return s;
    }

    /** {@code _fm0encodeonemarker}. */
    static byte[] encodeFm0(Marker m) throws IOException {
        if ((m.flags() & USING_SHA256) != 0) {
            throw new HgValidationException("cannot handle sha256 with old obsstore format");
        }
        TreeMap<String, String> meta = new TreeMap<>(m.metadata());
        meta.put("date", repr(m.date()) + " " + m.tz());
        if (m.parents() != null) {
            if (m.parents().isEmpty()) {
                meta.put("p0", "");
            }
            for (int i = 0; i < m.parents().size(); i++) {
                meta.put("p" + (i + 1), HexFormat.of().formatHex(m.parents().get(i)));
            }
        }
        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<String, String> e : meta.entrySet()) {
            if (encoded.length() > 0) {
                encoded.append('\0');
            }
            encoded.append(e.getKey()).append(':').append(e.getValue());
        }
        byte[] metadata = encoded.toString().getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer buf = ByteBuffer.allocate(26 + 20 * m.sucs().size() + metadata.length).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) m.sucs().size());
        buf.putInt(metadata.length);
        buf.put((byte) m.flags());
        buf.put(m.pre());
        for (byte[] s : m.sucs()) {
            buf.put(s);
        }
        buf.put(metadata);
        return buf.array();
    }

    /** {@code _fm1encodeonemarker}. */
    static byte[] encodeFm1(Marker m) throws IOException {
        int nodeSize = (m.flags() & USING_SHA256) != 0 ? 32 : 20;
        int nodes = 1 + m.sucs().size() + (m.parents() == null ? 0 : m.parents().size());
        int total = 19 + nodeSize * nodes + 2 * m.metadata().size();
        for (Map.Entry<String, String> e : m.metadata().entrySet()) {
            if (e.getKey().length() > 255 || e.getValue().length() > 255) {
                throw new HgValidationException("obsolete marker metadata entry is longer than 255 bytes");
            }
            total += e.getKey().length() + e.getValue().length();
        }
        ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(total);
        buf.putDouble(m.date());
        buf.putShort((short) Math.floorDiv(m.tz(), 60));
        buf.putShort((short) m.flags());
        buf.put((byte) m.sucs().size());
        buf.put((byte) (m.parents() == null ? FM1_PARENT_NONE : m.parents().size()));
        buf.put((byte) m.metadata().size());
        buf.put(m.pre());
        for (byte[] s : m.sucs()) {
            buf.put(s);
        }
        if (m.parents() != null) {
            for (byte[] p : m.parents()) {
                buf.put(p);
            }
        }
        for (Map.Entry<String, String> e : m.metadata().entrySet()) {
            buf.put((byte) e.getKey().length());
            buf.put((byte) e.getValue().length());
        }
        for (Map.Entry<String, String> e : m.metadata().entrySet()) {
            buf.put(e.getKey().getBytes(StandardCharsets.ISO_8859_1));
            buf.put(e.getValue().getBytes(StandardCharsets.ISO_8859_1));
        }
        return buf.array();
    }

    // ---------------------------------------------------------------- the store and the pushkey namespace

    private static File obsstoreFile(HgRepository repo) {
        return new File(repo.getStoreDir(), "obsstore");
    }

    /** {@code obsolete.isenabled(repo, exchangeopt)}: {@code experimental.evolution} names {@code exchange} or {@code all}. */
    public static boolean exchangeEnabled(HgRepository repo) {
        String evolution = repo.getConfig().get("experimental", "evolution", "");
        for (String option : evolution.split("[,\\s]+")) {
            if (option.equals("all") || option.equals("exchange")) {
                return true;
            }
        }
        String direct = repo.getConfig().get("experimental", "evolution.exchange", "false").trim().toLowerCase(java.util.Locale.ROOT);
        return direct.equals("true") || direct.equals("1") || direct.equals("yes") || direct.equals("on");
    }

    /** The markers of the store, in file order. */
    public static List<Marker> markers(HgRepository repo) throws IOException {
        File file = obsstoreFile(repo);
        return file.isFile() ? read(Files.readAllBytes(file.toPath())) : new ArrayList<>();
    }

    /** {@code _pushkeyescape(sorted(repo.obsstore))}: {@code dumpN} keys holding base85 of version 0 markers, 5300 bytes at most each. */
    public static Map<String, String> listMarkers(HgRepository repo) throws IOException {
        List<Marker> sorted = new ArrayList<>(markers(repo));
        java.util.Collections.sort(sorted);
        List<List<byte[]>> parts = new ArrayList<>();
        int currentLength = MAX_PAYLOAD * 2; // makes the first marker open a part
        for (Marker marker : sorted) {
            byte[] next = encodeFm0(marker);
            if (next.length + currentLength > MAX_PAYLOAD) {
                parts.add(new ArrayList<>());
                currentLength = 0;
            }
            parts.get(parts.size() - 1).add(next);
            currentLength += next.length;
        }
        Map<String, String> keys = new LinkedHashMap<>();
        for (int idx = 0; idx < parts.size(); idx++) {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            data.write(0); // _fm0version
            for (byte[] chunk : parts.get(parts.size() - 1 - idx)) {
                data.write(chunk);
            }
            keys.put("dump" + idx, Base85.encode(data.toByteArray()));
        }
        return keys;
    }

    /**
     * {@code obsolete.pushmarker}: the markers of a {@code dumpN} value are merged into the store under {@code repo.lock()}. A key that
     * does not start with {@code dump} or a non-empty old value is refused ({@code false}).
     */
    public static boolean pushMarker(HgRepository repo, String key, String old, String value) throws IOException, HgLockException {
        if (key == null || !key.startsWith("dump")) {
            System.err.print("unknown key: b'" + key + "'");
            return false;
        }
        if (old != null && !old.isEmpty()) {
            System.err.print("unexpected old value for b'" + key + "'");
            return false;
        }
        byte[] data;
        try {
            data = Base85.decode(value == null ? "" : value);
        } catch (IllegalArgumentException e) {
            return false;
        }
        try (HgLock lock = repo.lockStore()) {
            return mergeMarkers(repo, data) >= 0;
        }
    }

    /**
     * {@code obsstore.mergemarkers} + {@code add}: the markers not yet known are appended, in the store's own version. A failure
     * inside the append removes what was written (the transaction aborting).
     *
     * @return how many markers were new
     */
    static int mergeMarkers(HgRepository repo, byte[] data) throws IOException {
        List<Marker> received = read(data);
        File file = obsstoreFile(repo);
        List<Marker> existing = markers(repo);
        Set<String> known = new LinkedHashSet<>();
        for (Marker m : existing) {
            known.add(m.key());
        }
        List<Marker> fresh = new ArrayList<>();
        for (Marker m : received) {
            if (known.add(m.key())) {
                fresh.add(m);
            }
        }
        if (fresh.isEmpty()) {
            return 0;
        }
        int version = file.isFile() && file.length() > 0 ? Files.readAllBytes(file.toPath())[0] & 0xFF : 1;
        long before = file.isFile() ? file.length() : 0;
        SafeFileIO.breakHardLinks(file);
        try (java.io.FileOutputStream out = SafeFileIO.openAppend(file)) {
            if (before == 0) {
                out.write(version);
            }
            for (Marker m : fresh) {
                out.write(version == 0 ? encodeFm0(m) : encodeFm1(m));
            }
            out.getFD().sync();
        } catch (IOException | RuntimeException failed) {
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                raf.setLength(before);
            }
            if (before == 0) {
                Files.deleteIfExists(file.toPath());
            }
            throw failed;
        }
        return fresh.size();
    }
}
