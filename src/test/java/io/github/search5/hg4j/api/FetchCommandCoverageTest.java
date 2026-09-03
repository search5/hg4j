package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.bundle.ChangegroupParser;
import io.github.search5.hg4j.errors.HgCorruptDataException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.transport.CredentialsProvider;
import io.github.search5.hg4j.transport.HgRemoteConnection;
import io.github.search5.hg4j.transport.HgRemoteConnectionFactory;
import io.github.search5.hg4j.transport.HgHttpWireServer;
import io.github.search5.hg4j.transport.TransportProtocol;
import io.github.search5.hg4j.treewalk.HgTreeFilter;
import io.github.search5.hg4j.util.NodeIdUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage for {@link FetchCommand}, focused on branches not exercised by
 * {@link FetchCommandTest}: real bundle2 (HG20) container decoding, cg3 treemanifest
 * ({@code manifestGroups}) application, tree-filter file rejection, file-group link-commit
 * corruption, pre-existing dirstate/fncache backup+restore, and the clonebundle
 * merge-with-catch-up-pull path (both the "merged with subsequent results" and the
 * "clonebundle turned out to be empty" cases).
 */
public class FetchCommandCoverageTest {

    // ------------------------------------------------------------------
    // Own scripted transport registry, kept independent from FetchCommandTest's so the two
    // test classes never share mutable static state.
    // ------------------------------------------------------------------

    private static final Map<String, ScriptedRemoteConnection> SCRIPTED_CONNECTIONS = new ConcurrentHashMap<>();

    static {
        HgRemoteConnectionFactory.register(new TransportProtocol() {
            @Override
            public boolean canHandle(String url) {
                return url != null && url.startsWith("scriptedcov://");
            }
            @Override
            public HgRemoteConnection open(String url) {
                ScriptedRemoteConnection conn = SCRIPTED_CONNECTIONS.get(url);
                assertNotNull(conn, "No scripted connection registered for " + url);
                return conn;
            }
        });
    }

    private static String registerScripted(ScriptedRemoteConnection conn) {
        String url = "scriptedcov://" + UUID.randomUUID();
        SCRIPTED_CONNECTIONS.put(url, conn);
        return url;
    }

    private static final class ScriptedRemoteConnection implements HgRemoteConnection {
        List<String> capabilities = new ArrayList<>();
        List<String> heads = new ArrayList<>();
        byte[] changegroupBytes = new byte[0];
        byte[] bundleBytesToReturn = new byte[0];
        Map<String, String> bookmarks = new LinkedHashMap<>();
        Map<String, String> phases = new LinkedHashMap<>();

        @Override
        public List<String> getCapabilities() {
            return capabilities;
        }

        @Override
        public List<String> getHeads() {
            return heads;
        }

        @Override
        public byte[] getChangegroup(List<String> roots) {
            return changegroupBytes;
        }

        @Override
        public byte[] getBundle(List<String> common, List<String> hds, List<String> bundleCaps) {
            return bundleBytesToReturn;
        }

        @Override
        public String push(byte[] bundleBytes, List<String> hds) {
            return "ok";
        }

        @Override
        public Map<String, String> listKeys(String namespace) {
            if ("bookmarks".equals(namespace)) {
                return bookmarks;
            }
            if ("phases".equals(namespace)) {
                return phases;
            }
            return Map.of();
        }

        @Override
        public boolean pushkey(String namespace, String key, String oldVal, String newVal) {
            return true;
        }

        @Override
        public List<String> between(List<String> pairs) {
            return List.of();
        }

        @Override
        public String known(List<String> nodes) {
            return "";
        }

        @Override
        public void setCredentialsProvider(CredentialsProvider provider) {
        }

        @Override
        public void close() {
        }
    }

