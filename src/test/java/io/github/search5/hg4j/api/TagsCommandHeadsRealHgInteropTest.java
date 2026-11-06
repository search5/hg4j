package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code TagsCommand} reading the heads gives the list real {@code hg tags} prints for a repository without working files. */
@Tag("interop")
class TagsCommandHeadsRealHgInteropTest {

    @TempDir
    Path tempDir;

    private static final Pattern LINE = Pattern.compile("^(.+?)\\s+(\\d+):([0-9a-f]{12})(?: local)?$");

    private static List<String> realTags(File dir) throws Exception {
        List<String> lines = new ArrayList<>();
        for (String line : HgTestUtils.hg(dir, "tags").split("\n")) {
            Matcher m = LINE.matcher(line.trim());
            if (m.matches()) {
                lines.add(m.group(1) + " " + m.group(2) + ":" + m.group(3));
            }
        }
        return lines;
    }

    private static List<String> ours(HgRepository repo) throws Exception {
        List<String> lines = new ArrayList<>();
        for (TagsCommand.Tag t : new TagsCommand(repo).setReadFromHeads(true).call()) {
            lines.add(t.getName() + " " + t.getRev() + ":" + NodeIdUtil.toHex(t.getNode()).substring(0, 12));
        }
        return lines;
    }

    private void commit(File dir, String file, String text, String message) throws Exception {
        Files.writeString(new File(dir, file).toPath(), text);
        HgTestUtils.hg(dir, "add", file);
        HgTestUtils.hg(dir, "commit", "-m", message, "-u", "t", "-d", "1700000000 0");
    }

    private void tag(File dir, String... args) throws Exception {
        List<String> all = new ArrayList<>(List.of("tag", "-u", "t", "-d", "1700000000 0"));
        all.addAll(List.of(args));
        HgTestUtils.hg(dir, all.toArray(new String[0]));
    }

    @Test
    void twoHeadsThatTagAndMoveAndRemoveTagsListLikeTheOriginal() throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "real hg required");
        File src = tempDir.resolve("src").toFile();
        src.mkdirs();
        HgTestUtils.hg(src, "init");
        commit(src, "a.txt", "a\n", "a");
        commit(src, "b.txt", "b\n", "b");
        tag(src, "-r", "0", "v1");
        tag(src, "-r", "1", "keep");
        HgTestUtils.hg(src, "update", "-r", "1");
        commit(src, "c.txt", "c\n", "c");
        tag(src, "-f", "-r", "tip", "v1");
        tag(src, "-r", "tip", "only-b");
        tag(src, "--remove", "keep");

        File server = tempDir.resolve("server").toFile();
        HgTestUtils.hg(tempDir.toFile(), "clone", "-U", src.getAbsolutePath(), server.getAbsolutePath());
        HgRepository repo = Hg.open(server).getRepository();

        assertEquals(realTags(server), ours(repo));
    }
}
