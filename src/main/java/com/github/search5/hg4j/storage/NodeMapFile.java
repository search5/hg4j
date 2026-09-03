package com.github.search5.hg4j.storage;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

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
    private final String uid;

    private NodeMapFile(int tipRev, byte[] tipNode, long dataLength, long dataUnused, int[][] blocks, String uid) {
        this.tipRev = tipRev;
        this.tipNode = tipNode;
        this.dataLength = dataLength;
        this.dataUnused = dataUnused;
        this.blocks = blocks;
        this.rootBlockIndex = blocks.length - 1;
        this.uid = uid;
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
            return new NodeMapFile((int) tipRevLong, tipNode, dataLength, dataUnused, blocks, uid);
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

    // ------------------------------------------------------------------------------------------
    // Writer: builds/updates the on-disk trie, mirroring real Mercurial's
    // mercurial/revlogutils/nodemap.py `persist_nodemap()` (transaction-level orchestration) and
    // its "reference" pure-Python trie builder (`_build_trie`/`_update_trie`/`_insert_into_block`/
    // `_persist_trie`/`_walk_trie` -- the SAME binary format the Rust/C accelerated index also
    // produces, just computed slower; deterministic given the same final revision set, so a full
    // rebuild here always matches byte-for-byte what real hg would produce for that same state).
    // ------------------------------------------------------------------------------------------

    /**
     * In-memory trie node, mirroring real hg's {@code nodemap.Block} (a dict keyed 0-15 whose
     * values are either another {@code Block} (sub-trie), an {@code Integer} revision number
     * (leaf), or absent/{@code null} (no entry)). {@code ondiskId} mirrors real hg's
     * {@code Block.ondisk_id}: non-null means "this block's bytes are already correctly persisted
     * at this on-disk block index and can be referenced as-is without re-writing it"; any
     * insertion that touches a block resets it to {@code null} ("dirty").
     */
    private static final class Block {
        final Object[] slots = new Object[16];
        Integer ondiskId;
    }

    /**
     * Reconstructs a mutable {@link Block} tree from this instance's flat on-disk block array
     * (as loaded by {@link #tryLoad}), matching real hg's {@code parse_data()}: block array index
     * <em>is</em> the on-disk id (children always precede the parents that reference them, see
     * {@link #tryLoad}'s javadoc on why the root is always the last block), so a single forward
     * pass can resolve every {@code >= 0} slot value to an already-constructed sibling/child.
     */
    private Block toBlockTree() {
        Block[] built = new Block[blocks.length];
        for (int i = 0; i < blocks.length; i++) {
            built[i] = new Block();
            built[i].ondiskId = i;
        }
        for (int i = 0; i < blocks.length; i++) {
            for (int j = 0; j < 16; j++) {
                int v = blocks[i][j];
                if (v == NO_ENTRY) {
                    built[i].slots[j] = null;
                } else if (v >= 0) {
                    built[i].slots[j] = built[v];
                } else {
                    built[i].slots[j] = -(v + REV_OFFSET);
                }
            }
        }
        return built[rootBlockIndex];
    }

    /**
     * Inserts {@code rev} (whose full node hash hex string is {@code hex}) into the trie rooted
     * at {@code block}, recursing/splitting exactly as real hg's {@code _insert_into_block} does
     * (including its asymmetry: only the "recurse into an existing sub-block" branch accumulates
     * into the returned touch count -- the two recursive calls made to resolve a fresh collision
     * do not -- matching real hg's own {@code data_changed_count} bookkeeping quirk exactly, since
     * this count only ever feeds the internal "is incremental still worth it" heuristic below, not
     * anything read-side/interop-visible).
     *
     * @param nodeOf resolves an already-inserted revision back to its node hash, needed to expand
     *               a leaf into a fresh sub-block when a new revision collides with it.
     */
    private static long insertIntoBlock(IntFunction<byte[]> nodeOf, Block block, int level, int rev, String hex) {
        long changed = 1;
        block.ondiskId = null;
        int digit = Character.digit(hex.charAt(level), 16);
        Object entry = block.slots[digit];
        if (entry == null) {
            block.slots[digit] = rev;
        } else if (entry instanceof Block) {
            changed += insertIntoBlock(nodeOf, (Block) entry, level + 1, rev, hex);
        } else {
            int otherRev = (Integer) entry;
            String otherHex = toHex(clip20(nodeOf.apply(otherRev)));
            Block fresh = new Block();
            block.slots[digit] = fresh;
            insertIntoBlock(nodeOf, fresh, level + 1, otherRev, otherHex);
            insertIntoBlock(nodeOf, fresh, level + 1, rev, hex);
        }
        return changed;
    }

    /**
     * Serializes only the "dirty" (never-persisted, {@code ondiskId == null}) blocks of the trie
     * rooted at {@code root}, assigning them fresh sequential ids starting right after
     * {@code existingMaxId} (or from 0 if {@code existingMaxId} is {@code null}, i.e. a full
     * rebuild) -- matching real hg's {@code _persist_trie(root, existing_idx=...)}. Blocks that
     * are still clean (already correctly on disk) are skipped entirely (their id is simply
     * referenced by any dirty parent that points at them), so calling this after inserting only a
     * handful of new revisions into an otherwise-untouched large trie yields a small delta, not a
     * full re-encoding.
     */
    private static byte[] persistTrie(Block root, Integer existingMaxId) {
        Map<Block, Integer> blockMap = new IdentityHashMap<>();
        int baseIdx = existingMaxId != null ? existingMaxId + 1 : 0;
        List<byte[]> chunks = new ArrayList<>();
        for (Block tn : walkTrie(root)) {
            if (tn.ondiskId != null) {
                blockMap.put(tn, tn.ondiskId);
            } else {
                blockMap.put(tn, chunks.size() + baseIdx);
                chunks.add(persistBlock(tn, blockMap));
            }
        }
        int total = 0;
        for (byte[] c : chunks) {
            total += c.length;
        }
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] c : chunks) {
            System.arraycopy(c, 0, out, pos, c.length);
            pos += c.length;
        }
        return out;
    }

    /** Post-order walk (children before their parent), matching real hg's {@code _walk_trie}. */
    private static List<Block> walkTrie(Block root) {
        List<Block> out = new ArrayList<>();
        walkTrieInto(root, out);
        return out;
    }

    private static void walkTrieInto(Block block, List<Block> out) {
        for (int i = 0; i < 16; i++) {
            Object v = block.slots[i];
            if (v instanceof Block) {
                walkTrieInto((Block) v, out);
            }
        }
        out.add(block);
    }

    private static byte[] persistBlock(Block block, Map<Block, Integer> blockMap) {
        ByteBuffer buf = ByteBuffer.allocate(BLOCK_SIZE).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < 16; i++) {
            Object v = block.slots[i];
            int value;
            if (v == null) {
                value = NO_ENTRY;
            } else if (v instanceof Block) {
                value = blockMap.get(v);
            } else {
                value = -(((Integer) v) + REV_OFFSET);
            }
            buf.putInt(value);
        }
        return buf.array();
    }

    /**
     * A fresh docket uid: 8 lowercase hex characters from 4 random bytes, matching real hg's
     * {@code mercurial/utils/docket.py} {@code make_uid()} (UID_SIZE=8, {@code os.urandom(4)} hex
     * -encoded).
     */
    private static String makeUid() {
        byte[] raw = new byte[4];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder(8);
        for (byte b : raw) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * Updates (or creates from scratch) the on-disk persistent nodemap for the revlog whose index
     * file is {@code idxFile}, after {@code revisionCount} revisions now exist. Mirrors real hg's
     * {@code persist_nodemap()}: attempts an incremental append when {@code existing} is a valid
     * prefix of the current revision range (its {@code tip_rev}'s recorded node hash still
     * matches what the revlog actually has there -- if not, e.g. after a strip/rewrite, this is
     * conservatively treated the same as "no usable prior docket") <em>and</em> doing so wouldn't
     * leave the {@code .nd} file mostly dead weight ({@code new_length <= new_unused * 10}, the
     * exact "under 10% of unused data" threshold from real hg's source); otherwise rebuilds the
     * whole trie from scratch under a fresh uid (real hg's own fallback path, not a compromise).
     *
     * <p>Best-effort: any I/O problem here is swallowed and simply leaves the on-disk nodemap
     * unchanged (returns {@code existing} as-is) -- this is never a correctness issue since {@link
     * #tryLoad} always safely falls back to a full index scan for a missing/stale/malformed
     * docket, exactly matching real hg's own "the persistent nodemap is an accelerating cache, not
     * a source of truth" contract.
     *
     * @param nodeOf resolves a revision number to its full node hash (>= 20 bytes; only the first
     *               20 are used, matching {@link #clip20}).
     * @return the freshly-written nodemap snapshot (re-read from the files just written, so it is
     *         exactly what a fresh {@link #tryLoad} would now return) for the caller to keep any
     *         in-memory read-acceleration copy in sync, or {@code existing} unchanged if nothing
     *         was written.
     */
    public static NodeMapFile persist(File idxFile, NodeMapFile existing, int revisionCount, IntFunction<byte[]> nodeOf) {
        if (revisionCount <= 0) {
            return existing;
        }
        try {
            String idxName = idxFile.getName();
            int dot = idxName.lastIndexOf('.');
            String radix = dot >= 0 ? idxName.substring(0, dot) : idxName;
            File dir = idxFile.getParentFile();

            boolean validPrefix = existing != null && existing.uid != null
                    && existing.tipRev >= 0 && existing.tipRev < revisionCount
                    && Arrays.equals(clip20(existing.tipNode), clip20(nodeOf.apply(existing.tipRev)));

            byte[] appendData;
            long newDataLength;
            long newDataUnused;
            String uid;
            boolean incremental = false;
            long appendOffset = 0;

            if (validPrefix) {
                Block root = existing.toBlockTree();
                int existingMaxId = existing.blocks.length - 1;
                long changed = 0;
                for (int rev = existing.tipRev + 1; rev < revisionCount; rev++) {
                    changed += insertIntoBlock(nodeOf, root, 0, rev, toHex(clip20(nodeOf.apply(rev))));
                }
                byte[] delta = persistTrie(root, existingMaxId);
                long candidateLength = existing.dataLength + delta.length;
                long candidateUnused = existing.dataUnused + changed;
                if (candidateLength > candidateUnused * 10) {
                    appendData = delta;
                    newDataLength = candidateLength;
                    newDataUnused = candidateUnused;
                    uid = existing.uid;
                    incremental = true;
                    appendOffset = existing.dataLength;
                } else {
                    appendData = null;
                    newDataLength = 0;
                    newDataUnused = 0;
                    uid = null;
                }
            } else {
                appendData = null;
                newDataLength = 0;
                newDataUnused = 0;
                uid = null;
            }

            if (!incremental) {
                Block root = new Block();
                for (int rev = 0; rev < revisionCount; rev++) {
                    insertIntoBlock(nodeOf, root, 0, rev, toHex(clip20(nodeOf.apply(rev))));
                }
                appendData = persistTrie(root, null);
                newDataLength = appendData.length;
                newDataUnused = 0;
                uid = makeUid();
                appendOffset = 0;
            }

            File dataFile = new File(dir, radix + "-" + uid + ".nd");
            if (incremental) {
                try (RandomAccessFile raf = new RandomAccessFile(dataFile, "rw")) {
                    raf.seek(appendOffset);
                    raf.write(appendData);
                }
            } else {
                Files.write(dataFile.toPath(), appendData);
            }

            int tipRev = revisionCount - 1;
            byte[] tipNode = clip20(nodeOf.apply(tipRev));
            byte[] uidBytes = uid.getBytes(StandardCharsets.US_ASCII);
            ByteBuffer docketBuf = ByteBuffer.allocate(1 + 33 + uidBytes.length + tipNode.length).order(ByteOrder.BIG_ENDIAN);
            docketBuf.put((byte) ONDISK_VERSION);
            docketBuf.put((byte) uidBytes.length);
            docketBuf.putLong(tipRev);
            docketBuf.putLong(newDataLength);
            docketBuf.putLong(newDataUnused);
            docketBuf.putLong(tipNode.length);
            docketBuf.put(uidBytes);
            docketBuf.put(tipNode);

            File docketFile = new File(dir, radix + ".n");
            File tmpFile = File.createTempFile(radix + ".n", ".tmp", dir);
            Files.write(tmpFile.toPath(), docketBuf.array());
            Files.move(tmpFile.toPath(), docketFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            if (!incremental) {
                // a full rebuild picked a fresh uid -- any other "<radix>-*.nd" file is now
                // orphaned (matches real hg's post-transaction _other_rawdata_filepath cleanup).
                File[] siblings = dir.listFiles((d, name) -> name.startsWith(radix + "-") && name.endsWith(".nd") && !name.equals(dataFile.getName()));
                if (siblings != null) {
                    for (File f : siblings) {
                        Files.deleteIfExists(f.toPath());
                    }
                }
            }

            NodeMapFile fresh = tryLoad(idxFile);
            return fresh != null ? fresh : existing;
        } catch (IOException | RuntimeException e) {
            return existing;
        }
    }
}
