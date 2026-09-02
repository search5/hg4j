package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.errors.HgLockException;
import com.github.search5.hg4j.errors.HgValidationException;
import com.github.search5.hg4j.transport.HgLocalClient;
import com.github.search5.hg4j.transport.HgRemoteConnection;
import com.github.search5.hg4j.transport.HgRemoteConnectionFactory;
import com.github.search5.hg4j.transport.TransportProtocol;
import com.github.search5.hg4j.util.NodeIdUtil;
import java.util.ArrayList;
import java.util.Map;

public class PushCommandTest {

    @Test
    public void callThrowsWhenNoDestinationConfiguredOrSpecified(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        assertThrows(IllegalStateException.class, () -> new PushCommand(repo).call());
    }

    @Test
    public void pushesAllLocalCommitsToAnEmptyRemote(@TempDir Path tempDir) throws Exception {
        File remoteDir = tempDir.resolve("remote").toFile();
        HgRepository remoteRepo = Hg.init().setDirectory(remoteDir).call();

        File localDir = tempDir.resolve("local").toFile();
        HgRepository localRepo = Hg.init().setDirectory(localDir).call();
        Files.writeString(new File(localDir, "a.txt").toPath(), "v1");
        new AddCommand(localRepo).call();
        new CommitCommand(localRepo).setMessage("first").call();
        Files.writeString(new File(localDir, "a.txt").toPath(), "v2");
        new CommitCommand(localRepo).setMessage("second").call();

        String result = new PushCommand(localRepo).setDestination(remoteDir.getAbsolutePath()).call();
        assertTrue(result.toLowerCase().contains("push"), "Unexpected push result: " + result);

        List<HgCommit> remoteLog = new LogCommand(remoteRepo).call();
        assertEquals(2, remoteLog.size());
    }

    @Test
    public void reportsUpToDateWhenRemoteAlreadyHasEverything(@TempDir Path tempDir) throws Exception {
        File remoteDir = tempDir.resolve("remote").toFile();
        Hg.init().setDirectory(remoteDir).call();

        File localDir = tempDir.resolve("local").toFile();
        HgRepository localRepo = Hg.init().setDirectory(localDir).call();
        Files.writeString(new File(localDir, "a.txt").toPath(), "v1");
        new AddCommand(localRepo).call();
        new CommitCommand(localRepo).setMessage("first").call();

        new PushCommand(localRepo).setDestination(remoteDir.getAbsolutePath()).call();
        String second = new PushCommand(localRepo).setDestination(remoteDir.getAbsolutePath()).call();
        assertTrue(second.toLowerCase().contains("up-to-date") || second.toLowerCase().contains("up to date"),
                "Expected an up-to-date style message, got: " + second);
    }

    @Test
    public void emptyLocalRepositoryReportsNothingToPush(@TempDir Path tempDir) throws Exception {
        File remoteDir = tempDir.resolve("remote").toFile();
        Hg.init().setDirectory(remoteDir).call();
        HgRepository localRepo = Hg.init().setDirectory(tempDir.resolve("local").toFile()).call();

        String result = new PushCommand(localRepo).setDestination(remoteDir.getAbsolutePath()).call();
        assertTrue(result.toLowerCase().contains("no changesets"), "Unexpected message: " + result);
    }

    @Test
    public void throwsWhenLocalAndRemoteRepositoriesAreUnrelated(@TempDir Path tempDir) throws Exception {
        File remoteDir = tempDir.resolve("remote").toFile();
        HgRepository remoteRepo = Hg.init().setDirectory(remoteDir).call();
        Files.writeString(new File(remoteDir, "remote-only.txt").toPath(), "remote content");
        new AddCommand(remoteRepo).call();
        new CommitCommand(remoteRepo).setMessage("remote history").call();

        File localDir = tempDir.resolve("local").toFile();
        HgRepository localRepo = Hg.init().setDirectory(localDir).call();
        Files.writeString(new File(localDir, "local-only.txt").toPath(), "local content");
        new AddCommand(localRepo).call();
        new CommitCommand(localRepo).setMessage("local history").call();

        assertThrows(HgValidationException.class,
                () -> new PushCommand(localRepo).setDestination(remoteDir.getAbsolutePath()).call());
    }

