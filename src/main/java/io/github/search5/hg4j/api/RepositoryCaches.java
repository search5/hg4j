package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.util.SafeFileIO;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
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
 * The performance caches real hg keeps in {@code .hg/cache} and that it reads back on the next command: the branch cache
 * ({@code branch2-served}), the tags cache ({@code tags2-visible}), the {@code .hgtags} filenode cache ({@code hgtagsfnodes1}) and the
 * revision-to-branch cache ({@code rbc-names-v2}, {@code rbc-revs-v2}). Their formats are the ones Mercurial 7.2 writes, so a real
 * {@code hg} opens the repository without having to compute them again.
 *
 * <p>How each is written follows hg (a cache that cannot be written is never an error):</p>
 * <ul>
 *   <li>{@code branch2-*} and {@code tags2-*}: written to a temporary file and moved into place ({@code atomictemp}); no lock.</li>
 *   <li>{@code hgtagsfnodes1}: {@code repo.lock(wait=False)}; when the store lock is busy nothing is written
 *       ({@code not writing ... because lock cannot be acquired}).</li>
 *   <li>{@code rbc-names-v2}, {@code rbc-revs-v2}: {@code repo.wlock(wait=False)}, skipped the same way.</li>
 * </ul>
 *
 * <p>Not written: the filtered caches ({@code branch2-visible} and so on) of a repository that has hidden or secret changesets.
 * Those depend on the repoview filters and a hash of the hidden set, which hg4j does not compute, and hg rebuilds them itself.</p>
 */
public final class RepositoryCaches {

    /** What an update did: the cache files written, and why the others were left alone. */
    public record Outcome(List<String> written, List<String> skipped) {
    }

    private static final int FNODES_RECORD = 24;
    private static final HexFormat HEX = HexFormat.of();

    private RepositoryCaches() {
    }

    /** Updates every cache; each one independently, so a busy lock for one does not stop the others. */
    public static Outcome update(HgRepository repo) throws IOException {
        List<String> written = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        File changelogIndex = new File(repo.getStoreDir(), "00changelog.i");
        if (!changelogIndex.isFile()) {
            return new Outcome(written, skipped);
        }
        Revlog changelog = repo.getRevlog(changelogIndex, new File(repo.getStoreDir(), "00changelog.d"));
        int count = changelog.getRevisionCount();
        if (count == 0) {
            return new Outcome(written, skipped); // hg does not write an empty cache
        }
        File cacheDir = new File(repo.getHgDir(), "cache");
        Files.createDirectories(cacheDir.toPath());

        byte[][] nodes = new byte[count][];
        int[] parent1 = new int[count];
        int[] parent2 = new int[count];
        String[] branch = new String[count];
        boolean[] closed = new boolean[count];
        boolean[] hasChild = new boolean[count];
        for (int r = 0; r < count; r++) {
            Revlog.IndexRecord record = changelog.getIndexRecord(r);
            nodes[r] = record.getNodeId();
            parent1[r] = record.getParent1();
            parent2[r] = record.getParent2();
            ChangesetRewrite.Entry entry = ChangesetRewrite.Entry.parse(changelog.getRevisionContent(r));
            String name = entry.extra.get("branch");
            branch[r] = name == null ? "default" : name;
            closed[r] = entry.extra.containsKey("close");
            if (parent1[r] >= 0) {
                hasChild[parent1[r]] = true;
            }
            if (parent2[r] >= 0) {
                hasChild[parent2[r]] = true;
            }
        }

        writeRevisionBranchCache(repo, cacheDir, nodes, branch, closed, written, skipped);

        boolean filtered = hasHiddenChangesets(repo, changelog);
        if (filtered) {
            skipped.add("branch2-served, tags2-visible: this repository has hidden or secret changesets (repoview filters)");
        } else {
            writeBranchCache(cacheDir, nodes, parent1, parent2, branch, closed, written);
        }
        Map<Integer, byte[]> fnodes = writeTagsFnodesCache(repo, cacheDir, nodes, hasChild, written, skipped);
        if (!filtered && fnodes != null) {
            writeTagsCache(repo, cacheDir, changelog, nodes, hasChild, fnodes, written);
        }
        return new Outcome(written, skipped);
    }

