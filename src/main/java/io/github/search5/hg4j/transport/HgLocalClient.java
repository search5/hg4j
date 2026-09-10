package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.errors.HgLockException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.HgHook;
import io.github.search5.hg4j.api.PullCommand;
import io.github.search5.hg4j.bundle.Bundle2Parser;
import io.github.search5.hg4j.bundle.ChangegroupParser;
import io.github.search5.hg4j.errors.HgCorruptDataException;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.phase.PhaseRoots;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.util.SafeFileIO;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import io.github.search5.hg4j.errors.HgPushRacedException;
import io.github.search5.hg4j.api.FetchCommand;
import io.github.search5.hg4j.storage.SidedataCodec;
import io.github.search5.hg4j.treewalk.HgTreeFilter;
import io.github.search5.hg4j.treewalk.HgTreeFilter.NarrowPattern;

/**
 * Pure Java transport that provides a connection to a Mercurial repository on the local filesystem.
 * Directly parses and merges the remote repository without invoking a native hg subprocess.
 *
 * @apiNote Handles {@code file://} URLs (and bare local paths) from {@link
 *     HgRemoteConnectionFactory#createConnection}; used by {@code PullCommand}/{@code
 *     FetchCommand}/{@code PushCommand}/{@code BundleCommand}/{@code BranchesCommand} whenever
 *     the remote is a directory on the same filesystem rather than a networked server. Also
 *     serves as the local-clone server-side push apply path, implementing the same {@code
 *     check:heads} concurrent-push race detection ({@link
 *     io.github.search5.hg4j.errors.HgPushRacedException}) a real networked server would.
 */
public class HgLocalClient implements HgRemoteConnection {

    private final File repoDir;
    private final HgRepository remoteRepo;

    /**
     * Opens the local repository at the given path.
     *
     * @param path a {@code file://} URL or bare filesystem path to the remote repository's directory
     * @throws IOException if the repository at that location cannot be opened
     */
    public HgLocalClient(String path) throws IOException {
        String cleanPath = path.startsWith("file://") ? path.substring(7) : path;
        this.repoDir = new File(cleanPath);
        this.remoteRepo = new HgRepository(repoDir);
    }

    /**
     * Wraps an already-open repository instead of reopening one from a path — used by server-side
     * wire protocol dispatch ({@code transport.wireprotov1.Wire1Commands}), which already has a
     * live {@link HgRepository} for the request and reuses this class's bundle-building logic
     * (getbundle/changegroup/listkeys/pushkey) rather than duplicating it.
     *
     * @param repository already-open repository to serve as the remote endpoint
     */
    public HgLocalClient(HgRepository repository) {
        this.repoDir = repository.getDirectory();
        this.remoteRepo = repository;
    }

    @Override
    public List<String> getCapabilities() throws IOException {
        return List.of("changegroup", "getbundle", "lookup", "pushkey", "branchmap", "exp-narrow-1");
    }

