package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.SafeFileIO;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The state of an interrupted rebase, in the file and the format Mercurial uses ({@code .hg/rebasestate}, {@code hgext/rebase.py
 * rebaseruntime._writestatus/_read}); see {@code llm-wiki/concepts/rebase-original.md}. Changesets are nodes (hex); the file names
 * them by hash.
 *
 * <pre>
 * originalwd            line 0
 * (empty)               line 1, was "dest" in old versions
 * external              line 2, the null hash when there is none
 * collapse, keep, keepbranches   lines 3 to 5, 0 or 1
 * activebookmark        line 6, may be empty
 * oldnode:newnode:destnode       one line per changeset; newnode is a hash or "-1" while it is to do
 * </pre>
 */
final class RebaseState {

    /** {@code revtodo}: not rebased yet. */
    static final String TODO = "-1";
    static final String NULL_HEX = "0".repeat(40);
    /** States of old versions that are skipped when reading ({@code legacystates}): nullmerge, revignored, revprecursor, revpruned. */
    private static final Set<String> LEGACY = Set.of("-2", "-3", "-4", "-5");

    /** One changeset: what it became (a hash, or {@link #TODO}) and where it goes. */
    record Entry(String newNode, String destNode) {
    }

    String originalWd;
    String external = NULL_HEX;
    boolean collapse;
    boolean keep;
    boolean keepBranches;
    String activeBookmark = "";
    final Map<String, Entry> state = new LinkedHashMap<>();

    /** {@code _writestatus}. */
    byte[] toBytes() {
        StringBuilder out = new StringBuilder();
        out.append(originalWd).append('\n');
        out.append('\n'); // was "dest"; written per source below
        out.append(external).append('\n');
        out.append(collapse ? 1 : 0).append('\n');
        out.append(keep ? 1 : 0).append('\n');
        out.append(keepBranches ? 1 : 0).append('\n');
        out.append(activeBookmark == null ? "" : activeBookmark).append('\n');
        for (Map.Entry<String, Entry> e : state.entrySet()) {
            out.append(e.getKey()).append(':').append(e.getValue().newNode()).append(':').append(e.getValue().destNode()).append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * {@code _read}. A line 6 that looks like a state line (81 characters with a colon) is not the active bookmark: that line was added
     * later and older files go straight to the states. A file missing its flags is {@code .hg/rebasestate is incomplete}.
     */
    static RebaseState parse(byte[] bytes) throws HgValidationException {
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
        // splitlines(): a final newline does not make an extra empty line
        int count = lines.length > 0 && lines[lines.length - 1].isEmpty() ? lines.length - 1 : lines.length;
        RebaseState s = new RebaseState();
        Boolean collapse = null;
        Boolean keep = null;
        Boolean keepBranches = null;
        String legacyDest = null;
        for (int i = 0; i < count; i++) {
            String l = lines[i];
            if (i == 0) {
                s.originalWd = l;
            } else if (i == 1) {
                if (!l.isEmpty()) {
                    legacyDest = l; // an old client wrote the destination here
                }
            } else if (i == 2) {
                s.external = l;
            } else if (i == 3) {
                collapse = !l.trim().equals("0");
            } else if (i == 4) {
                keep = !l.trim().equals("0");
            } else if (i == 5) {
                keepBranches = !l.trim().equals("0");
            } else if (i == 6 && !(l.length() == 81 && l.contains(":"))) {
                s.activeBookmark = l;
            } else {
                String[] args = l.split(":", -1);
                String newNode = args[1];
                if (LEGACY.contains(newNode)) {
                    continue;
                }
                String dest = args.length > 2 ? args[2] : legacyDest;
                s.state.put(args[0], new Entry(newNode, dest));
            }
        }
        if (keepBranches == null) {
            throw new HgValidationException(".hg/rebasestate is incomplete");
        }
        s.collapse = collapse != null && collapse;
        s.keep = keep != null && keep;
        s.keepBranches = keepBranches;
        return s;
    }

    /**
     * {@code skipped}: not stored, computed again. Unless collapsing, the destinations are "seen"; walking the state in order, a changeset
     * whose new node was already seen (and is not to do) was skipped.
     */
    Set<String> skipped() {
        Set<String> skipped = new LinkedHashSet<>();
        if (collapse) {
            return skipped;
        }
        Set<String> seen = new LinkedHashSet<>();
        state.values().forEach(e -> seen.add(e.destNode()));
        for (Map.Entry<String, Entry> e : state.entrySet()) {
            String n = e.getValue().newNode();
            if (!n.equals(TODO) && seen.contains(n)) {
                skipped.add(e.getKey());
            }
            seen.add(n);
        }
        return skipped;
    }

    /** The changesets still to do, in file order. */
    List<String> todo() {
        List<String> out = new ArrayList<>();
        state.forEach((k, v) -> {
            if (v.newNode().equals(TODO)) {
                out.add(k);
            }
        });
        return out;
    }

    // ---------------------------------------------------------------- files

    static File file(HgRepository repo) {
        return new File(repo.getHgDir(), "rebasestate");
    }

    static File collapseMessageFile(HgRepository repo) {
        return new File(repo.getHgDir(), "last-message.txt");
    }

    boolean exists(HgRepository repo) {
        return file(repo).isFile();
    }

    /** {@code storestatus()} without a transaction: the file is rewritten whole. */
    void store(HgRepository repo) throws IOException {
        SafeFileIO.writeAtomic(file(repo), toBytes());
    }

    static RebaseState read(HgRepository repo) throws IOException {
        return parse(Files.readAllBytes(file(repo).toPath()));
    }

    /** {@code clearstatus}. */
    static void clear(HgRepository repo) throws IOException {
        Files.deleteIfExists(file(repo).toPath());
    }

    /** {@code storecollapsemsg}: the message and a newline. */
    static void storeCollapseMessage(HgRepository repo, String message) throws IOException {
        Files.writeString(collapseMessageFile(repo).toPath(), (message == null ? "" : message) + "\n", StandardCharsets.UTF_8);
    }

    /** {@code restorecollapsemsg}: the first line; a missing file is an empty message when aborting. */
    static String restoreCollapseMessage(HgRepository repo, boolean abort) throws IOException {
        File f = collapseMessageFile(repo);
        if (!f.isFile()) {
            if (abort) {
                return "";
            }
            throw new HgValidationException("missing .hg/last-message.txt for rebase");
        }
        List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        return lines.isEmpty() ? "" : lines.get(0).strip();
    }

    /** {@code clearcollapsemsg}. */
    static void clearCollapseMessage(HgRepository repo) throws IOException {
        Files.deleteIfExists(collapseMessageFile(repo).toPath());
    }
}
