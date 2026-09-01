package com.github.search5.hg4j.transport.wireprotov1;

import com.github.search5.hg4j.api.AddCommand;
import com.github.search5.hg4j.api.CommitCommand;
import com.github.search5.hg4j.api.Hg;
import com.github.search5.hg4j.api.HgHook;
import com.github.search5.hg4j.lib.HgRepository;
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
import com.github.search5.hg4j.HgTestUtils;
import com.github.search5.hg4j.api.FetchCommand;
import com.github.search5.hg4j.bundle.ChangegroupParser;
import java.io.ByteArrayInputStream;
import java.util.Arrays;

public class Wire1CommandsTest {

    private static String bytes(Wire1Response r) {
        return new String(r.getPayload(), StandardCharsets.UTF_8);
    }

    @Test
    public void capabilitiesContainsTheRealTokensHgRemoteClientLooksFor() {
        // HgRemoteClient.negotiateV2() looks for "httpheader="; other hg4j client capability
        // checks (FetchCommand) look for "getbundle"/"bundle2" substrings.
        String caps = Wire1Commands.capabilitiesString();
        assertTrue(caps.contains("getbundle"));
        assertTrue(caps.contains("lookup"));
        assertTrue(caps.contains("pushkey"));
    }

    @Test
    public void headsReturnsSpaceSeparatedHexWithTrailingNewline(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File f = new File(tempDir.toFile(), "a.txt");
        Files.writeString(f.toPath(), "hello");
        new AddCommand(repo).call();
        byte[] commit = new CommitCommand(repo).setMessage("v1").setAuthor("dev").call();

        Wire1Response r = Wire1Commands.heads(repo);
        assertEquals(NodeIdUtil.toHex(commit) + "\n", bytes(r));
    }

    @Test
    public void headsOnAnEmptyRepositoryReturnsJustANewline(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        assertEquals("\n", bytes(Wire1Commands.heads(repo)));
    }

    @Test
    public void knownReportsOneBitPerRequestedNode(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File f = new File(tempDir.toFile(), "a.txt");
        Files.writeString(f.toPath(), "hello");
        new AddCommand(repo).call();
        byte[] commit = new CommitCommand(repo).setMessage("v1").setAuthor("dev").call();

        Map<String, String> args = new LinkedHashMap<>();
        args.put("nodes", NodeIdUtil.toHex(commit) + " " + "f".repeat(40));
        assertEquals("10", bytes(Wire1Commands.known(repo, args)));
    }

    @Test
    public void lookupResolvesARealRevisionToItsHexNode(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File f = new File(tempDir.toFile(), "a.txt");
        Files.writeString(f.toPath(), "hello");
        new AddCommand(repo).call();
        byte[] commit = new CommitCommand(repo).setMessage("v1").setAuthor("dev").call();

        Map<String, String> args = new LinkedHashMap<>();
        args.put("key", "0");
        assertEquals("1 " + NodeIdUtil.toHex(commit) + "\n", bytes(Wire1Commands.lookup(repo, args)));
    }

    @Test
    public void lookupReportsFailureForAnUnknownRevision(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Map<String, String> args = new LinkedHashMap<>();
        args.put("key", "nosuchrev");
        String result = bytes(Wire1Commands.lookup(repo, args));
        assertTrue(result.startsWith("0 "), "Unknown revision must report failure: " + result);
    }

    @Test
    public void pushkeySetsABookmarkAndListkeysReportsItBack(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File f = new File(tempDir.toFile(), "a.txt");
        Files.writeString(f.toPath(), "hello");
        new AddCommand(repo).call();
        byte[] commit = new CommitCommand(repo).setMessage("v1").setAuthor("dev").call();
        String hex = NodeIdUtil.toHex(commit);

        Map<String, String> pushArgs = new LinkedHashMap<>();
        pushArgs.put("namespace", "bookmarks");
        pushArgs.put("key", "mybook");
        pushArgs.put("old", "");
        pushArgs.put("new", hex);
        assertEquals("1\n", bytes(Wire1Commands.pushkey(repo, pushArgs)));

        Map<String, String> listArgs = new LinkedHashMap<>();
        listArgs.put("namespace", "bookmarks");
        assertEquals("mybook\t" + hex + "\n", bytes(Wire1Commands.listkeys(repo, listArgs)));
    }

    @Test
    public void batchDispatchesEachSubcommandAndJoinsResponsesWithSemicolons(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File f = new File(tempDir.toFile(), "a.txt");
        Files.writeString(f.toPath(), "hello");
        new AddCommand(repo).call();
        byte[] commit = new CommitCommand(repo).setMessage("v1").setAuthor("dev").call();
        String hex = NodeIdUtil.toHex(commit);

        // Mirrors real hg's own encodebatchcmds(): "<op> <k>=<v>,<k>=<v>;<op> ..."
        Map<String, String> args = new LinkedHashMap<>();
        args.put("cmds", "heads ;known nodes=" + hex);
        Wire1Response r = Wire1Commands.batch(repo, args);

        String[] parts = bytes(r).split(";", -1);
        assertEquals(2, parts.length);
        assertEquals(hex + "\n", parts[0]);
        assertEquals("1", parts[1]);
    }

