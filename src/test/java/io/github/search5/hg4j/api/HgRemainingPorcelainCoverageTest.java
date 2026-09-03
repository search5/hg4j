package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.bundle.ChangegroupParser;
import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgRcConfig;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.treewalk.ManifestTreeIterator;
import io.github.search5.hg4j.treewalk.SparseConfig;
import io.github.search5.hg4j.treewalk.TreeWalk;
import io.github.search5.hg4j.util.NodeIdUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
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

    @Test
    public void testWrapNullRepositoryThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> Hg.wrap(null));
    }

    @Test
    public void testResolveCommandFacadeQueriesAndUpdatesMergeState(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("resolve_facade_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        try (Hg hg = Hg.wrap(repo)) {
            File f = new File(repoDir, "conflict.txt");
            Files.writeString(f.toPath(), "line1\n");
            hg.add().addFile("conflict.txt").call();
            hg.commit().setAuthor("T").setMessage("c1").call();

            Files.writeString(f.toPath(), "line1-A\n");
            byte[] other = hg.commit().setAuthor("T").setMessage("c2A").call();

            hg.update().setRevision("0").call();
            Files.writeString(f.toPath(), "line1-B\n");
            hg.commit().setAuthor("T").setMessage("c2B").call();

            MergeCommand.MergeResult mergeResult = hg.merge().setNodeId(other).call();
            assertTrue(mergeResult.isConflicted(), "Setup must actually produce a real conflict");

            // hg.resolve() facade: list shows the conflicted file as unresolved
            Map<String, Boolean> listed = hg.resolve().list(true).call();
            assertEquals(1, listed.size());
            assertFalse(listed.get("conflict.txt"));

            // hg.resolve() facade: mark it resolved and confirm the state round-trips
            Map<String, Boolean> resolved = hg.resolve().setFile("conflict.txt").markResolved(true).call();
            assertTrue(resolved.get("conflict.txt"));
            assertTrue(hg.resolve().list(true).call().get("conflict.txt"));
        }
    }

    @Test
    public void testUnbundleCommandFacadeAppliesChangegroupToRepository(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("unbundle_src_repo").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        try (Hg srcHg = Hg.wrap(srcRepo)) {
            File f = new File(srcDir, "a.txt");
            Files.writeString(f.toPath(), "content for unbundle facade test");
            srcHg.add().addFile("a.txt").call();
            srcHg.commit().setAuthor("tester").setMessage("only commit").call();
        }

        ChangegroupParser.ChangegroupBundle bundle = HgTestUtils.createMockBundleFromRepo(srcRepo);
        byte[] changegroupBytes = HgTestUtils.serializeBundleToBytes(bundle);
        // UnbundleCommand requires a recognized container header (HG10UN/HG10GZ/HG10BZ/HG20);
        // a raw changegroup payload with no header is rejected as corrupt.
        byte[] header = "HG10UN".getBytes(StandardCharsets.US_ASCII);
        byte[] bundleBytes = new byte[header.length + changegroupBytes.length];
        System.arraycopy(header, 0, bundleBytes, 0, header.length);
        System.arraycopy(changegroupBytes, 0, bundleBytes, header.length, changegroupBytes.length);
        File bundleFile = tempDir.resolve("out.hg").toFile();
        Files.write(bundleFile.toPath(), bundleBytes);

        File dstDir = tempDir.resolve("unbundle_dst_repo").toFile();
        HgRepository dstRepo = Hg.init().setDirectory(dstDir).call();
        try (Hg dstHg = Hg.wrap(dstRepo)) {
            List<byte[]> imported = dstHg.unbundle().setBundleFile(bundleFile).call();
            assertEquals(1, imported.size());

            Revlog dstCl = dstRepo.getRevlog(new File(dstRepo.getStoreDir(), "00changelog.i"), new File(dstRepo.getStoreDir(), "00changelog.d"));
            assertEquals(1, dstCl.getRevisionCount());
        }
    }

    @Test
    public void testSparseConfigFacadeResolvesIncludesFromWorkingSparseFile(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("sparse_facade_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        try (Hg hg = Hg.wrap(repo)) {
            new File(repoDir, "a").mkdirs();
            new File(repoDir, "b").mkdirs();
            Files.writeString(new File(repoDir, "a/1.txt").toPath(), "x");
            Files.writeString(new File(repoDir, "b/2.txt").toPath(), "x");
            hg.add().addFile("a/1.txt").call();
            hg.add().addFile("b/2.txt").call();
            hg.commit().setAuthor("T").setMessage("c1").call();

            Files.writeString(new File(repoDir, ".hg/sparse").toPath(), "[include]\na/*.txt\n");

            SparseConfig resolved = hg.sparseConfig(0);
            assertTrue(resolved.includes.contains("a/*.txt"));
            assertTrue(resolved.toPathFilter().accept("a/1.txt"));
            assertFalse(resolved.toPathFilter().accept("b/2.txt"));
        }
    }

    @Test
    public void testConfigLoadsUserHomeHgrcWhenPresent(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("user_hgrc_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File fakeUserHome = tempDir.resolve("fake_home_hgrc").toFile();
        assertTrue(fakeUserHome.mkdirs());
        Files.writeString(new File(fakeUserHome, ".hgrc").toPath(), "[ui]\nusername = User Home Tester\n");

        String originalUserHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", fakeUserHome.getAbsolutePath());
            try (Hg hg = Hg.wrap(repo)) {
                HgRcConfig cfg = hg.config();
                assertEquals("User Home Tester", cfg.get("ui", "username"));
            }
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    public void testConfigLoadsMercurialIniWhenHgrcAbsent(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("mercurial_ini_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File fakeUserHome = tempDir.resolve("fake_home_ini").toFile();
        assertTrue(fakeUserHome.mkdirs());
        Files.writeString(new File(fakeUserHome, "mercurial.ini").toPath(), "[ui]\nusername = Ini Tester\n");

        String originalUserHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", fakeUserHome.getAbsolutePath());
            try (Hg hg = Hg.wrap(repo)) {
                HgRcConfig cfg = hg.config();
                assertEquals("Ini Tester", cfg.get("ui", "username"));
            }
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    public void testConfigSkipsUserConfigurationWhenUserHomeSystemPropertyIsAbsent(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("no_user_home_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File localHgrc = new File(repo.getHgDir(), "hgrc");
        Files.writeString(localHgrc.toPath(), "[ui]\nusername = Local Only Tester\n");

        String originalUserHome = System.getProperty("user.home");
        try {
            System.clearProperty("user.home");
            try (Hg hg = Hg.wrap(repo)) {
                HgRcConfig cfg = assertDoesNotThrow(hg::config,
                        "Hg.config() must tolerate a missing user.home system property");
                // The user-wide lookup (step 2) is skipped entirely, but the local repository
                // hgrc (step 3) is unaffected and still loads.
                assertEquals("Local Only Tester", cfg.get("ui", "username"));
            }
        } finally {
            if (originalUserHome != null) {
                System.setProperty("user.home", originalUserHome);
            }
        }
    }

    @Test
    public void testConfigSwallowsIOExceptionFromUnreadableLocalHgrc(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("malformed_hgrc_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // Point user.home at a directory with no .hgrc/mercurial.ini so only the local
        // repository hgrc load is exercised below.
        File fakeUserHome = tempDir.resolve("fake_home_none").toFile();
        assertTrue(fakeUserHome.mkdirs());

        File localHgrc = new File(repo.getHgDir(), "hgrc");
        // Files.readString() (used by HgRcConfig.load) throws IOException (MalformedInputException)
        // on a byte sequence that is not valid UTF-8, so this exercises Hg.config()'s catch block.
        Files.write(localHgrc.toPath(), new byte[]{(byte) 0xFF, (byte) 0xFE, (byte) 0x00, (byte) 0x80});

        String originalUserHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", fakeUserHome.getAbsolutePath());
            try (Hg hg = Hg.wrap(repo)) {
                HgRcConfig cfg = assertDoesNotThrow(hg::config,
                        "Hg.config() must swallow IOException from an unreadable hgrc file, not propagate it");
                assertNotNull(cfg);
                assertNull(cfg.get("ui", "username"), "malformed local hgrc must not have contributed any settings");
            }
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    public void testBranchesFacadeReportsOpenHeadPerBranch(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("branches_facade_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("dev").setMessage("default head").call();

        new BranchCommand(repo).setBranchName("feature").call();
        Files.writeString(f.toPath(), "v1");
        new CommitCommand(repo).setAuthor("dev").setMessage("feature head").call();

        try (Hg hg = Hg.wrap(repo)) {
            List<BranchesCommand.BranchHead> heads = hg.branches().call();
            assertEquals(2, heads.size());
            assertTrue(heads.stream().anyMatch(h -> "default".equals(h.getBranch()) && !h.isClosed()));
            assertTrue(heads.stream().anyMatch(h -> "feature".equals(h.getBranch()) && !h.isClosed()));
        }
    }

    @Test
    public void testTreeMergeFacadeComputesResultWithoutTouchingWorkingCopy(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("treemerge_facade_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File a = new File(repoDir, "a.txt");
        Files.writeString(a.toPath(), "base");
        new AddCommand(repo).call();
        byte[] base = new CommitCommand(repo).setAuthor("dev").setMessage("base").call();

        Files.writeString(a.toPath(), "base");
        File ourFile = new File(repoDir, "ours.txt");
        Files.writeString(ourFile.toPath(), "mine");
        new AddCommand(repo).call();
        byte[] ours = new CommitCommand(repo).setAuthor("dev").setMessage("ours").call();

        Dirstate dirstate = repo.getDirstate();
        dirstate.setParents(base, new byte[20]);
        repo.writeDirstate(dirstate);
        File theirFile = new File(repoDir, "theirs.txt");
        Files.writeString(theirFile.toPath(), "yours");
        new AddCommand(repo).call();
        byte[] theirs = new CommitCommand(repo).setAuthor("dev").setMessage("theirs").call();

        // Explicitly check out `ours` -- committing `theirs` above only moved the dirstate
        // parent pointer, it never touched the working copy, so theirs.txt would still
        // physically exist on disk without this.
        new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(ours)).call();
        assertFalse(theirFile.exists());

        try (Hg hg = Hg.wrap(repo)) {
            TreeMergeCommand.TreeMergeResult result = hg.treeMerge().setOurs(ours).setTheirs(theirs).call();
            assertFalse(result.isConflicted());
            assertTrue(result.getChangedFiles().containsKey("theirs.txt"),
                    "theirs-only file must appear as a change to bring into ours");
        }

        assertEquals("mine", Files.readString(ourFile.toPath()), "working copy must be untouched by a pure treeMerge computation");
        assertFalse(theirFile.exists(), "treeMerge must not write theirs.txt into the working copy");
    }

    @Test
    public void testCensorFacadeReplacesRevisionContentWithATombstone(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("censor_facade_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f = new File(repoDir, "secret.txt");
        Files.writeString(f.toPath(), "sensitive data");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("dev").setMessage("oops").call();

        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "secret.txt");
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        Revlog filelog = repo.getRevlog(flIdx, flDat);
        assertFalse(filelog.isCensored(0));
        String fileRevHex = NodeIdUtil.toHex(filelog.getIndexRecord(0).getNodeId());

        try (Hg hg = Hg.wrap(repo)) {
            hg.censor().setFile("secret.txt").setRevision(fileRevHex).setTombstone("leaked credentials").call();
        }

        Revlog filelogAfter = repo.getRevlog(flIdx, flDat);
        assertTrue(filelogAfter.isCensored(0));
    }

    @Test
    public void testClonebundleFacadeDownloadsAndAppliesFromAPlainHttpUrl(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("clonebundle_facade_src").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "hello");
        new AddCommand(srcRepo).call();
        byte[] commitNode = new CommitCommand(srcRepo).setMessage("v1").setAuthor("dev").call();

        ChangegroupParser.ChangegroupBundle bundle = HgTestUtils.createMockBundleFromRepo(srcRepo);
        byte[] changegroupBytes = HgTestUtils.serializeBundleToBytes(bundle);
        byte[] bundleBytes = new byte[6 + changegroupBytes.length];
        System.arraycopy("HG10UN".getBytes(StandardCharsets.US_ASCII), 0, bundleBytes, 0, 6);
        System.arraycopy(changegroupBytes, 0, bundleBytes, 6, changegroupBytes.length);

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/bundles/full.hg", exchange -> {
                exchange.sendResponseHeaders(200, bundleBytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bundleBytes);
                }
            });
            server.start();
            String bundleUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/bundles/full.hg";

            File destDir = tempDir.resolve("clonebundle_facade_dest").toFile();
            HgRepository destRepo = Hg.init().setDirectory(destDir).call();

            try (Hg hg = Hg.wrap(destRepo)) {
                List<byte[]> imported = hg.clonebundle(bundleUrl);
                assertEquals(1, imported.size());
                assertEquals(NodeIdUtil.toHex(commitNode), NodeIdUtil.toHex(imported.get(0)));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testTagsFacadeReportsTipOnAFreshRepo(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("tags_facade_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("dev").setMessage("c0").call();

        try (Hg hg = Hg.wrap(repo)) {
            List<TagsCommand.Tag> tags = hg.tags().call();
            assertTrue(tags.stream().anyMatch(t -> "tip".equals(t.getName())));
        }
    }

    @Test
    public void testPathsFacadeReadsPathsSection(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("paths_facade_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        repo.getConfig().set("paths", "default", "https://example.invalid/repo");

        try (Hg hg = Hg.wrap(repo)) {
            Map<String, String> paths = hg.paths().call();
            assertEquals("https://example.invalid/repo", paths.get("default"));
        }
    }

    @Test
    public void testFilesFacadeListsTrackedWorkingCopyFiles(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("files_facade_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("dev").setMessage("c0").call();

        try (Hg hg = Hg.wrap(repo)) {
            assertEquals(List.of("a.txt"), hg.files().call());
        }
    }

    @Test
    public void testLocateFacadeMatchesGlobAnywhereInTheTree(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("locate_facade_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File sub = new File(repoDir, "sub");
        sub.mkdirs();
        Files.writeString(new File(sub, "b.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("dev").setMessage("c0").call();

        try (Hg hg = Hg.wrap(repo)) {
            assertEquals(List.of("sub/b.txt"), hg.locate().setPattern("*.txt").call());
        }
    }

    @Test
    public void testManifestFacadeListsWorkingCopyParentRevisionFiles(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("manifest_facade_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("dev").setMessage("c0").call();

        try (Hg hg = Hg.wrap(repo)) {
            List<ManifestCommand.ManifestEntry> entries = hg.manifest().call();
            assertEquals(1, entries.size());
            assertEquals("a.txt", entries.get(0).getPath());
        }
    }

    @Test
    public void testCopyFacadePreservesOriginalAndTracksDestination(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("copy_facade_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File a = new File(repoDir, "a.txt");
        Files.writeString(a.toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("dev").setMessage("c0").call();

        try (Hg hg = Hg.wrap(repo)) {
            hg.copy().setSource("a.txt").setDestination("b.txt").call();
        }

        assertTrue(a.exists(), "copy must preserve the original file");
        assertEquals("v0", Files.readString(new File(repoDir, "b.txt").toPath()));
    }

    @Test
    public void testBundleFacadeWritesAReadableBundleFile(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("bundle_facade_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("dev").setMessage("c0").call();

        File out = tempDir.resolve("out.hg").toFile();
        try (Hg hg = Hg.wrap(repo)) {
            int count = hg.bundle().setOutputFile(out).setBaseRevision("null").call();
            assertEquals(1, count);
        }
        assertTrue(out.exists());

        File destDir = tempDir.resolve("bundle_facade_dest").toFile();
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        List<byte[]> imported = new UnbundleCommand(destRepo).setBundleFile(out).call();
        assertEquals(1, imported.size());
    }

    @Test
    public void testRecoverFacadeReportsNoInterruptedTransactionOnAHealthyRepo(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("recover_facade_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("dev").setMessage("c0").call();

        try (Hg hg = Hg.wrap(repo)) {
            RecoverCommand.RecoverResult result = hg.recover().call();
            assertFalse(result.wasInterrupted());
            assertTrue(result.isSuccess());
        }
    }
}