    private static boolean hasHiddenChangesets(HgRepository repo, Revlog changelog) throws IOException {
        if (io.github.search5.hg4j.transport.ServedView.hidden(repo, changelog) != null) {
            return true;
        }
        return !repo.getPhaseRoots().getRoots(io.github.search5.hg4j.phase.PhaseRoots.Phase.SECRET).isEmpty();
    }

    // ---------------------------------------------------------------- branch2-served

    /**
     * {@code branchcache._write_header} + {@code _write_heads}: {@code tipnode tiprev}, then {@code node state branch} for the heads of each
     * branch, branches sorted by name and the heads of a branch by revision. A head is {@code c} when its changeset closes the branch.
     */
    private static void writeBranchCache(File cacheDir, byte[][] nodes, int[] parent1, int[] parent2, String[] branch, boolean[] closed,
                                         List<String> written) throws IOException {
        int count = nodes.length;
        // a head of a branch is a changeset of that branch no changeset of the same branch has as a parent
        boolean[] hasChildInBranch = new boolean[count];
        for (int r = 0; r < count; r++) {
            for (int p : new int[]{parent1[r], parent2[r]}) {
                if (p >= 0 && branch[p].equals(branch[r])) {
                    hasChildInBranch[p] = true;
                }
            }
        }
        TreeMap<String, List<Integer>> heads = new TreeMap<>((a, b) -> compareBytes(a, b));
        for (int r = 0; r < count; r++) {
            if (!hasChildInBranch[r]) {
                heads.computeIfAbsent(branch[r], k -> new ArrayList<>()).add(r);
            }
        }
        StringBuilder text = new StringBuilder();
        text.append(HEX.formatHex(nodes[count - 1])).append(' ').append(count - 1).append('\n');
        for (Map.Entry<String, List<Integer>> e : heads.entrySet()) {
            for (int r : e.getValue()) {
                text.append(HEX.formatHex(nodes[r])).append(closed[r] ? " c " : " o ").append(e.getKey()).append('\n');
            }
        }
        SafeFileIO.writeStringAtomic(new File(cacheDir, "branch2-served"), text.toString());
        written.add("branch2-served");
    }

