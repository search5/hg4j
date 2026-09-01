package com.github.search5.hg4j.transport;

import com.github.search5.hg4j.HgTestUtils;
import com.github.search5.hg4j.api.AddCommand;
import com.github.search5.hg4j.api.CommitCommand;
import com.github.search5.hg4j.api.Hg;
import com.github.search5.hg4j.api.HgHook;
import com.github.search5.hg4j.api.MergeCommand;
import com.github.search5.hg4j.bundle.ChangegroupParser;
import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.errors.HgCorruptDataException;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.util.NodeIdUtil;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Additional coverage for {@link HgLocalClient} beyond what the existing integration tests reach
 * indirectly through {@link HgRemoteConnectionFactory}: the {@code file://} URI-stripping
 * constructor branch, empty/zero-revision repository edge cases in getHeads/getBundle, common-node
 * filtering and backward propagation (including through a merge's second parent) when computing
 * incremental startRev, a null-manifest changeset produced by real {@code hg} (a changeset with
 * zero files, whose manifest node is the null id and therefore has no manifest revlog entry at
 * all -- verified against the real hg CLI below), malformed/edge-case handling in
 * {@link HgLocalClient#pushWithHooks}, and the bookmarks/phases listkeys/pushkey namespace and
 * CAS-mismatch branches.
 */
public class HgLocalClientCoverageTest {

    // ==========================================================
    // Constructor: file:// URI stripping
    // ==========================================================

    @Test
    public void constructorStripsFileUriPrefixBeforeOpeningTheRepository(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "hello");
        new AddCommand(repo).call();
        byte[] node = new CommitCommand(repo).setAuthor("dev").setMessage("c1").call();

        try (HgLocalClient client = new HgLocalClient("file://" + repoDir.getAbsolutePath())) {
            assertEquals(List.of(NodeIdUtil.toHex(node)), client.getHeads());
        }
    }

    // ==========================================================
    // getHeads edge cases
    // ==========================================================

    @Test
    public void getHeadsReturnsEmptyListWhenChangelogIndexExistsButHasNoRevisions(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.write(new File(repo.getStoreDir(), "00changelog.i").toPath(), new byte[0]);

        try (HgLocalClient client = new HgLocalClient(repo)) {
            assertEquals(List.of(), client.getHeads());
        }
    }

    @Test
    public void getHeadsReportsOnlyTheMergeCommitAsHeadTreatingBothParentsAsNonHeads(@TempDir Path tempDir) throws Exception {
        MergeFixture m = buildMergeFixture(tempDir.resolve("repo").toFile());
        try (HgLocalClient client = new HgLocalClient(m.repo)) {
            assertEquals(List.of(NodeIdUtil.toHex(m.mergeNode)), client.getHeads());
        }
    }

    // ==========================================================
    // getBundle edge cases: missing/empty changelog
    // ==========================================================

    @Test
    public void getBundleReturnsEmptyBytesWhenChangelogIndexMissing(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        try (HgLocalClient client = new HgLocalClient(repo)) {
            assertEquals(0, client.getBundle(null, null, null).length);
        }
    }

    @Test
    public void getBundleReturnsEmptyBytesWhenChangelogHasZeroRevisions(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        Files.write(new File(repo.getStoreDir(), "00changelog.i").toPath(), new byte[0]);
        try (HgLocalClient client = new HgLocalClient(repo)) {
            assertEquals(0, client.getBundle(null, null, null).length);
        }
    }

    // ==========================================================
    // getBundle: common-node filtering, unknown/propagation branches, startRev computation
    // ==========================================================

    @Test
    public void getBundleFiltersOutNullAndNullIdEntriesFromCommonList(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "hi");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("dev").setMessage("c1").call();

        List<String> common = new ArrayList<>();
        common.add(null);
        common.add("0000000000000000000000000000000000000000");
        try (HgLocalClient client = new HgLocalClient(repo)) {
            byte[] bytes = client.getBundle(common, null, null);
            assertTrue(bytes.length > 6, "null/nullid common entries must be ignored, yielding a full bundle");
        }
    }

    @Test
    public void getBundleTreatsUnknownCommonHashAsNotFoundAndStillProducesAFullBundle(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "hi");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("dev").setMessage("c1").call();

        try (HgLocalClient client = new HgLocalClient(repo)) {
            byte[] bytes = client.getBundle(List.of("deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"), null, null);
            assertTrue(bytes.length > 6, "an unrecognized common node must not block a full bundle");
        }
    }

    @Test
    public void getBundleReturnsEmptyBytesWhenCommonAlreadyIncludesAllHeads(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "hi");
        new AddCommand(repo).call();
        byte[] node = new CommitCommand(repo).setAuthor("dev").setMessage("c1").call();

        try (HgLocalClient client = new HgLocalClient(repo)) {
            byte[] bytes = client.getBundle(List.of(NodeIdUtil.toHex(node)), null, null);
            assertEquals(0, bytes.length);
        }
    }

    @Test
    public void getBundleCommonPropagatesBackwardThroughBothMergeParents(@TempDir Path tempDir) throws Exception {
        MergeFixture m = buildMergeFixture(tempDir.resolve("repo").toFile());
        try (HgLocalClient client = new HgLocalClient(m.repo)) {
            // Reporting only the merge tip as common must propagate "known" status backward
            // through BOTH of its parents (and transitively to the shared base), exercising the
            // parent1/parent2 backward-propagation branches in the startRev computation.
            byte[] bytes = client.getBundle(List.of(NodeIdUtil.toHex(m.mergeNode)), null, null);
            assertEquals(0, bytes.length, "nothing new once the merge tip itself is already common");
        }
    }

    // ==========================================================
    // getBundle: merge topology inside the bundle payload itself (parent2 on
    // changelog/manifest/filelog entries)
    // ==========================================================

    @Test
    public void getBundleFullCloneEncodesMergeSecondParentAcrossChangelogManifestAndFilelog(@TempDir Path tempDir) throws Exception {
        MergeFixture m = buildMergeFixture(tempDir.resolve("repo").toFile());

        byte[] bundleBytes;
        try (HgLocalClient client = new HgLocalClient(m.repo)) {
            bundleBytes = client.getBundle(null, null, null);
        }
        assertTrue(bundleBytes.length > 6);
        ChangegroupParser.ChangegroupBundle bundle = ChangegroupParser.parseBundle(
                new ByteArrayInputStream(bundleBytes, 6, bundleBytes.length - 6), "01");

        assertEquals(4, bundle.changelogEntries.size());
        ChangegroupParser.ChangeGroupEntry mergeClEntry = bundle.changelogEntries.get(3);
        assertArrayEquals(m.mergeNode, mergeClEntry.node);
        assertFalse(isNullId(mergeClEntry.p2), "merge changeset must carry a real second parent in the changelog group");

        ChangegroupParser.ChangeGroupEntry mergeMfEntry = bundle.manifestEntries.get(bundle.manifestEntries.size() - 1);
        assertFalse(isNullId(mergeMfEntry.p2), "merge changeset's manifest entry must carry a real second parent");

        assertEquals(1, bundle.fileGroups.size());
        ChangegroupParser.FileGroup fg = bundle.fileGroups.get(0);
        assertEquals("hello.txt", fg.path);
        boolean anyTwoParentFileRevision = fg.entries.stream()
                .anyMatch(e -> !isNullId(e.p1) && !isNullId(e.p2));
        assertTrue(anyTwoParentFileRevision,
                "the merge must have produced a two-parent filelog revision for hello.txt");

        // Round-trip: pushing this bundle into a fresh target repository must apply cleanly.
        HgRepository targetRepo = Hg.init().setDirectory(tempDir.resolve("target").toFile()).call();
        try (HgLocalClient targetClient = new HgLocalClient(targetRepo)) {
            HgLocalClient.PushResult result =
                    targetClient.pushWithHooks(bundleBytes, List.of(), List.of(), List.of());
            assertEquals("push successful, imported 4 changesets natively", result.status);
        }
    }

    // ==========================================================
    // getBundle: null-manifest changeset produced by real hg (a changeset with zero files has
    // manifest node = nullid and gets NO manifest revlog entry at all -- verified against the
    // real hg CLI: `hg log --debug` on such a changeset shows "manifest: -1:0000...0000").
    // ==========================================================

    @Test
    public void getBundleSkipsManifestEntryForNullManifestChangesetFromRealHg(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "real hg CLI required for this interop check");

        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = HgTestUtils.nativeRepo(repoDir, dir -> {});
        HgTestUtils.hg(repoDir, "commit", "--config", "ui.allowemptycommit=true", "-m", "empty", "-u", "tester");
        Files.writeString(new File(repoDir, "a.txt").toPath(), "hello\n");
        HgTestUtils.hg(repoDir, "add", "a.txt");
        HgTestUtils.hg(repoDir, "commit", "-m", "add a", "-u", "tester");
        String emptyNodeHex = HgTestUtils.hg(repoDir, "log", "-r", "0", "--template", "{node}");

        try (HgLocalClient client = new HgLocalClient(repo)) {
            byte[] fullBytes = client.getBundle(null, null, null);
            ChangegroupParser.ChangegroupBundle fullBundle = ChangegroupParser.parseBundle(
                    new ByteArrayInputStream(fullBytes, 6, fullBytes.length - 6), "01");
            assertEquals(2, fullBundle.changelogEntries.size(), "both changesets, including the empty one, are present");
            assertEquals(1, fullBundle.manifestEntries.size(),
                    "the null-manifest empty changeset must not get a manifest entry, matching real hg's changegroup packer");

            // Incremental pull starting right after the null-manifest changeset: the "previous
            // common manifest content" lookup must gracefully fall back to empty content instead
            // of failing, since the null id has no manifest revision to look up.
            byte[] incBytes = client.getBundle(List.of(emptyNodeHex), null, null);
            ChangegroupParser.ChangegroupBundle incBundle = ChangegroupParser.parseBundle(
                    new ByteArrayInputStream(incBytes, 6, incBytes.length - 6), "01");
            assertEquals(1, incBundle.changelogEntries.size());
            assertEquals(1, incBundle.manifestEntries.size());
        }
    }

    // ==========================================================
    // pushWithHooks: bundleBytes guard, malformed HG10 header handling, hooks
    // ==========================================================

    @Test
    public void pushWithHooksReportsNoChangesForNullBundleBytes(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        try (HgLocalClient client = new HgLocalClient(repo)) {
            HgLocalClient.PushResult result = client.pushWithHooks(null, List.of(), List.of(), List.of());
            assertEquals("no changes found", result.status);
            assertTrue(result.importedNodeHexes.isEmpty());
        }
    }

    @Test
    public void pushTreatsSubHeaderLengthBundleAsARawEmptyChangegroup(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        byte[] tooShortToHaveAMagicHeader = new byte[]{1, 2, 3};
        try (HgLocalClient client = new HgLocalClient(repo)) {
            HgLocalClient.PushResult result =
                    client.pushWithHooks(tooShortToHaveAMagicHeader, List.of(), List.of(), List.of());
            assertEquals("push successful, imported 0 changesets natively", result.status);
        }
    }

    @Test
    public void pushRejectsEachMalformedHg10MagicPrefixVariantAsCorruptData(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        try (HgLocalClient client = new HgLocalClient(repo)) {
            List<byte[]> malformedHeaders = List.of(
                    "XG10UN______".getBytes(StandardCharsets.US_ASCII), // byte 0 mismatch ('H' expected)
                    "HX10UN______".getBytes(StandardCharsets.US_ASCII), // byte 1 mismatch ('G' expected)
                    "HGX0UN______".getBytes(StandardCharsets.US_ASCII), // byte 2 mismatch ('1' expected)
                    "HG1XUN______".getBytes(StandardCharsets.US_ASCII)  // byte 3 mismatch ('0' expected)
            );
            for (byte[] bytes : malformedHeaders) {
                assertThrows(HgCorruptDataException.class,
                        () -> client.pushWithHooks(bytes, List.of(), List.of(), List.of()),
                        "malformed HG10 magic prefix must be rejected: " + new String(bytes, StandardCharsets.US_ASCII));
            }
        }
    }

    @Test
    public void pushRejectsUnsupportedCompressionCodeInAWellFormedHg10Header(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        byte[] bzBundle = "HG10BZsomepayloadbytes".getBytes(StandardCharsets.US_ASCII);
        try (HgLocalClient client = new HgLocalClient(repo)) {
            IOException ex = assertThrows(HgCorruptDataException.class,
                    () -> client.pushWithHooks(bzBundle, List.of(), List.of(), List.of()));
            assertTrue(ex.getMessage().contains("HG10BZ"), "message should name the unsupported format: " + ex.getMessage());
        }
    }

    @Test
    public void pushWithHooksInvokesPreChangegroupHookWithPendingNodesAndAllowsPushWhenApproved(@TempDir Path tempDir) throws Exception {
        File sourceDir = tempDir.resolve("source").toFile();
        HgRepository sourceRepo = Hg.init().setDirectory(sourceDir).call();
        Files.writeString(new File(sourceDir, "a.txt").toPath(), "hi");
        new AddCommand(sourceRepo).call();
        byte[] node = new CommitCommand(sourceRepo).setAuthor("dev").setMessage("c1").call();
        byte[] bundleBytes;
        try (HgLocalClient sourceClient = new HgLocalClient(sourceRepo)) {
            bundleBytes = sourceClient.getBundle(null, null, null);
        }

        HgRepository targetRepo = Hg.init().setDirectory(tempDir.resolve("target").toFile()).call();
        List<String> seenNodes = new ArrayList<>();
        HgHook approvingHook = ctx -> {
            @SuppressWarnings("unchecked")
            List<String> nodes = (List<String>) ctx.get("nodes");
            seenNodes.addAll(nodes);
            return true;
        };

        try (HgLocalClient targetClient = new HgLocalClient(targetRepo)) {
            HgLocalClient.PushResult result =
                    targetClient.pushWithHooks(bundleBytes, List.of(), List.of(approvingHook), List.of());
            assertEquals(List.of(NodeIdUtil.toHex(node)), seenNodes);
            assertEquals("push successful, imported 1 changesets natively", result.status);
            assertEquals(List.of(NodeIdUtil.toHex(node)), result.importedNodeHexes);
        }
    }

    @Test
    public void pushWithHooksAbortsBeforeApplyingAnythingWhenPreChangegroupHookRejects(@TempDir Path tempDir) throws Exception {
        File sourceDir = tempDir.resolve("source").toFile();
        HgRepository sourceRepo = Hg.init().setDirectory(sourceDir).call();
        Files.writeString(new File(sourceDir, "a.txt").toPath(), "hi");
        new AddCommand(sourceRepo).call();
        new CommitCommand(sourceRepo).setAuthor("dev").setMessage("c1").call();
        byte[] bundleBytes;
        try (HgLocalClient sourceClient = new HgLocalClient(sourceRepo)) {
            bundleBytes = sourceClient.getBundle(null, null, null);
        }

        HgRepository targetRepo = Hg.init().setDirectory(tempDir.resolve("target").toFile()).call();
        HgHook rejectingHook = ctx -> false;

        try (HgLocalClient targetClient = new HgLocalClient(targetRepo)) {
            IOException ex = assertThrows(IOException.class,
                    () -> targetClient.pushWithHooks(bundleBytes, List.of(), List.of(rejectingHook), List.of()));
            assertTrue(ex.getMessage().contains("pre-changegroup hook"));
        }

        File clIdx = new File(targetRepo.getStoreDir(), "00changelog.i");
        assertFalse(clIdx.exists(), "nothing should be written to the target once the pre-hook rejects the push");
    }

    // ==========================================================
    // listKeys: namespace dispatch, bookmarks parsing resilience, phases filtering
    // ==========================================================

    @Test
    public void listKeysReturnsEmptyMapForAnUnrecognizedNamespace(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        try (HgLocalClient client = new HgLocalClient(repo)) {
            assertEquals(Map.of(), client.listKeys("obsolete"));
        }
    }

    @Test
    public void listKeysBookmarksSkipsBlankAndSpacelessMalformedLines(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        File bkFile = new File(repo.getHgDir(), "bookmarks");
        String node1 = "1111111111111111111111111111111111111111";
        String node2 = "2222222222222222222222222222222222222222";
        Files.writeString(bkFile.toPath(),
                node1 + " good-bookmark\n"
                        + "\n"
                        + "malformed-line-without-a-space\n"
                        + node2 + " another-bookmark\n");

        try (HgLocalClient client = new HgLocalClient(repo)) {
            Map<String, String> keys = client.listKeys("bookmarks");
            assertEquals(Map.of("good-bookmark", node1, "another-bookmark", node2), keys);
        }
    }

    @Test
    public void listKeysPhasesReturnsEmptyMapWhenNoCommitsExist(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        try (HgLocalClient client = new HgLocalClient(repo)) {
            assertEquals(Map.of(), client.listKeys("phases"));
        }
    }

    @Test
    public void listKeysPhasesExcludesPublicRevisionsAndIncludesDraftOnes(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "real hg CLI required for this interop check");

        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = HgTestUtils.nativeRepo(repoDir, dir -> {});
        Files.writeString(new File(repoDir, "a.txt").toPath(), "one\n");
        HgTestUtils.hg(repoDir, "add", "a.txt");
        HgTestUtils.hg(repoDir, "commit", "-m", "c0", "-u", "tester");
        Files.writeString(new File(repoDir, "a.txt").toPath(), "two\n");
        HgTestUtils.hg(repoDir, "commit", "-m", "c1", "-u", "tester");
        HgTestUtils.hg(repoDir, "phase", "--public", "-r", "0");

        String node0 = HgTestUtils.hg(repoDir, "log", "-r", "0", "--template", "{node}");
        String node1 = HgTestUtils.hg(repoDir, "log", "-r", "1", "--template", "{node}");

        try (HgLocalClient client = new HgLocalClient(repo)) {
            Map<String, String> phases = client.listKeys("phases");
            assertFalse(phases.containsKey(node0), "a public revision must not appear in listkeys phases");
            assertEquals("1", phases.get(node1), "a draft revision must be reported with phase value 1");
        }
    }

    // ==========================================================
    // pushkey: namespace dispatch, explicit non-null CAS values, mismatch, removal
    // ==========================================================

    @Test
    public void pushkeyReturnsFalseForAnUnsupportedNamespace(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        try (HgLocalClient client = new HgLocalClient(repo)) {
            assertFalse(client.pushkey("phases", "somekey", null, "1"));
        }
    }

    @Test
    public void pushkeyAddsBookmarkWhenOldAndNewValuesAreExplicitNonNullStrings(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        String node = "3333333333333333333333333333333333333333";
        try (HgLocalClient client = new HgLocalClient(repo)) {
            boolean ok = client.pushkey("bookmarks", "feature", "", node);
            assertTrue(ok);
            assertEquals(node, client.listKeys("bookmarks").get("feature"));
        }
    }

    @Test
    public void pushkeyReturnsFalseWhenOldValueDoesNotMatchTheCurrentValue(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        try (HgLocalClient client = new HgLocalClient(repo)) {
            boolean ok = client.pushkey("bookmarks", "feature", "deadbeef", "cafebabe");
            assertFalse(ok, "the CAS check must fail when oldVal doesn't match the (absent) current value");
            assertTrue(client.listKeys("bookmarks").isEmpty());
        }
    }

    @Test
    public void pushkeyTreatsANullNewValueTheSameAsAnEmptyStringDeleteRequest(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        String node = "5555555555555555555555555555555555555555";
        try (HgLocalClient client = new HgLocalClient(repo)) {
            assertTrue(client.pushkey("bookmarks", "solo2", null, node));
            boolean removed = client.pushkey("bookmarks", "solo2", node, null);
            assertTrue(removed, "a null newVal must be treated like an empty string (delete request)");
            assertTrue(client.listKeys("bookmarks").isEmpty());
        }
    }

    @Test
    public void pushkeyRemovesSoleBookmarkAndDeletesTheBookmarksFile(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        String node = "4444444444444444444444444444444444444444";
        try (HgLocalClient client = new HgLocalClient(repo)) {
            assertTrue(client.pushkey("bookmarks", "solo", null, node));
            File bkFile = new File(repo.getHgDir(), "bookmarks");
            assertTrue(bkFile.exists());

            boolean removed = client.pushkey("bookmarks", "solo", node, "");
            assertTrue(removed);
            assertFalse(bkFile.exists(), "the bookmarks file must be deleted once the last bookmark is removed");
            assertTrue(client.listKeys("bookmarks").isEmpty());
        }
    }

    // ==========================================================
    // Shared fixtures
    // ==========================================================

    private static final class MergeFixture {
        HgRepository repo;
        byte[] baseNode;
        byte[] yoursNode;
        byte[] theirsNode;
        byte[] mergeNode;
    }

    /**
     * Base commit touching hello.txt, then two divergent edits on different lines ("yours" and
     * "theirs"), merged back together -- mirrors OutgoingCommandCoverageTest's buildMergeFixture,
     * which is the established pattern in this repo for exercising 2-parent (merge) topology in
     * changelog/manifest/filelog revisions without textual conflict.
     */
    private static MergeFixture buildMergeFixture(File repoDir) throws Exception {
        MergeFixture m = new MergeFixture();
        m.repo = Hg.init().setDirectory(repoDir).call();

        File f1 = new File(repoDir, "hello.txt");
        Files.writeString(f1.toPath(), "Line 1\nLine 2\nLine 3\n");
        new AddCommand(m.repo).call();
        m.baseNode = new CommitCommand(m.repo).setAuthor("A <a@example.com>").setMessage("base").call();

        Files.writeString(f1.toPath(), "Line 1 [MINE]\nLine 2\nLine 3\n");
        m.yoursNode = new CommitCommand(m.repo).setAuthor("A <a@example.com>").setMessage("yours").call();

        Dirstate dirstate = m.repo.getDirstate();
        dirstate.setParents(m.baseNode, new byte[20]);
        m.repo.writeDirstate(dirstate);

        Files.writeString(f1.toPath(), "Line 1\nLine 2\nLine 3 [THEIRS]\n");
        m.theirsNode = new CommitCommand(m.repo).setAuthor("A <a@example.com>").setMessage("theirs").call();

        new MergeCommand(m.repo).setNodeId(m.yoursNode).call();
        m.mergeNode = new CommitCommand(m.repo).setAuthor("A <a@example.com>").setMessage("merge").call();
        return m;
    }

    private static boolean isNullId(byte[] node) {
        for (byte b : node) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }
}