    @Test
    public void blocksPushWhenAnyPushedCommitIsInSecretPhase(@TempDir Path tempDir) throws Exception {
        File remoteDir = tempDir.resolve("remote").toFile();
        Hg.init().setDirectory(remoteDir).call();

        File localDir = tempDir.resolve("local").toFile();
        HgRepository localRepo = Hg.init().setDirectory(localDir).call();
        Files.writeString(new File(localDir, "a.txt").toPath(), "v1");
        new AddCommand(localRepo).call();
        new CommitCommand(localRepo).setMessage("secret commit").call();
        new PhaseCommand(localRepo).setRevision("0").setPhase(2).call(); // SECRET

        assertThrows(HgValidationException.class,
                () -> new PushCommand(localRepo).setDestination(remoteDir.getAbsolutePath()).call());
    }

    @Test
    public void syncsLocalBookmarksToRemoteDuringPush(@TempDir Path tempDir) throws Exception {
        File remoteDir = tempDir.resolve("remote").toFile();
        Hg.init().setDirectory(remoteDir).call();

        File localDir = tempDir.resolve("local").toFile();
        HgRepository localRepo = Hg.init().setDirectory(localDir).call();
        Files.writeString(new File(localDir, "a.txt").toPath(), "v1");
        new AddCommand(localRepo).call();
        byte[] node = new CommitCommand(localRepo).setMessage("first").call();
        new BookmarkCommand(localRepo).setBookmarkName("stable").setNodeId(node).call();

        new PushCommand(localRepo).setDestination(remoteDir.getAbsolutePath()).call();

        HgRepository remoteRepo = new HgRepository(remoteDir);
        Map<String, String> remoteBookmarks = new BookmarkCommand(remoteRepo).call();
        assertEquals(NodeIdUtil.toHex(node), remoteBookmarks.get("stable"));
    }

    @Test
    public void usesConfiguredDefaultPushPathWhenDestinationNotSpecified(@TempDir Path tempDir) throws Exception {
        File remoteDir = tempDir.resolve("remote").toFile();
        Hg.init().setDirectory(remoteDir).call();

        File localDir = tempDir.resolve("local").toFile();
        HgRepository localRepo = Hg.init().setDirectory(localDir).call();
        Files.writeString(new File(localDir, "a.txt").toPath(), "v1");
        new AddCommand(localRepo).call();
        new CommitCommand(localRepo).setMessage("first").call();

        localRepo.getConfig().set("paths", "default-push", remoteDir.getAbsolutePath());
        String result = new PushCommand(localRepo).call();
        assertTrue(result.toLowerCase().contains("push"));
    }

    @Test
    public void resolvesNamedPathAliasFromConfig(@TempDir Path tempDir) throws Exception {
        File remoteDir = tempDir.resolve("remote").toFile();
        Hg.init().setDirectory(remoteDir).call();

        File localDir = tempDir.resolve("local").toFile();
        HgRepository localRepo = Hg.init().setDirectory(localDir).call();
        Files.writeString(new File(localDir, "a.txt").toPath(), "v1");
        new AddCommand(localRepo).call();
        new CommitCommand(localRepo).setMessage("first").call();

        localRepo.getConfig().set("paths", "upstream", remoteDir.getAbsolutePath());
        String result = new PushCommand(localRepo).setDestination("upstream").call();
        assertTrue(result.toLowerCase().contains("push"));
    }

    @Test
    public void prePushHookCanRejectAndPostPushHookRunsOnSuccess(@TempDir Path tempDir) throws Exception {
        File remoteDir = tempDir.resolve("remote").toFile();
        Hg.init().setDirectory(remoteDir).call();

        File localDir = tempDir.resolve("local").toFile();
        HgRepository localRepo = Hg.init().setDirectory(localDir).call();
        Files.writeString(new File(localDir, "a.txt").toPath(), "v1");
        new AddCommand(localRepo).call();
        new CommitCommand(localRepo).setMessage("first").call();

        assertThrows(HgValidationException.class, () ->
                new PushCommand(localRepo).setDestination(remoteDir.getAbsolutePath())
                        .registerPrePushHook(ctx -> false)
                        .call());

        List<String> fired = new ArrayList<>();
        new PushCommand(localRepo).setDestination(remoteDir.getAbsolutePath())
                .registerPrePushHook(ctx -> { fired.add("pre"); return true; })
                .registerPostPushHook(ctx -> { fired.add("post"); return true; })
                .call();
        assertEquals(List.of("pre", "post"), fired);
    }

