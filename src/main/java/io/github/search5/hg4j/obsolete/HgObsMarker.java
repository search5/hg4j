package io.github.search5.hg4j.obsolete;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.io.FileWriter;
import java.nio.file.Files;

/**
 * Represents a single Obsolescence Marker (Evolve mechanism).
 * Marks a predecessor revision as "obsolete" and maps it to optional successor revisions.
 */
public final class HgObsMarker {
    private final byte[] predecessor;
    private final List<byte[]> successors;
    private final int flags;
    private final Map<String, String> metadata;

    public HgObsMarker(byte[] predecessor, List<byte[]> successors, int flags, Map<String, String> metadata) {
        if (predecessor == null || predecessor.length != 20) {
            throw new IllegalArgumentException("Predecessor node must be exactly 20 bytes");
        }
        this.predecessor = predecessor.clone();
        this.successors = successors != null ? successors : List.of();
        this.flags = flags;
        this.metadata = metadata != null ? metadata : Map.of();
    }

    public byte[] getPredecessor() {
        return predecessor.clone();
    }

    public List<byte[]> getSuccessors() {
        return successors.stream().map(byte[]::clone).toList();
    }

    public int getFlags() {
        return flags;
    }

    public Map<String, String> getMetadata() {
        return Map.copyOf(metadata);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HgObsMarker that = (HgObsMarker) o;
        return flags == that.flags &&
                Arrays.equals(predecessor, that.predecessor) &&
                successorsEqual(this.successors, that.successors) &&
                metadata.equals(that.metadata);
    }

