package com.github.search5.hg4j.storage;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Reader for the on-disk "persistent nodemap" ({@code persistent-nodemap} requirement) that real
 * Mercurial keeps next to a non-inline revlog index — a small docket file ({@code <radix>.n}) plus
 * a companion raw trie data file ({@code <radix>-<uid>.nd}) — so that a node hash can be resolved
 * to a revision number without scanning the (potentially huge) revlog index.
 *
 * <p>The exact binary layout was determined by reading real Mercurial's
 * {@code mercurial/revlogutils/nodemap.py} (docket serialization + trie encoding) directly — not
 * guessed — and cross-checked against bytes produced by a real Rust-enabled {@code hg} 7.2.4
 * (this environment's Python-only {@code hg} cannot create persistent-nodemap repositories at
 * all). See {@code src/test/resources/fixtures/persistent-nodemap/README.md} for how the fixture
 * was generated and independently re-verified with {@code mercurial.revlogutils.nodemap} itself.
 *
 * <h2>Docket file ({@code <radix>.n})</h2>
 * <pre>
 * offset 0:            version (1 byte, unsigned) — must be 1 (ONDISK_VERSION); anything else
 *                       means the docket format is unknown and must be ignored.
 * offset 1:             uid_size (1 byte, unsigned)
 * offset 2:             tip_rev (8 bytes, big-endian unsigned)
 * offset 10:            data_length (8 bytes, big-endian unsigned) — size in bytes of the valid
 *                       prefix of the companion .nd file; anything beyond this is stale/unused.
 * offset 18:            data_unused (8 bytes, big-endian unsigned) — bytes of dead/orphaned trie
 *                       blocks still physically present in the .nd file (informational only).
 * offset 26:            tip_node_size (8 bytes, big-endian unsigned) — normally 20.
 * offset 34:            uid (uid_size bytes, ASCII) — identifies the companion "<radix>-<uid>.nd"
 *                       raw trie data file.
 * offset 34+uid_size:    tip_node (tip_node_size bytes) — the full node hash of revision tip_rev;
 *                       if it doesn't match the revlog's actual node at tip_rev, the docket is
 *                       stale (e.g. history-rewriting since the nodemap was last persisted) and
 *                       must be discarded.
 * </pre>
 *
 * <h2>Raw trie data file ({@code <radix>-<uid>.nd})</h2>
 * A sequence of fixed-size 64-byte blocks. Each block holds 16 big-endian signed 32-bit entries,
 * indexed by hex nibble (0-f) of the node hash at the block's depth in the trie. An entry is one
 * of:
 * <ul>
 *   <li>{@code -1} — no entry for this nibble.</li>
 *   <li>{@code >= 0} — index of a child block (byte offset = value * 64).</li>
 *   <li>{@code < -1} — a leaf: revision number = {@code -(value + 2)}.</li>
 * </ul>
 * The root block is always the very last block within the first {@code data_length} bytes of the
 * file (real hg's trie serialization always writes children before their parent, see
 * {@code nodemap.py:_walk_trie}), so {@code rootBlockIndex = data_length / 64 - 1}.
 */
public final class NodeMapFile {

    private static final int ONDISK_VERSION = 1;
    private static final int BLOCK_SIZE = 64; // 16 * 4-byte big-endian signed ints
    private static final int NO_ENTRY = -1;
    private static final int REV_OFFSET = 2;

    private final int tipRev;
    private final byte[] tipNode;
    private final long dataLength;
    private final long dataUnused;
    private final int[][] blocks;
    private final int rootBlockIndex;

    private NodeMapFile(int tipRev, byte[] tipNode, long dataLength, long dataUnused, int[][] blocks) {
        this.tipRev = tipRev;
        this.tipNode = tipNode;
        this.dataLength = dataLength;
        this.dataUnused = dataUnused;
        this.blocks = blocks;
        this.rootBlockIndex = blocks.length - 1;
    }

    public int getTipRev() {
        return tipRev;
    }

    public byte[] getTipNode() {
        return tipNode;
    }

    public long getDataLength() {
        return dataLength;
    }

    public long getDataUnused() {
        return dataUnused;
    }