    @Test
    public void registeringNullHooksIsANoOp(@TempDir Path tempDir) throws Exception {
        File remoteDir = tempDir.resolve("remote").toFile();
        Hg.init().setDirectory(remoteDir).call();

        File localDir = tempDir.resolve("local").toFile();
        HgRepository localRepo = Hg.init().setDirectory(localDir).call();
        Files.writeString(new File(localDir, "a.txt").toPath(), "v1");
        new AddCommand(localRepo).call();
        new CommitCommand(localRepo).setMessage("first").call();

        List<String> fired = new ArrayList<>();
        String result = new PushCommand(localRepo).setDestination(remoteDir.getAbsolutePath())
                .registerPrePushHook(null)
                .registerPostPushHook(null)
                .registerPrePushHook(ctx -> { fired.add("pre"); return true; })
                .registerPostPushHook(ctx -> { fired.add("post"); return true; })
                .call();
        assertTrue(result.toLowerCase().contains("push"));
        assertEquals(List.of("pre", "post"), fired);
    }

    @Test
    public void throwsWhenConfiguredDefaultPushAndDefaultPathsAreBothBlank(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        repo.getConfig().set("paths", "default-push", "");
        repo.getConfig().set("paths", "default", "");
        assertThrows(IllegalStateException.class, () -> new PushCommand(repo).call());
    }

    @Test
    public void treatsExplicitlyEmptyDestinationAsUnspecifiedAndFallsBackToConfiguredDefaultPush(@TempDir Path tempDir) throws Exception {
        File remoteDir = tempDir.resolve("remote").toFile();
        Hg.init().setDirectory(remoteDir).call();

        File localDir = tempDir.resolve("local").toFile();
        HgRepository localRepo = Hg.init().setDirectory(localDir).call();
        Files.writeString(new File(localDir, "a.txt").toPath(), "v1");
        new AddCommand(localRepo).call();
        new CommitCommand(localRepo).setMessage("first").call();

        localRepo.getConfig().set("paths", "default-push", remoteDir.getAbsolutePath());
        String result = new PushCommand(localRepo).setDestination("").call();
        assertTrue(result.toLowerCase().contains("push"));
    }

    @Test
    public void bookmarkSyncExceptionIsSwallowedAndPushStillSucceeds(@TempDir Path tempDir) throws Exception {
        File remoteDir = tempDir.resolve("remote").toFile();
        Hg.init().setDirectory(remoteDir).call();

        File localDir = tempDir.resolve("local").toFile();
        HgRepository localRepo = Hg.init().setDirectory(localDir).call();
        Files.writeString(new File(localDir, "a.txt").toPath(), "v1");
        new AddCommand(localRepo).call();
        byte[] node1 = new CommitCommand(localRepo).setMessage("first").call();
        new BookmarkCommand(localRepo).setBookmarkName("stable").setNodeId(node1).call();

        HgRemoteConnectionFactory.register(new TransportProtocol() {
            @Override
            public boolean canHandle(String url) {
                return url.startsWith(THROW_LISTKEYS_SCHEME);
            }
            @Override
            public HgRemoteConnection open(String url) throws IOException {
                return new QuirkyLocalConnection(url.substring(THROW_LISTKEYS_SCHEME.length()),
                        QuirkyLocalConnection.Quirk.THROW_LISTKEYS);
            }
        });

        String result = new PushCommand(localRepo)
                .setDestination(THROW_LISTKEYS_SCHEME + remoteDir.getAbsolutePath())
                .call();
        assertTrue(result.toLowerCase().contains("push"));
    }

    @Test
    public void postPushHookExceptionIsSwallowedAndPushStillSucceeds(@TempDir Path tempDir) throws Exception {
        File remoteDir = tempDir.resolve("remote").toFile();
        Hg.init().setDirectory(remoteDir).call();

        File localDir = tempDir.resolve("local").toFile();
        HgRepository localRepo = Hg.init().setDirectory(localDir).call();
        Files.writeString(new File(localDir, "a.txt").toPath(), "v1");
        new AddCommand(localRepo).call();
        new CommitCommand(localRepo).setMessage("first").call();

        String result = new PushCommand(localRepo).setDestination(remoteDir.getAbsolutePath())
                .registerPostPushHook(ctx -> { throw new IOException("boom"); })
                .call();
        assertTrue(result.toLowerCase().contains("push"));
    }

