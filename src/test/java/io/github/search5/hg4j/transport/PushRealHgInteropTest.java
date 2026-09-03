package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.BookmarkCommand;
import io.github.search5.hg4j.api.BranchCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.InitCommand;
import io.github.search5.hg4j.api.PullCommand;
import io.github.search5.hg4j.api.PushCommand;
import io.github.search5.hg4j.api.UpdateCommand;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.NodeId;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-hg-CLI interop verification for {@code hg push}'s less-common porcelain scenarios
 * (backlog item 23, "push" bullet): pushing FROM hg4j (via {@link PushCommand}, over real HTTP
 * wire protocol) TO a repository served by the host-installed real {@code hg serve} process --
 * so the accept/reject decision on the remote side, and everything read back afterwards (`hg
 * heads`, `hg branches`, `hg bookmarks`), is made and observed by real, unmodified Mercurial.
 * This is a genuine two-directional round trip, not an hg4j-only self-consistency check.
 */
@Tag("interop")
public class PushRealHgInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    private static void allowPush(File repoDir) throws Exception {
        Files.writeString(new File(repoDir, ".hg/hgrc").toPath(),
                "[web]\nallow_push = *\npush_ssl = false\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * A remote that ALREADY has multiple heads before the push must still accept a push that
     * merely extends one of those heads (net head count unchanged) -- without {@code --force}.
     * Only a push that INCREASES the head count needs rejecting (covered separately below).
     */
    @Test
    public void testPushSucceedsAgainstMultiHeadRemoteWhenNotIncreasingHeadCount(@TempDir Path tempDir) throws Exception {
        File remoteRepoDir = tempDir.resolve("remote").toFile();
        HgTestUtils.nativeRepo(remoteRepoDir, dir -> {
            try {
                Files.writeString(new File(dir, "base.txt").toPath(), "base");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        HgTestUtils.hg(remoteRepoDir, "add");
        HgTestUtils.hg(remoteRepoDir, "commit", "-u", "T", "-m", "base");
        String baseHex = HgTestUtils.hg(remoteRepoDir, "log", "-r", "0", "--template", "{node}");
        // Two pre-existing, already-divergent heads on the remote, unrelated to hg4j entirely.
        Files.writeString(new File(remoteRepoDir, "base.txt").toPath(), "head-a");
        HgTestUtils.hg(remoteRepoDir, "commit", "-u", "T", "-m", "head-a");
        String headAHex = HgTestUtils.hg(remoteRepoDir, "log", "-r", "tip", "--template", "{node}");
        HgTestUtils.hg(remoteRepoDir, "update", "-r", baseHex);
        Files.writeString(new File(remoteRepoDir, "other.txt").toPath(), "head-b");
        HgTestUtils.hg(remoteRepoDir, "add");
        HgTestUtils.hg(remoteRepoDir, "commit", "-u", "T", "-m", "head-b");
        assertEquals(2, HgTestUtils.hg(remoteRepoDir, "heads", "--template", "{node} ").trim().split("\\s+").length);
        allowPush(remoteRepoDir);

        try (RealHgServeSupport.ServeHandle serve = RealHgServeSupport.start(remoteRepoDir)) {
            File localDir = tempDir.resolve("local").toFile();
            new InitCommand().setDirectory(localDir).call();
            HgRepository local = new HgRepository(localDir);
            new PullCommand(local).setSource(serve.url).call();

            // Extend head-a by one commit; head-b is untouched. Net head count stays 2.
            new UpdateCommand(local).setRevision(headAHex).call();
            Files.writeString(new File(localDir, "base.txt").toPath(), "head-a-extended");
            byte[] extended = new CommitCommand(local).setAuthor("T").setMessage("extend head-a").call();

            String response = new PushCommand(local).setDestination(serve.url).call();
            assertNotNull(response);

            String headsAfter = HgTestUtils.hg(remoteRepoDir, "heads", "--template", "{node} ");
            String[] headsArr = headsAfter.trim().split("\\s+");
            assertEquals(2, headsArr.length, "head count must stay at 2 (extended, not added): " + headsAfter);
            assertTrue(headsAfter.contains(new NodeId(extended).toHex()), "the extended head must be the new tip of that line: " + headsAfter);
        }
    }

    /**
     * Pushing a changeset that would add a NEW remote head (not a 1:1 replacement of an existing
     * one) must be rejected by default, and must succeed once {@link PushCommand#setForce}
     * is set -- both outcomes verified by real hg reading the served repository afterwards.
     */
    @Test
    public void testPushRejectedWhenCreatingNewHeadThenForceSucceeds(@TempDir Path tempDir) throws Exception {
        File remoteRepoDir = tempDir.resolve("remote").toFile();
        HgTestUtils.nativeRepo(remoteRepoDir, dir -> {
            try {
                Files.writeString(new File(dir, "base.txt").toPath(), "base");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        HgTestUtils.hg(remoteRepoDir, "add");
        HgTestUtils.hg(remoteRepoDir, "commit", "-u", "T", "-m", "base");
        String baseHex = HgTestUtils.hg(remoteRepoDir, "log", "-r", "0", "--template", "{node}");
        // A first remote head, one commit past base.
        Files.writeString(new File(remoteRepoDir, "base.txt").toPath(), "remote-head");
        HgTestUtils.hg(remoteRepoDir, "commit", "-u", "T", "-m", "remote head");
        allowPush(remoteRepoDir);

        try (RealHgServeSupport.ServeHandle serve = RealHgServeSupport.start(remoteRepoDir)) {
            // hg4j clones the remote, then updates back to `base` (NOT the remote's current
            // head) and commits -- this creates a second, divergent head unrelated to the
            // remote's existing one.
            File localDir = tempDir.resolve("local").toFile();
            new InitCommand().setDirectory(localDir).call();
            HgRepository local = new HgRepository(localDir);
            new PullCommand(local).setSource(serve.url).call();
            new UpdateCommand(local).setRevision(baseHex).call();
            Files.writeString(new File(localDir, "other.txt").toPath(), "divergent");
            new AddCommand(local).call();
            new CommitCommand(local).setAuthor("T").setMessage("divergent local head").call();

            HgValidationException ex = assertThrows(HgValidationException.class,
                    () -> new PushCommand(local).setDestination(serve.url).call(),
                    "push creating a new remote head must be rejected without --force");
            assertTrue(ex.getMessage().contains("new remote head") || ex.getMessage().contains("new heads"),
                    "rejection message should mention new head(s): " + ex.getMessage());

            // Remote must be untouched: real hg still sees exactly one head.
            String headsAfterReject = HgTestUtils.hg(remoteRepoDir, "heads", "--template", "{node} ");
            assertEquals(1, headsAfterReject.trim().split("\\s+").length,
                    "rejected push must not have changed the remote's head count: " + headsAfterReject);

            // Now force it through.
            String response = new PushCommand(local).setDestination(serve.url).setForce(true).call();
            assertNotNull(response);

            String headsAfterForce = HgTestUtils.hg(remoteRepoDir, "heads", "--template", "{node} ");
            assertEquals(2, headsAfterForce.trim().split("\\s+").length,
                    "forced push must have landed the new head, giving the remote 2 heads: " + headsAfterForce);
        }
    }

    /**
     * Pushing a changeset on a NAMED BRANCH the remote doesn't have yet must be rejected by
     * default ("push creates new remote branches"), and must succeed once {@link
     * PushCommand#setAllowNewBranch} is set -- both outcomes verified by real hg's own {@code hg
     * branches} on the served repository afterwards.
     */
    @Test
    public void testPushRejectedForNewBranchThenAllowNewBranchSucceeds(@TempDir Path tempDir) throws Exception {
        File remoteRepoDir = tempDir.resolve("remote").toFile();
        HgTestUtils.nativeRepo(remoteRepoDir, dir -> {
            try {
                Files.writeString(new File(dir, "a.txt").toPath(), "one");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        HgTestUtils.hg(remoteRepoDir, "add");
        HgTestUtils.hg(remoteRepoDir, "commit", "-u", "T", "-m", "c0 on default");
        allowPush(remoteRepoDir);

        try (RealHgServeSupport.ServeHandle serve = RealHgServeSupport.start(remoteRepoDir)) {
            File localDir = tempDir.resolve("local").toFile();
            new InitCommand().setDirectory(localDir).call();
            HgRepository local = new HgRepository(localDir);
            new PullCommand(local).setSource(serve.url).call();
            new UpdateCommand(local).setRevision("tip").call();

            new BranchCommand(local).setBranchName("feature").call();
            Files.writeString(new File(localDir, "a.txt").toPath(), "two");
            new CommitCommand(local).setAuthor("T").setMessage("c1 on feature").call();

            HgValidationException ex = assertThrows(HgValidationException.class,
                    () -> new PushCommand(local).setDestination(serve.url).call(),
                    "pushing a brand-new named branch must be rejected without --new-branch");
            assertTrue(ex.getMessage().contains("new remote branches") || ex.getMessage().contains("new branch"),
                    "rejection message should mention new branch(es): " + ex.getMessage());

            String branchesAfterReject = HgTestUtils.hg(remoteRepoDir, "branches");
            assertFalse(branchesAfterReject.contains("feature"), "rejected push must not create the branch on remote: " + branchesAfterReject);

            String response = new PushCommand(local).setDestination(serve.url).setAllowNewBranch(true).call();
            assertNotNull(response);

            String branchesAfterAllow = HgTestUtils.hg(remoteRepoDir, "branches");
            assertTrue(branchesAfterAllow.contains("feature"), "allowed push must create the branch on remote: " + branchesAfterAllow);
        }
    }

    /** Pushing over HTTP to a real hg server must move the remote's bookmark via the wire
     * `pushkey` command, exactly like `hg push` from real hg itself would. */
    @Test
    public void testPushMovesBookmarkOnRealHgServerOverHttp(@TempDir Path tempDir) throws Exception {
        File remoteRepoDir = tempDir.resolve("remote").toFile();
        HgTestUtils.nativeRepo(remoteRepoDir, dir -> {
            try {
                Files.writeString(new File(dir, "a.txt").toPath(), "one");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        HgTestUtils.hg(remoteRepoDir, "add");
        HgTestUtils.hg(remoteRepoDir, "commit", "-u", "T", "-m", "c0");
        HgTestUtils.hg(remoteRepoDir, "bookmark", "stable");
        allowPush(remoteRepoDir);

        try (RealHgServeSupport.ServeHandle serve = RealHgServeSupport.start(remoteRepoDir)) {
            File localDir = tempDir.resolve("local").toFile();
            new InitCommand().setDirectory(localDir).call();
            HgRepository local = new HgRepository(localDir);
            new PullCommand(local).setSource(serve.url).call();
            java.util.Map<String, String> pulledBookmarks = new BookmarkCommand(local).call();
            assertEquals(1, pulledBookmarks.size());

            // UpdateCommand.setRevision() only resolves "tip"/numeric-rev/hex(-prefix)
            // identifiers, not bookmark names -- resolve "stable" to its hex ourselves.
            new UpdateCommand(local).setRevision(pulledBookmarks.get("stable")).call();
            Files.writeString(new File(localDir, "a.txt").toPath(), "two");
            byte[] c1 = new CommitCommand(local).setAuthor("T").setMessage("c1").call();
            new BookmarkCommand(local).setBookmarkName("stable").setRevision(new NodeId(c1).toHex()).call();

            new PushCommand(local).setDestination(serve.url).call();

            String remoteBookmarks = HgTestUtils.hg(remoteRepoDir, "bookmarks");
            assertTrue(remoteBookmarks.contains(new NodeId(c1).toHex().substring(0, 12)),
                    "real hg server must see the bookmark moved to the newly pushed commit: " + remoteBookmarks);
        }
    }

    /** A push that has nothing new to send (remote already has every local changeset) must
     * succeed without error and leave the remote's state completely unchanged. */
    @Test
    public void testPushWithNoChangesSucceedsWithoutError(@TempDir Path tempDir) throws Exception {
        File remoteRepoDir = tempDir.resolve("remote").toFile();
        HgTestUtils.nativeRepo(remoteRepoDir, dir -> {
            try {
                Files.writeString(new File(dir, "a.txt").toPath(), "one");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        HgTestUtils.hg(remoteRepoDir, "add");
        HgTestUtils.hg(remoteRepoDir, "commit", "-u", "T", "-m", "c0");
        allowPush(remoteRepoDir);

        try (RealHgServeSupport.ServeHandle serve = RealHgServeSupport.start(remoteRepoDir)) {
            File localDir = tempDir.resolve("local").toFile();
            new InitCommand().setDirectory(localDir).call();
            HgRepository local = new HgRepository(localDir);
            new PullCommand(local).setSource(serve.url).call();

            String beforeHex = HgTestUtils.hg(remoteRepoDir, "log", "-r", "tip", "--template", "{node}");

            // No local changes at all -- push again immediately.
            String response = new PushCommand(local).setDestination(serve.url).call();
            assertNotNull(response);
            assertTrue(response.toLowerCase().contains("no changes") || response.toLowerCase().contains("up-to-date"),
                    "push with nothing new should report no changes: " + response);

            String afterHex = HgTestUtils.hg(remoteRepoDir, "log", "-r", "tip", "--template", "{node}");
            assertEquals(beforeHex, afterHex, "remote tip must be unchanged by a no-op push");

            String verify = HgTestUtils.hg(remoteRepoDir, "verify");
            assertFalse(verify.contains("integrity error"), "remote must remain valid: " + verify);
        }
    }
}
