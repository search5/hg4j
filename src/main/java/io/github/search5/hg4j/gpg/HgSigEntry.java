package io.github.search5.hg4j.gpg;

import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * One line of Mercurial's {@code .hgsigs} (or {@code .hg/localsigs}): {@code <40 hex node> <version>
 * <base64 signature>}. The version is always {@code 0} today; a signature of version 0 signs the text
 * {@code hex(node) + "\n"} (see {@link #signedText}).
 *
 * <p>Format source: {@code hgext/gpg.py} ({@code sigwalk}, {@code node2txt}, {@code _dosign}).</p>
 */
public final class HgSigEntry {

    /** The only signature version Mercurial defines. */
    public static final String VERSION_0 = "0";

    private final String nodeHex;
    private final String version;
    private final byte[] signature;

    /**
     * @param nodeHex   the signed changeset's node, 40 lowercase hex digits
     * @param version   the signature version ({@link #VERSION_0})
     * @param signature the binary OpenPGP detached signature (not base64)
     */
    public HgSigEntry(String nodeHex, String version, byte[] signature) {
        this.nodeHex = Objects.requireNonNull(nodeHex, "nodeHex");
        this.version = Objects.requireNonNull(version, "version");
        this.signature = Objects.requireNonNull(signature, "signature").clone();
    }

    public String getNodeHex() {
        return nodeHex;
    }

    public String getVersion() {
        return version;
    }

    /** @return a copy of the binary detached signature */
    public byte[] getSignature() {
        return signature.clone();
    }

    /**
     * The exact bytes that are signed for {@code version} 0: the node as lowercase hex plus a newline.
     *
     * @throws HgSigFormatException for any other version (real hg aborts with "unknown signature version")
     */
    public static byte[] signedText(String nodeHex, String version) throws HgSigFormatException {
        if (!VERSION_0.equals(version)) {
            throw new HgSigFormatException("unknown signature version: " + version);
        }
        return (nodeHex + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    /** @return the bytes this entry's signature covers */
    public byte[] signedText() throws HgSigFormatException {
        return signedText(nodeHex, version);
    }

    /** @return the file line without the trailing newline: {@code <hex> <version> <base64>} */
    public String toLine() {
        return nodeHex + " " + version + " " + Base64.getEncoder().encodeToString(signature);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HgSigEntry e && nodeHex.equals(e.nodeHex) && version.equals(e.version)
                && Arrays.equals(signature, e.signature);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeHex, version) * 31 + Arrays.hashCode(signature);
    }

    @Override
    public String toString() {
        return "HgSigEntry[" + nodeHex + " v" + version + "]";
    }
}