    @Test
    public void secondPushSkipsAlreadyKnownFilelogRevisionsAndUnchangedBookmark(@TempDir Path tempDir) throws Exception {
        File remoteDir = tempDir.resolve("remote").toFile();
        Hg.init().setDirectory(remoteDir).call();

        File localDir = tempDir.resolve("local").toFile();
        HgRepository localRepo = Hg.init().setDirectory(localDir).call();
        Files.writeString(new File(localDir, "a.txt").toPath(), "v1");
        new AddCommand(localRepo).call();
        byte[] node1 = new CommitCommand(localRepo).setMessage("first").call();
        new BookmarkCommand(localRepo).setBookmarkName("stable").setNodeId(node1).call();
        new PushCommand(localRepo).setDestination(remoteDir.getAbsolutePath()).call();

        Files.writeString(new File(localDir, "a.txt").toPath(), "v2");
        new CommitCommand(localRepo).setMessage("second").call();
        String result = new PushCommand(localRepo).setDestination(remoteDir.getAbsolutePath()).call();
        assertTrue(result.toLowerCase().contains("push"));

        HgRepository remoteRepo = new HgRepository(remoteDir);
        Map<String, String> remoteBookmarks = new BookmarkCommand(remoteRepo).call();
        assertEquals(NodeIdUtil.toHex(node1), remoteBookmarks.get("stable"));
        List<HgCommit> remoteLog = new LogCommand(remoteRepo).call();
        assertEquals(2, remoteLog.size());
    }

    @Test
    public void packsBothParentsWhenPushingAMergeCommitAndPropagatesRemoteKnownThroughIt(@TempDir Path tempDir) throws Exception {
        File remoteDir = tempDir.resolve("remote").toFile();
        Hg.init().setDirectory(remoteDir).call();

        File localDir = tempDir.resolve("local").toFile();
        HgRepository localRepo = Hg.init().setDirectory(localDir).call();

        File f1 = new File(localDir, "hello.txt");
        Files.writeString(f1.toPath(), "Line 1\nLine 2\nLine 3\n");
        new AddCommand(localRepo).call();
        byte[] baseNode = new CommitCommand(localRepo).setMessage("Base commit").call();

        Files.writeString(f1.toPath(), "Line 1 [MINE]\nLine 2\nLine 3\n");
        byte[] yoursNode = new CommitCommand(localRepo).setMessage("Yours change").call();

        Dirstate dirstate = localRepo.getDirstate();
        dirstate.setParents(baseNode, new byte[20]);
        localRepo.writeDirstate(dirstate);
        Files.writeString(f1.toPath(), "Line 1\nLine 2\nLine 3 [THEIRS]\n");
        new CommitCommand(localRepo).setMessage("Theirs change").call();

        new MergeCommand(localRepo).setNodeId(yoursNode).call();
        new CommitCommand(localRepo).setMessage("Merge").call();

        String firstPush = new PushCommand(localRepo).setDestination(remoteDir.getAbsolutePath()).call();
        assertTrue(firstPush.toLowerCase().contains("push"));

        HgRepository remoteRepo = new HgRepository(remoteDir);
        assertEquals(4, new LogCommand(remoteRepo).call().size());

        Files.writeString(new File(localDir, "another.txt").toPath(), "more");
        new AddCommand(localRepo).call();
        new CommitCommand(localRepo).setMessage("after merge").call();

        String secondPush = new PushCommand(localRepo).setDestination(remoteDir.getAbsolutePath()).call();
        assertTrue(secondPush.toLowerCase().contains("push"));
        assertEquals(5, new LogCommand(remoteRepo).call().size());
    }

    private static final String NULL_EVERYTHING_SCHEME = "pushtest-nulleverything://";
    private static final String WEIRD_HEADS_SCHEME = "pushtest-weirdheads://";
    private static final String THROW_LISTKEYS_SCHEME = "pushtest-throwlistkeys://";