    @Test
    public void batchArgumentEscapingRoundTripsSpecialCharacters(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        // "lookup" with a key containing a literal '=' -- must survive escapebatcharg's ":e" round trip.
        Map<String, String> args = new LinkedHashMap<>();
        args.put("cmds", "lookup key=weird" + escapeForTest("=") + "name");
        Wire1Response r = Wire1Commands.batch(repo, args);
        assertTrue(bytes(r).startsWith("0 "), "Unknown revision must still report failure cleanly: " + bytes(r));
    }

    private static String escapeForTest(String raw) {
        return raw.replace(":", ":c").replace(",", ":o").replace(";", ":s").replace("=", ":e");
    }

    @Test
    public void getbundleProducesABundleThatDecodesBackToTheSameCommit(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File f = new File(tempDir.toFile(), "a.txt");
        Files.writeString(f.toPath(), "hello");
        new AddCommand(repo).call();
        byte[] commit = new CommitCommand(repo).setMessage("v1").setAuthor("dev").call();

        Map<String, String> args = new LinkedHashMap<>();
        args.put("common", "");
        args.put("heads", NodeIdUtil.toHex(commit));
        Wire1Response r = Wire1Commands.getbundle(repo, args);
        assertEquals(Wire1Response.Kind.STREAM, r.getKind());
        byte[] bundle = r.getPayload();
        assertTrue(bundle.length > 0);

        // Round-trip: apply into a fresh repo and confirm the same commit shows up.
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        ChangegroupParser.ChangegroupBundle parsed;
        String cgVersion = "01";
        byte[] cgBytes = bundle;
        if (bundle.length >= 6 && bundle[0] == 'H' && bundle[1] == 'G' && bundle[2] == '1' && bundle[3] == '0') {
            cgBytes = Arrays.copyOfRange(bundle, 6, bundle.length);
        }
        parsed = ChangegroupParser.parseBundle(new ByteArrayInputStream(cgBytes), cgVersion);
        new FetchCommand(destRepo).applyBundle(parsed);

        File clIdx = new File(destRepo.getStoreDir(), "00changelog.i");
        File clDat = new File(destRepo.getStoreDir(), "00changelog.d");
        var cl = destRepo.getRevlog(clIdx, clDat);
        assertEquals(1, cl.getRevisionCount());
        assertEquals(NodeIdUtil.toHex(commit), NodeIdUtil.toHex(cl.getIndexRecord(0).getNodeId()));
    }

    @Test
    public void unbundleAppliesAnIncomingPushBundle(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        File f = new File(srcDir, "a.txt");
        Files.writeString(f.toPath(), "hello");
        new AddCommand(srcRepo).call();
        byte[] commit = new CommitCommand(srcRepo).setMessage("v1").setAuthor("dev").call();

        ChangegroupParser.ChangegroupBundle bundle =
                HgTestUtils.createMockBundleFromRepo(srcRepo);
        byte[] cgBytes = HgTestUtils.serializeBundleToBytes(bundle);
        byte[] bundleBytes = new byte[6 + cgBytes.length];
        System.arraycopy("HG10UN".getBytes(StandardCharsets.US_ASCII), 0, bundleBytes, 0, 6);
        System.arraycopy(cgBytes, 0, bundleBytes, 6, cgBytes.length);

        File destDir = tempDir.resolve("dest").toFile();
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();

        Map<String, String> args = new LinkedHashMap<>();
        args.put("heads", NodeIdUtil.toHex(commit));
        Wire1Response r = Wire1Commands.unbundle(destRepo, bundleBytes, args);
        // Real hg's pushres leading digit means "were new revisions actually added", not
        // "did the request succeed" -- 1 here since a real commit landed.
        assertTrue(bytes(r).startsWith("1\n"), "unbundle must report that new revisions were added: " + bytes(r));

        File clIdx = new File(destRepo.getStoreDir(), "00changelog.i");
        File clDat = new File(destRepo.getStoreDir(), "00changelog.d");
        var cl = destRepo.getRevlog(clIdx, clDat);
        assertEquals(1, cl.getRevisionCount());
    }

    private static byte[] buildPushBundle(HgRepository srcRepo) throws Exception {
        ChangegroupParser.ChangegroupBundle bundle =
                HgTestUtils.createMockBundleFromRepo(srcRepo);
        byte[] cgBytes = HgTestUtils.serializeBundleToBytes(bundle);
        byte[] bundleBytes = new byte[6 + cgBytes.length];
        System.arraycopy("HG10UN".getBytes(StandardCharsets.US_ASCII), 0, bundleBytes, 0, 6);
        System.arraycopy(cgBytes, 0, bundleBytes, 6, cgBytes.length);
        return bundleBytes;
    }

