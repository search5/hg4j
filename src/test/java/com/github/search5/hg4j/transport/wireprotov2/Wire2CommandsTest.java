package com.github.search5.hg4j.transport.wireprotov2;

import com.github.search5.hg4j.api.AddCommand;
import com.github.search5.hg4j.api.BranchCommand;
import com.github.search5.hg4j.api.CommitCommand;
import com.github.search5.hg4j.api.Hg;
import com.github.search5.hg4j.api.PhaseCommand;
import com.github.search5.hg4j.errors.HgProtocolException;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Wire2Commands} exercised directly against real repositories -- no HTTP
 * framing involved (that is covered separately by {@code HgHttpTransportV2RoundtripTest}). Args
 * maps are passed as plain Java objects ({@code byte[]}, {@code List}, {@code Map}) since
 * {@link Cbor}'s {@code asX} accessors accept already-decoded values directly.
 */
public class Wire2CommandsTest {

    private static final byte[] NULL_NODE = new byte[20];

    private static byte[] writeAndCommit(File repoDir, HgRepository repo, String relPath, String content, String message) throws Exception {
        File f = new File(repoDir, relPath);
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), content);
        new AddCommand(repo).call();
        return new CommitCommand(repo).setMessage(message).setAuthor("dev").call();
    }

    private static String hex(byte[] node) {
        return NodeIdUtil.toHex(node);
    }

    private static Map<String, Object> explicitSpec(byte[]... nodes) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("type", "changesetexplicit");
        List<Object> nodeList = new ArrayList<>();
        for (byte[] n : nodes) nodeList.add(n);
        spec.put("nodes", nodeList);
        return spec;
    }

    private static Map<String, Object> dagrangeSpec(List<byte[]> roots, List<byte[]> heads) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("type", "changesetdagrange");
        spec.put("roots", new ArrayList<Object>(roots));
        spec.put("heads", new ArrayList<Object>(heads));
        return spec;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> recordAt(List<Object> result, int index) {
        return (Map<String, Object>) result.get(index);
    }

    @SuppressWarnings("unchecked")
    private static List<byte[]> parents(Map<String, Object> record) {
        List<Object> raw = (List<Object>) record.get("parents");
        List<byte[]> out = new ArrayList<>();
        for (Object o : raw) out.add((byte[]) o);
        return out;
    }

    // ==================== heads / known / lookup ====================

    @Test
    public void headsOnEmptyRepositoryReturnsAnEmptyList(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        List<Object> result = Wire2Commands.heads(repo);
        assertEquals(List.of(List.of()), result);
    }

    @Test
    public void headsReturnsOnlyTheNonParentNode(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        writeAndCommit(tempDir.toFile(), repo, "a.txt", "one", "c1");
        byte[] second = writeAndCommit(tempDir.toFile(), repo, "a.txt", "two", "c2");

        List<Object> result = Wire2Commands.heads(repo);
        assertEquals(1, result.size());
        @SuppressWarnings("unchecked")
        List<Object> headsList = (List<Object>) result.get(0);
        assertEquals(1, headsList.size());
        assertArrayEquals(second, (byte[]) headsList.get(0));
    }

    @Test
    public void knownOnEmptyRepositoryReportsEverythingUnknown(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("nodes", List.of((Object) new byte[20]));
        List<Object> result = Wire2Commands.known(repo, args);
        assertEquals("0", new String((byte[]) result.get(0), StandardCharsets.US_ASCII));
    }

    @Test
    public void knownReportsOneBitPerRequestedNode(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        byte[] commit = writeAndCommit(tempDir.toFile(), repo, "a.txt", "hello", "c1");

        Map<String, Object> args = new LinkedHashMap<>();
        byte[] unknown = new byte[20];
        unknown[0] = (byte) 0xFF;
        args.put("nodes", List.of(commit, unknown));
        List<Object> result = Wire2Commands.known(repo, args);
        assertEquals("10", new String((byte[]) result.get(0), StandardCharsets.US_ASCII));
    }

    @Test
    public void lookupResolvesARealRevisionToItsNode(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        byte[] commit = writeAndCommit(tempDir.toFile(), repo, "a.txt", "hello", "c1");

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("key", "0");
        List<Object> result = Wire2Commands.lookup(repo, args);
        assertArrayEquals(commit, (byte[]) result.get(0));
    }

    @Test
    public void lookupThrowsForAnUnknownRevision(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("key", "nosuchrev");
        HgProtocolException e = assertThrows(HgProtocolException.class, () -> Wire2Commands.lookup(repo, args));
        assertTrue(e.getMessage().contains("unknown revision: nosuchrev"), e.getMessage());
    }

    // ==================== branchmap ====================

    @Test
    public void branchmapOnEmptyRepositoryReturnsAnEmptyMap(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        List<Object> result = Wire2Commands.branchmap(repo);
        assertEquals(List.of(Map.of()), result);
    }

    @Test
    public void branchmapGroupsHeadsUnderTheDefaultBranch(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        byte[] commit = writeAndCommit(tempDir.toFile(), repo, "a.txt", "hello", "c1");

        List<Object> result = Wire2Commands.branchmap(repo);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result.get(0);
        assertTrue(map.containsKey("default"));
        @SuppressWarnings("unchecked")
        List<Object> nodes = (List<Object>) map.get("default");
        assertEquals(1, nodes.size());
        assertArrayEquals(commit, (byte[]) nodes.get(0));
    }

    @Test
    public void branchmapUsesTheActiveNamedBranch(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        new BranchCommand(repo).setBranchName("feature").call();
        writeAndCommit(tempDir.toFile(), repo, "a.txt", "hello", "c1");

        List<Object> result = Wire2Commands.branchmap(repo);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result.get(0);
        assertTrue(map.containsKey("feature"), map.keySet().toString());
        assertFalse(map.containsKey("default"));
    }

    @Test
    public void branchmapFallsBackToDefaultWhenTheBranchFileIsEmpty(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        writeAndCommit(tempDir.toFile(), repo, "a.txt", "hello", "c1");
        // getBranch() trims file content; an empty/blank file makes branch.isEmpty() true,
        // exercising branchmap's null-or-empty ternary branch distinctly from the "no file" default.
        Files.writeString(new File(repo.getHgDir(), "branch").toPath(), "   \n");

        List<Object> result = Wire2Commands.branchmap(repo);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result.get(0);
        assertTrue(map.containsKey("default"), map.keySet().toString());
    }

    // ==================== changesetdata ====================

    @Test
    public void changesetdataWithAllFieldsOnARootCommit(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        byte[] commit = writeAndCommit(tempDir.toFile(), repo, "a.txt", "hello", "c1");
        new PhaseCommand(repo).setRevision(hex(commit)).setPhase(1).call(); // draft
        Wire2Commands.applyPushkey(repo, "bookmarks", "mybook", "", hex(commit));

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("revisions", List.of(explicitSpec(commit)));
        args.put("fields", List.of("parents", "phase", "bookmarks", "revision"));
        List<Object> result = Wire2Commands.changesetdata(repo, args);

        assertEquals(3, result.size(), "header + record + following revision bytes");
        @SuppressWarnings("unchecked")
        Map<String, Object> header = (Map<String, Object>) result.get(0);
        assertEquals(1L, header.get("totalitems"));

        Map<String, Object> record = recordAt(result, 1);
        assertArrayEquals(commit, (byte[]) record.get("node"));
        List<byte[]> p = parents(record);
        assertArrayEquals(NULL_NODE, p.get(0));
        assertArrayEquals(NULL_NODE, p.get(1));
        assertEquals("draft", record.get("phase"));
        assertEquals(List.of("mybook"), record.get("bookmarks"));

        @SuppressWarnings("unchecked")
        List<Object> fieldsFollowing = (List<Object>) record.get("fieldsfollowing");
        assertEquals(1, fieldsFollowing.size());

        byte[] revisionBytes = (byte[]) result.get(2);
        Revlog changelog = Wire2Commands.changelog(repo);
        assertArrayEquals(changelog.getRevisionContent(0), revisionBytes);
    }

    @Test
    public void changesetdataChildCommitHasParentsSetAndNoBookmarksField(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        byte[] first = writeAndCommit(tempDir.toFile(), repo, "a.txt", "one", "c1");
        byte[] second = writeAndCommit(tempDir.toFile(), repo, "a.txt", "two", "c2");

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("revisions", List.of(explicitSpec(second)));
        args.put("fields", List.of("parents", "phase", "bookmarks"));
        List<Object> result = Wire2Commands.changesetdata(repo, args);

        Map<String, Object> record = recordAt(result, 1);
        List<byte[]> p = parents(record);
        assertArrayEquals(first, p.get(0));
        assertArrayEquals(NULL_NODE, p.get(1));
        assertEquals("draft", record.get("phase"), "new commits default to draft with no explicit phase change");
        assertFalse(record.containsKey("bookmarks"), "no bookmark points at this node");
        assertFalse(record.containsKey("fieldsfollowing"), "revision field not requested");
        assertEquals(2, result.size(), "header + record only, no following bytes");
    }

    @Test
    public void changesetdataReportsSecretPhase(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        byte[] commit = writeAndCommit(tempDir.toFile(), repo, "a.txt", "hello", "c1");
        new PhaseCommand(repo).setRevision(hex(commit)).setPhase(2).call(); // secret

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("revisions", List.of(explicitSpec(commit)));
        args.put("fields", List.of("phase"));
        List<Object> result = Wire2Commands.changesetdata(repo, args);
        assertEquals("secret", recordAt(result, 1).get("phase"));
    }

    @Test
    public void changesetdataReportsPublicPhaseWhenExplicitlyPublished(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        byte[] commit = writeAndCommit(tempDir.toFile(), repo, "a.txt", "hello", "c1");
        new PhaseCommand(repo).setRevision(hex(commit)).setPhase(0).call(); // public

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("revisions", List.of(explicitSpec(commit)));
        args.put("fields", List.of("phase"));
        List<Object> result = Wire2Commands.changesetdata(repo, args);
        assertEquals("public", recordAt(result, 1).get("phase"));
    }

    @Test
    public void changesetdataDagrangeExcludesAncestorsOfRoots(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        byte[] c0 = writeAndCommit(tempDir.toFile(), repo, "a.txt", "one", "c0");
        byte[] c1 = writeAndCommit(tempDir.toFile(), repo, "a.txt", "two", "c1");
        byte[] c2 = writeAndCommit(tempDir.toFile(), repo, "a.txt", "three", "c2");

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("revisions", List.of(dagrangeSpec(List.of(c0), List.of(c2))));
        args.put("fields", List.of());
        List<Object> result = Wire2Commands.changesetdata(repo, args);

        @SuppressWarnings("unchecked")
        Map<String, Object> header = (Map<String, Object>) result.get(0);
        assertEquals(2L, header.get("totalitems"), "c0 (the root) and its ancestors must be excluded");
        assertArrayEquals(c1, (byte[]) recordAt(result, 1).get("node"));
        assertArrayEquals(c2, (byte[]) recordAt(result, 2).get("node"));
    }

    @Test
    public void changesetdataRejectsAnUnsupportedRevisionSpecifierType(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("type", "changesetexplicitdepth");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("revisions", List.of(spec));
        args.put("fields", List.of());
        HgProtocolException e = assertThrows(HgProtocolException.class, () -> Wire2Commands.changesetdata(repo, args));
        assertTrue(e.getMessage().contains("unsupported revision specifier type"), e.getMessage());
    }

    // ==================== manifestdata ====================

    @Test
    public void manifestdataRejectsANonEmptyTreeArgument(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("tree", "somepath");
        args.put("nodes", List.of());
        args.put("fields", List.of());
        HgProtocolException e = assertThrows(HgProtocolException.class, () -> Wire2Commands.manifestdata(repo, args));
        assertTrue(e.getMessage().contains("tree manifests are not supported"), e.getMessage());
    }

    @Test
    public void manifestdataThrowsForAnUnknownNode(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        writeAndCommit(tempDir.toFile(), repo, "a.txt", "hello", "c1");

        byte[] bogus = new byte[20];
        bogus[0] = (byte) 0xEE;
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("nodes", List.of((Object) bogus));
        args.put("fields", List.of());
        HgProtocolException e = assertThrows(HgProtocolException.class, () -> Wire2Commands.manifestdata(repo, args));
        assertTrue(e.getMessage().contains("unknown node"), e.getMessage());
    }

    @Test
    public void manifestdataReturnsParentsAndRevisionForASecondRevision(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        writeAndCommit(tempDir.toFile(), repo, "a.txt", "one", "c1");
        writeAndCommit(tempDir.toFile(), repo, "a.txt", "two", "c2");

        Revlog manifest = repo.getManifestRevlog();
        assertEquals(2, manifest.getRevisionCount());
        byte[] mfNode0 = manifest.getIndexRecord(0).getNodeId();
        byte[] mfNode1 = manifest.getIndexRecord(1).getNodeId();

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("nodes", List.of((Object) mfNode1));
        args.put("fields", List.of("parents", "revision"));
        List<Object> result = Wire2Commands.manifestdata(repo, args);

        @SuppressWarnings("unchecked")
        Map<String, Object> header = (Map<String, Object>) result.get(0);
        assertEquals(1L, header.get("totalitems"));

        Map<String, Object> record = recordAt(result, 1);
        assertArrayEquals(mfNode1, (byte[]) record.get("node"));
        List<byte[]> p = parents(record);
        assertArrayEquals(mfNode0, p.get(0));
        assertArrayEquals(NULL_NODE, p.get(1));

        byte[] revisionBytes = (byte[]) result.get(2);
        assertArrayEquals(manifest.getRevisionContent(1), revisionBytes);
    }

    // ==================== filesdata ====================

    @Test
    public void filesdataReturnsNodeParentsLinknodeAndRevisionForATrackedFile(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        byte[] commit = writeAndCommit(tempDir.toFile(), repo, "a.txt", "hello", "c1");

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("revisions", List.of(explicitSpec(commit)));
        args.put("fields", List.of("parents", "linknode", "revision"));
        List<Object> result = Wire2Commands.filesdata(repo, args);

        @SuppressWarnings("unchecked")
        Map<String, Object> header = (Map<String, Object>) result.get(0);
        assertEquals(1L, header.get("totalpaths"));
        assertEquals(1L, header.get("totalitems"));

        @SuppressWarnings("unchecked")
        Map<String, Object> pathHeader = (Map<String, Object>) result.get(1);
        assertEquals("a.txt", pathHeader.get("path"));
        assertEquals(1L, pathHeader.get("totalitems"));

        Map<String, Object> record = recordAt(result, 2);
        List<byte[]> p = parents(record);
        assertArrayEquals(NULL_NODE, p.get(0));
        assertArrayEquals(NULL_NODE, p.get(1));
        assertArrayEquals(commit, (byte[]) record.get("linknode"));

        byte[] content = (byte[]) result.get(3);
        assertEquals("hello", new String(content, StandardCharsets.UTF_8));
        assertEquals(4, result.size());
    }

    @Test
    public void filesdataSkipsAPathWhoseFilelogIsMissingFromTheStore(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "a.txt").toPath(), "hello");
        Files.writeString(new File(tempDir.toFile(), "b.txt").toPath(), "world");
        new AddCommand(repo).call();
        byte[] commit = new CommitCommand(repo).setMessage("c1").setAuthor("dev").call();

        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "b.txt");
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        assertTrue(flIdx.exists());
        Files.delete(flIdx.toPath());
        Files.deleteIfExists(flDat.toPath());

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("revisions", List.of(explicitSpec(commit)));
        args.put("fields", List.of("revision"));
        List<Object> result = Wire2Commands.filesdata(repo, args);

        // header, a.txt path header + record + content, then b.txt path header with nothing
        // following it because its filelog no longer exists on disk.
        assertEquals(5, result.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> aHeader = (Map<String, Object>) result.get(1);
        assertEquals("a.txt", aHeader.get("path"));
        @SuppressWarnings("unchecked")
        Map<String, Object> bHeader = (Map<String, Object>) result.get(4);
        assertEquals("b.txt", bHeader.get("path"));
        assertEquals(1L, bHeader.get("totalitems"), "header count is independent of on-disk filelog presence");
    }

    // ==================== listkeys / pushkey / readListKeys / applyPushkey ====================

    @Test
    public void listkeysOnAnUnrecognizedNamespaceReturnsAnEmptyMap(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("namespace", "tags");
        List<Object> result = Wire2Commands.listkeys(repo, args);
        assertEquals(Map.of(), result.get(0));
    }

    @Test
    public void pushkeyThenListkeysRoundTripsABookmarkThroughTheTopLevelMethods(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        byte[] commit = writeAndCommit(tempDir.toFile(), repo, "a.txt", "hello", "c1");
        String h = hex(commit);

        Map<String, Object> pushArgs = new LinkedHashMap<>();
        pushArgs.put("namespace", "bookmarks");
        pushArgs.put("key", "mybook");
        pushArgs.put("old", "");
        pushArgs.put("new", h);
        List<Object> pushResult = Wire2Commands.pushkey(repo, pushArgs);
        assertEquals(List.of(true), pushResult);

        Map<String, Object> listArgs = new LinkedHashMap<>();
        listArgs.put("namespace", "bookmarks");
        List<Object> listResult = Wire2Commands.listkeys(repo, listArgs);
        @SuppressWarnings("unchecked")
        Map<String, Object> keys = (Map<String, Object>) listResult.get(0);
        assertEquals(h, keys.get("mybook"));
    }

    @Test
    public void applyPushkeyRejectsANonBookmarksNamespace(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        assertFalse(Wire2Commands.applyPushkey(repo, "phases", "k", "", "v"));
    }

    @Test
    public void applyPushkeyFailsWhenTheOldValueDoesNotMatch(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        byte[] commit = writeAndCommit(tempDir.toFile(), repo, "a.txt", "hello", "c1");
        assertTrue(Wire2Commands.applyPushkey(repo, "bookmarks", "mybook", "", hex(commit)));
        assertFalse(Wire2Commands.applyPushkey(repo, "bookmarks", "mybook", "wrongold", "newval"),
                "a stale expected-old value must be rejected (CAS semantics)");
    }

    @Test
    public void applyPushkeyWithAnEmptyNewValueRemovesTheBookmarkAndDeletesTheFileWhenNoneRemain(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        byte[] commit = writeAndCommit(tempDir.toFile(), repo, "a.txt", "hello", "c1");
        String h = hex(commit);
        assertTrue(Wire2Commands.applyPushkey(repo, "bookmarks", "mybook", "", h));

        File bkFile = new File(repo.getHgDir(), "bookmarks");
        assertTrue(bkFile.exists());

        assertTrue(Wire2Commands.applyPushkey(repo, "bookmarks", "mybook", h, ""));
        assertFalse(bkFile.exists(), "removing the only bookmark must delete the bookmarks file");
        assertTrue(Wire2Commands.readListKeys(repo, "bookmarks").isEmpty());
    }

    @Test
    public void applyPushkeyRemovingOneOfTwoBookmarksLeavesTheOtherInThePersistedFile(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        byte[] commit = writeAndCommit(tempDir.toFile(), repo, "a.txt", "hello", "c1");
        String h = hex(commit);
        assertTrue(Wire2Commands.applyPushkey(repo, "bookmarks", "first", "", h));
        assertTrue(Wire2Commands.applyPushkey(repo, "bookmarks", "second", "", h));

        assertTrue(Wire2Commands.applyPushkey(repo, "bookmarks", "first", h, ""));

        File bkFile = new File(repo.getHgDir(), "bookmarks");
        assertTrue(bkFile.exists(), "the file must still exist because 'second' remains");
        Map<String, String> remaining = Wire2Commands.readListKeys(repo, "bookmarks");
        assertEquals(Map.of("second", h), remaining);
    }

    @Test
    public void readListKeysSkipsBlankAndMalformedLinesInTheBookmarksFile(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        byte[] commit = writeAndCommit(tempDir.toFile(), repo, "a.txt", "hello", "c1");
        String h = hex(commit);
        File bkFile = new File(repo.getHgDir(), "bookmarks");
        Files.writeString(bkFile.toPath(), h + " good\n\n   \nmalformednospace\n" + h + " good2\n");

        Map<String, String> keys = Wire2Commands.readListKeys(repo, "bookmarks");
        assertEquals(2, keys.size());
        assertEquals(h, keys.get("good"));
        assertEquals(h, keys.get("good2"));
        assertFalse(keys.containsKey("malformednospace"));
    }

    @Test
    public void readListKeysPhasesNamespaceOnlyReportsNonPublicRevisions(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        byte[] pub = writeAndCommit(tempDir.toFile(), repo, "a.txt", "one", "c1");
        byte[] draft = writeAndCommit(tempDir.toFile(), repo, "a.txt", "two", "c2");
        byte[] secret = writeAndCommit(tempDir.toFile(), repo, "a.txt", "three", "c3");
        new PhaseCommand(repo).setRevision(hex(pub)).setPhase(0).call();
        new PhaseCommand(repo).setRevision(hex(draft)).setPhase(1).call();
        new PhaseCommand(repo).setRevision(hex(secret)).setPhase(2).call();

        Map<String, String> keys = Wire2Commands.readListKeys(repo, "phases");
        assertFalse(keys.containsKey(hex(pub)), "public revisions are not reported as phase roots");
        assertEquals("1", keys.get(hex(draft)));
        assertEquals("2", keys.get(hex(secret)));
    }
}
