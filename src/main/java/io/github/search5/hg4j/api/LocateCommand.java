package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Porcelain command mirroring {@code hg locate}: prints files under Mercurial
 * control whose names match a given pattern.
 * <p>
 * {@code hg locate} is a distinct, older command from {@code hg files} with its
 * own quirks, verified against real {@code hg} (v7.2) and
 * {@code mercurial/commands.py}'s {@code locate} function:
 * <ul>
 *     <li>With no revision set, it searches the <b>working copy</b> via the
 *     dirstate ({@code repo.dirstate.matches(m)} in Python) rather than the
 *     working-copy context, which means files marked <i>removed</i> (state
 *     {@code 'r'}, i.e. {@code hg rm}'d but not yet committed) are still
 *     reported &mdash; unlike {@code hg files}, which filters on
 *     {@code dirstate.get_entry(f).tracked} and excludes them. A file that is
 *     simply missing from disk (deleted without {@code hg rm}, dirstate state
 *     {@code 'n'}/{@code '!'} status) is reported by both commands since
 *     neither checks physical existence.</li>
 *     <li>With a revision set via {@link #setRevision(String)}, it searches
 *     that revision's manifest instead (equivalent to {@code ctx.matches(m)}
 *     in Python), which has no notion of "removed" files.</li>
 *     <li>With no pattern set, every candidate path is returned (equivalent to
 *     no {@code pats} being given to {@code hg locate}).</li>
 *     <li>With a pattern set, it is matched using {@code hg}'s default pattern
 *     kind for {@code locate}, {@code relglob} &mdash; an <b>unrooted</b> glob,
 *     matched against the path's basename in any directory (verified: pattern
 *     {@code "*.txt"} against a repo with {@code a.txt} and {@code sub/b.txt}
 *     matches both; {@code "sub/*.txt"} matches only {@code sub/b.txt}).</li>
 * </ul>
 * Only {@code *}, {@code **}, and {@code ?} glob metacharacters are supported
 * (matching {@link io.github.search5.hg4j.treewalk.SparsePathFilter}'s existing
 * precedent in this codebase); {@code [...]} character classes and
 * {@code {a,b}} brace alternation are not translated and are treated as
 * literal characters.
 */
public class LocateCommand {

    private final HgRepository repository;
    private String pattern;
    private String revision;

    public LocateCommand(HgRepository repository) {
        this.repository = repository;
    }

    public LocateCommand setPattern(String pattern) {
        this.pattern = pattern;
        return this;
    }

    public LocateCommand setRevision(String revision) {
        this.revision = revision;
        return this;
    }

    public List<String> call() throws IOException {
        repository.clearRevlogCache();

        Set<String> candidatePaths;
        if (revision == null || revision.isEmpty()) {
            // Working copy: mirror repo.dirstate.matches(m), which returns
            // dirstate entries in ANY state (added/normal/merged/removed),
            // deliberately not filtered by "tracked" the way hg files/
            // workingctx.matches() is. This is what makes locate report
            // removed-but-uncommitted files while files does not.
            candidatePaths = repository.getDirstate().getEntries().keySet();
        } else {
            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");
            Revlog changelog = repository.getRevlog(clIdx, clDat);
            byte[] targetNode = NodeIdUtil.resolveRevision(changelog, revision);
            if (targetNode == null) {
                throw new HgRevisionNotFoundException("Unable to resolve revision: " + revision);
            }
            candidatePaths = repository.getManifestAtCommit(targetNode).keySet();
        }

        Pattern compiled = (pattern == null || pattern.isEmpty()) ? null : compileRelglobPattern(pattern);

        List<String> result = new ArrayList<>();
        for (String path : candidatePaths) {
            if (compiled == null || compiled.matcher(path).matches()) {
                result.add(path);
            }
        }
        result.sort(NodeIdUtil.UTF8_STRING_COMPARATOR);
        return result;
    }

    /**
     * Translates an {@code hg}-style "relglob" pattern into a fully-anchored
     * Java regex, matching {@code mercurial/match.py}'s {@code _globre} plus
     * the {@code relglob} wrapping: an optional directory prefix, followed by
     * the translated glob body, followed by an end-of-string anchor (verified
     * by reading that source directly): {@code *} matches within one path
     * segment, {@code **} (optionally followed by {@code /}) matches across
     * segments including zero, {@code ?} matches any single character
     * (including {@code /}), and the whole pattern may be preceded by an
     * arbitrary directory prefix so it matches anywhere in the tree, not just
     * relative to the repository root.
     */
    private static Pattern compileRelglobPattern(String pattern) {
        StringBuilder body = new StringBuilder();
        int n = pattern.length();
        for (int i = 0; i < n; i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                if (i + 1 < n && pattern.charAt(i + 1) == '*') {
                    i++; // consume second '*'
                    if (i + 1 < n && pattern.charAt(i + 1) == '/') {
                        i++; // consume '/', absorbed into the group below
                        body.append("(?:.*/)?");
                    } else {
                        body.append(".*");
                    }
                } else {
                    body.append("[^/]*");
                }
            } else if (c == '?') {
                body.append('.');
            } else if ("\\.^$|()[]{}+".indexOf(c) >= 0) {
                body.append('\\').append(c);
            } else {
                body.append(c);
            }
        }
        return Pattern.compile("(?:|.*/)" + body);
    }
}
