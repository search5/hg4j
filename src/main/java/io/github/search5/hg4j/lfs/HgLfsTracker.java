package io.github.search5.hg4j.lfs;

import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgRepository;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides which files are kept in the LFS store, like {@code _trackedmatcher} of real hg's {@code hgext/lfs/__init__.py}.
 *
 * <p>If the working directory has a {@code .hglfs} file, its {@code [track]} rules decide (the first rule whose pattern matches
 * the path gives the answer, no match means "not tracked", an empty file tracks nothing) and the configuration is ignored.
 * Otherwise {@code [lfs] track} (a {@link MiniFileset} expression, default {@code none()}) decides, and the deprecated
 * {@code [lfs] threshold} adds {@code | size('>N')}.
 */
public final class HgLfsTracker {

    private final MiniFileset.Matcher matcher;

    private HgLfsTracker(MiniFileset.Matcher matcher) {
        this.matcher = matcher;
    }

    /** Whether a file of {@code path} (repository-relative) and {@code size} bytes of content is kept in the LFS store. */
    public boolean tracks(String path, long size) {
        return matcher.matches(path, size);
    }

    /**
     * Builds the tracker for a repository from its {@code .hglfs} file or its configuration.
     *
     * @param repository the repository whose working directory and configuration are read; must not be {@code null}
     * @return the tracker, never {@code null}
     * @throws IOException if {@code .hglfs} cannot be read; an invalid {@code lfs.track} or {@code .hglfs} expression raises an {@link HgValidationException}
     */
    public static HgLfsTracker forRepository(HgRepository repository) throws IOException {
        File hglfs = new File(repository.getDirectory(), ".hglfs");
        if (!hglfs.exists()) {
            String trackSpec = repository.getConfig().get("lfs", "track");
            if (trackSpec == null) {
                trackSpec = "none()";
            }
            // real hg tests `if threshold:`, so 0 behaves exactly like unset
            long threshold = HgLfsManager.parseThresholdBytes(repository.getConfig().get("lfs", "threshold"));
            if (threshold > 0) {
                MiniFileset.check(trackSpec); // make sure syntax errors are confined
                trackSpec = "(" + trackSpec + ") | size('>" + threshold + "')";
            }
            return new HgLfsTracker(MiniFileset.compile(trackSpec));
        }

        byte[] data = Files.readAllBytes(hglfs.toPath());
        if (data.length == 0) {
            return new HgLfsTracker((p, s) -> false);
        }
        List<MiniFileset.Matcher[]> rules = new ArrayList<>();
        try {
            for (String[] rule : trackRules(new String(data, StandardCharsets.UTF_8))) {
                rules.add(new MiniFileset.Matcher[] {MiniFileset.compile(rule[0]), MiniFileset.compile(rule[1])});
            }
        } catch (HgValidationException e) {
            // the original exception gives no hint that the error is in the .hglfs file, so add that
            throw new HgValidationException("parse error in .hglfs: " + e.getMessage(), e);
        }
        return new HgLfsTracker((path, size) -> {
            for (MiniFileset.Matcher[] rule : rules) {
                if (rule[0].matches(path, size)) {
                    return rule[1].matches(path, size);
                }
            }
            return false;
        });
    }

    /**
     * The {@code pattern = rule} items of the {@code [track]} section of an hgrc-style file, in file order. Comment lines
     * ({@code #}, {@code ;}) and blank lines are skipped, an indented line continues the previous value.
     */
    static List<String[]> trackRules(String text) throws HgValidationException {
        List<String[]> items = new ArrayList<>();
        String section = null;
        String[] current = null;
        int lineNo = 0;
        for (String raw : text.split("\r?\n", -1)) {
            lineNo++;
            String line = raw;
            if (line.isBlank()) {
                continue;
            }
            char first = line.charAt(0);
            if (first == '#' || first == ';') {
                continue;
            }
            if ((first == ' ' || first == '\t') && current != null) { // continuation of the previous value
                current[1] = current[1] + "\n" + line.strip();
                continue;
            }
            line = line.stripTrailing();
            if (first == '[') {
                int end = line.indexOf(']');
                if (end < 0) {
                    throw new HgValidationException(".hglfs:" + lineNo + ": unexpected leading whitespace or bad section");
                }
                section = line.substring(1, end);
                current = null;
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new HgValidationException(".hglfs:" + lineNo + ": " + line);
            }
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            current = new String[] {key, value};
            if ("track".equals(section)) {
                items.add(current);
            }
        }
        return items;
    }
}