    @Override
    public List<String> getHeads() throws IOException {
        File clIdx = new File(remoteRepo.getStoreDir(), "00changelog.i");
        File clDat = new File(remoteRepo.getStoreDir(), "00changelog.d");
        if (!clIdx.exists()) {
            return new ArrayList<>();
        }
        Revlog changelog = remoteRepo.getRevlog(clIdx, clDat);
        int count = changelog.getRevisionCount();
        if (count == 0) {
            return new ArrayList<>();
        }

        boolean[] isParent = new boolean[count];
        for (int i = 0; i < count; i++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(i);
            if (rec.getParent1() >= 0) isParent[rec.getParent1()] = true;
            if (rec.getParent2() >= 0) isParent[rec.getParent2()] = true;
        }

        List<String> heads = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (!isParent[i]) {
                heads.add(NodeIdUtil.toHex(changelog.getIndexRecord(i).getNodeId()));
            }
        }
        return heads;
    }

    /**
     * A revision is a "branch head" if it has no child on the SAME named branch (closed heads
     * included -- matches real hg's own {@code branchmap} wire command, which always passes
     * {@code closed=True}). This is the core of real hg's branch-head definition; it doesn't
     * replicate every nuance of {@code mercurial/branchmap.py} (e.g. its incremental-update
     * caching), only the observable result for a fully-materialized local changelog, which is
     * all {@link io.github.search5.hg4j.api.PushCommand}'s checkheads-style logic needs.
     */
    @Override
    public Map<String, List<String>> getBranchHeads() throws IOException {
        File clIdx = new File(remoteRepo.getStoreDir(), "00changelog.i");
        File clDat = new File(remoteRepo.getStoreDir(), "00changelog.d");
        Map<String, List<String>> result = new HashMap<>();
        if (!clIdx.exists()) {
            return result;
        }
        Revlog changelog = remoteRepo.getRevlog(clIdx, clDat);
        int count = changelog.getRevisionCount();
        if (count == 0) {
            return result;
        }

        String[] branchByRev = new String[count];
        for (int i = 0; i < count; i++) {
            branchByRev[i] = CommitCommand.getBranchOfRevision(changelog, i);
        }
        boolean[] hasSameBranchChild = new boolean[count];
        for (int i = 0; i < count; i++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(i);
            int p1 = rec.getParent1();
            int p2 = rec.getParent2();
            if (p1 >= 0 && branchByRev[p1].equals(branchByRev[i])) {
                hasSameBranchChild[p1] = true;
            }
            if (p2 >= 0 && branchByRev[p2].equals(branchByRev[i])) {
                hasSameBranchChild[p2] = true;
            }
        }
        for (int i = 0; i < count; i++) {
            if (!hasSameBranchChild[i]) {
                result.computeIfAbsent(branchByRev[i], b -> new ArrayList<>())
                        .add(NodeIdUtil.toHex(changelog.getIndexRecord(i).getNodeId()));
            }
        }
        return result;
    }

    @Override
    public byte[] getChangegroup(List<String> roots) throws IOException {
        return getBundle(roots, null, null);
    }

    @Override
    public byte[] getBundle(List<String> common, List<String> heads, List<String> bundleCaps) throws IOException {
        return getBundle(common, heads, bundleCaps, null);
    }

    /**
     * Whether this local ({@code file://}) peer role can serve real hg's narrow clone wire
     * arguments -- always {@code true}, since {@link #getBundle(List, List, List, NarrowScope)}
     * below implements the actual filelog filtering directly rather than needing a remote
     * extension.
     */
    @Override
    public boolean supportsNarrow() {
        return true;
    }

    /**
     * Same as {@link #getBundle(List, List, List)}, but when {@code narrowScope} is non-{@code
     * null}, omits filelog data for any path outside the given include/exclude narrowspec --
     * exactly what real hg's own non-ellipses narrow clone server does (per Mercurial 7.2's
     * {@code mercurial/changegroup.py} {@code cgpacker.generatefiles()}:
     * {@code changedfiles = [f for f in changedfiles if self._matcher(f) and not
     * self._oldmatcher(f)]}), and unlike this class's flat-manifest storage, does <em>not</em>
     * filter changelog or manifest revlog content at all -- those two are always sent in full,
     * matching real hg's own behavior for non-treemanifest repositories (a flat manifest revision
     * is one indivisible blob listing every tracked path; only the treemanifest per-directory
     * storage real hg optionally uses can prune individual subtrees via {@code matcher.visitdir()},
     * and hg4j doesn't implement treemanifest). This is what makes narrow clone/pull actually
     * reduce wire transfer size, instead of only filtering post-hoc, client-side, after
     * downloading everything.
     */
    public byte[] getBundle(List<String> common, List<String> heads, List<String> bundleCaps,
                             HgRemoteConnection.NarrowScope narrowScope) throws IOException {
        HgTreeFilter narrowFilter = null;
        if (narrowScope != null) {
            List<NarrowPattern> includes = new ArrayList<>();
            for (String p : narrowScope.includePatterns) {
                includes.add(HgTreeFilter.normalizeNarrowPattern(p));
            }
            List<NarrowPattern> excludes = new ArrayList<>();
            for (String p : narrowScope.excludePatterns) {
                excludes.add(HgTreeFilter.normalizeNarrowPattern(p));
            }
            narrowFilter = HgTreeFilter.createNarrowSpecFilter(includes, excludes);
        }
        File clIdx = new File(remoteRepo.getStoreDir(), "00changelog.i");
        File clDat = new File(remoteRepo.getStoreDir(), "00changelog.d");
        File mfIdx = new File(remoteRepo.getStoreDir(), "00manifest.i");
        File mfDat = new File(remoteRepo.getStoreDir(), "00manifest.d");

        // Must not return `new byte[0]` here (and also below at "startRev >= count") -- a real
        // hg client has no way to distinguish "a properly bundle-wrapped, genuinely empty
        // changegroup/bundle2 response" from "the stream got truncated" (HTTP path: aborts
        // immediately with "stream ended unexpectedly (got 0 bytes, expected 4)"; SSH raw-stream
        // path: hangs forever waiting for the 4-byte length header) -- this matters most when
        // cloning a freshly `hg init`ed repository with zero commits. Real hg's own
        // `exchange.getbundlechunks()` (mercurial/exchange.py) never returns early even when
        // there is nothing to send at all -- it always goes through the same chunk-generation
        // path, producing a valid changegroup that consists of nothing but the terminator chunk
        // that means "this group is empty". Simply letting execution continue below (the
        // following changelog/manifest/filelog loops just iterate zero times and finish cleanly
        // when count==0) lets `ChangegroupParser.writeBundle` produce that same valid,
        // terminator-only empty changegroup, which `Bundle2Parser.wrapChangegroupInBundle2`
        // then wraps in a proper HG20 envelope (for a bundle2 request), or which keeps its plain
        // "HG10UN" prefix as-is (for a legacy request) -- from the client's point of view, "a
        // remote with no commits" and "a remote that has commits but they're all already common,
        // so there's nothing new to send" look like exactly the same, valid, empty changegroup
        // (real hg itself doesn't distinguish the two either). `remoteRepo.getRevlog()` also
        // safely produces an empty Revlog with revisionCount=0, writing nothing to disk, even
        // when idxFile doesn't exist at all (for a plain v1 store; see `RevlogIndex`, whose v1
        // branch leaves everything at its default empty state with no side effects when the
        // file is absent).
        Revlog changelog = remoteRepo.getRevlog(clIdx, clDat);
        int count = changelog.getRevisionCount();

        // Calculate startRev based on common bases
        int startRev = 0;
        List<String> validCommon = new ArrayList<>();
        if (common != null) {
            for (String c : common) {
                if (c != null && !c.equals("0000000000000000000000000000000000000000")) {
                    validCommon.add(c);
                }
            }
        }

        if (!validCommon.isEmpty()) {
            boolean[] commonKnown = new boolean[count];
            for (String c : validCommon) {
                int rev = changelog.findRevision(NodeIdUtil.fromHex(c));
                if (rev != -1) {
                    commonKnown[rev] = true;
                }
            }
            // Propagate descendants/ancestors
            for (int i = count - 1; i >= 0; i--) {
                if (commonKnown[i]) {
                    Revlog.IndexRecord rec = changelog.getIndexRecord(i);
                    if (rec.getParent1() >= 0) commonKnown[rec.getParent1()] = true;
                    if (rec.getParent2() >= 0) commonKnown[rec.getParent2()] = true;
                }
            }
            int firstNewRev = count;
            for (int i = 0; i < count; i++) {
                if (!commonKnown[i]) {
                    firstNewRev = i;
                    break;
                }
            }
            startRev = firstNewRev;
        }

        // (This no longer returns `new byte[0]` early when startRev >= count here -- same
        // reason as the count==0 case just above. The common "no-op pull" case, where the
        // client is already up to date and there is nothing new to send, also goes through this
        // branch; the changelog/manifest/filelog loops below are all shaped as
        // `for (int r = startRev; r < count; r++)`, so when startRev>=count they simply iterate
        // zero times and finish, correctly producing an empty bundle.)

        // Negotiates the changegroup version the client actually requested from
        // bundleCaps (real spec, matches mercurial/exchange.py): if the client sent no
        // token starting with "HG2" at all (bundle2 not requested -- e.g. bundleCaps==null, or a
        // pure legacy call like changegroupsubset), the version is unconditionally "01" and the
        // response goes out as the bare cg1 chunk with no HG20 envelope. If bundle2 was
        // requested, this picks the maximum of the intersection between the
        // changegroup=01,02,... list the client sent inside its own bundle2= blob and the set of
        // versions hg4j can actually pack (falling back to "01", matching real hg, when the
        // intersection is empty or the list itself is absent), and the response is always
        // wrapped in an HG20 envelope (even if the version ends up being "01") -- see
        // Bundle2Parser#decodeChangegroupVersions/#requestsBundle2's own documentation.
        boolean usebundle2 = Bundle2Parser.requestsBundle2(bundleCaps);
        String version = "01";
        if (usebundle2) {
            List<String> requestedVersions = Bundle2Parser.decodeChangegroupVersions(bundleCaps);
            List<String> supportedOutgoingVersions = List.of("01", "02", "03", "04", "05");
            String best = null;
            for (String requested : requestedVersions) {
                if (supportedOutgoingVersions.contains(requested) && (best == null || requested.compareTo(best) > 0)) {
                    best = requested;
                }
            }
            if (best != null) {
                version = best;
            }
        }
        // Only sends SD_FILES sidedata with changelog entries when the version is cg5 (the only
        // version that can carry sidedata at all) AND this repository itself actually uses
        // changelog sidedata (exp-copies-sidedata-changeset) -- so that what is already written
        // for local commits round-trips through the getbundle response path too, without loss.
        boolean packChangelogSidedata = "05".equals(version) && remoteRepo.isSidedataCopies();

        // Build bundle
        ChangegroupParser.ChangegroupBundle bundle = new ChangegroupParser.ChangegroupBundle();
        bundle.changelogEntries = new ArrayList<>();
        bundle.manifestEntries = new ArrayList<>();
        bundle.fileGroups = new ArrayList<>();

        // 1a. Pack Changelogs
        // cg1 encodes each entry's delta against "whichever entry was packed immediately before
        // it in this group stream", not its "actual DAG parent (p1)" (matches
        // mercurial/changegroup.py's ChangeGroupPacker01, forcedeltaparentprev=True).
        // Building the delta against p1 in a multi-head repository disagrees with
        // both real hg's and hg4j's own unbundle logic, corrupting the content.
        // For an incremental pull (startRev > 0), the first new entry's base must be the
        // content of the last revision both sides already share in common (startRev-1) --
        // resetting to empty bytes would disagree with the receiving side's rev-1-based
        // reconstruction.
        byte[] prevClContent = (startRev > 0) ? changelog.getRevisionContent(startRev - 1) : new byte[0];
        // The explicit deltabase field, only actually read by cg2 and above -- simply declares
        // the node of "whichever entry was packed immediately before it in this group stream"
        // (just making explicit the same base the delta computation above already uses, with
        // the same resulting content as cg1's implicit rule). The first entry (startRev==0)
        // uses the all-zero (null revision) base.
        byte[] prevClNode = (startRev > 0) ? changelog.getIndexRecord(startRev - 1).getNodeId() : new byte[20];
        for (int r = startRev; r < count; r++) {
            Revlog.IndexRecord clRec = changelog.getIndexRecord(r);
            ChangegroupParser.ChangeGroupEntry clEntry = new ChangegroupParser.ChangeGroupEntry();
            clEntry.node = clRec.getNodeId();
            clEntry.p1 = (clRec.getParent1() != -1) ? changelog.getIndexRecord(clRec.getParent1()).getNodeId() : new byte[20];
            clEntry.p2 = (clRec.getParent2() != -1) ? changelog.getIndexRecord(clRec.getParent2()).getNodeId() : new byte[20];
            clEntry.cs = clRec.getNodeId();
            clEntry.deltabase = prevClNode;
            clEntry.flags = clRec.getFlags();

            byte[] content = changelog.getRevisionContent(r);
            clEntry.delta = Revlog.createDelta(prevClContent, content);
            if (packChangelogSidedata) {
                Map<Integer, byte[]> sidedata = changelog.getSidedata(r);
                if (sidedata != null && !sidedata.isEmpty()) {
                    clEntry.sidedata = SidedataCodec.serialize(sidedata);
                }
            }
            bundle.changelogEntries.add(clEntry);
            prevClContent = content;
            prevClNode = clRec.getNodeId();
        }

        // 1b. Pack Manifests
        Revlog manifest = remoteRepo.getRevlog(mfIdx, mfDat);
        Set<String> affectedFiles = new HashSet<>();
        // For an incremental pull, the base of the first new entry is the manifest content the
        // last shared changelog revision (startRev-1) points at (same reasoning as changelog
        // above).
        byte[] prevMfContent = new byte[0];
        // The explicit deltabase for cg2 and above (same reasoning as changelog's prevClNode
        // above) -- the node of the last manifest entry actually added to the bundle. The first
        // entry uses the all-zero (null revision) base.
        byte[] deltaBaseMfNode = new byte[20];
        if (startRev > 0) {
            byte[] prevClRaw = changelog.getRevisionContent(startRev - 1);
            String prevClText = new String(prevClRaw, StandardCharsets.UTF_8);
            int nl = prevClText.indexOf('\n');
            if (nl > 0) {
                byte[] prevMfNode = NodeIdUtil.fromHex(prevClText.substring(0, nl).trim().substring(0, 40));
                int prevMfRev = manifest.findRevision(prevMfNode);
                if (prevMfRev != -1) {
                    prevMfContent = manifest.getRevisionContent(prevMfRev);
                    deltaBaseMfNode = manifest.getIndexRecord(prevMfRev).getNodeId();
                }
            }
        }
        for (int r = startRev; r < count; r++) {
            byte[] clContent = changelog.getRevisionContent(r);
            String clText = new String(clContent, StandardCharsets.UTF_8);
            String[] clLines = clText.split("\n");
            for (int i = 3; i < clLines.length; i++) {
                String line = clLines[i].trim();
                if (line.isEmpty()) break;
                affectedFiles.add(line);
            }

            byte[] mfNode = NodeIdUtil.fromHex(clLines[0].trim().substring(0, 40));
            int mfRev = manifest.findRevision(mfNode);
            if (mfRev == -1) continue;

            Revlog.IndexRecord mfRec = manifest.getIndexRecord(mfRev);
            ChangegroupParser.ChangeGroupEntry mfEntry = new ChangegroupParser.ChangeGroupEntry();
            mfEntry.node = mfRec.getNodeId();
            mfEntry.p1 = (mfRec.getParent1() != -1) ? manifest.getIndexRecord(mfRec.getParent1()).getNodeId() : new byte[20];
            mfEntry.p2 = (mfRec.getParent2() != -1) ? manifest.getIndexRecord(mfRec.getParent2()).getNodeId() : new byte[20];
            mfEntry.cs = changelog.getIndexRecord(r).getNodeId();
            mfEntry.deltabase = deltaBaseMfNode;
            mfEntry.flags = mfRec.getFlags();

            byte[] content = manifest.getRevisionContent(mfRev);
            mfEntry.delta = Revlog.createDelta(prevMfContent, content);
            bundle.manifestEntries.add(mfEntry);
            prevMfContent = content;
            deltaBaseMfNode = mfRec.getNodeId();
        }

        // 1c. Pack Filelogs
        for (String path : affectedFiles) {
            if (narrowFilter != null && !narrowFilter.accept(path)) {
                // This is the actual bandwidth-saving step -- real hg's own
                // cgpacker.generatefiles() prunes exactly this way (see this method's javadoc).
                // Manifests/changelog above stay unfiltered on purpose.
                continue;
            }
            File flIdx = CommitCommand.getFilelogIndex(remoteRepo.getStoreDir(), path);
            File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
            if (!flIdx.exists()) continue;

            Revlog fl = remoteRepo.getRevlog(flIdx, flDat);
            List<ChangegroupParser.ChangeGroupEntry> flEntries = new ArrayList<>();

            // For an incremental pull, the base of the first new entry is the content of the
            // last already-shared filelog revision (the most recent one with linkRev < startRev).
            // Raw (as-stored) content throughout, not getRevisionContent(): a filelog revision
            // can be censored (Revlog.REVIDX_ISCENSORED), and bundling must transfer its
            // tombstone bytes as-is rather than throwing HgCensoredContentException -- real hg's
            // own changegroup packer likewise always uses rawdata()/`_chunk()`, never the decoded
            // text.
            byte[] prevFlContent = new byte[0];
            // The explicit deltabase for cg2 and above (same reasoning as changelog/manifest).
            // The first entry uses the all-zero (null revision) base.
            byte[] deltaBaseFlNode = new byte[20];
            for (int i = fl.getRevisionCount() - 1; i >= 0; i--) {
                if (fl.getIndexRecord(i).getLinkRev() < startRev) {
                    prevFlContent = fl.getRawRevisionContent(i);
                    deltaBaseFlNode = fl.getIndexRecord(i).getNodeId();
                    break;
                }
            }
            for (int i = 0; i < fl.getRevisionCount(); i++) {
                Revlog.IndexRecord flRec = fl.getIndexRecord(i);
                if (flRec.getLinkRev() >= startRev) {
                    ChangegroupParser.ChangeGroupEntry flEntry = new ChangegroupParser.ChangeGroupEntry();
                    flEntry.node = flRec.getNodeId();
                    flEntry.p1 = (flRec.getParent1() != -1) ? fl.getIndexRecord(flRec.getParent1()).getNodeId() : new byte[20];
                    flEntry.p2 = (flRec.getParent2() != -1) ? fl.getIndexRecord(flRec.getParent2()).getNodeId() : new byte[20];
                    flEntry.cs = changelog.getIndexRecord(flRec.getLinkRev()).getNodeId();
                    flEntry.deltabase = deltaBaseFlNode;
                    flEntry.flags = flRec.getFlags();

                    byte[] content = fl.getRawRevisionContent(i);
                    flEntry.delta = Revlog.createDelta(prevFlContent, content);
                    flEntries.add(flEntry);
                    prevFlContent = content;
                    deltaBaseFlNode = flRec.getNodeId();
                }
            }

            if (!flEntries.isEmpty()) {
                ChangegroupParser.FileGroup fg = new ChangegroupParser.FileGroup();
                fg.path = path;
                fg.entries = flEntries;
                bundle.fileGroups.add(fg);
            }
        }

        // Serialize to binary bytes at the negotiated version (01-05, not hardcoded cg1) via
        // ChangegroupParser.writeBundle.
        ByteArrayOutputStream cgOut = new ByteArrayOutputStream();
        ChangegroupParser.writeBundle(cgOut, bundle, version);
        byte[] cgBytes = cgOut.toByteArray();

        if (usebundle2) {
            // Real hg spec (matches exchange.getbundlechunks()'s usebundle2 branch in
            // mercurial/exchange.py): if the client requested bundle2, the response is always
            // wrapped in an HG20 envelope, even if the version ends up being "01".
            return Bundle2Parser.wrapChangegroupInBundle2(cgBytes, version);
        }

        // Legacy (non-bundle2) request -- keeps hg4j's own "HG10UN" file convention (the prefix
        // existing callers such as file:// itself and HgRemoteClient still expect;
        // Wire1Commands#stripHg10Prefix strips it before it actually goes out on the wire).
        // This path always has version "01" (see the negotiation logic above), so it's plain
        // cg1.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.write("HG10UN".getBytes(StandardCharsets.US_ASCII));
            dos.write(cgBytes);
        }
        return baos.toByteArray();
    }

    @Override
    public String push(byte[] bundleBytes, List<String> heads) throws IOException, HgLockException {
        return pushWithHooks(bundleBytes, heads, List.of(), List.of()).status;
    }

    /** Result of {@link #pushWithHooks}, reported back so wireprotocol server glue can tell an
     * empty/rejected push apart from a genuine import when firing hooks or building the pushres line. */
    public static class PushResult {
        /** Wire-protocol push status line (e.g. {@code "no changes found"} or an ok/pushres value). */
        public final String status;
        /** Hex node ids of the changesets actually imported by the push; empty if none were. */
        public final List<String> importedNodeHexes;

        /**
         * Creates a result pairing the wire-protocol status with the imported node ids.
         *
         * @param status wire-protocol push status line
         * @param importedNodeHexes hex node ids of the changesets actually imported
         */
        public PushResult(String status, List<String> importedNodeHexes) {
            this.status = status;
            this.importedNodeHexes = importedNodeHexes;
        }
    }

    /**
     * Same as {@link #push}, but also runs server-side {@link io.github.search5.hg4j.api.HgHook}
     * callbacks around applying the incoming changegroup -- real hg's {@code pretxnchangegroup}
     * (abort-capable, sees the pending node hexes before anything is written) and {@code
     * changegroup} (notification-only, sees the actually-imported node hexes) hooks. A pre-hook
     * returning {@code false} aborts before {@link io.github.search5.hg4j.api.PullCommand#applyBundle}
     * runs, so nothing lands.
     *
     * @param bundleBytes the raw pushed bundle (bundle1 changegroup, or an HG20/bundle2 envelope)
     * @param heads the pushing client's expected remote heads, used for the {@code check:heads}
     *              concurrent-push race check when the bundle itself carries no bundle2 {@code
     *              check:heads} part
     * @param preHooks hooks run, in order, before the changegroup is applied; any hook returning
     *                 {@code false} aborts the push with nothing written
     * @param postHooks hooks run, in order, after the changegroup has been applied
     * @return the push status together with the hex node ids of any changesets actually imported
     * @throws IOException if the bundle cannot be decoded or applying it fails
     * @throws HgLockException if the store/working-copy locks cannot be acquired
     */
    public PushResult pushWithHooks(byte[] bundleBytes, List<String> heads,
                                     List<HgHook> preHooks,
                                     List<HgHook> postHooks) throws IOException, HgLockException {
        if (bundleBytes == null || bundleBytes.length == 0) {
            return new PushResult("no changes found", List.of());
        }

        // Backup remote dirstate before push to preserve bare status
        File dirstateFile = new File(remoteRepo.getDirectory(), ".hg/dirstate");
        byte[] dirstateBackup = dirstateFile.exists() ? Files.readAllBytes(dirstateFile.toPath()) : null;

        byte[] changegroupBytes = bundleBytes;
        String cgVersion = "01";
        // If the incoming bundle2 envelope carries a
        // `check:heads` part, it's the authoritative source for what the pushing client computed
        // its push against -- captured here (bundle2-only) and used to build the post-lock race
        // validator below, alongside the plain wire `heads` arg for non-bundle2 pushes.
        Bundle2Parser.ExtractedBundle2 extracted = null;
        if (bundleBytes.length >= 4 && bundleBytes[0] == 'H' && bundleBytes[1] == 'G' && bundleBytes[2] == '2' && bundleBytes[3] == '0') {
            // Now that Wire1Commands.capabilitiesString() advertises bundle2=
            // (to make getbundle version negotiation possible), a real hg client's push no
            // longer sends bare cg1 bytes either -- it wraps the body in an HG20/bundle2
            // envelope (mercurial/exchange.py's _pushbundle2), because exchange._forcebundle1
            // simply follows remote.capable('bundle2'). Bundle2Parser already has a utility
            // for parsing this envelope (originally meant for the client side reading a
            // getbundle response), reused here as-is.
            extracted = Bundle2Parser.extractChangegroupDetailed(new ByteArrayInputStream(bundleBytes));
            changegroupBytes = extracted.changegroupBytes;
            cgVersion = extracted.cgVersion;
        } else if (bundleBytes.length >= 6 && bundleBytes[0] == 'H' && bundleBytes[1] == 'G' && bundleBytes[2] == '1' && bundleBytes[3] == '0') {
            String comp = new String(bundleBytes, 4, 2, StandardCharsets.US_ASCII);
            ByteArrayInputStream bais = new ByteArrayInputStream(bundleBytes, 6, bundleBytes.length - 6);
            if ("UN".equals(comp)) {
                changegroupBytes = bais.readAllBytes();
            } else {
                throw new HgCorruptDataException("Unsupported compression format in local push: HG10" + comp);
            }
        }

        try (ByteArrayInputStream bais = new ByteArrayInputStream(changegroupBytes)) {
            ChangegroupParser.ChangegroupBundle bundle =
                    ChangegroupParser.parseBundle(bais, cgVersion);

            List<String> pendingNodeHexes = new ArrayList<>();
            for (ChangegroupParser.ChangeGroupEntry entry : bundle.changelogEntries) {
                pendingNodeHexes.add(NodeIdUtil.toHex(entry.node));
            }
            if (!preHooks.isEmpty()) {
                Map<String, Object> preCtx = new HashMap<>();
                preCtx.put("repository", remoteRepo);
                preCtx.put("nodes", pendingNodeHexes);
                for (HgHook hook : preHooks) {
                    if (!hook.run(preCtx)) {
                        throw new IOException("push rejected by pre-changegroup hook");
                    }
                }
            }

            // Apply bundle natively to remoteRepo using transactional API of PullCommand.
            // This is the SERVER direction of a concurrent push (Wire1Commands's
            // unbundle -- HTTP and SSH both -- and the file:// local-peer role reach this exact
            // same code path). Real hg's own server-side unbundle apply (mercurial/exchange.py's
            // unbundle(): `with repo.lock(), repo.transaction(...)`) waits for the target repo's
            // store lock (repo.lock() default wait=True, timeout from ui.timeout -- 600s default)
            // rather than failing on the very first contended attempt. hg4j's own equivalent
            // (this pullApi.applyBundle call) used to lock with timeoutMs=0 (immediate fail-fast)
            // unconditionally -- passing the repository's own resolvePushLockTimeoutMs() (mirrors
            // ui.timeout) here makes it wait like real hg's does, while every OTHER
            // lockStore()/lockWorkingCopy() caller (commit, update, rebase, ...) is intentionally
            // left untouched -- see HgRepository#lockStore(int)'s doc.
            PullCommand pullApi = new PullCommand(remoteRepo);
            int lockTimeoutMs = remoteRepo.resolvePushLockTimeoutMs();
            FetchCommand.PostLockValidator raceCheck = buildPushRaceValidator(
                    extracted != null ? extracted.checkHeadsRaw : null, heads);
            List<byte[]> imported = pullApi.applyBundle(bundle, lockTimeoutMs, raceCheck);

            // Restore remote dirstate to preserve bare repo status
            if (dirstateBackup != null) {
                Files.write(dirstateFile.toPath(), dirstateBackup);
            } else {
                Files.deleteIfExists(dirstateFile.toPath());
            }

            List<String> importedNodeHexes = new ArrayList<>();
            for (byte[] node : imported) {
                importedNodeHexes.add(NodeIdUtil.toHex(node));
            }
            if (!postHooks.isEmpty()) {
                Map<String, Object> postCtx = new HashMap<>();
                postCtx.put("repository", remoteRepo);
                postCtx.put("nodes", importedNodeHexes);
                for (HgHook hook : postHooks) {
                    hook.run(postCtx);
                }
            }

            return new PushResult("push successful, imported " + imported.size() + " changesets natively", importedNodeHexes);
        }
    }

    /** Wire encoding of real hg's own {@code "hashed"} sentinel byte string, used as the FIRST
     * element of a 2-element hashed-heads wire value -- see {@link
     * io.github.search5.hg4j.util.NodeIdUtil#computeUnbundleHeadsWireValue}'s doc and {@code
     * mercurial/exchange.py}'s {@code if heads != [b'force'] and self.capable(b'unbundlehash')}. */
    private static final String HASHED_SENTINEL_HEX = NodeIdUtil.toHex("hashed".getBytes(StandardCharsets.US_ASCII));

    /** Wire encoding of real hg's own force-push sentinel -- real hg's {@code
     * wireprototypes.encodelist()} hex-encodes {@code [b'force']} exactly like a genuine head
     * list (no special-casing), so the wire value a real client OR hg4j's own {@link
     * io.github.search5.hg4j.transport.HgRemoteClient}/{@link io.github.search5.hg4j.transport.HgSshClient}
     * (via {@link io.github.search5.hg4j.util.NodeIdUtil#computeUnbundleHeadsWireValue}) actually
     * sends is {@code hex(b'force')}, not the bare ASCII word -- see that method's doc. The bare
     * word is ALSO accepted below because {@link #push}/{@link #pushWithHooks} is reached directly
     * (no wire encoding at all) for the {@code file://} local-peer role, where {@link
     * io.github.search5.hg4j.api.PushCommand} passes the literal {@code "force"} straight through. */
    private static final String FORCE_SENTINEL_HEX = NodeIdUtil.toHex("force".getBytes(StandardCharsets.US_ASCII));

    /**
     * Builds the server-side push-race-re-check validator
     * {@link FetchCommand#applyBundle(io.github.search5.hg4j.bundle.ChangegroupParser.ChangegroupBundle, int, FetchCommand.PostLockValidator)}
     * runs immediately after the store/working-copy locks are acquired, to reject a push whose
     * target heads changed underneath it since the client computed the push -- exactly what real
     * hg's own {@code error.PushRaced} guards against (see {@code
     * mercurial/bundle2_part_handlers.py}'s {@code handlecheckheads()} for the bundle2 case this
     * mirrors, and {@code mercurial/exchange.py}'s {@code check_heads()} for the legacy bundle1
     * wire-argument case).
     *
     * <p>Priority, matching which mechanism the pushing client actually used:
     * <ol>
     *   <li>A bundle2 {@code check:heads} part ({@code checkHeadsPartRaw} non-null) is
     *       authoritative when present -- real hg's client only sends it for a non-{@code --force}
     *       push that has something to push, so its mere presence means "please check this".</li>
     *   <li>Otherwise, the legacy {@code heads=} wire argument ({@code wireHeads}) is used, unless
     *       it is empty (real hg4j's own {@link io.github.search5.hg4j.api.PushCommand} omits the
     *       argument entirely for a brand-new, currently-headless remote -- indistinguishable on
     *       the wire from "no race info sent at all", so this is conservatively treated as "skip"
     *       rather than risk rejecting a legitimate first push) or is exactly {@code ["force"]}
     *       (real hg's own force-push sentinel, {@code mercurial/exchange.py}'s {@code if
     *       pushop.force: remoteheads = [b'force']} -- see {@code PushCommand#call()}'s matching
     *       send-side fix) or the 2-element {@code ["hashed", <sha1-hex>]} form (real hg's {@code
     *       unbundlehash} bandwidth optimization for very large head counts -- hg4j's own server
     *       never advertises that capability today, so no client should ever actually send this
     *       to hg4j, but the check is implemented for source fidelity in case that changes).</li>
     * </ol>
     *
     * @return {@code null} if no race check applies (nothing to compare against, or the push was
     *         forced); otherwise a validator that throws {@link HgPushRacedException} on mismatch.
     */
    private FetchCommand.PostLockValidator buildPushRaceValidator(List<byte[]> checkHeadsPartRaw, List<String> wireHeads) {
        if (checkHeadsPartRaw != null) {
            List<String> expectedHex = new ArrayList<>(checkHeadsPartRaw.size());
            for (byte[] node : checkHeadsPartRaw) {
                expectedHex.add(NodeIdUtil.toHex(node));
            }
            return () -> validatePushNotRaced(expectedHex);
        }
        // Defensive sanitization: PushCommand itself already treats a null entry or the all-zero
        // "no real head" sentinel node as "not a real head" when computing its OWN local
        // validRemoteHeads (see PushCommand#call()) -- but the RAW, unfiltered head list is what
        // actually goes out over the wire as this heads= argument. Now that this argument feeds
        // the race check, the same filtering is applied here so a stray null/sentinel entry can't
        // either NPE or be compared against a real head hex.
        List<String> sanitized = sanitizeWireHeads(wireHeads);
        if (sanitized.isEmpty()) {
            return null;
        }
        if (sanitized.size() == 1
                && ("force".equalsIgnoreCase(sanitized.get(0)) || FORCE_SENTINEL_HEX.equalsIgnoreCase(sanitized.get(0)))) {
            return null;
        }
        if (sanitized.size() == 2 && HASHED_SENTINEL_HEX.equalsIgnoreCase(sanitized.get(0))) {
            String expectedDigestHex = sanitized.get(1);
            return () -> validatePushNotRacedHashed(expectedDigestHex);
        }
        return () -> validatePushNotRaced(sanitized);
    }

    private static final String NULL_NODE_HEX = "0".repeat(40);

    private static List<String> sanitizeWireHeads(List<String> wireHeads) {
        if (wireHeads == null) {
            return List.of();
        }
        List<String> sanitized = new ArrayList<>(wireHeads.size());
        for (String h : wireHeads) {
            if (h != null && !h.isEmpty() && !NULL_NODE_HEX.equalsIgnoreCase(h)) {
                sanitized.add(h);
            }
        }
        return sanitized;
    }

    /** Real hg's own message, verbatim ({@code mercurial/bundle2_part_handlers.py}'s {@code
     * handlecheckheads()}: {@code b'remote repository changed while pushing - please try again'}). */
    private static final String PUSH_RACED_MESSAGE = "remote repository changed while pushing - please try again";

    private void validatePushNotRaced(List<String> expectedHeadHex) throws IOException {
        TreeSet<String> expected = new TreeSet<>();
        for (String h : expectedHeadHex) {
            expected.add(h.toLowerCase());
        }
        TreeSet<String> current = new TreeSet<>();
        for (String h : new HgLocalClient(remoteRepo).getHeads()) {
            current.add(h.toLowerCase());
        }
        if (!expected.equals(current)) {
            throw new HgPushRacedException(PUSH_RACED_MESSAGE);
        }
    }

    private void validatePushNotRacedHashed(String expectedDigestHex) throws IOException {
        List<String> currentHex = new ArrayList<>(new HgLocalClient(remoteRepo).getHeads());
        // Hex-string order matches the underlying unsigned-byte order (each byte maps to exactly
        // two hex digits), so sorting hex strings here is equivalent to real hg's own
        // `sorted(heads)` over the raw 20-byte node ids -- no need to decode back to bytes first.
        currentHex.sort(String::compareTo);
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            for (String h : currentHex) {
                sha1.update(NodeIdUtil.fromHex(h));
            }
            String actualDigestHex = NodeIdUtil.toHex(sha1.digest());
            if (!actualDigestHex.equalsIgnoreCase(expectedDigestHex)) {
                throw new HgPushRacedException(PUSH_RACED_MESSAGE);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 unavailable for push-race hashed-heads check", e);
        }
    }

    @Override
    public Map<String, String> listKeys(String namespace) throws IOException {
        Map<String, String> map = new HashMap<>();
        if ("bookmarks".equals(namespace)) {
            File bkFile = new File(remoteRepo.getHgDir(), "bookmarks");
            if (bkFile.exists()) {
                List<String> lines = Files.readAllLines(bkFile.toPath(), StandardCharsets.UTF_8);
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    int spaceIdx = line.indexOf(' ');
                    if (spaceIdx != -1) {
                        String node = line.substring(0, spaceIdx).trim();
                        String name = line.substring(spaceIdx + 1).trim();
                        map.put(name, node);
                    }
                }
            }
        } else if ("phases".equals(namespace)) {
            PhaseRoots phaseRoots = remoteRepo.getPhaseRoots();
            File clIdx = new File(remoteRepo.getStoreDir(), "00changelog.i");
            File clDat = new File(remoteRepo.getStoreDir(), "00changelog.d");
            if (clIdx.exists()) {
                Revlog cl = remoteRepo.getRevlog(clIdx, clDat);
                for (int i = 0; i < cl.getRevisionCount(); i++) {
                    byte[] nodeBytes = cl.getIndexRecord(i).getNodeId();
                    NodeId nodeId = new NodeId(nodeBytes);
                    PhaseRoots.Phase phase = phaseRoots.getPhase(nodeId, cl);
                    if (phase != PhaseRoots.Phase.PUBLIC) {
                        map.put(NodeIdUtil.toHex(nodeBytes), String.valueOf(phase.getValue()));
                    }
                }
            }
        }
        return map;
    }

    @Override
    public boolean pushkey(String namespace, String key, String oldVal, String newVal) throws IOException {
        if ("bookmarks".equals(namespace)) {
            File bkFile = new File(remoteRepo.getHgDir(), "bookmarks");
            Map<String, String> bks = listKeys("bookmarks");
            String currentVal = bks.getOrDefault(key, "");
            if (oldVal == null) oldVal = "";
            if (newVal == null) newVal = "";
            
            if (currentVal.equals(oldVal)) {
                if (newVal.isEmpty()) {
                    bks.remove(key);
                } else {
                    bks.put(key, newVal);
                }
                
                // Write back bookmarks to file
                if (bks.isEmpty()) {
                    if (bkFile.exists()) bkFile.delete();
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, String> entry : bks.entrySet()) {
                        sb.append(entry.getValue()).append(" ").append(entry.getKey()).append("\n");
                    }
                    SafeFileIO.writeStringAtomic(bkFile, sb.toString());
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        // No resource release required
    }
}
