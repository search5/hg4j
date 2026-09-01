package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.github.search5.hg4j.errors.HgValidationException;
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
}