    /**
     * Wraps a cg1 changegroup payload (as produced by {@link HgTestUtils#serializeBundleToBytes})
     * inside a minimal, hand-rolled real bundle2 (HG20) container carrying a single uncompressed
     * CHANGEGROUP part with {@code version=01} -- exercises {@link FetchCommand}'s HG20 magic
     * dispatch branch in {@code call()}, which none of the existing scripted-transport tests hit
     * (they only ever return bundle1 "HG10*" wire bytes).
     */
    private static byte[] wrapAsBundle2(byte[] changegroupBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(out)) {
            dos.write("HG20".getBytes(StandardCharsets.US_ASCII));
            dos.writeInt(0); // no stream-level params -> uncompressed payload

            byte[] partName = "CHANGEGROUP".getBytes(StandardCharsets.US_ASCII);
            byte[] keyBytes = "version".getBytes(StandardCharsets.US_ASCII);
            byte[] valBytes = "01".getBytes(StandardCharsets.US_ASCII);

            ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
            headerBuf.write(partName.length);
            headerBuf.write(partName);
            headerBuf.write(new byte[]{0, 0, 0, 1}); // part id
            headerBuf.write(1); // mandatory param count
            headerBuf.write(0); // advisory param count
            headerBuf.write(keyBytes.length);
            headerBuf.write(valBytes.length);
            headerBuf.write(keyBytes);
            headerBuf.write(valBytes);
            byte[] headerBlock = headerBuf.toByteArray();

            dos.writeInt(headerBlock.length);
            dos.write(headerBlock);

            dos.writeInt(changegroupBytes.length);
            dos.write(changegroupBytes);
            dos.writeInt(0); // end of this part's payload

            dos.writeInt(0); // end of bundle2 (no more parts)
        }
        return out.toByteArray();
    }

    /** A genuinely empty (but well-formed) cg1 changegroup: changelog/manifest/filelist all empty. */
    private static byte[] emptyChangegroupBytes() {
        return new byte[12]; // three 4-byte zero-length terminators, all-zero bytes already
    }

    @Test
    public void fetchDecodesRealBundle2ContainerFormat(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "hello bundle2");
        new AddCommand(srcRepo).call();
        byte[] commitNode = new CommitCommand(srcRepo).setAuthor("dev").setMessage("v1").call();

        byte[] cg1Bytes = HgTestUtils.serializeBundleToBytes(HgTestUtils.createMockBundleFromRepo(srcRepo));
        byte[] bundle2Bytes = wrapAsBundle2(cg1Bytes);

        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.capabilities = List.of("getbundle", "bundle2");
        conn.heads = List.of(NodeIdUtil.toHex(commitNode));
        conn.bundleBytesToReturn = bundle2Bytes;
        String url = registerScripted(conn);

        List<byte[]> imported = new FetchCommand(destRepo).setSource(url).call();
        assertEquals(1, imported.size(), "A real HG20 bundle2 container must be decoded and applied");
        assertArrayEquals(commitNode, imported.get(0));
    }

    @Test
    public void applyBundleWritesTreemanifestManifestGroupsIncludingNestedPaths(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "hello treemanifest");
        new AddCommand(srcRepo).call();
        byte[] commitNode = new CommitCommand(srcRepo).setAuthor("dev").setMessage("v1").call();

        ChangegroupParser.ChangegroupBundle bundle = HgTestUtils.createMockBundleFromRepo(srcRepo);
        // Simulate a cg3 treemanifest response: a root manifest group ("") plus one nested
        // subtree manifest group -- FetchCommand.applyBundle's manifestGroups branch (used
        // instead of the flat manifestEntries branch whenever manifestGroups is non-empty) is
        // otherwise never touched by any existing test.
        List<ChangegroupParser.ManifestGroup> groups = new ArrayList<>();
        ChangegroupParser.ManifestGroup root = new ChangegroupParser.ManifestGroup();
        root.path = "";
        root.entries = bundle.manifestEntries;
        groups.add(root);
        ChangegroupParser.ManifestGroup nested = new ChangegroupParser.ManifestGroup();
        nested.path = "subdir";
        nested.entries = bundle.manifestEntries;
        groups.add(nested);
        bundle.manifestGroups = groups;

        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        FetchCommand fetchCmd = new FetchCommand(destRepo);
        List<byte[]> imported = fetchCmd.applyBundle(bundle);

        assertEquals(1, imported.size());
        assertArrayEquals(commitNode, imported.get(0));

        Revlog rootManifest = destRepo.getManifestRevlog();
        assertEquals(1, rootManifest.getRevisionCount(), "Root ('') manifest group must go to 00manifest.i/.d");

        String nestedIdxRel = NodeIdUtil.encodeFname("meta/subdir/00manifest.i");
        String nestedDatRel = NodeIdUtil.encodeFname("meta/subdir/00manifest.d");
        File nestedIdx = new File(destRepo.getStoreDir(), nestedIdxRel);
        File nestedDat = new File(destRepo.getStoreDir(), nestedDatRel);
        assertTrue(nestedIdx.exists(), "Nested treemanifest revlog index must be created");
        Revlog nestedRevlog = new Revlog(nestedIdx, nestedDat);
        assertEquals(1, nestedRevlog.getRevisionCount());

        List<String> fncacheLines = Files.readAllLines(new File(destRepo.getStoreDir(), "fncache").toPath());
        assertTrue(fncacheLines.contains(nestedIdxRel), "fncache must track the new nested manifest index file");
        assertTrue(fncacheLines.contains(nestedDatRel), "fncache must track the new nested manifest data file");
    }

    @Test
    public void applyBundleSkipsFilesRejectedByTreeFilter(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "keep.txt").toPath(), "keep me");
        Files.writeString(new File(srcDir, "skip.txt").toPath(), "skip me");
        new AddCommand(srcRepo).call();
        byte[] commitNode = new CommitCommand(srcRepo).setAuthor("dev").setMessage("two files").call();

        ChangegroupParser.ChangegroupBundle bundle = HgTestUtils.createMockBundleFromRepo(srcRepo);
        assertEquals(2, bundle.fileGroups.size(), "Sanity: both files must be present in the mock bundle");

        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        FetchCommand fetchCmd = new FetchCommand(destRepo).setTreeFilter(new HgTreeFilter() {
            @Override
            public boolean accept(String path) {
                return !"skip.txt".equals(path);
            }
        });
        List<byte[]> imported = fetchCmd.applyBundle(bundle);

        assertEquals(1, imported.size());
        assertArrayEquals(commitNode, imported.get(0));

        File keptIdx = CommitCommand.getFilelogIndex(destRepo.getStoreDir(), "keep.txt");
        File skippedIdx = CommitCommand.getFilelogIndex(destRepo.getStoreDir(), "skip.txt");
        assertTrue(keptIdx.exists(), "Filtered-in file's filelog must be created");
        assertFalse(skippedIdx.exists(), "Filtered-out file's filelog must never be created");

        File fncacheFile = new File(destRepo.getStoreDir(), "fncache");
        List<String> fncacheLines = fncacheFile.exists() ? Files.readAllLines(fncacheFile.toPath()) : List.of();
        assertFalse(fncacheLines.contains("data/skip.txt.i"), "fncache must not list a tree-filtered-out file");
    }

    @Test
    public void applyBundleRollsBackWhenAFileGroupEntryHasAnUnresolvableLinkCommit(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "hello");
        new AddCommand(srcRepo).call();
        new CommitCommand(srcRepo).setAuthor("dev").setMessage("v1").call();

        ChangegroupParser.ChangegroupBundle bundle = HgTestUtils.createMockBundleFromRepo(srcRepo);
        assertFalse(bundle.fileGroups.isEmpty());
        bundle.fileGroups.get(0).entries.get(0).cs = NodeIdUtil.fromHex("9".repeat(40));

        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        FetchCommand fetchCmd = new FetchCommand(destRepo);

        assertThrows(HgCorruptDataException.class, () -> fetchCmd.applyBundle(bundle));

        Revlog cl = new Revlog(new File(destRepo.getStoreDir(), "00changelog.i"), new File(destRepo.getStoreDir(), "00changelog.d"));
        Revlog mf = new Revlog(new File(destRepo.getStoreDir(), "00manifest.i"), new File(destRepo.getStoreDir(), "00manifest.d"));
        assertEquals(0, cl.getRevisionCount(), "Changelog must be rolled back on file-group failure");
        assertEquals(0, mf.getRevisionCount(), "Manifest must be rolled back on file-group failure too");
        assertFalse(new File(destRepo.getStoreDir(), "journal").exists());
    }

    @Test
    public void applyBundleBacksUpAndCleansUpPreexistingDirstateAndFncacheOnSuccess(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "hello");
        new AddCommand(srcRepo).call();
        byte[] commitNode = new CommitCommand(srcRepo).setAuthor("dev").setMessage("v1").call();
        ChangegroupParser.ChangegroupBundle bundle = HgTestUtils.createMockBundleFromRepo(srcRepo);

        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        File dirstateFile = new File(destRepo.getDirectory(), ".hg/dirstate");
        File fncacheFile = new File(destRepo.getStoreDir(), "fncache");
        byte[] originalDirstate = "PRE-EXISTING-DIRSTATE-MARKER".getBytes(StandardCharsets.UTF_8);
        Files.write(dirstateFile.toPath(), originalDirstate);
        Files.writeString(fncacheFile.toPath(), "data/preexisting.i\n");

        FetchCommand fetchCmd = new FetchCommand(destRepo);
        List<byte[]> imported = fetchCmd.applyBundle(bundle);

        assertEquals(1, imported.size());
        assertArrayEquals(commitNode, imported.get(0));

        assertArrayEquals(originalDirstate, Files.readAllBytes(dirstateFile.toPath()),
                "Fetch must never touch the working copy dirstate content");
        List<String> fncacheLines = Files.readAllLines(fncacheFile.toPath());
        assertTrue(fncacheLines.contains("data/preexisting.i"), "Pre-existing fncache entries must be preserved");
        assertTrue(fncacheLines.contains("data/a.txt.i"), "Newly-fetched file must be added to fncache");

        assertFalse(new File(destRepo.getDirectory(), ".hg/dirstate.backup").exists(),
                "dirstate.backup must be cleaned up after a successful apply");
        assertFalse(new File(destRepo.getStoreDir(), "fncache.backup").exists(),
                "fncache.backup must be cleaned up after a successful apply");
        assertFalse(new File(destRepo.getStoreDir(), "journal").exists());
    }

    @Test
    public void applyBundleRestoresPreexistingDirstateAndFncacheOnFailure(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "hello");
        new AddCommand(srcRepo).call();
        new CommitCommand(srcRepo).setAuthor("dev").setMessage("v1").call();

        ChangegroupParser.ChangegroupBundle bundle = HgTestUtils.createMockBundleFromRepo(srcRepo);
        bundle.manifestEntries.get(0).cs = NodeIdUtil.fromHex("9".repeat(40));

        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        File dirstateFile = new File(destRepo.getDirectory(), ".hg/dirstate");
        File fncacheFile = new File(destRepo.getStoreDir(), "fncache");
        byte[] originalDirstate = "PRE-EXISTING-DIRSTATE-MARKER-2".getBytes(StandardCharsets.UTF_8);
        String originalFncache = "data/preexisting2.i\n";
        Files.write(dirstateFile.toPath(), originalDirstate);
        Files.writeString(fncacheFile.toPath(), originalFncache);

        FetchCommand fetchCmd = new FetchCommand(destRepo);
        assertThrows(HgCorruptDataException.class, () -> fetchCmd.applyBundle(bundle));

        assertArrayEquals(originalDirstate, Files.readAllBytes(dirstateFile.toPath()),
                "Pre-existing dirstate content must be restored from its backup on rollback");
        assertEquals(originalFncache, Files.readString(fncacheFile.toPath()),
                "Pre-existing fncache content must be restored from its backup on rollback");
        assertFalse(new File(destRepo.getDirectory(), ".hg/dirstate.backup").exists());
        assertFalse(new File(destRepo.getStoreDir(), "fncache.backup").exists());
        assertFalse(new File(destRepo.getStoreDir(), "journal").exists());
    }

    @Test
    public void applyBundleReusesExistingTreemanifestFilesAcrossTwoIncrementalApplies(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "v1");
        new AddCommand(srcRepo).call();
        new CommitCommand(srcRepo).setAuthor("dev").setMessage("v1").call();

        ChangegroupParser.ChangegroupBundle bundle1 = HgTestUtils.createMockBundleFromRepo(srcRepo);
        List<ChangegroupParser.ManifestGroup> groups1 = new ArrayList<>();
        ChangegroupParser.ManifestGroup root1 = new ChangegroupParser.ManifestGroup();
        root1.path = "";
        root1.entries = bundle1.manifestEntries;
        groups1.add(root1);
        ChangegroupParser.ManifestGroup nested1 = new ChangegroupParser.ManifestGroup();
        nested1.path = "subdir";
        nested1.entries = bundle1.manifestEntries;
        groups1.add(nested1);
        bundle1.manifestGroups = groups1;

        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        FetchCommand fetchCmd = new FetchCommand(destRepo);
        List<byte[]> imported1 = fetchCmd.applyBundle(bundle1);
        assertEquals(1, imported1.size());

        String nestedIdxRel = NodeIdUtil.encodeFname("meta/subdir/00manifest.i");
        String nestedDatRel = NodeIdUtil.encodeFname("meta/subdir/00manifest.d");
        File nestedIdx = new File(destRepo.getStoreDir(), nestedIdxRel);
        File nestedDat = new File(destRepo.getStoreDir(), nestedDatRel);
        long nestedIdxLenAfterFirst = nestedIdx.length();
        assertTrue(nestedIdxLenAfterFirst > 0, "sanity: first apply must have created a non-empty nested manifest index");

        Files.writeString(new File(srcDir, "a.txt").toPath(), "v2");
        byte[] commitNode2 = new CommitCommand(srcRepo).setAuthor("dev").setMessage("v2").call();

        ChangegroupParser.ChangegroupBundle fullBundle2 = HgTestUtils.createMockBundleFromRepo(srcRepo);
        assertEquals(2, fullBundle2.manifestEntries.size(), "sanity: manifest must have gained a second revision");

        ChangegroupParser.ChangegroupBundle bundle2 = new ChangegroupParser.ChangegroupBundle();
        bundle2.changelogEntries = new ArrayList<>(List.of(fullBundle2.changelogEntries.get(1)));
        bundle2.manifestEntries = new ArrayList<>();
        bundle2.fileGroups = new ArrayList<>();
        List<ChangegroupParser.ManifestGroup> groups2 = new ArrayList<>();
        ChangegroupParser.ManifestGroup root2 = new ChangegroupParser.ManifestGroup();
        root2.path = "";
        root2.entries = new ArrayList<>(List.of(fullBundle2.manifestEntries.get(1)));
        groups2.add(root2);
        ChangegroupParser.ManifestGroup nested2 = new ChangegroupParser.ManifestGroup();
        nested2.path = "subdir";
        nested2.entries = new ArrayList<>(List.of(fullBundle2.manifestEntries.get(1)));
        groups2.add(nested2);
        bundle2.manifestGroups = groups2;

        List<byte[]> imported2 = fetchCmd.applyBundle(bundle2);
        assertEquals(1, imported2.size());
        assertArrayEquals(commitNode2, imported2.get(0));

        Revlog nestedRevlog = new Revlog(nestedIdx, nestedDat);
        assertEquals(2, nestedRevlog.getRevisionCount(),
                "Second apply must add a second revision to the already-existing nested manifest revlog, not recreate it");
    }

    @Test
    public void applyBundleRollsBackWhenATreemanifestGroupEntryHasAnUnresolvableLinkCommit(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "hello");
        new AddCommand(srcRepo).call();
        new CommitCommand(srcRepo).setAuthor("dev").setMessage("v1").call();

        ChangegroupParser.ChangegroupBundle bundle = HgTestUtils.createMockBundleFromRepo(srcRepo);
        List<ChangegroupParser.ManifestGroup> groups = new ArrayList<>();
        ChangegroupParser.ManifestGroup nested = new ChangegroupParser.ManifestGroup();
        nested.path = "subdir";
        List<ChangegroupParser.ChangeGroupEntry> corrupted = new ArrayList<>(bundle.manifestEntries);
        corrupted.get(0).cs = NodeIdUtil.fromHex("9".repeat(40));
        nested.entries = corrupted;
        groups.add(nested);
        bundle.manifestGroups = groups;

        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        FetchCommand fetchCmd = new FetchCommand(destRepo);

        assertThrows(HgCorruptDataException.class, () -> fetchCmd.applyBundle(bundle));

        Revlog cl = new Revlog(new File(destRepo.getStoreDir(), "00changelog.i"), new File(destRepo.getStoreDir(), "00changelog.d"));
        assertEquals(0, cl.getRevisionCount(), "Changelog must be rolled back when a treemanifest group entry's link commit is unresolvable");
        assertFalse(new File(destRepo.getStoreDir(), "journal").exists());
    }

    @Test
    public void applyBundleSucceedsEvenWhenWritingUndoInfoFails(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "hello");
        new AddCommand(srcRepo).call();
        byte[] commitNode = new CommitCommand(srcRepo).setAuthor("dev").setMessage("v1").call();
        ChangegroupParser.ChangegroupBundle bundle = HgTestUtils.createMockBundleFromRepo(srcRepo);

        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        File undoDir = new File(destRepo.getStoreDir(), "undo");
        assertTrue(undoDir.mkdirs());
        Files.writeString(new File(undoDir, "keep.txt").toPath(), "not empty, so undoFile deletion fails");

        FetchCommand fetchCmd = new FetchCommand(destRepo);
        List<byte[]> imported = fetchCmd.applyBundle(bundle);

        assertEquals(1, imported.size(), "A failure while writing rollback undo info must not fail the whole apply");
        assertArrayEquals(commitNode, imported.get(0));
        assertTrue(undoDir.isDirectory(),
                "The pre-existing non-empty 'undo' directory must be left in place since it could not be deleted");
    }

    @Test
    public void applyBundleTruncatesAlreadyPopulatedStoreFilesBackToTheirPreexistingSizeOnFailure(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "v1");
        new AddCommand(srcRepo).call();
        new CommitCommand(srcRepo).setAuthor("dev").setMessage("v1").call();

        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        FetchCommand fetchCmd = new FetchCommand(destRepo);
        List<byte[]> imported1 = fetchCmd.applyBundle(HgTestUtils.createMockBundleFromRepo(srcRepo));
        assertEquals(1, imported1.size());

        File clIdx = new File(destRepo.getStoreDir(), "00changelog.i");
        File clDat = new File(destRepo.getStoreDir(), "00changelog.d");
        File mfIdx = new File(destRepo.getStoreDir(), "00manifest.i");
        File mfDat = new File(destRepo.getStoreDir(), "00manifest.d");
        long clIdxLenBefore = clIdx.length();
        long clDatLenBefore = clDat.length();
        long mfIdxLenBefore = mfIdx.length();
        long mfDatLenBefore = mfDat.length();
        assertTrue(clIdxLenBefore > 0 && mfIdxLenBefore > 0, "sanity: store files from the first apply must be non-empty");

        Files.writeString(new File(srcDir, "b.txt").toPath(), "new file");
        new AddCommand(srcRepo).call();
        new CommitCommand(srcRepo).setAuthor("dev").setMessage("v2").call();

        ChangegroupParser.ChangegroupBundle fullBundle2 = HgTestUtils.createMockBundleFromRepo(srcRepo);
        assertEquals(2, fullBundle2.changelogEntries.size());
        assertEquals(2, fullBundle2.manifestEntries.size());

        ChangegroupParser.ChangegroupBundle incremental = new ChangegroupParser.ChangegroupBundle();
        incremental.changelogEntries = new ArrayList<>(List.of(fullBundle2.changelogEntries.get(1)));
        incremental.manifestEntries = new ArrayList<>(List.of(fullBundle2.manifestEntries.get(1)));
        incremental.manifestGroups = new ArrayList<>();
        ChangegroupParser.FileGroup bGroup = fullBundle2.fileGroups.stream()
                .filter(fg -> "b.txt".equals(fg.path)).findFirst().orElseThrow();
        // Corrupt the link commit AFTER the changelog/manifest entries above have already been
        // captured -- so the changelog and manifest revlogs have already grown on disk by the
        // time this throws, forcing the rollback path to truncate them back down rather than
        // just delete freshly-created (zero-size) files, which is what every other rollback
        // test in this suite exercises.
        bGroup.entries.get(0).cs = NodeIdUtil.fromHex("9".repeat(40));
        incremental.fileGroups = new ArrayList<>(List.of(bGroup));

        assertThrows(HgCorruptDataException.class, () -> fetchCmd.applyBundle(incremental));

        assertEquals(clIdxLenBefore, clIdx.length(), "changelog index must be truncated back to its pre-existing non-zero size");
        assertEquals(clDatLenBefore, clDat.length(), "changelog data must be truncated back to its pre-existing non-zero size");
        assertEquals(mfIdxLenBefore, mfIdx.length(), "manifest index must be truncated back to its pre-existing non-zero size");
        assertEquals(mfDatLenBefore, mfDat.length(), "manifest data must be truncated back to its pre-existing non-zero size");

        Revlog cl = new Revlog(clIdx, clDat);
        assertEquals(1, cl.getRevisionCount(), "Only the first commit must remain in the changelog after rollback");
        assertFalse(new File(destRepo.getStoreDir(), "journal").exists());
    }

    /**
     * Exercises the "clonebundle imported something AND the catch-up pull also found new
     * commits" branch of {@code mergeClonebundleResults} directly via reflection (it's a private
     * static helper). An end-to-end version of this scenario -- a real clonebundle apply
     * followed by a real incremental {@code getbundle} round trip that itself returns further
     * commits -- was attempted here first, but real {@link HgHttpWireServer}/{@link
     * io.github.search5.hg4j.transport.HgRemoteClient} always auto-upgrades to wireprotocol v2
     * (it sends the v2 upgrade headers unconditionally on its first {@code capabilities} call),
     * and {@code HgRemoteClientV2.getBundle} has a pre-existing, separate bug: it always seeds
     * {@code prevClContent} (the delta base for the first changeset in the response) with an
     * empty array instead of the content of the last common/root revision (unlike {@link
     * io.github.search5.hg4j.transport.HgLocalClient#getBundle}, which does this correctly) --
     * so any incremental (non-full) fetch that goes through wireprotocol v2 decodes corrupted
     * changelog content and fails a hash-integrity check. That bug lives in {@code
     * HgRemoteClientV2}/{@code transport.wireprotov2}, not in {@link FetchCommand}, so it is out
     * of scope to fix here; this reflection-based test verifies the merge-ordering behavior this
     * class is actually responsible for without depending on that unrelated, broken code path.
     */
    @Test
    public void mergeClonebundleResultsConcatenatesClonebundleImportsBeforeCatchUpResults() throws Exception {
        java.lang.reflect.Method merge = FetchCommand.class.getDeclaredMethod(
                "mergeClonebundleResults", List.class, List.class);
        merge.setAccessible(true);

        byte[] fromClonebundle = "clonebundle-commit".getBytes(StandardCharsets.UTF_8);
        byte[] fromCatchUp = "catch-up-commit".getBytes(StandardCharsets.UTF_8);
        List<byte[]> clonebundleImported = new ArrayList<>(List.of(fromClonebundle));
        List<byte[]> catchUpResults = new ArrayList<>(List.of(fromCatchUp));

        @SuppressWarnings("unchecked")
        List<byte[]> merged = (List<byte[]>) merge.invoke(null, clonebundleImported, catchUpResults);

        assertEquals(2, merged.size());
        assertArrayEquals(fromClonebundle, merged.get(0),
                "Clonebundle-imported commits must come first (oldest-first) so callers keying off the last element still see the true tip");
        assertArrayEquals(fromCatchUp, merged.get(1));

        // Sanity check the two branches that were already reachable end-to-end elsewhere, so this
        // reflection test documents the helper's full contract in one place.
        @SuppressWarnings("unchecked")
        List<byte[]> whenNoClonebundleApplied = (List<byte[]>) merge.invoke(null, (Object) null, catchUpResults);
        assertSame(catchUpResults, whenNoClonebundleApplied, "null clonebundle result must pass rest through unchanged");

        @SuppressWarnings("unchecked")
        List<byte[]> whenClonebundleWasEmpty = (List<byte[]>) merge.invoke(null, new ArrayList<byte[]>(), catchUpResults);
        assertSame(catchUpResults, whenClonebundleWasEmpty, "an empty (but non-null) clonebundle result must also pass rest through unchanged");
    }

    @Test
    public void fetchTreatsAnEmptyClonebundleAsNoOpAndFallsBackToNormalPull(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("server_repo").toFile();
        HgRepository serverRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "hello");
        new AddCommand(serverRepo).call();
        byte[] commitNode = new CommitCommand(serverRepo).setMessage("v1").setAuthor("dev").call();

        byte[] emptyCloneBundleFile = concat("HG10UN".getBytes(StandardCharsets.US_ASCII), emptyChangegroupBytes());

        HttpServer bundleServer = HttpServer.create(new InetSocketAddress(0), 0);
        bundleServer.createContext("/empty.hg", exchange -> {
            exchange.sendResponseHeaders(200, emptyCloneBundleFile.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(emptyCloneBundleFile);
            }
        });
        bundleServer.start();

        HttpServer wireServer = HttpServer.create(new InetSocketAddress(0), 0);
        wireServer.createContext("/", new HgHttpWireServer(serverRepo));
        wireServer.start();
        try {
            String manifestUrl = "http://127.0.0.1:" + bundleServer.getAddress().getPort() + "/empty.hg";
            Files.writeString(new File(serverRepo.getHgDir(), "clonebundles.manifest").toPath(),
                    manifestUrl + " BUNDLESPEC=none-v1\n");

            File destDir = tempDir.resolve("dest").toFile();
            HgRepository destRepo = Hg.init().setDirectory(destDir).call();
            String wireUrl = "http://127.0.0.1:" + wireServer.getAddress().getPort();

            List<byte[]> imported = new FetchCommand(destRepo).setSource(wireUrl).call();

            assertEquals(1, imported.size(),
                    "An empty (but validly applied) clonebundle must not stop the normal catch-up pull from running");
            assertArrayEquals(commitNode, imported.get(0));
        } finally {
            wireServer.stop(0);
            bundleServer.stop(0);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