    private static int compareBytes(String a, String b) {
        return Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- rbc-names-v2, rbc-revs-v2

    /**
     * {@code revbranchcache.write}: {@code repo.wlock(wait=False)}. The names are kept in the order they were first stored (new ones
     * are added at the end); each revision has a record of the first four bytes of its node and the branch index, with the top bit set for a
     * changeset that closes its branch.
     */
    private static void writeRevisionBranchCache(HgRepository repo, File cacheDir, byte[][] nodes, String[] branch, boolean[] closed,
                                                 List<String> written, List<String> skipped) throws IOException {
        try (HgLock wlock = repo.lockWorkingCopy(0)) {
            File namesFile = new File(cacheDir, "rbc-names-v2");
            List<String> names = new ArrayList<>();
            if (namesFile.isFile() && namesFile.length() > 0) {
                names.addAll(Arrays.asList(new String(Files.readAllBytes(namesFile.toPath()), StandardCharsets.UTF_8).split("\0", -1)));
            }
            for (String b : branch) {
                if (!names.contains(b)) {
                    names.add(b);
                }
            }
            ByteBuffer revs = ByteBuffer.allocate(nodes.length * 8);
            for (int r = 0; r < nodes.length; r++) {
                revs.put(nodes[r], 0, 4);
                revs.putInt(names.indexOf(branch[r]) | (closed[r] ? 0x80000000 : 0));
            }
            SafeFileIO.writeAtomic(namesFile, String.join("\0", names).getBytes(StandardCharsets.UTF_8));
            SafeFileIO.writeAtomic(new File(cacheDir, "rbc-revs-v2"), revs.array());
            written.add("rbc-names-v2");
            written.add("rbc-revs-v2");
        } catch (HgLockException busy) {
            skipped.add("rbc-names-v2, rbc-revs-v2: the working copy lock is busy");
        }
    }

    // ---------------------------------------------------------------- hgtagsfnodes1

    /** {@code tags.hgtagsfnodescache.getfnode} for the heads, written under {@code repo.lock(wait=False)}. @return the head fnodes, null if skipped */
    private static Map<Integer, byte[]> writeTagsFnodesCache(HgRepository repo, File cacheDir, byte[][] nodes, boolean[] hasChild,
                                                             List<String> written, List<String> skipped) throws IOException {
        Map<Integer, byte[]> computed = new LinkedHashMap<>();
        for (int r = nodes.length - 1; r >= 0; r--) {
            if (!hasChild[r]) {
                computed.put(r, hgtagsFilenode(repo, nodes[r]));
            }
        }
        try (HgLock lock = repo.lockStoreWithoutRecovery(0)) {
            File file = new File(cacheDir, "hgtagsfnodes1");
            byte[] raw = file.isFile() ? Files.readAllBytes(file.toPath()) : new byte[0];
            int wanted = nodes.length * FNODES_RECORD;
            byte[] data = new byte[Math.max(wanted, raw.length / FNODES_RECORD * FNODES_RECORD)];
            Arrays.fill(data, (byte) 0xFF);
            System.arraycopy(raw, 0, data, 0, Math.min(raw.length - raw.length % FNODES_RECORD, data.length));
            int dirty = Integer.MAX_VALUE;
            for (Map.Entry<Integer, byte[]> e : computed.entrySet()) {
                int offset = e.getKey() * FNODES_RECORD;
                boolean valid = !isMissing(data, offset) && Arrays.equals(Arrays.copyOfRange(data, offset, offset + 4),
                        Arrays.copyOf(nodes[e.getKey()], 4));
                if (!valid) {
                    System.arraycopy(nodes[e.getKey()], 0, data, offset, 4);
                    System.arraycopy(e.getValue(), 0, data, offset + 4, 20);
                    dirty = Math.min(dirty, offset);
                }
            }
            if (dirty != Integer.MAX_VALUE || !file.isFile() || file.length() < data.length) {
                int from = dirty == Integer.MAX_VALUE ? (int) Math.min(file.exists() ? file.length() : 0, data.length) : dirty;
                SafeFileIO.breakHardLinks(file);
                try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
                    from = (int) Math.min(from, out.length());
                    out.seek(from);
                    out.setLength(from);
                    out.write(data, from, data.length - from);
                }
                written.add("hgtagsfnodes1");
            }
            return computed;
        } catch (HgLockException busy) {
            skipped.add("hgtagsfnodes1: the store lock is busy"); // hg logs "not writing ... because lock cannot be acquired"
            return computed; // the tags themselves can still be computed
        }
    }

    private static boolean isMissing(byte[] data, int offset) {
        if (offset + FNODES_RECORD > data.length) {
            return true;
        }
        for (int i = 0; i < FNODES_RECORD; i++) {
            if (data[offset + i] != (byte) 0xFF) {
                return false;
            }
        }
        return true;
    }

    /** The filenode of {@code .hgtags} in the changeset, or the null id when it has none. */
    private static byte[] hgtagsFilenode(HgRepository repo, byte[] changeset) throws IOException {
        Map<String, String> manifest = repo.getManifestAtCommit(changeset);
        String entry = manifest.get(".hgtags");
        if (entry == null) {
            return new byte[20];
        }
        return HEX.parseHex(entry.substring(0, 40));
    }

    // ---------------------------------------------------------------- tags2-visible