    private static boolean successorsEqual(List<byte[]> a, List<byte[]> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!Arrays.equals(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(predecessor);
        result = 31 * result + successors.size();
        result = 31 * result + flags;
        result = 31 * result + metadata.hashCode();
        return result;
    }

    /**
     * Appends a single marker in obsstore (FM1, version=1) format.
     *
     * <p>This layout was verified by decoding real obsstore bytes -- obtained by running an
     * amend via the real hg CLI (`--config experimental.evolution.createmarkers=true`) --
     * directly with {@code mercurial.obsolete._readmarkers()}.</p>
     *
     * <p>Fixed header (19 bytes, {@code mercurial/obsolete.py}'s {@code _fm1fixed = '>IdhHBBB'}):
     * totalsize(I,4) + date_secs(d,8) + tz_minutes(h,2) + flags(H,2) + numsuc(B,1) +
     * numpar(B,1) + nummeta(B,1). Followed by predecessor(20B) + successors(20B*numsuc) +
     * (parents are always omitted, since numpar=3 means "not recorded") + the metapair length
     * table (2B*nummeta) + the raw metadata bytes.</p>
     *
     * @apiNote Called by {@code AmendCommand} (marking the pre-amend revision obsolete),
     *     {@code HisteditCommand}/{@code StripCommand} (marking pruned revisions obsolete, with
     *     no successor), and {@code RebaseCommand} (marking each original revision obsolete in
     *     favor of its rebased copy). {@code BookmarkCommand}/{@code PushCommand} read markers
     *     back via {@link HgObsolescenceParser#parse}.
     */
    public static void writeMarker(File storeDir, byte[] predecessor, List<byte[]> successors, String operation) throws IOException {
        File obsstoreFile = new File(storeDir, "obsstore");
        boolean writeVersionByte = !obsstoreFile.exists() || obsstoreFile.length() == 0;

        ensureEvolutionConfigEnabled(storeDir);

        List<byte[]> succList = successors != null ? successors : List.of();
        int numsuc = succList.size();
        final int NUMPAR_NONE = 3; // _fm1parentnone: parent information is not recorded
        final int NODE_SIZE = 20;  // sha1 (usingsha256 flag not used)

        LinkedHashMap<String, String> meta = new LinkedHashMap<>();
        meta.put("operation", operation != null ? operation : "amend");
        meta.put("user", "hg4j");

        int fixedSize = 19; // I(4)+d(8)+h(2)+H(2)+B(1)+B(1)+B(1)
        int nodesSection = NODE_SIZE * (1 + numsuc); // predecessor + successors only, parents omitted
        int metaPairsSection = 2 * meta.size();
        int metaBytesLen = 0;
        for (Map.Entry<String, String> e : meta.entrySet()) {
            metaBytesLen += e.getKey().getBytes(StandardCharsets.UTF_8).length
                    + e.getValue().getBytes(StandardCharsets.UTF_8).length;
        }
        int totalSize = fixedSize + nodesSection + metaPairsSection + metaBytesLen;

        ByteBuffer buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(totalSize);
        buf.putDouble(System.currentTimeMillis() / 1000.0);
        buf.putShort((short) 0); // tz (minutes) -- recorded as UTC for simplicity
        buf.putShort((short) 0); // flags -- sha256 not used
        buf.put((byte) numsuc);
        buf.put((byte) NUMPAR_NONE);
        buf.put((byte) meta.size());
        buf.put(predecessor, 0, NODE_SIZE);
        for (byte[] succ : succList) {
            buf.put(succ, 0, NODE_SIZE);
        }
        for (Map.Entry<String, String> e : meta.entrySet()) {
            buf.put((byte) e.getKey().getBytes(StandardCharsets.UTF_8).length);
            buf.put((byte) e.getValue().getBytes(StandardCharsets.UTF_8).length);
        }
        for (Map.Entry<String, String> e : meta.entrySet()) {
            buf.put(e.getKey().getBytes(StandardCharsets.UTF_8));
            buf.put(e.getValue().getBytes(StandardCharsets.UTF_8));
        }

        try (FileOutputStream out = new FileOutputStream(obsstoreFile, true)) {
            if (writeVersionByte) {
                out.write(1); // _fm1version
            }
            out.write(buf.array());
            out.getFD().sync();
        }
    }

    /**
     * Makes sure a real hg CLI plain {@code hg verify} (no special {@code --config}) does not flag
     * a freshly-written marker as invalid.
     *
     * <p>Real hg gates whether obsstore markers are considered legitimate purely by a UI config
     * check ({@code obsolete.isenabled()} / {@code obsolete._getoptionvalue()} in {@code
     * mercurial/obsolete.py}, reading {@code experimental.evolution.createmarkers} or the broader
     * {@code experimental.evolution}) -- NOT by anything recorded in {@code .hg/requires} or in the
     * obsstore file itself: {@code hg debugobsolete <node>} with no special config aborts
     * outright ("creating obsolete markers is
     * not enabled on this repo"), and even forcing the write via {@code --config
     * experimental.evolution.createmarkers=true} still leaves a repo that a subsequent plain {@code
     * hg verify} (without that same config) reports as broken: {@code "obsolete" feature not
     * enabled but 1 markers found!}, counted among its integrity errors.
     *
     * <p>hg4j has no general hgrc config-parsing layer, so rather than gate marker writes on a
     * config lookup, it does what a real user enabling evolution would do: persist the enabling
     * config into the repository's own {@code .hg/hgrc} the first time this repo ever gets a
     * marker, so every subsequent plain {@code hg} invocation (verify included) picks it up the
     * same way it would for a real evolve-enabled repository. Idempotent: does nothing once the
     * setting is already present anywhere in the file.
     */
    private static void ensureEvolutionConfigEnabled(File storeDir) throws IOException {
        File hgDir = storeDir.getParentFile(); // .hg/store -> .hg
        File hgrc = new File(hgDir, "hgrc");
        String existing = "";
        if (hgrc.exists()) {
            existing = new String(Files.readAllBytes(hgrc.toPath()), StandardCharsets.UTF_8);
            if (existing.contains("createmarkers") || existing.contains("evolution")) {
                return;
            }
        }
        String addition = "[experimental]\nevolution.createmarkers = true\n";
        try (FileWriter w = new FileWriter(hgrc, StandardCharsets.UTF_8, true)) {
            w.write(addition);
        }
    }
}
