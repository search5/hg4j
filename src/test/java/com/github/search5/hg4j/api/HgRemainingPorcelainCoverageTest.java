package com.github.search5.hg4j.api;

import com.github.search5.hg4j.errors.HgValidationException;
import com.github.search5.hg4j.lib.HgRcConfig;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.treewalk.ManifestTreeIterator;
import com.github.search5.hg4j.treewalk.TreeWalk;
import com.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Hg} 파사드가 감싸는 나머지 포슬린 팩토리 메서드(tag/push 훅 위임, runTransaction, config,
 * walkTree, root/tip/parents/summary/heads/identify/verify/phase/describe/revset 조회계열,
 * forget/addremove/backout/strip/grep/export/importPatch/purge/archive/gc/subrepo/bisect/histedit
 * 변경계열, incoming/outgoing, open(String) 예외 경로)를 실제 동작 검증과 함께 커버한다.
 * 기존 커맨드 클래스 자체의 세부 로직 테스트는 각 전용 테스트 파일이 이미 담당하므로, 여기서는
 * {@code Hg} 파사드 메서드를 통해서 호출했을 때 실제로 저장소에 반영되는지를 확인한다.
 */
public class HgRemainingPorcelainCoverageTest {

    @Test
    public void testTagCommandHookIntegration(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("tag_hook_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "content");
        new AddCommand(repo).call();
        byte[] commitNode = new CommitCommand(repo).setAuthor("tester").setMessage("base").call();

        // 1. PRE_TAG hook rejects the tag creation
        try (Hg hgReject = Hg.wrap(repo)) {
            hgReject.registerHook(HgHookType.PRE_TAG, ctx -> {
                assertEquals(repo, ctx.get("repository"));
                assertEquals("bad-tag", ctx.get("tag"));
                return false;
            });
            TagCommand rejectCmd = hgReject.tag().setTagName("bad-tag").setNodeId(commitNode);
            assertThrows(HgValidationException.class, rejectCmd::call);
        }

        // .hgtags must not have been written since the hook rejected the creation
        assertFalse(new File(repoDir, ".hgtags").exists());

        // 2. PRE_TAG allows, POST_TAG then fires with the created tag's context
        List<String> postTagSeen = new ArrayList<>();
        try (Hg hgAccept = Hg.wrap(repo)) {
            hgAccept.registerHook(HgHookType.PRE_TAG, ctx -> true);
            hgAccept.registerHook(HgHookType.POST_TAG, ctx -> {
                postTagSeen.add((String) ctx.get("tag"));
                return true;
            });
            Map<String, String> result = hgAccept.tag().setTagName("v1.0").setNodeId(commitNode).call();
            assertEquals(1, result.size());
            assertEquals(NodeIdUtil.toHex(commitNode).substring(0, 40), result.get("v1.0"));
        }
        assertEquals(1, postTagSeen.size());
        assertEquals("v1.0", postTagSeen.get(0));
        assertTrue(new File(repoDir, ".hgtags").exists());
    }

    @Test
    public void testPushCommandHookRegistrationAndPreHookRejection(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("push_hook_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        try (Hg hg = Hg.wrap(repo)) {
            List<String> seenDest = new ArrayList<>();
            AtomicBoolean postPushSeen = new AtomicBoolean(false);

            // Registering a POST_PUSH hook exercises Hg.push()'s POST_PUSH wiring loop even though
            // the rejected push below never reaches PushCommand's own post-push trigger point.
            hg.registerHook(HgHookType.POST_PUSH, ctx -> {
                postPushSeen.set(true);
                return true;
            });
            hg.registerHook(HgHookType.PRE_PUSH, ctx -> {
                seenDest.add((String) ctx.get("destinationUrl"));
                return false;
            });

            PushCommand pushCmd = hg.push().setDestination("http://invalid.example.test/repo");
            assertThrows(HgValidationException.class, pushCmd::call);
            assertEquals(1, seenDest.size());
            assertEquals("http://invalid.example.test/repo", seenDest.get(0));
            assertFalse(postPushSeen.get(), "PRE_PUSH rejection must prevent the push from ever reaching remote/post-push stage");
        }
    }

    @Test
    public void testRunTransactionExecutesActionAndPropagatesFailure(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("txn_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        try (Hg hg = Hg.wrap(repo)) {
            // 1. Success path: the action actually runs, and its effects (a real commit) are visible
            AtomicBoolean ran = new AtomicBoolean(false);
            hg.runTransaction(() -> {
                ran.set(true);
                try {
                    File f = new File(repoDir, "a.txt");
                    Files.writeString(f.toPath(), "txn content");
                    hg.add().addFile("a.txt").call();
                    hg.commit().setAuthor("tx").setMessage("txn commit").call();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            assertTrue(ran.get());

            Revlog cl = repo.getRevlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"));
            assertEquals(1, cl.getRevisionCount());

            // 2. Failure path: an exception thrown by the action propagates out of runTransaction
            RuntimeException boom = new RuntimeException("boom");
            RuntimeException thrown = assertThrows(RuntimeException.class, () -> hg.runTransaction(() -> {
                throw boom;
            }));
            assertSame(boom, thrown);

            // 3. Locks must have been released despite the failure - a subsequent transaction still works
            AtomicBoolean ranAgain = new AtomicBoolean(false);
            hg.runTransaction(() -> ranAgain.set(true));
            assertTrue(ranAgain.get());
        }
    }

    @Test
    public void testConfigLoadsLocalRepositoryHgrc(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("config_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File localHgrc = new File(repo.getHgDir(), "hgrc");
        Files.writeString(localHgrc.toPath(),
                "[ui]\nusername = Config Tester <tester@example.com>\n[paths]\ndefault = http://example.test/repo\n");

        try (Hg hg = Hg.wrap(repo)) {
            HgRcConfig cfg = hg.config();
            assertNotNull(cfg);
            assertEquals("Config Tester <tester@example.com>", cfg.get("ui", "username"));
            assertEquals("http://example.test/repo", cfg.getPath("default"));
        }
    }

    @Test
    public void testWalkTreeInstanceIsUsableAcrossTwoRevisions(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("walktree_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        try (Hg hg = Hg.wrap(repo)) {
            File fileA = new File(repoDir, "a.txt");
            Files.writeString(fileA.toPath(), "Content A", StandardCharsets.UTF_8);
            hg.add().addFile("a.txt").call();
            hg.commit().setAuthor("tester").setMessage("Commit 1").call();

            File fileB = new File(repoDir, "b.txt");
            Files.writeString(fileB.toPath(), "Content B", StandardCharsets.UTF_8);
            hg.add().addFile("b.txt").call();
            hg.commit().setAuthor("tester").setMessage("Commit 2").call();

            TreeWalk walk = hg.walkTree();
            assertNotNull(walk);
            walk.addTree(new ManifestTreeIterator(repo, "0"));
            walk.addTree(new ManifestTreeIterator(repo, "1"));

            assertTrue(walk.next());
            assertEquals("a.txt", walk.getPath());
            assertTrue(walk.isTracked(0));
            assertTrue(walk.isTracked(1));

            assertTrue(walk.next());
            assertEquals("b.txt", walk.getPath());
            assertFalse(walk.isTracked(0));
            assertTrue(walk.isTracked(1));

            assertFalse(walk.next());
        }
    }

    @Test
    public void testOpenStringEdgeCases(@TempDir Path tempDir) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> Hg.open((String) null));
        assertThrows(IllegalArgumentException.class, () -> Hg.open(""));

        File repoDir = tempDir.resolve("open_string_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        try (Hg hg = Hg.open(repoDir.getAbsolutePath())) {
            assertNotNull(hg);
            assertEquals(repoDir.getAbsolutePath(), hg.getRepository().getDirectory().getAbsolutePath());
        }
    }

    @Test
    public void testReadOnlyQueryFacadeMethods(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("query_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        try (Hg hg = Hg.wrap(repo)) {
            File f = new File(repoDir, "a.txt");
            Files.writeString(f.toPath(), "hello world\nsecond line\n");
            hg.add().addFile("a.txt").call();
            byte[] commitNode = hg.commit().setAuthor("tester").setMessage("initial commit").call();
            String commitHex = NodeIdUtil.toHex(commitNode);

            // root()
            assertEquals(repoDir.getAbsolutePath(), hg.root().call());

            // tip()
            byte[] tipNode = hg.tip().call();
            assertArrayEquals(commitNode, tipNode);

            // parents()
            List<String> parents = hg.parents().call();
            assertEquals(1, parents.size());
            assertEquals(commitHex, parents.get(0));

            // summary()
            SummaryCommand.SummaryInfo summary = hg.summary().call();
            assertEquals("default", summary.branch());
            assertEquals(1, summary.parents().size());
            assertFalse(summary.mergeInProgress());

            // heads()
            List<String> heads = hg.heads().call();
            assertEquals(1, heads.size());
            assertEquals(commitHex, heads.get(0));

            // identify()
            String identity = hg.identify().call();
            assertTrue(identity.startsWith(commitHex.substring(0, 12)));
            assertTrue(identity.contains("tip"));

            // verify()
            List<String> errors = hg.verify().call();
            assertTrue(errors.isEmpty(), "freshly committed repository should verify clean: " + errors);

            // phase() default query (no setPhase -> query mode)
            int phase = hg.phase().setRevision("0").call();
            assertTrue(phase >= 0 && phase <= 2);

            // describe()
            String described = hg.describe().call();
            assertNotNull(described);

            // revset()
            List<String> revsetResult = hg.revset().setExpression("0").call();
            assertEquals(1, revsetResult.size());
            assertEquals(commitHex, revsetResult.get(0));
        }
    }

    @Test
    public void testForgetAddremoveBackoutStripFacadeMethods(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("mutation_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        try (Hg hg = Hg.wrap(repo)) {
            File a = new File(repoDir, "a.txt");
            Files.writeString(a.toPath(), "content a");
            hg.add().addFile("a.txt").call();
            byte[] commit1 = hg.commit().setAuthor("tester").setMessage("commit 1").call();

            // forget(): stop tracking a committed file without deleting it from disk
            hg.forget().setFile("a.txt").call();
            Status statusAfterForget = hg.status().call();
            assertTrue(statusAfterForget.getRemoved().contains("a.txt"));
            assertTrue(a.exists(), "forget must not delete the working copy file");
            hg.commit().setAuthor("tester").setMessage("forget a.txt").call();

            // addremove(): untracked new file gets added, missing tracked file gets marked removed
            File b = new File(repoDir, "b.txt");
            Files.writeString(b.toPath(), "content b");
            List<String> addremoveResult = hg.addremove().call();
            assertTrue(addremoveResult.contains("A b.txt"));
            byte[] commit2 = hg.commit().setAuthor("tester").setMessage("addremove b.txt").call();

            // backout(): create a new commit reverting commit2's change
            byte[] backoutNode = hg.backout().setRevision(NodeIdUtil.toHex(commit2)).setAuthor("tester")
                    .setMessage("Backed out changeset").call();
            assertNotNull(backoutNode);
            Revlog cl = repo.getRevlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"));
            assertEquals(4, cl.getRevisionCount());

            // strip(): physically remove the backout changeset and everything after it
            hg.strip().setRevision(NodeIdUtil.toHex(backoutNode)).call();
            repo.clearRevlogCache();
            cl = repo.getRevlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"));
            assertEquals(3, cl.getRevisionCount());
        }
    }

    @Test
    public void testGrepExportImportPurgeArchiveGcSubrepoFacadeMethods(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("grep_export_repo").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();

        try (Hg hg = Hg.wrap(srcRepo)) {
            File f = new File(srcDir, "a.txt");
            Files.writeString(f.toPath(), "findable needle content\nsecond line\n");
            hg.add().addFile("a.txt").call();
            hg.commit().setAuthor("developer").setMessage("Feature commit message").call();

            // grep(): search committed file history for a pattern
            List<GrepCommand.GrepResult> hits = hg.grep().setQuery("needle").call();
            assertFalse(hits.isEmpty(), "grep should find the committed 'needle' text");
            assertTrue(hits.stream().anyMatch(r -> r.path.equals("a.txt")));

            // export(): produce a unified patch for revision 0
            String patch = hg.export().setRevision("0").call();
            assertNotNull(patch);
            assertTrue(patch.contains("# User developer"));
            assertTrue(patch.contains("Feature commit message"));

            // importPatch(): apply the exported patch to a brand-new repository
            File dstDir = tempDir.resolve("import_dst_repo").toFile();
            HgRepository dstRepo = Hg.init().setDirectory(dstDir).call();
            try (Hg dstHg = Hg.wrap(dstRepo)) {
                dstHg.importPatch().setPatchText(patch).call();
                Revlog dstCl = dstRepo.getRevlog(new File(dstRepo.getStoreDir(), "00changelog.i"), new File(dstRepo.getStoreDir(), "00changelog.d"));
                assertEquals(1, dstCl.getRevisionCount());
            }

            // purge(): remove an untracked file from the working copy
            File untracked = new File(srcDir, "junk.tmp");
            Files.writeString(untracked.toPath(), "temp");
            hg.purge().call();
            assertFalse(untracked.exists(), "purge must delete untracked files");
            assertTrue(f.exists(), "purge must not touch tracked files");

            // archive(): snapshot tip into a directory
            File archiveDir = tempDir.resolve("archive_out").toFile();
            hg.archive().setRevision("tip").setDestination(archiveDir).call();
            assertTrue(new File(archiveDir, "a.txt").exists());

            // gc(): store optimization/verification pass
            String gcSummary = hg.gc().call();
            assertNotNull(gcSummary);

            // subrepo(): register a subrepo entry via 'add' action (no network required)
            hg.subrepo().setAction("add").setSubrepoPath("libs/foo").setSubrepoUrl("http://example.test/foo").call();
            File hgsub = new File(srcDir, ".hgsub");
            assertTrue(hgsub.exists());
            String hgsubContent = Files.readString(hgsub.toPath());
            assertTrue(hgsubContent.contains("libs/foo = http://example.test/foo"));
        }
    }

    @Test
    public void testBisectAndHisteditFacadeMethods(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("bisect_histedit_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        try (Hg hg = Hg.wrap(repo)) {
            File f = new File(repoDir, "a.txt");
            Files.writeString(f.toPath(), "v1");
            hg.add().addFile("a.txt").call();
            byte[] good = hg.commit().setAuthor("tester").setMessage("good commit").call();

            Files.writeString(f.toPath(), "v2 (regression)");
            byte[] bad = hg.commit().setAuthor("tester").setMessage("bad commit").call();

            // bisect(): the midpoint candidate between the two single-step-apart revisions must be
            // one of the two known endpoints (a 2-revision range has no interior midpoint).
            byte[] candidate = hg.bisect().setGood(good).setBad(bad).next();
            assertNotNull(candidate);
            assertTrue(java.util.Arrays.equals(candidate, good) || java.util.Arrays.equals(candidate, bad));

            // histedit(): a single PICK rule re-commits the picked changeset on top of history
            // (this implementation appends rather than truncating), so the picked message must
            // survive verbatim in the newly appended revision.
            try (HisteditCommand histedit = hg.histedit()) {
                histedit.addRule(HisteditCommand.Action.PICK, NodeIdUtil.toHex(bad));
                histedit.call();
            }
            Revlog cl = repo.getRevlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"));
            assertEquals(3, cl.getRevisionCount());
            String newTipText = new String(cl.getRevisionContent(2), StandardCharsets.UTF_8);
            assertTrue(newTipText.contains("bad commit"));
        }
    }

    @Test
    public void testIncomingOutgoingFacadeMethods(@TempDir Path tempDir) throws Exception {
        File remoteDir = tempDir.resolve("remote_repo").toFile();
        HgRepository remoteRepo = Hg.init().setDirectory(remoteDir).call();
        try (Hg remoteHg = Hg.wrap(remoteRepo)) {
            File f = new File(remoteDir, "a.txt");
            Files.writeString(f.toPath(), "content");
            remoteHg.add().addFile("a.txt").call();
            remoteHg.commit().setAuthor("tester").setMessage("first").call();
        }

        File localDir = tempDir.resolve("local_repo").toFile();
        HgRepository localRepo = Hg.cloneRepository().setSource(remoteDir.getAbsolutePath()).setDirectory(localDir).call();

        try (Hg localHg = Hg.wrap(localRepo)) {
            // Freshly cloned: nothing new incoming, nothing to push out
            List<String> incomingNone = localHg.incoming().setSource(remoteDir.getAbsolutePath()).call();
            assertEquals(1, incomingNone.size());
            assertEquals("no incoming changes found", incomingNone.get(0));

            // Advance the remote so local has both an incoming changeset to pull and can diverge
            try (Hg remoteHg = Hg.wrap(remoteRepo)) {
                File f2 = new File(remoteDir, "b.txt");
                Files.writeString(f2.toPath(), "content2");
                remoteHg.add().addFile("b.txt").call();
                remoteHg.commit().setAuthor("tester").setMessage("second").call();
            }

            List<String> incoming = localHg.incoming().setSource(remoteDir.getAbsolutePath()).call();
            assertTrue(incoming.stream().anyMatch(line -> line.startsWith("changeset:")),
                    "expected an incoming changeset header, got: " + incoming);

            // Local repository has no unpushed changes of its own yet
            List<String> outgoing = localHg.outgoing().setDestination(remoteDir.getAbsolutePath()).call();
            assertNotNull(outgoing);
        }
    }
}