    /**
     * {@code tags._writetagcache}: {@code tiprev tipnode}, then for each tag (sorted) its earlier nodes and the current one, as
     * {@code node name}. Tags come from the {@code .hgtags} filenodes of the heads, oldest head first, each filenode once
     * ({@code _filterfnodes}, {@code _tagsfromfnodes}, {@code _updatetags}).
     */
    private static void writeTagsCache(HgRepository repo, File cacheDir, Revlog changelog, byte[][] nodes, boolean[] hasChild,
                                       Map<Integer, byte[]> fnodes, List<String> written) throws IOException {
        int tip = nodes.length - 1;
        StringBuilder text = new StringBuilder();
        text.append(tip).append(' ').append(HEX.formatHex(nodes[tip])).append('\n');
        File tagLog = new File(repo.getStoreDir(), NodeIdUtil.encodeFname("data/.hgtags.i"));
        if (tagLog.isFile()) { // "No tags have ever been committed": the header alone
            Revlog hgtags = repo.getRevlog(tagLog, new File(repo.getStoreDir(), NodeIdUtil.encodeFname("data/.hgtags.d")));
            List<Integer> headsOldestFirst = new ArrayList<>(fnodes.keySet());
            java.util.Collections.reverse(headsOldestFirst); // fnodes was filled newest first
            Set<String> seen = new LinkedHashSet<>();
            Map<String, Object[]> all = new TreeMap<>((a, b) -> compareBytes(a, b));
            for (int head : headsOldestFirst) {
                byte[] fnode = fnodes.get(head);
                if (NodeIdUtil.isAllZero(fnode) || !seen.add(HEX.formatHex(fnode))) {
                    continue;
                }
                int rev = hgtags.findRevision(fnode);
                if (rev < 0) {
                    continue;
                }
                updateTags(readTags(hgtags.getRevisionContent(rev)), all);
            }
            for (Map.Entry<String, Object[]> e : all.entrySet()) {
                @SuppressWarnings("unchecked")
                List<byte[]> hist = (List<byte[]>) e.getValue()[1];
                for (byte[] n : hist) {
                    text.append(HEX.formatHex(n)).append(' ').append(e.getKey()).append('\n');
                }
                text.append(HEX.formatHex((byte[]) e.getValue()[0])).append(' ').append(e.getKey()).append('\n');
            }
        }
        SafeFileIO.writeAtomic(new File(cacheDir, "tags2-visible"), text.toString().getBytes(StandardCharsets.UTF_8));
        written.add("tags2-visible");
    }

    /** {@code tags._readtags}: tag name to (last node, the earlier ones in file order). */
    private static Map<String, Object[]> readTags(byte[] content) {
        Map<String, List<byte[]>> history = new LinkedHashMap<>();
        for (String line : new String(content, StandardCharsets.UTF_8).split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            int space = line.indexOf(' ');
            if (space < 0) {
                continue;
            }
            String hex = line.substring(0, space);
            String name = line.substring(space + 1).strip();
            if (hex.length() != 40 || !hex.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                continue;
            }
            history.computeIfAbsent(name, k -> new ArrayList<>()).add(HEX.parseHex(hex));
        }
        Map<String, Object[]> tags = new LinkedHashMap<>();
        for (Map.Entry<String, List<byte[]>> e : history.entrySet()) {
            List<byte[]> h = e.getValue();
            tags.put(e.getKey(), new Object[]{h.get(h.size() - 1), new ArrayList<>(h.subList(0, h.size() - 1))});
        }
        return tags;
    }

    /** {@code tags._updatetags}. */
    @SuppressWarnings("unchecked")
    private static void updateTags(Map<String, Object[]> fileTags, Map<String, Object[]> all) {
        for (Map.Entry<String, Object[]> e : fileTags.entrySet()) {
            Object[] existing = all.get(e.getKey());
            if (existing == null) {
                all.put(e.getKey(), e.getValue());
                continue;
            }
            byte[] anode = (byte[]) e.getValue()[0];
            List<byte[]> ahist = (List<byte[]>) e.getValue()[1];
            byte[] bnode = (byte[]) existing[0];
            List<byte[]> bhist = (List<byte[]>) existing[1];
            if (!Arrays.equals(bnode, anode) && contains(bhist, anode) && (!contains(ahist, bnode) || bhist.size() > ahist.size())) {
                anode = bnode;
            }
            for (byte[] n : bhist) {
                if (!contains(ahist, n)) {
                    ahist.add(n);
                }
            }
            all.put(e.getKey(), new Object[]{anode, ahist});
        }
    }

    private static boolean contains(List<byte[]> list, byte[] node) {
        for (byte[] n : list) {
            if (Arrays.equals(n, node)) {
                return true;
            }
        }
        return false;
    }
}
