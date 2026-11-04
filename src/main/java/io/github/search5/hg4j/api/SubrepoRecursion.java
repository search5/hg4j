package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.match.Matcher;
import io.github.search5.hg4j.submodule.HgSubrepoEntry;
import io.github.search5.hg4j.submodule.HgSubrepoParser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What {@code cmdutil.add/forget/remove/revert} do for the subrepositories of the working directory ({@code wctx.substate}): each
 * hg subrepository gets the matcher of the parent seen from inside it ({@code matchmod.subdirmatcher}) and runs the same operation on
 * its own working copy, under its own working copy lock (hgsubrepo takes {@code self._repo.wlock()} for each). The parent's locks
 * stay held meanwhile; the subrepository is another repository, so they cannot conflict.
 *
 * <p>Subrepositories of another kind (git, svn) are skipped: the base {@code abstractsubrepo} does nothing for them.</p>
 */
public final class SubrepoRecursion {

    /** One hg subrepository: its path in the parent and its repository, {@code null} when it is not on the disk. */
    public record Sub(String path, HgRepository repository) {
    }

    private SubrepoRecursion() {
    }

    /**
     * {@code wctx.substate} restricted to hg subrepositories, sorted by path: {@code .hgsub} and {@code .hgsubstate} of the working
     * directory.
     */
    public static List<Sub> hgSubrepos(HgRepository parent) throws IOException {
        File hgsub = new File(parent.getDirectory(), ".hgsub");
        if (!hgsub.isFile()) {
            return List.of();
        }
        File hgsubstate = new File(parent.getDirectory(), ".hgsubstate");
        Map<String, HgSubrepoEntry> entries = HgSubrepoParser.parseSubrepositories(Files.readAllBytes(hgsub.toPath()),
                hgsubstate.isFile() ? Files.readAllBytes(hgsubstate.toPath()) : new byte[0]);
        List<Sub> subs = new ArrayList<>();
        for (Map.Entry<String, HgSubrepoEntry> entry : new TreeMap<>(entries).entrySet()) {
            if (entry.getValue().getType() != HgSubrepoEntry.Type.HG) {
                continue;
            }
            File dir = new File(parent.getDirectory(), entry.getKey());
            subs.add(new Sub(entry.getKey(), new File(dir, ".hg").isDirectory() ? new HgRepository(dir) : null));
        }
        return subs;
    }

    /** {@code matchmod.subdirmatcher(path, matcher)}: the parent's matcher for the files below {@code path}, named from inside it. */
    public static Matcher subdir(String path, Matcher parent) {
        String prefix = path + "/";
        List<String> files = new ArrayList<>();
        for (String f : parent.files()) {
            if (f.startsWith(prefix)) {
                files.add(f.substring(prefix.length()));
            }
        }
        // a prefix matcher that names the subrepository itself matches everything in it
        boolean always = parent.always() || parent.files().contains(path) && !parent.isExact();
        return new Matcher() {
            @Override
            public boolean matches(String f) {
                return always || parent.matches(prefix + f);
            }

            @Override
            public List<String> files() {
                return files;
            }

            @Override
            public boolean always() {
                return always;
            }

            @Override
            public boolean isExact() {
                return parent.isExact();
            }

            @Override
            public boolean visitDir(String dir) {
                return always || parent.visitDir(dir.isEmpty() ? path : prefix + dir);
            }
        };
    }

    /** {@code subdiruipathfn}: a path inside the subrepository as the user sees it from the parent. */
    public static String display(String subpath, String file) {
        return subpath + "/" + file;
    }

    /** What {@code hg} prints when a subrepository named in {@code .hgsub} is not on the disk. */
    public static String missingMessage(String subpath) {
        return "skipping missing subrepository: " + subpath + "\n";
    }
}