    /**
     * Walks the trie for the given (clipped, 20-byte) node hash. Returns the revision number
     * encoded at the leaf the walk terminates on, or {@code null} if the trie has no entry along
     * that path.
     *
     * <p><b>Callers must independently verify the returned revision's actual node hash matches
     * {@code node}</b> before trusting it: a query for a node hash that is <em>not</em> in the
     * revlog at all, but happens to share a hex prefix with one that is, will walk down to that
     * other revision's leaf and return it — the trie only guarantees prefixes are unique among
     * nodes it was actually built from, it does not itself store full node hashes to confirm an
     * exact match (this mirrors real hg's own reference nodemap.py {@code _find_node}, and is why
     * production Mercurial's C/Rust nodetree implementations always confirm the candidate's full
     * node before trusting a trie hit).
     */
    public Integer findRevision(byte[] node20) {
        if (blocks.length == 0) {
            return null;
        }
        String hex = toHex(node20);
        int blockIndex = rootBlockIndex;
        for (int level = 0; level < hex.length(); level++) {
            int nibble = Character.digit(hex.charAt(level), 16);
            int value = blocks[blockIndex][nibble];
            if (value == NO_ENTRY) {
                return null;
            } else if (value >= 0) {
                if (value >= blocks.length) {
                    return null; // corrupt trie: dangling child block reference
                }
                blockIndex = value;
            } else {
                return -(value + REV_OFFSET);
            }
        }
        return null;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * Attempts to load a persistent nodemap for the revlog whose index file is {@code idxFile}
     * (e.g. {@code .../00changelog.i}), i.e. the docket at {@code <radix>.n} plus its companion
     * {@code <radix>-<uid>.nd} raw trie data file next to it.
     *
     * <p>Never throws: any missing file, unsupported version, truncated/short companion data
     * file, or malformed block count is treated the same as "no usable persistent nodemap" (real
     * hg's own {@code persisted_data()} behaves the same way — a docket that doesn't check out is
     * silently discarded, not an error) so callers can safely fall back to the ordinary
     * full-revlog-scan node lookup.
     */
    public static NodeMapFile tryLoad(File idxFile) {
        try {
            String idxName = idxFile.getName();
            int dot = idxName.lastIndexOf('.');
            String radix = dot >= 0 ? idxName.substring(0, dot) : idxName;
            File docketFile = new File(idxFile.getParentFile(), radix + ".n");
            if (!docketFile.isFile()) {
                return null;
            }
            byte[] docket = Files.readAllBytes(docketFile.toPath());
            if (docket.length < 1) {
                return null;
            }
            ByteBuffer buf = ByteBuffer.wrap(docket).order(ByteOrder.BIG_ENDIAN);
            int version = buf.get() & 0xFF;
            if (version != ONDISK_VERSION) {
                return null;
            }
            if (buf.remaining() < 33) {
                return null;
            }
            int uidSize = buf.get() & 0xFF;
            long tipRevLong = buf.getLong();
            long dataLength = buf.getLong();
            long dataUnused = buf.getLong();
            long tipNodeSize = buf.getLong();
            if (tipRevLong < 0 || tipRevLong > Integer.MAX_VALUE || tipNodeSize < 0 || tipNodeSize > 64) {
                return null;
            }
            if (buf.remaining() < uidSize + tipNodeSize) {
                return null;
            }
            byte[] uidBytes = new byte[uidSize];
            buf.get(uidBytes);
            String uid = new String(uidBytes, StandardCharsets.US_ASCII);
            byte[] tipNode = new byte[(int) tipNodeSize];
            buf.get(tipNode);

            if (dataLength < 0 || dataLength % BLOCK_SIZE != 0) {
                return null;
            }
            File dataFile = new File(idxFile.getParentFile(), radix + "-" + uid + ".nd");
            if (!dataFile.isFile()) {
                return null;
            }
            if (dataFile.length() < dataLength) {
                // real hg discards the docket if the raw data file is shorter than data_length
                return null;
            }
            int numBlocks = (int) (dataLength / BLOCK_SIZE);
            int[][] blocks = new int[numBlocks][];
            if (numBlocks > 0) {
                try (var channel = java.nio.channels.FileChannel.open(dataFile.toPath(), java.nio.file.StandardOpenOption.READ)) {
                    ByteBuffer blockBuf = ByteBuffer.allocate((int) dataLength).order(ByteOrder.BIG_ENDIAN);
                    while (blockBuf.hasRemaining()) {
                        if (channel.read(blockBuf) == -1) break;
                    }
                    if (blockBuf.hasRemaining()) {
                        return null; // truncated
                    }
                    blockBuf.flip();
                    for (int i = 0; i < numBlocks; i++) {
                        int[] block = new int[16];
                        for (int j = 0; j < 16; j++) {
                            block[j] = blockBuf.getInt();
                        }
                        blocks[i] = block;
                    }
                }
            }
            return new NodeMapFile((int) tipRevLong, tipNode, dataLength, dataUnused, blocks);
        } catch (IOException | RuntimeException e) {
            // Any parse failure -> treat as "no usable persistent nodemap", never propagate: the
            // caller always has a correct (if slower) fallback available.
            return null;
        }
    }

    /** For tests: clip/pad a node hash to exactly 20 bytes, matching real hg's node identity. */
    static byte[] clip20(byte[] node) {
        if (node.length == 20) {
            return node;
        }
        return Arrays.copyOf(node, 20);
    }
}
