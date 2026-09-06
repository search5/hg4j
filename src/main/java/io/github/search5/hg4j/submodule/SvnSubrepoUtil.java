package io.github.search5.hg4j.submodule;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import java.io.ByteArrayInputStream;

/**
 * Shell-out helper replicating the pieces of a {@code [svn]}-prefixed {@code .hgsub} subrepo's
 * lifecycle that hg4j needs from real hg's {@code mercurial/subrepo.py} {@code svnsubrepo} class
 * (read live against Mercurial 7.2's installed {@code /usr/lib/python3/dist-packages/mercurial/
 * subrepo.py} plus a real local {@code svn}/{@code svnadmin} 1.14 CLI, backlog item 41):
 * querying its working-copy status ({@code svn status --xml}, real hg's {@code _wcchanged()}),
 * its checked-out/last-committed revisions ({@code svn info --xml}, real hg's {@code _wcrevs()}),
 * resolving the revision to record in {@code .hgsubstate} ({@code basestate()}), checking out a
 * pinned {@code url@revision} ({@code svn checkout --force}, real hg's {@code get()}), and
 * committing local changes back to the (centralized) SVN repository ({@code svn commit}, real
 * hg's {@code commit()}).
 *
 * <p>Unlike {@link GitSubrepoUtil}'s split between "clone if missing" + "checkout", real hg's
 * {@code svnsubrepo.get()} unconditionally re-runs {@code svn checkout --force <url>@<rev>}
 * whether or not {@code .svn} already exists at the target path -- verified live: this is cheap
 * and idempotent against a local/already-current working copy (svn only touches what changed),
 * and is exactly what real hg itself does every single time, with no "already there" fast path
 * of its own. This class deliberately does not add one either, to stay byte-for-byte faithful to
 * what was observed.
 *
 * <p>Also verified live and load-bearing: real hg forces {@code LC_MESSAGES=C} (preserving
 * {@code LC_ALL} for everything else) when shelling out to {@code svn}, because it parses
 * English-language substrings out of {@code svn commit}'s plain-text output (e.g. {@code
 * "Committed revision 5."}) -- without it, a non-English locale (this sandbox's default is
 * Korean) produces a translated message the regex never matches, silently breaking {@link
 * #commit}. {@code --non-interactive} is likewise only passed for {@code update}/{@code
 * checkout}/{@code commit}, exactly mirroring real hg's own {@code _svncommand()}.
 */
public final class SvnSubrepoUtil {

    private static final Pattern COMMITTED_REVISION = Pattern.compile("Committed revision ([0-9]+)\\.");

    private SvnSubrepoUtil() {
    }

    /** Whether {@code dir} is (or contains) a local svn working copy, mirroring real hg's
     * {@code svnsubrepo._svnmissing()} check (inverted). */
    public static boolean isSvnCheckout(File dir) {
        return new File(dir, ".svn").exists();
    }

    /**
     * {@code svn info --xml} parsed into {@code {lastCommittedRev, checkedOutRev}} -- real hg's
     * {@code svnsubrepo._wcrevs()}. {@code lastCommittedRev} is the revision of the last commit
     * that touched this path (the {@code <commit revision="...">} attribute); {@code
     * checkedOutRev} is the working copy's own currently pinned revision (the {@code
     * <entry revision="...">} attribute) -- these differ whenever the working copy is stale
     * relative to its own last change (e.g. right after a fresh {@code svn commit}, before the
     * follow-up {@code svn update}).
     */
    public static String[] wcRevs(File dir) throws IOException {
        String xml = run(dir, "info", "--xml");
        Document doc = parseXml(xml);
        NodeList entries = doc.getElementsByTagName("entry");
        String lastRev = "0";
        String rev = "0";
        if (entries.getLength() > 0) {
            Element entry = (Element) entries.item(0);
            String r = entry.getAttribute("revision");
            if (r != null && !r.isEmpty()) {
                rev = r;
            }
            NodeList commits = entry.getElementsByTagName("commit");
            if (commits.getLength() > 0) {
                String lr = ((Element) commits.item(0)).getAttribute("revision");
                if (lr != null && !lr.isEmpty()) {
                    lastRev = lr;
                }
            }
        }
        return new String[]{lastRev, rev};
    }

    /** Outcome of {@code svn status --xml}, mirroring real hg's {@code svnsubrepo._wcchanged()}
     * return tuple {@code (changes, extchanges, missing)}. */
    public static final class WcStatus {
        public final boolean changed;
        public final boolean externalChanged;
        public final boolean missing;

        WcStatus(boolean changed, boolean externalChanged, boolean missing) {
            this.changed = changed;
            this.externalChanged = externalChanged;
            this.missing = missing;
        }
    }