    @Test
    public void handlesRemoteConnectionReturningNullHeadsNullListKeysAndSentinelHeadEntries(@TempDir Path tempDir) throws Exception {
        File remoteDir = tempDir.resolve("remote").toFile();
        Hg.init().setDirectory(remoteDir).call();

        File localDir = tempDir.resolve("local").toFile();
        HgRepository localRepo = Hg.init().setDirectory(localDir).call();
        Files.writeString(new File(localDir, "a.txt").toPath(), "v1");
        new AddCommand(localRepo).call();
        byte[] node1 = new CommitCommand(localRepo).setMessage("first").call();
        new BookmarkCommand(localRepo).setBookmarkName("stable").setNodeId(node1).call();

        HgRemoteConnectionFactory.register(new TransportProtocol() {
            @Override
            public boolean canHandle(String url) {
                return url.startsWith(NULL_EVERYTHING_SCHEME);
            }
            @Override
            public HgRemoteConnection open(String url) throws IOException {
                return new QuirkyLocalConnection(url.substring(NULL_EVERYTHING_SCHEME.length()),
                        QuirkyLocalConnection.Quirk.NULL_EVERYTHING);
            }
        });

        String firstPush = new PushCommand(localRepo)
                .setDestination(NULL_EVERYTHING_SCHEME + remoteDir.getAbsolutePath())
                .call();
        assertTrue(firstPush.toLowerCase().contains("push"));

        HgRepository remoteRepo = new HgRepository(remoteDir);
        assertEquals(NodeIdUtil.toHex(node1), new BookmarkCommand(remoteRepo).call().get("stable"));

        Files.writeString(new File(localDir, "a.txt").toPath(), "v2");
        new CommitCommand(localRepo).setMessage("second").call();

        HgRemoteConnectionFactory.register(new TransportProtocol() {
            @Override
            public boolean canHandle(String url) {
                return url.startsWith(WEIRD_HEADS_SCHEME);
            }
            @Override
            public HgRemoteConnection open(String url) throws IOException {
                return new QuirkyLocalConnection(url.substring(WEIRD_HEADS_SCHEME.length()),
                        QuirkyLocalConnection.Quirk.WEIRD_HEADS);
            }
        });

        String secondPush = new PushCommand(localRepo)
                .setDestination(WEIRD_HEADS_SCHEME + remoteDir.getAbsolutePath())
                .call();
        assertTrue(secondPush.toLowerCase().contains("push"));
        assertEquals(2, new LogCommand(remoteRepo).call().size());
    }

    /**
     * Wraps a real {@link HgLocalClient} but lets individual tests force the otherwise
     * unreachable defensive {@code null}/sentinel-handling branches in {@code PushCommand}
     * (no {@link HgRemoteConnection} implementation in this codebase ever actually returns
     * {@code null} heads/listKeys, or a sentinel/zero head) by routing through
     * {@link HgRemoteConnectionFactory}'s public {@link TransportProtocol} extension point.
     */
    private static final class QuirkyLocalConnection implements HgRemoteConnection {
        enum Quirk { NULL_EVERYTHING, WEIRD_HEADS, THROW_LISTKEYS }

        private final HgLocalClient delegate;
        private final Quirk quirk;

        QuirkyLocalConnection(String path, Quirk quirk) throws IOException {
            this.delegate = new HgLocalClient(path);
            this.quirk = quirk;
        }

        @Override
        public List<String> getCapabilities() throws IOException {
            return delegate.getCapabilities();
        }

        @Override
        public List<String> getHeads() throws IOException {
            if (quirk == Quirk.NULL_EVERYTHING) {
                return null;
            }
            List<String> heads = new ArrayList<>(delegate.getHeads());
            heads.add(null);
            heads.add("0000000000000000000000000000000000000000");
            return heads;
        }

        @Override
        public byte[] getChangegroup(List<String> roots) throws IOException {
            return delegate.getChangegroup(roots);
        }

        @Override
        public byte[] getBundle(List<String> common, List<String> heads, List<String> bundleCaps) throws IOException {
            return delegate.getBundle(common, heads, bundleCaps);
        }

        @Override
        public String push(byte[] bundleBytes, List<String> heads) throws IOException, HgLockException {
            return delegate.push(bundleBytes, heads);
        }

        @Override
        public Map<String, String> listKeys(String namespace) throws IOException {
            if (quirk == Quirk.NULL_EVERYTHING) {
                return null;
            }
            if (quirk == Quirk.THROW_LISTKEYS) {
                throw new IOException("simulated listKeys failure");
            }
            return delegate.listKeys(namespace);
        }

        @Override
        public boolean pushkey(String namespace, String key, String oldVal, String newVal) throws IOException {
            return delegate.pushkey(namespace, key, oldVal, newVal);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
