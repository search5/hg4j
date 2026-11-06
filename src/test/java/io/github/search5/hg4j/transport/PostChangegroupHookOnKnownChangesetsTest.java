package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.HgHook;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the {@code changegroup} hook sees when a push brings only changesets the repository already has. The real
 * {@code hg unbundle} runs the hook only when at least one changeset is new; this pins what hg4j does in the same case.
 */
class PostChangegroupHookOnKnownChangesetsTest {

    @TempDir
    Path tempDir;

    @Test
    void secondPushOfTheSameBundleDoesNotRunTheChangegroupHookAndLeavesTheRepositoryAlone() throws Exception {
        File sourceDir = tempDir.resolve("source").toFile();
        HgRepository source = Hg.init().setDirectory(sourceDir).call();
        Files.writeString(new File(sourceDir, "a.txt").toPath(), "one\n");
        new AddCommand(source).call();
        new CommitCommand(source).setAuthor("t").setMessage("one").call();
        Files.writeString(new File(sourceDir, "a.txt").toPath(), "two\n");
        new CommitCommand(source).setAuthor("t").setMessage("two").call();
        byte[] bundle;
        try (HgLocalClient client = new HgLocalClient(source)) {
            bundle = client.getBundle(null, null, null);
        }

        HgRepository target = Hg.init().setDirectory(tempDir.resolve("target").toFile()).call();
        List<List<String>> seen = new ArrayList<>();
        HgHook post = context -> {
            @SuppressWarnings("unchecked")
            List<String> nodes = (List<String>) context.get("nodes");
            seen.add(new ArrayList<>(nodes));
            return true;
        };
        try (HgLocalClient client = new HgLocalClient(target)) {
            client.pushWithHooks(bundle, List.of(), List.of(), List.of(post));
            client.pushWithHooks(bundle, List.of(), List.of(), List.of(post));
        }
        // the real hg runs the changegroup hook once, for the two new changesets, and not at all for the repeated bundle
        assertEquals(1, seen.size(), "hook calls: " + seen);
        assertEquals(2, seen.get(0).size());
        assertEquals(2, target.getRevlog(new File(target.getStoreDir(), "00changelog.i"),
                new File(target.getStoreDir(), "00changelog.d")).getRevisionCount());
    }

    @Test
    void realHgDoesNotRunTheChangegroupHookForAnUnbundleWithoutNewChangesets() throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "real hg required");
        File sourceDir = tempDir.resolve("rsource").toFile();
        HgTestUtils.nativeRepo(sourceDir, dir -> {});
        Files.writeString(new File(sourceDir, "a.txt").toPath(), "one\n");
        HgTestUtils.hg(sourceDir, "add", "a.txt");
        HgTestUtils.hg(sourceDir, "commit", "-m", "one", "-u", "t");
        File bundle = tempDir.resolve("b.hg").toFile();
        HgTestUtils.hg(sourceDir, "bundle", "--all", bundle.getAbsolutePath());
        File targetDir = tempDir.resolve("rtarget").toFile();
        HgTestUtils.nativeRepo(targetDir, dir -> {});
        File marker = tempDir.resolve("marker").toFile();
        String hook = "hooks.changegroup.mark=echo $HG_NODE >> " + marker.getAbsolutePath();
        HgTestUtils.hg(targetDir, "--config", hook, "unbundle", bundle.getAbsolutePath());
        HgTestUtils.hg(targetDir, "--config", hook, "unbundle", bundle.getAbsolutePath());
        long lines = Files.readAllLines(marker.toPath()).size();
        assertEquals(1, lines);
    }
}