    /**
     * {@code svn status --xml}, ported directly from real hg's {@code svnsubrepo._wcchanged()}:
     * any entry whose {@code wc-status item} isn't one of {@code normal}/{@code unversioned}/
     * {@code external} (or empty), or whose {@code props} isn't {@code none}/{@code normal} (or
     * empty), counts as a change; a change nested under a path reported as {@code external}
     * additionally sets {@code externalChanged}; any {@code missing} entry sets {@code missing}.
     */
    public static WcStatus wcChanged(File dir) throws IOException {
        String xml = run(dir, "status", "--xml");
        Document doc = parseXml(xml);
        NodeList entries = doc.getElementsByTagName("entry");
        List<String> externals = new ArrayList<>();
        List<String> changes = new ArrayList<>();
        boolean missing = false;
        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);
            NodeList wcStatusList = entry.getElementsByTagName("wc-status");
            if (wcStatusList.getLength() == 0) {
                continue;
            }
            Element wcStatus = (Element) wcStatusList.item(0);
            String item = wcStatus.getAttribute("item");
            String props = wcStatus.getAttribute("props");
            String path = entry.getAttribute("path");
            if ("external".equals(item)) {
                externals.add(path);
            } else if ("missing".equals(item)) {
                missing = true;
            }
            boolean itemChanged = !("".equals(item) || "normal".equals(item)
                    || "unversioned".equals(item) || "external".equals(item));
            boolean propsChanged = !("".equals(props) || "none".equals(props) || "normal".equals(props));
            if (itemChanged || propsChanged) {
                changes.add(path);
            }
        }
        boolean externalChanged = false;
        for (String path : changes) {
            for (String ext : externals) {
                if (path.equals(ext) || path.startsWith(ext + File.separatorChar) || path.startsWith(ext + "/")) {
                    externalChanged = true;
                    break;
                }
            }
            if (externalChanged) {
                break;
            }
        }
        return new WcStatus(externalChanged || !changes.isEmpty(), externalChanged, missing);
    }

    /**
     * Mirrors real hg's {@code svnsubrepo.dirty(ignoreupdate, missing=False)}: a not-checked-out
     * subrepo ({@code .svn} absent) is dirty iff a non-empty revision was previously recorded for
     * it; otherwise dirty iff the working copy has local changes, UNLESS {@code ignoreUpdate} is
     * set (in which case a clean working copy is never dirty regardless of which revision it
     * happens to be pinned at) or {@code pinnedRev} matches either of {@link #wcRevs}'s two
     * revisions.
     */
    public static boolean isDirty(File dir, String pinnedRev, boolean ignoreUpdate) throws IOException {
        if (!isSvnCheckout(dir)) {
            return pinnedRev != null && !pinnedRev.isEmpty();
        }
        WcStatus wc = wcChanged(dir);
        if (!wc.changed) {
            if (ignoreUpdate) {
                return false;
            }
            String[] revs = wcRevs(dir);
            if (pinnedRev != null && (pinnedRev.equals(revs[0]) || pinnedRev.equals(revs[1]))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Resolves the {@code .hgsubstate} revision to record for a clean svn subrepo, mirroring
     * real hg's {@code svnsubrepo.basestate()}: prefers the last-committed revision, but falls
     * back to the working copy's own checked-out revision if the source URL doesn't resolve
     * (via {@code svn list}) at that last-committed revision (e.g. the path didn't exist there
     * yet under this URL).
     */
    public static String basestate(File dir, String url) throws IOException {
        String[] revs = wcRevs(dir);
        String lastRev = revs[0];
        String rev = revs[1];
        if (!lastRev.equals(rev)) {
            try {
                run(dir, "list", url + "@" + lastRev);
                return lastRev;
            } catch (IOException ignored) {
                // Fall through to the working copy's own checked-out revision.
            }
        }
        return rev;
    }

    /**
     * {@code svn checkout --force <url>@<revision> <targetDir>} -- real hg's {@code
     * svnsubrepo.get()}. Works identically whether {@code targetDir} is a fresh (non-existent or
     * empty) directory or an already-checked-out working copy at a different revision (verified
     * live: svn switches the existing checkout in place rather than re-fetching everything).
     */
    public static void get(File parentDir, String url, String revision, File targetDir) throws IOException {
        parentDir.mkdirs();
        run(parentDir, "checkout", "--non-interactive", "--force",
                url + "@" + revision, targetDir.getAbsolutePath());
    }

    /**
     * {@code svn checkout <url> <targetDir>} with no {@code @revision} suffix (defaults to
     * HEAD) -- used when a {@code [svn]} subrepo is declared in {@code .hgsub} but has never yet
     * been recorded in {@code .hgsubstate} (no pinned revision exists yet to check out).
     */
    public static void checkoutHead(File parentDir, String url, File targetDir) throws IOException {
        parentDir.mkdirs();
        run(parentDir, "checkout", "--non-interactive", "--force", url, targetDir.getAbsolutePath());
    }

    /** {@code svn update -r <revision>} -- real hg's post-commit working-copy resync in {@code
     * svnsubrepo.commit()}. */
    public static void update(File dir, String revision) throws IOException {
        run(dir, "update", "--non-interactive", "-r", revision);
    }

    /** {@code svn revert --recursive .} -- real hg's {@code svnsubrepo.get(overwrite=True)}
     * pre-step, exposed for symmetry with {@link GitSubrepoUtil} even though hg4j's own
     * checkout callers do not currently request the overwrite variant. */
    public static void revert(File dir) throws IOException {
        run(dir, "revert", "--recursive", ".");
    }

    /**
     * Mirrors real hg's {@code svnsubrepo.commit(text, user, date)} -- note real hg's own
     * comment that user/date are ignored "since svn is centralized" (the SVN server stamps the
     * commit with whatever local OS/svn-auth identity is configured, not anything hg tracks):
     * if the working copy has no local changes, just returns {@link #basestate}; aborts with
     * real hg's own message if the only changes are under an svn "external" or are missing
     * files; otherwise runs {@code svn commit -m <text>}, parses the new revision out of its
     * {@code "Committed revision N."} output, {@code svn update}s the working copy to it (real
     * hg does this explicitly -- a plain {@code commit} does not itself advance the local
     * checkout's revision, verified live), and returns that new revision.
     */
    public static String commit(File dir, String message, String url) throws IOException {
        WcStatus wc = wcChanged(dir);
        if (!wc.changed) {
            return basestate(dir, url);
        }
        if (wc.externalChanged) {
            throw new IOException("cannot commit svn externals");
        }
        if (wc.missing) {
            throw new IOException("cannot commit missing svn entries");
        }
        String out = run(dir, "commit", "--non-interactive", "-m", message == null ? "" : message);
        Matcher m = COMMITTED_REVISION.matcher(out);
        if (!m.find()) {
            if (out.trim().isEmpty()) {
                throw new IOException("failed to commit svn changes");
            }
            String[] lines = out.split("\n");
            throw new IOException(lines[lines.length - 1]);
        }
        String newRev = m.group(1);
        update(dir, newRev);
        return newRev;
    }

    /**
     * Mirrors real hg's {@code svnsubrepo.merge()} for the deterministic (non-interactive
     * default) case where a svn subrepo's pinned revision diverged between the two {@code hg
     * merge} parents -- read live from Mercurial 7.2's {@code subrepo.py} (backlog 41):
     * {@code merge()} only ever acts when the two prompt-choice branches it can take are hit,
     * and both route through {@code _updateprompt()}'s {@code ui.promptchoice(msg, 0)}, whose
     * non-interactive default is choice index 0 ("Local"). Since real hg's own {@code merge()}
     * body is literally {@code if _updateprompt(...): self.get(state, False)} and Python
     * evaluates index {@code 0} as falsy, the non-interactive default NEVER calls {@code get()}
     * -- real hg's default behavior for a diverged svn subrepo is a pure no-op that leaves the
     * working copy exactly as it was, deferring entirely to whatever the parent-level {@code
     * .hgsubstate} merge already decided to record (the local pin, per {@code
     * subrepoutil.submerge()}'s {@code sm[s] = l}). This method exists (mirroring {@link
     * GitSubrepoUtil#mergeDiverged}'s call site symmetry in {@code MergeCommand}) purely to
     * document that fact at the dispatch site -- it intentionally performs no svn operation.
     */
    public static void mergeDiverged(File svnDir, String remoteRev, String localRev) {
        // Intentionally a no-op -- see the class/method javadoc above.
    }

    private static Document parseXml(String xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IOException("Failed to parse svn XML output: " + e.getMessage(), e);
        }
    }

    private static String run(File cwd, String... args) throws IOException {
        // Note: --non-interactive is passed explicitly by the call sites above (mirroring real
        // hg's own _svncommand(), which only adds it for update/checkout/commit) rather than
        // injected here.
        List<String> cmd = new ArrayList<>();
        cmd.add("svn");
        Collections.addAll(cmd, args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd);
        Map<String, String> env = pb.environment();
        // Real hg's own _svncommand() forces LC_MESSAGES=C (English) while preserving LC_ALL for
        // everything else, because it parses English substrings out of svn's plain-text output
        // (e.g. "Committed revision N."). Verified live: this sandbox's default locale is
        // Korean, and without this the regex in commit() never matches.
        String lcAll = env.get("LC_ALL");
        if (lcAll != null) {
            env.put("LANG", lcAll);
            env.remove("LC_ALL");
        }
        env.put("LC_MESSAGES", "C");
        Process process = pb.start();
        process.getOutputStream().close(); // stdin closed, matching real hg's PIPE-with-no-input
        String output;
        try (InputStream is = process.getInputStream()) {
            output = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        String errOutput;
        try (InputStream es = process.getErrorStream()) {
            errOutput = new String(es.readAllBytes(), StandardCharsets.UTF_8);
        }
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("svn " + String.join(" ", args) + " was interrupted", e);
        }
        if (exitCode != 0) {
            String msg = !errOutput.trim().isEmpty() ? errOutput.trim() : output.trim();
            throw new IOException("svn " + String.join(" ", args) + " failed (exit " + exitCode + "): " + msg);
        }
        return output;
    }
}