    @Test
    public void unbundleRejectsThePushWhenAPreChangegroupHookReturnsFalse(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "hello");
        new AddCommand(srcRepo).call();
        byte[] commit = new CommitCommand(srcRepo).setMessage("v1").setAuthor("dev").call();
        byte[] bundleBytes = buildPushBundle(srcRepo);

        File destDir = tempDir.resolve("dest").toFile();
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();

        List<HgHook> preHooks = List.of(ctx -> false);
        Map<String, String> args = new LinkedHashMap<>();
        args.put("heads", NodeIdUtil.toHex(commit));
        Wire1Response r = Wire1Commands.unbundle(destRepo, bundleBytes, args, preHooks, List.of());

        assertTrue(bytes(r).startsWith("0\n"), "A pre-changegroup hook rejection must report no revisions added: " + bytes(r));

        File clIdx = new File(destRepo.getStoreDir(), "00changelog.i");
        File clDat = new File(destRepo.getStoreDir(), "00changelog.d");
        var cl = destRepo.getRevlog(clIdx, clDat);
        assertEquals(0, cl.getRevisionCount(), "A rejected push must not land any revisions");
    }

    @Test
    public void unbundleFiresPostChangegroupHookWithTheNewlyImportedNodeHexes(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "hello");
        new AddCommand(srcRepo).call();
        byte[] commit = new CommitCommand(srcRepo).setMessage("v1").setAuthor("dev").call();
        byte[] bundleBytes = buildPushBundle(srcRepo);

        File destDir = tempDir.resolve("dest").toFile();
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();

        List<Map<String, Object>> observedContexts = new ArrayList<>();
        List<HgHook> postHooks = List.of(ctx -> {
            observedContexts.add(ctx);
            return true;
        });
        Map<String, String> args = new LinkedHashMap<>();
        args.put("heads", NodeIdUtil.toHex(commit));
        Wire1Response r = Wire1Commands.unbundle(destRepo, bundleBytes, args, List.of(), postHooks);

        assertTrue(bytes(r).startsWith("1\n"));
        assertEquals(1, observedContexts.size(), "The post-changegroup hook must fire exactly once for a successful push");
        @SuppressWarnings("unchecked")
        List<String> nodes = (List<String>) observedContexts.get(0).get("nodes");
        assertEquals(List.of(NodeIdUtil.toHex(commit)), nodes);
        assertSame(destRepo, observedContexts.get(0).get("repository"));
    }

    @Test
    public void unbundleDoesNotFireHooksWhenThereIsNothingToPush(@TempDir Path tempDir) throws Exception {
        HgRepository destRepo = Hg.init().setDirectory(tempDir.resolve("dest").toFile()).call();

        List<Map<String, Object>> observedContexts = new ArrayList<>();
        List<HgHook> postHooks = List.of(ctx -> {
            observedContexts.add(ctx);
            return true;
        });
        Wire1Response r = Wire1Commands.unbundle(destRepo, new byte[0], new LinkedHashMap<>(), List.of(), postHooks);

        assertTrue(bytes(r).startsWith("0\n"));
        assertTrue(observedContexts.isEmpty(), "No changegroup was received, so the hook must not fire");
    }

    @Test
    public void capabilitiesStringAdvertisesClonebundlesOnlyWhenTheManifestFileExists(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();

        assertFalse(Wire1Commands.capabilitiesString(repo).contains("clonebundles"),
                "Without a clonebundles.manifest file, the capability must not be advertised");

        File manifest = new File(repo.getHgDir(), "clonebundles.manifest");
        Files.writeString(manifest.toPath(), "https://example.com/bundle.hg BUNDLESPEC=none-v2\n");

        assertTrue(Wire1Commands.capabilitiesString(repo).contains("clonebundles"),
                "Once clonebundles.manifest exists, the capability must be advertised");
    }

    @Test
    public void clonebundlesCommandReturnsTheManifestFileContentVerbatim(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        String manifestBody = "https://example.com/bundle.hg BUNDLESPEC=none-v2\n";
        Files.writeString(new File(repo.getHgDir(), "clonebundles.manifest").toPath(), manifestBody);

        Wire1Response r = Wire1Commands.clonebundles(repo);
        assertEquals(Wire1Response.Kind.BYTES, r.getKind());
        assertEquals(manifestBody, bytes(r));
    }

    @Test
    public void clonebundlesCommandReturnsEmptyWhenNoManifestFileExists(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Wire1Response r = Wire1Commands.clonebundles(repo);
        assertEquals("", bytes(r));
    }

    @Test
    public void dispatchRoutesClonebundlesToTheSameHandler(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        String manifestBody = "https://example.com/bundle.hg BUNDLESPEC=none-v2\n";
        Files.writeString(new File(repo.getHgDir(), "clonebundles.manifest").toPath(), manifestBody);

        Wire1Response r = Wire1Commands.dispatch(repo, "clonebundles", new LinkedHashMap<>());
        assertEquals(manifestBody, bytes(r));
    }
}
