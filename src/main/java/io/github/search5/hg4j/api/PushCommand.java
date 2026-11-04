package io.github.search5.hg4j.api;

import io.github.search5.hg4j.bundle.Bundle2;
import io.github.search5.hg4j.bundle.ChangegroupParser;
import io.github.search5.hg4j.bundle.Bundle2Parser;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.transport.HgRemoteConnection;
import io.github.search5.hg4j.transport.HgRemoteConnectionFactory;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.errors.HgLockException;

import io.github.search5.hg4j.obsolete.HgObsMarker;
import io.github.search5.hg4j.obsolete.HgObsolescenceParser;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.phase.PhaseRoots;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import io.github.search5.hg4j.storage.SidedataCodec;
import java.util.TreeSet;

/**
 * Porcelain command to push local commits to a remote Mercurial repository.
 * Compiles a dynamic binary changegroup bundle of new revisions and transfers it securely.
 */
public class PushCommand {
    private static final Logger LOGGER = Logger.getLogger(PushCommand.class.getName());

    /** What a pushing client tells the server it can understand in the reply (the "replycaps" part). */
    private static final String PUSH_REPLY_CAPS =
            "HG20\nchangegroup=01,02,03,04,05\nerror=abort,unsupportedcontent,pushraced,pushkey\npushkey\n";

    private final HgRepository repository;
    private String destinationUrl;
    private boolean force = false;
    private boolean allowNewBranch = false;
    /** {@code hg push -B}: the bookmarks named explicitly. */
    private final List<String> explicitBookmarks = new ArrayList<>();
    /** The exit status of the bookmark part of the last push, as in Mercurial: 0 fine, 1 a bookmark update failed, 2 refused. */
    private int bookmarkResult;
    /** The revisions this push sends ({@code outgoing}): ancestors of what is pushed, minus what the remote has, minus secret. */
    private boolean[] outgoing;
    /** With -B: the ancestors of the named bookmarks (what Mercurial calls {@code pushed}); {@code null} when unrestricted. */
    private boolean[] pushedAncestors;
    /** The revisions the remote already has (known locally). */
    private boolean[] remoteHas;

    private final List<HgHook> prePushHooks = new ArrayList<>();
    private final List<HgHook> postPushHooks = new ArrayList<>();

    /** {@code hg push --force}: bypasses the "creates new remote head(s)" safety check below
     * (real hg: {@code pushop.force}, skips {@code discovery.checkheads()} entirely). Does NOT
     * imply {@link #setAllowNewBranch} in real hg, but in practice a force push also always
     * succeeds against the new-branch check below since {@code force} short-circuits the whole
     * checkheads pass, matching real hg exactly. */
    public PushCommand setForce(boolean force) {
        this.force = force;
        return this;
    }

    /** {@code hg push --new-branch}: permits pushing changesets on a named branch the remote
     * doesn't have yet (real hg: {@code pushop.newbranch}). Without it, such a push aborts with
     * "push creates new remote branches: ..." -- matches real hg's {@code discovery.checkheads()}. */
    public PushCommand setAllowNewBranch(boolean allowNewBranch) {
        this.allowNewBranch = allowNewBranch;
        return this;
    }

    /**
     * {@code hg push -B NAME ...}: pushes the bookmark (creating it on the remote if the remote lacks it, moving it to where
     * the local one is, or deleting it remotely when it no longer exists locally) together with the changesets it needs. Only
     * the ancestors of the named bookmarks are pushed then, not the other heads.
     */
    public PushCommand setBookmarks(String... names) {
        explicitBookmarks.clear();
        if (names != null) {
            explicitBookmarks.addAll(List.of(names));
        }
        return this;
    }

    /** The status of the bookmark part of the last {@link #call()}: 0 fine, 1 a bookmark update failed, 2 a bookmark was refused. */
    public int getBookmarkResult() {
        return bookmarkResult;
    }

    public PushCommand registerPrePushHook(HgHook hook) {
        if (hook != null) {
            prePushHooks.add(hook);
        }
        return this;
    }

    public PushCommand registerPostPushHook(HgHook hook) {
        if (hook != null) {
            postPushHooks.add(hook);
        }
        return this;
    }

    public PushCommand(HgRepository repository) {
        this.repository = repository;
    }

    public PushCommand setDestination(String destinationUrl) {
        this.destinationUrl = destinationUrl;
        return this;
    }

    /** Receives the progress of the LFS uploads and the lines Mercurial prints about them (see {@code HgLfsProgress}). */
    public PushCommand setProgressMonitor(io.github.search5.hg4j.lib.ProgressMonitor monitor) {
        this.monitor = monitor;
        return this;
    }

    private io.github.search5.hg4j.lib.ProgressMonitor monitor;

    private boolean sourceLocked;
    private final List<String> messages = new ArrayList<>();

    /** What the push printed besides its result (e.g. {@code cannot lock source repo, skipping local public phase update}). */
    public List<String> getMessages() {
        return messages;
    }

    /**
     * {@code lock = pushop.repo.lock()}: waits for {@code ui.timeout}. When the source repository cannot be locked the push is not
     * aborted ({@code except error.LockUnavailable}): only the local phase update is skipped. An abandoned transaction is not
     * "unavailable": it still aborts.
     */
    private HgLock lockSourceRepository() throws HgLockException {
        HgLock wlock = null;
        try {
            // "bundle2 push may receive a reply bundle touching bookmarks requiring the wlock. Take it now to ensure proper ordering"
            if (isTrue(repository.getConfig().get("experimental", "bundle2.pushback", "false"))) {
                wlock = repository.lockWorkingCopy(repository.resolvePushLockTimeoutMs());
            }
            HgLock lock = repository.lockStore(repository.resolvePushLockTimeoutMs());
            sourceLocked = true;
            return wlock == null ? lock : new PairLock(lock, wlock);
        } catch (io.github.search5.hg4j.errors.HgAbandonedTransactionException e) {
            releaseQuietly(wlock);
            throw e;
        } catch (HgLockException e) {
            releaseQuietly(wlock);
            sourceLocked = false;
            return HgLock.noOp();
        }
    }

    /** The store lock and the working copy lock taken before it; released in the order of `lockmod.release(trmanager, lock, wlock)`. */
    private static final class PairLock extends HgLock {
        private final HgLock store;
        private final HgLock working;

        PairLock(HgLock store, HgLock working) {
            super();
            this.store = store;
            this.working = working;
        }

        @Override
        public void close() throws IOException {
            try {
                store.close();
            } finally {
                working.close();
            }
        }
    }

    private static void releaseQuietly(HgLock lock) {
        if (lock != null) {
            try {
                lock.close();
            } catch (IOException ignored) {
                // nothing more can be done about a lock that does not release
            }
        }
    }

    private static boolean isTrue(String value) {
        return value != null && java.util.Set.of("1", "yes", "true", "on", "always").contains(value.trim().toLowerCase());
    }

    /**
     * {@code _pushsyncphase} for the local repository: what the remote has now (the changesets just pushed, and those it had) is public
     * here too when the remote is publishing, otherwise what is not under one of its draft roots. Without the lock the phases are left
     * alone and the user is told ({@code _localphasemove}).
     */
    private void syncLocalPhases(HgRemoteConnection client, Revlog changelog, int count, boolean[] ancestorsOfWanted, boolean[] send,
                                 boolean[] remoteKnown, boolean pushed) throws IOException {
        List<Integer> common = new ArrayList<>();
        boolean[] hasChild = new boolean[count];
        for (int r = 0; r < count; r++) {
            if (ancestorsOfWanted[r] && (send[r] || remoteKnown[r])) {
                Revlog.IndexRecord rec = changelog.getIndexRecord(r);
                if (rec.getParent1() >= 0) hasChild[rec.getParent1()] = true;
                if (rec.getParent2() >= 0) hasChild[rec.getParent2()] = true;
            }
        }
        for (int r = 0; r < count; r++) {
            if (ancestorsOfWanted[r] && (send[r] || remoteKnown[r]) && !hasChild[r]) {
                common.add(r);
            }
        }
        Map<String, String> remotePhases;
        try {
            remotePhases = client.listKeys("phases");
        } catch (Exception noPhases) {
            remotePhases = null; // an old server: the reply is read as "no phase information", everything pushed is public
        }
        boolean publishing = remotePhases == null || remotePhases.isEmpty()
                || (remotePhases.containsKey("publishing") && !remotePhases.get("publishing").isEmpty());
        List<Integer> toPublic = common;
        if (!publishing) {
            List<Integer> draftRoots = new ArrayList<>();
            for (Map.Entry<String, String> entry : remotePhases.entrySet()) {
                if (entry.getKey().length() == 40 && Integer.parseInt(entry.getValue().trim()) == PhaseRoots.Phase.DRAFT.getValue()) {
                    int rev = changelog.findRevision(NodeIdUtil.fromHex(entry.getKey()));
                    if (rev >= 0) {
                        draftRoots.add(rev);
                    }
                }
            }
            toPublic = FetchCommand.publicHeads(changelog, common, draftRoots);
        }
        PhaseRoots phaseRoots = repository.getPhaseRoots();
        // `_pushdiscoveryphase` (before anything moves): to a remote that is not publishing, the pushed changesets that are already public
        // here have to be published there as well; the heads of those are what the remote is told about
        List<Integer> outdated = new ArrayList<>();
        if (!publishing && !pushed) {
            // nothing was pushed (`fallbackoutdatedphases`): the remote draft roots that are public here, and what lies between them and the
            // common heads, are published there
            List<Integer> fallbackRoots = new ArrayList<>();
            for (Map.Entry<String, String> entry : remotePhases.entrySet()) {
                if (entry.getKey().length() == 40 && Integer.parseInt(entry.getValue().trim()) == PhaseRoots.Phase.DRAFT.getValue()) {
                    int rev = changelog.findRevision(NodeIdUtil.fromHex(entry.getKey()));
                    if (rev >= 0 && phaseRoots.getPhase(new NodeId(changelog.getIndexRecord(rev).getNodeId()), changelog) != PhaseRoots.Phase.DRAFT
                            && phaseRoots.getPhase(new NodeId(changelog.getIndexRecord(rev).getNodeId()), changelog) != PhaseRoots.Phase.SECRET) {
                        fallbackRoots.add(rev);
                    }
                }
            }
            if (!fallbackRoots.isEmpty()) {
                boolean[] belowHeads = new boolean[count];
                for (int head : common) {
                    java.util.ArrayDeque<Integer> work = new java.util.ArrayDeque<>();
                    work.add(head);
                    while (!work.isEmpty()) {
                        int r = work.poll();
                        if (r < 0 || belowHeads[r]) {
                            continue;
                        }
                        belowHeads[r] = true;
                        work.add(changelog.getIndexRecord(r).getParent1());
                        work.add(changelog.getIndexRecord(r).getParent2());
                    }
                }
                boolean[] inRange = new boolean[count];
                for (int r = 0; r < count; r++) {
                    Revlog.IndexRecord rec = changelog.getIndexRecord(r);
                    boolean fromRoot = fallbackRoots.contains(r)
                            || (rec.getParent1() >= 0 && inRange[rec.getParent1()] && belowHeads[rec.getParent1()])
                            || (rec.getParent2() >= 0 && inRange[rec.getParent2()] && belowHeads[rec.getParent2()]);
                    inRange[r] = fromRoot && belowHeads[r];
                }
                boolean[] rangeHasChild = new boolean[count];
                for (int r = 0; r < count; r++) {
                    if (inRange[r] && phaseRoots.getPhase(new NodeId(changelog.getIndexRecord(r).getNodeId()), changelog) == PhaseRoots.Phase.PUBLIC) {
                        Revlog.IndexRecord rec = changelog.getIndexRecord(r);
                        if (rec.getParent1() >= 0) rangeHasChild[rec.getParent1()] = true;
                        if (rec.getParent2() >= 0) rangeHasChild[rec.getParent2()] = true;
                    }
                }
                for (int r = 0; r < count; r++) {
                    if (inRange[r] && !rangeHasChild[r] && !toPublic.contains(r)
                            && phaseRoots.getPhase(new NodeId(changelog.getIndexRecord(r).getNodeId()), changelog) == PhaseRoots.Phase.PUBLIC) {
                        outdated.add(r);
                    }
                }
            }
        }
        if (!publishing && pushed) {
            boolean[] published = new boolean[count];
            for (int r = 0; r < count; r++) {
                published[r] = send[r] && phaseRoots.getPhase(new NodeId(changelog.getIndexRecord(r).getNodeId()), changelog)
                        == PhaseRoots.Phase.PUBLIC;
            }
            boolean[] publishedHasChild = new boolean[count];
            for (int r = 0; r < count; r++) {
                if (published[r]) {
                    Revlog.IndexRecord rec = changelog.getIndexRecord(r);
                    if (rec.getParent1() >= 0) publishedHasChild[rec.getParent1()] = true;
                    if (rec.getParent2() >= 0) publishedHasChild[rec.getParent2()] = true;
                }
            }
            for (int r = 0; r < count; r++) {
                if (published[r] && !publishedHasChild[r] && !toPublic.contains(r)) {
                    outdated.add(r);
                }
            }
        }
        boolean wouldMove = false;
        for (int head : toPublic) {
            NodeId node = new NodeId(changelog.getIndexRecord(head).getNodeId());
            if (phaseRoots.getPhase(node, changelog) == PhaseRoots.Phase.PUBLIC) {
                continue;
            }
            wouldMove = true;
            if (sourceLocked) {
                Hg.wrap(repository).phase().setRevision(NodeIdUtil.toHex(node.getBytes())).setPhase(0).call();
            }
        }
        if (wouldMove && !sourceLocked) {
            messages.add("cannot lock source repo, skipping local public phase update\n");
        }
        for (int rev : outdated) {
            String hex = NodeIdUtil.toHex(changelog.getIndexRecord(rev).getNodeId());
            if (!client.pushkey("phases", hex, "1", "0")) {
                messages.add("updating " + hex.substring(0, 12) + " to public failed!\n");
            }
        }
    }

    public String call() throws IOException, HgLockException {
        messages.clear();
        try (io.github.search5.hg4j.lfs.HgLfsProgress bound = io.github.search5.hg4j.lfs.HgLfsProgress.bind(monitor)) {
            return callPush();
        }
    }

    private String callPush() throws IOException, HgLockException {
        // 실제 hg 스펙(hg help urls): 목적지를 안 주면 paths.default-push를 우선 쓰고,
        // 없으면 paths.default로 폴백한다 — 2026-09-01 이전에는 여기서 무조건 예외를
        // 던져서 "그냥 hg push" 형태가 지원이 안 됐다.
        String effectiveDest = destinationUrl;
        if (effectiveDest == null || effectiveDest.isEmpty()) {
            effectiveDest = repository.getConfig().getPath("default-push");
            if (effectiveDest == null || effectiveDest.isEmpty()) {
                effectiveDest = repository.getConfig().getPath("default");
            }
        }
        if (effectiveDest == null || effectiveDest.isEmpty()) {
            throw new IllegalStateException("Remote destination URL must be specified.");
        }

        String resolvedUrl = effectiveDest;
        if (!effectiveDest.contains("://")) {
            String configPath = repository.getConfig().getPath(effectiveDest);
            if (configPath != null) {
                resolvedUrl = configPath;
            }
        }

        // PRE_PUSH hooks trigger
        if (!prePushHooks.isEmpty()) {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("destinationUrl", resolvedUrl);
            ctx.put("repository", repository);
            for (HgHook hook : prePushHooks) {
                if (!hook.run(ctx)) {
                    throw new HgValidationException("Pre-push hook execution rejected the push action");
                }
            }
        }

        // Like real hg's exchange.push, the store lock is taken once the connection is up and before anything is discovered, and
        // released after the last step (changesets, phases, bookmarks) has finished.
        try (HgRemoteConnection client = HgRemoteConnectionFactory.createConnection(resolvedUrl, repository.getConfig());
             HgLock storeLock = lockSourceRepository()) {
            List<String> remoteHeads = client.getHeads();

            // Like real hg's wrapped exchange.push: a repository that holds LFS files can only go to a remote that has the lfs
            // extension (a local destination is checked by the requirements comparison instead).
            if (repository.getRequirements().contains("lfs")
                    && !(client instanceof io.github.search5.hg4j.transport.HgLocalClient)
                    && !client.getCapabilities().contains("lfs")) {
                throw new HgValidationException("required features are not supported in the destination: lfs\n"
                        + "(enable the lfs extension on the server)");
            }

            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");

            // Backlog item 38: mirrors real hg's own client-side push locking its SOURCE repo
            // (mercurial/exchange.py's push(): `lock = pushop.repo.lock()`, default wait=True,
            // waiting up to ui.timeout -- 600s default -- rather than aborting on the very first
            // contended attempt) so this local read-lock waits like real hg's does instead of
            // failing immediately if another local hg4j operation happens to hold it.
            {
                Revlog changelog = repository.getRevlog(clIdx, clDat);
                int count = changelog.getRevisionCount();
                if (count == 0) {
                    return "No changesets to push (empty local repository)";
                }

                // The revisions to send: the ancestors of the heads being pushed (all visible heads, or the changesets of the
                // bookmarks named with -B), without what the remote has already and without secret changesets, which Mercurial
                // leaves out silently (discovery.findcommonoutgoing)
                boolean[] hiddenRevs = io.github.search5.hg4j.transport.ServedView.hidden(repository, changelog);
                int secretCount = 0;
                if (hiddenRevs != null) {
                    for (boolean h : hiddenRevs) {
                        if (h) {
                            secretCount++;
                        }
                    }
                }
                List<String> validRemoteHeads = new ArrayList<>();
                if (remoteHeads != null) {
                    for (String rh : remoteHeads) {
                        if (rh != null && !rh.equals("0000000000000000000000000000000000000000")) {
                            validRemoteHeads.add(rh);
                        }
                    }
                }
                boolean[] remoteKnown = new boolean[count];
                if (!validRemoteHeads.isEmpty()) {
                    for (String rh : validRemoteHeads) {
                        int rev = changelog.findRevision(NodeIdUtil.fromHex(rh));
                        if (rev != -1) {
                            remoteKnown[rev] = true;
                        }
                    }
                    // Propagate remote status to ancestors downwards
                    for (int i = count - 1; i >= 0; i--) {
                        if (remoteKnown[i]) {
                            Revlog.IndexRecord rec = changelog.getIndexRecord(i);
                            if (rec.getParent1() >= 0) remoteKnown[rec.getParent1()] = true;
                            if (rec.getParent2() >= 0) remoteKnown[rec.getParent2()] = true;
                        }
                    }
                    boolean hasAnyCommon = false;
                    for (boolean k : remoteKnown) {
                        if (k) {
                            hasAnyCommon = true;
                            break;
                        }
                    }
                    if (!hasAnyCommon) {
                        throw new HgValidationException("abort: repository is unrelated");
                    }
                }

                // the heads whose ancestors are pushed
                boolean[] wanted = new boolean[count];
                Map<String, String> localBookmarks = new BookmarkCommand(repository).list();
                if (!explicitBookmarks.isEmpty()) {
                    for (String name : explicitBookmarks) {
                        String hex = localBookmarks.get(name);
                        int rev = hex == null ? -1 : changelog.findRevision(NodeIdUtil.fromHex(hex));
                        if (rev >= 0) {
                            wanted[rev] = true;
                        }
                    }
                } else {
                    boolean[] isParent = new boolean[count];
                    for (int i = 0; i < count; i++) {
                        if (hiddenRevs != null && hiddenRevs[i]) {
                            continue;
                        }
                        Revlog.IndexRecord rec = changelog.getIndexRecord(i);
                        if (rec.getParent1() >= 0) isParent[rec.getParent1()] = true;
                        if (rec.getParent2() >= 0) isParent[rec.getParent2()] = true;
                    }
                    for (int i = 0; i < count; i++) {
                        if (!isParent[i] && (hiddenRevs == null || !hiddenRevs[i])) {
                            wanted[i] = true;
                        }
                    }
                }
                // ancestors of the wanted heads (parents have lower numbers, so one sweep down)
                boolean[] ancestorsOfWanted = new boolean[count];
                for (int i = count - 1; i >= 0; i--) {
                    if (wanted[i]) {
                        ancestorsOfWanted[i] = true;
                    }
                    if (ancestorsOfWanted[i]) {
                        Revlog.IndexRecord rec = changelog.getIndexRecord(i);
                        if (rec.getParent1() >= 0) ancestorsOfWanted[rec.getParent1()] = true;
                        if (rec.getParent2() >= 0) ancestorsOfWanted[rec.getParent2()] = true;
                    }
                }
                boolean[] send = new boolean[count];
                int startRev = count;
                for (int i = 0; i < count; i++) {
                    send[i] = ancestorsOfWanted[i] && !remoteKnown[i] && (hiddenRevs == null || !hiddenRevs[i]);
                    if (send[i] && startRev == count) {
                        startRev = i;
                    }
                }
                this.outgoing = send;
                this.remoteHas = remoteKnown;
                // Mercurial reads an empty "pushed" set (every -B name is missing locally) as "no restriction"
                boolean anyWanted = false;
                for (boolean w : wanted) {
                    anyWanted |= w;
                }
                this.pushedAncestors = explicitBookmarks.isEmpty() || !anyWanted ? null : ancestorsOfWanted;
                this.bookmarkResult = 0;

                if (startRev >= count) {
                    syncLocalPhases(client, changelog, count, ancestorsOfWanted, send, remoteKnown, false);
                    // nothing to send, but the bookmarks may still have to move
                    StringBuilder none = new StringBuilder(secretCount > 0 && explicitBookmarks.isEmpty()
                            ? "No changesets to push (remote is up-to-date; ignored " + secretCount + " secret changesets)"
                            : "No changesets to push (remote is up-to-date)");
                    String bookmarkLines = pushBookmarks(client, changelog, localBookmarks, null);
                    if (!bookmarkLines.isEmpty()) {
                        none.append('\n').append(bookmarkLines);
                    }
                    return none.toString();
                }

                PhaseRoots phaseRoots = repository.getPhaseRoots();

                // checkheads: reject a push that would create new remote head(s) or introduce a
                // brand-new named branch, unless the caller opted in via --force/--new-branch
                // (mirrors real hg's client-side mercurial/discovery.py checkheads(), which runs
                // BEFORE the changegroup is even built -- see PushCommand#checkHeads doc).
                if (!force && !validRemoteHeads.isEmpty()) {
                    checkHeads(changelog, count, startRev, validRemoteHeads, client, phaseRoots);
                }

                // 1. Pack changesets startRev ~ tip into changegroup bundle
                ChangegroupParser.ChangegroupBundle bundle = new ChangegroupParser.ChangegroupBundle();
                bundle.changelogEntries = new ArrayList<>();
                bundle.manifestEntries = new ArrayList<>();
                bundle.fileGroups = new ArrayList<>();

                // Backlog #39 (2026-09-05): negotiate a changegroup version from what THIS
                // push's own data needs, mirroring HgLocalClient#getBundle's own version
                // selection for the pull/getbundle response direction: cg5 whenever the
                // repository carries changelog sidedata (exp-use-copies-side-data-changeset --
                // cg5 is the only version able to carry a sidedata chunk at all), else cg3 (the
                // minimum tree-capable version) whenever the repository is treemanifest (cg3/
                // cg4/cg5 all wrap the manifest into the tree-capable envelope -- root group
                // plus zero or more per-directory subgroups; real hg emits this same envelope
                // even for a flat manifest once the version itself is cg3+, see
                // ChangegroupParser#isTreeCapableVersion), else the original cg1 (unchanged wire
                // bytes for every plain-format repo push, still the overwhelming majority).
                // Previously PushCommand always hand-rolled bare cg1 bytes here, which
                // structurally could not carry either a treemanifest directory group or a
                // sidedata chunk -- root-caused via RequirementMatrixPush{Core,Docker}RoundTripTest.
                boolean sidedataCopies = repository.isSidedataCopies();
                boolean treemanifest = repository.isTreemanifest();
                // real hg drops cg1 and cg2 for a repository with the lfs requirement: only cg3 and later carry the per-revision flags
                // that mark a revision as LFS-stored
                boolean lfsRepository = repository.getRequirements().contains("lfs");
                String version = sidedataCopies ? "05" : ((treemanifest || lfsRepository) ? "03" : "01");
                boolean treeCapable = !"01".equals(version);

                // 1a. Pack Changelogs
                // cg1은 각 엔트리의 델타를 "이 그룹 스트림에서 바로 직전에 패킹된 엔트리"를
                // 기준으로 인코딩한다(mercurial/changegroup.py의 ChangeGroupPacker01,
                // forcedeltaparentprev=True) -- 단, 그룹의 "첫" 엔트리만은 예외로, 그 엔트리
                // 자신의 실제 DAG 부모(p1)를 기준으로 삼는다(cg1unpacker._deltaheader:
                // `if prevnode is None: deltabase = p1`, 실제 hg 소스 확인, 2026-09-04).
                // 이전 수정(2026-09-02)은 이 "첫 엔트리 예외"를 놓치고 모든 엔트리(첫 엔트리
                // 포함)에 "startRev-1의 콘텐츠"를 베이스로 썼다 -- 그 값이 첫 신규 엔트리의
                // 실제 p1과 우연히 같을 때만(직전 로컬 rev가 곧 그 부모인 선형 히스토리)
                // 맞았고, 그렇지 않으면(예: 여러 head가 있는 저장소로의 push에서 startRev의
                // 진짜 부모가 startRev-1보다 앞선 리비전인 경우) 수신측이 엉뚱한 베이스로
                // 델타를 복원해 해시가 깨지고 unbundle이 실패한다(실제 hg 서버로 재현,
                // 2026-09-04: divergent head를 강제 push하면 HTTP 500). cg2+에서는 이 같은
                // 베이스를 deltabase 필드에도 명시적으로 실어야 한다(cg1은 스트림 순서로만
                // 암묵적으로 나타냄) -- HgLocalClient#getBundle의 prevClNode와 동일한 패턴.
                byte[] prevClContent = null;
                byte[] prevClNode = new byte[20];
                for (int r = startRev; r < count; r++) {
                    if (!send[r]) {
                        continue;
                    }
                    Revlog.IndexRecord clRec = changelog.getIndexRecord(r);
                    ChangegroupParser.ChangeGroupEntry clEntry = new ChangegroupParser.ChangeGroupEntry();
                    clEntry.node = clRec.getNodeId();
                    byte[] clP1Node = (clRec.getParent1() != -1) ? changelog.getIndexRecord(clRec.getParent1()).getNodeId() : new byte[20];
                    clEntry.p1 = clP1Node;
                    clEntry.p2 = (clRec.getParent2() != -1) ? changelog.getIndexRecord(clRec.getParent2()).getNodeId() : new byte[20];
                    clEntry.cs = clRec.getNodeId();
                    clEntry.flags = clRec.getFlags();

                    byte[] content = changelog.getRevisionContent(r);
                    byte[] deltaBasis;
                    byte[] deltaBaseNode;
                    if (prevClContent == null) {
                        deltaBasis = (clRec.getParent1() != -1) ? changelog.getRevisionContent(clRec.getParent1()) : new byte[0];
                        deltaBaseNode = clP1Node;
                    } else {
                        deltaBasis = prevClContent;
                        deltaBaseNode = prevClNode;
                    }
                    clEntry.deltabase = deltaBaseNode;
                    clEntry.delta = Revlog.createDelta(deltaBasis, content);
                    if (sidedataCopies) {
                        // Symmetric write-side counterpart of HgLocalClient#getBundle's own
                        // packChangelogSidedata block (backlog 26) -- push needs to carry
                        // outgoing sidedata into the pushed changegroup the same way getbundle
                        // already does for pull responses.
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
                Revlog manifest = repository.getManifestRevlog();
                Set<String> affectedFiles = new HashSet<>();
                // changelog와 동일한 규칙: 이 그룹의 "첫" 엔트리만 자신의 실제 p1 manifest
                // 리비전 콘텐츠를 베이스로 삼고(cg1unpacker._deltaheader의 prevnode==None
                // 규칙), 이후 엔트리는 직전에 패킹된 엔트리를 베이스로 삼는다.
                List<ChangegroupParser.ChangeGroupEntry> rootMfEntries = new ArrayList<>();
                byte[] prevMfContent = null;
                byte[] prevMfNode = new byte[20];
                for (int r = startRev; r < count; r++) {
                    if (!send[r]) {
                        continue;
                    }
                    byte[] clContent = changelog.getRevisionContent(r);
                    String clText = new String(clContent, StandardCharsets.UTF_8);
                    String[] clLines = clText.split("\n");

                    // Track affected files in this push range
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
                    byte[] mfP1Node = (mfRec.getParent1() != -1) ? manifest.getIndexRecord(mfRec.getParent1()).getNodeId() : new byte[20];
                    mfEntry.p1 = mfP1Node;
                    mfEntry.p2 = (mfRec.getParent2() != -1) ? manifest.getIndexRecord(mfRec.getParent2()).getNodeId() : new byte[20];
                    mfEntry.cs = changelog.getIndexRecord(r).getNodeId();
                    mfEntry.flags = mfRec.getFlags();

                    byte[] content = manifest.getRevisionContent(mfRev);
                    byte[] mfDeltaBasis;
                    byte[] mfDeltaBaseNode;
                    if (rootMfEntries.isEmpty()) {
                        mfDeltaBasis = (mfRec.getParent1() != -1) ? manifest.getRevisionContent(mfRec.getParent1()) : new byte[0];
                        mfDeltaBaseNode = mfP1Node;
                    } else {
                        mfDeltaBasis = prevMfContent;
                        mfDeltaBaseNode = prevMfNode;
                    }
                    mfEntry.deltabase = mfDeltaBaseNode;
                    mfEntry.delta = Revlog.createDelta(mfDeltaBasis, content);
                    rootMfEntries.add(mfEntry);
                    prevMfContent = content;
                    prevMfNode = mfRec.getNodeId();
                }

                if (treeCapable) {
                    // cg3/cg4/cg5 always wrap the manifest in the tree-capable envelope, even
                    // for a flat manifest (root group only, no subdirectory groups) -- real hg
                    // does the same (see ChangegroupParser#isTreeCapableVersion's javadoc).
                    bundle.manifestEntries = null;
                    bundle.manifestGroups = new ArrayList<>();
                    ChangegroupParser.ManifestGroup rootGroup = new ChangegroupParser.ManifestGroup();
                    rootGroup.path = "";
                    rootGroup.entries = rootMfEntries;
                    bundle.manifestGroups.add(rootGroup);

                    if (treemanifest) {
                        // Enumerate every directory manifest ("dirlog") this treemanifest
                        // repository has ever written (meta/<dir>/00manifest.i, the same plain
                        // -- unencoded -- path convention CommitCommand#writeTreeManifestDir
                        // already writes) and pack whichever of its revisions fall in this
                        // push's range, exactly like §1c below already does for filelogs (same
                        // linkRev-range selection, same delta-basis chaining). The RECEIVING
                        // side already fully supports this (FetchCommand#applyBundle's
                        // bundle.manifestGroups handling) -- only the SENDING side (this method)
                        // was missing it, which is the actual bug backlog #39's matrix found.
                        List<String> treeDirs = findTreemanifestDirs(repository);
                        Collections.sort(treeDirs);
                        for (String dirPath : treeDirs) {
                            File dirIdx = new File(repository.getStoreDir(), "meta/" + dirPath + "/00manifest.i");
                            File dirDat = new File(repository.getStoreDir(), "meta/" + dirPath + "/00manifest.d");
                            if (!dirIdx.exists()) {
                                continue;
                            }
                            Revlog dirlog = repository.getRevlog(dirIdx, dirDat);
                            List<ChangegroupParser.ChangeGroupEntry> dirEntries = packRevlogRange(dirlog, changelog, send);
                            if (!dirEntries.isEmpty()) {
                                ChangegroupParser.ManifestGroup mg = new ChangegroupParser.ManifestGroup();
                                mg.path = dirPath;
                                mg.entries = dirEntries;
                                bundle.manifestGroups.add(mg);
                            }
                        }
                    }
                } else {
                    bundle.manifestEntries = rootMfEntries;
                }

                // 1c. Pack Filelogs
                // 같은 규칙: 각 파일은 자기만의 별도 cg1 그룹이므로, 이 파일에서 이번 push로
                // 새로 패킹되는 "첫" 리비전은 그 리비전 자신의 실제 filelog p1 콘텐츠를
                // 베이스로 삼아야 한다("linkRev < startRev 중 가장 최근 것"은 틀린 근사치였다
                // -- 그 리비전이 첫 신규 리비전의 진짜 부모가 아닐 수 있다. 예: 같은 파일이
                // 서로 다른 head에서 각각 수정된 경우). 이후 리비전은 직전에 패킹된 리비전을
                // 베이스로 삼는다 -- packRevlogRange()로 일반화(§1b의 treemanifest dirlog
                // 패킹과 완전히 동일한 규칙이라 backlog #39에서 공용 헬퍼로 뽑음).
                List<Map.Entry<Revlog, Integer>> sentFileRevisions = new ArrayList<>();
                for (String path : affectedFiles) {
                    File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
                    File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
                    if (!flIdx.exists()) continue;

                    Revlog fl = repository.getRevlog(flIdx, flDat);
                    List<ChangegroupParser.ChangeGroupEntry> flEntries = packRevlogRange(fl, changelog, send);
                    for (int fileRev = 0; fileRev < fl.getRevisionCount(); fileRev++) {
                        if (send[fl.getIndexRecord(fileRev).getLinkRev()]) {
                            sentFileRevisions.add(Map.entry(fl, fileRev));
                        }
                    }

                    if (!flEntries.isEmpty()) {
                        ChangegroupParser.FileGroup fg = new ChangegroupParser.FileGroup();
                        fg.path = path;
                        fg.entries = flEntries;
                        bundle.fileGroups.add(fg);
                    }
                }

                // 2. Serialize bundle to binary bytes at the negotiated version, reusing the
                // same shared writer HgLocalClient#getBundle already relies on for the pull/
                // getbundle response direction (backlog #39, 2026-09-05: PushCommand used to
                // hand-roll bare cg1 bytes here via now-removed writeEntryChunk/writePathChunk/
                // writeTerminalChunk helpers, which structurally could not carry a treemanifest
                // directory group or a sidedata chunk).
                ByteArrayOutputStream cgOut = new ByteArrayOutputStream();
                ChangegroupParser.writeBundle(cgOut, bundle, version);
                byte[] cgBytes = cgOut.toByteArray();

                byte[] bundleBytes;
                if ("01".equals(version)) {
                    // Unchanged wire shape for the common (plain-format) case: hg4j's own
                    // "HG10UN" file-role convention (see HgLocalClient#getBundle's own
                    // legacy-branch comment).
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try (DataOutputStream dos = new DataOutputStream(baos)) {
                        dos.write("HG10UN".getBytes(StandardCharsets.US_ASCII));
                        dos.write(cgBytes);
                    }
                    bundleBytes = baos.toByteArray();
                } else {
                    // cg3+/cg5 needs the HG20 bundle2 envelope -- every push destination this
                    // repository can reach (local file://, or a wire server via
                    // Wire1Commands/HgSshWireServer, all funneling into
                    // HgLocalClient#pushWithHooks) only reads an explicit cgVersion off THIS
                    // envelope; its plain "HG10" branch hardcodes cgVersion="01".
                    // A server that answers a bundle2 push needs the "replycaps" part to build its reply (real hg's own client always
                    // sends it); without it a real hg server fails with HTTP 500.
                    Bundle2.Writer envelope = new Bundle2.Writer();
                    envelope.addPart("replycaps", true, Map.of(), Map.of(), PUSH_REPLY_CAPS.getBytes(StandardCharsets.ISO_8859_1));
                    envelope.addPart("changegroup", true, Map.of("version", version), Map.of(), cgBytes);
                    bundleBytes = envelope.toBytes(null);
                }

                // 3. Dispatch bundle to remote destination.
                // Backlog item 38: send real hg's own force sentinel (mercurial/exchange.py's
                // `_pushchangeset`: `if pushop.force: remoteheads = [b'force']`) instead of the
                // real head list when --force was requested -- a receiving server's push-race
                // re-check (see HgLocalClient#buildPushRaceValidator) treats a bare `["force"]`
                // wire heads value as "skip the check", matching real hg's own `check_heads()`
                // (`their_heads == [b'force']`). Without this, a --force push whose whole POINT
                // is overriding a head conflict could get spuriously rejected as "raced" by a
                // server-side check that (correctly, for a non-forced push) compares against the
                // heads this client saw before building the bundle.
                // the server must have a blob before it receives a pointer to it
                io.github.search5.hg4j.lfs.HgLfsPrePush.upload(repository, resolvedUrl, sentFileRevisions);
                String response = client.push(bundleBytes, force ? List.of("force") : remoteHeads);
                syncLocalPhases(client, changelog, count, ancestorsOfWanted, send, remoteKnown, true);

                // 3a. Bookmarks, decided like exchange._pushdiscoverybookmarks does
                String bookmarkLines = pushBookmarks(client, changelog, localBookmarks, null);
                if (!bookmarkLines.isEmpty()) {
                    response = response + "\n" + bookmarkLines;
                }

                // exchange.push: `if experimental.remotenames: logexchange.pullremotenames(repo, remote)` once the locks are released
                if (RemoteNames.enabled(repository)) {
                    RemoteNames.keepAfterLock(repository, RemoteNames.locationOf(client, resolvedUrl), client.listKeys("bookmarks"),
                            client.getBranchHeads());
                }

                // POST_PUSH hooks: they run after the store lock is released, like real hg's _afterlock work
                if (!postPushHooks.isEmpty()) {
                    final String hookResponse = response;
                    final String hookUrl = resolvedUrl;
                    repository.afterLock(() -> {
                        Map<String, Object> ctx = new HashMap<>();
                        ctx.put("destinationUrl", hookUrl);
                        ctx.put("response", hookResponse);
                        ctx.put("repository", repository);
                        for (HgHook hook : postPushHooks) {
                            try {
                                hook.run(ctx);
                            } catch (Exception e) {
                                // Non-blocking postScm trigger warning
                            }
                        }
                    });
                }

                return response;
            }
        }
    }

    /**
     * Mirrors real hg's client-side {@code mercurial/discovery.py checkheads()}: aborts the push
     * if it would (1) introduce a named branch the remote doesn't have yet (needs {@code
     * --new-branch}), or (2) increase the head count of any branch it touches, including the
     * remote's whole topology when the remote doesn't support the {@code branchmap} wire call
     * (real hg's {@code _oldheadssummary} fallback for old servers). Does nothing (real hg:
     * "remote is empty, nothing to check") when the remote has no valid heads at all -- callers
     * are expected to have already skipped calling this in that case.
     *
     * <p>Also ports real hg's two remaining {@code checkheads()} exceptions (2026-09-04, real hg
     * 7.2 {@code mercurial/discovery.py} read directly on this machine):
     *
     * <p><b>1. Obsolescence-marker exception</b> ({@code discovery._postprocessobsolete}): a
     * candidate new head that is itself recorded as obsolete in the LOCAL repo's obsstore (this
     * repo's own {@code .hg/store/obsstore}, via {@link HgObsMarker}/{@link
     * HgObsolescenceParser}) is not counted as a genuine new head if its successor chain reaches
     * a revision that will become common after the push (approximated here as "is one of this
     * check's own candidate revisions" -- the pushed revisions plus the already-known remote
     * heads). Real hg's exact rule additionally requires the predecessor not be public and,
     * for merged/branch-shaped predecessors, that no part of the branch is public or already
     * kept and that every node on it has an outgoing marker ({@code hasoutmarker}/{@code
     * pushingmarkerfor}); this port keeps the simpler single-node form, which is what {@code
     * hg amend}/{@code rebase}-style single-revision rewrites exercise. Verified directly
     * against real hg 7.2 (2026-09-04): amending a pushed head and pushing the successor
     * succeeds without {@code --force} even when the obsolescence markers themselves are never
     * exchanged with the remote ({@code experimental.evolution.exchange=no}) -- real hg's
     * client-side accept/reject decision depends only on the PUSHING repo's own obsstore, never
     * on whether the remote actually learns about the marker. hg4j's push never exchanges
     * obsmarkers either (bundle1-only), so this is an exact behavioral match, not just a
     * client-side approximation.
     *
     * <p><b>2. Bookmark-head exception</b> ({@code discovery._nowarnheads} /
     * {@code bookmarks.validdest}): a candidate new head that is the target of a local bookmark
     * whose remote counterpart is known locally is exempted from being blamed for a head-count
     * increase if the move is a valid "forward" move -- either a plain DAG descendant of the
     * bookmark's old remote position, or reachable from it via a chain that alternates
     * descendant steps and obsolescence-successor steps (real hg's {@code obsutil.foreground}).
     * Verified against real hg 7.2 (2026-09-04): moving a bookmark to a topologically UNRELATED
     * head with no obsolescence link at all is still rejected ("push creates new remote head ...
     * with bookmark") -- the exception only fires for genuine forward moves, never as a blanket
     * "bookmarked heads are always fine" rule. Per real hg's source, this exception does NOT
     * apply to the brand-new-named-branch multi-head sub-case ({@code remoteheads is None}),
     * only to the ordinary existing-branch new-head case -- {@link #checkHeadsPerBranch} mirrors
     * that split exactly.
     */
    private void checkHeads(Revlog changelog, int count, int startRev, List<String> validRemoteHeads,
                             HgRemoteConnection client, PhaseRoots phaseRoots) throws IOException {
        Map<String, List<String>> remoteBranchHeads;
        try {
            remoteBranchHeads = client.getBranchHeads();
        } catch (IOException e) {
            // A REAL hg server always supports branchmap (a core v1 wire command since ancient
            // versions); a connectivity/protocol hiccup fetching it here is not a reason to
            // abort an otherwise-valid push over a supplementary safety check -- degrade to the
            // topological-only check instead (real hg's own fallback path for servers that
            // don't advertise the capability at all), which is a strictly more conservative
            // (never more permissive) approximation.
            LOGGER.log(Level.WARNING, "Failed to fetch remote branch heads for push safety check; "
                    + "falling back to a topological-only check: " + e.getMessage(), e);
            remoteBranchHeads = null;
        }

        // Built once regardless of which branch below runs: both exceptions need to walk "does
        // this old head have a live/pushed replacement" chains through the local obsstore and/or
        // the full changelog's descendant edges.
        Map<String, List<String>> obsSuccessors = loadObsSuccessorMap();
        Map<Integer, List<Integer>> childrenByRev = buildChildrenMap(changelog, count);
        Set<Integer> nowarnRevs = computeNowarnRevs(changelog, client, obsSuccessors, childrenByRev);

        if (remoteBranchHeads != null) {
            checkHeadsPerBranch(changelog, count, startRev, remoteBranchHeads, phaseRoots, obsSuccessors, nowarnRevs);
        } else {
            checkHeadsTopological(changelog, count, startRev, validRemoteHeads, phaseRoots, obsSuccessors, nowarnRevs);
        }
    }

    private void checkHeadsPerBranch(Revlog changelog, int count, int startRev,
                                      Map<String, List<String>> remoteBranchHeads, PhaseRoots phaseRoots,
                                      Map<String, List<String>> obsSuccessors, Set<Integer> nowarnRevs) throws IOException {
        String[] branchByRev = new String[count];
        for (int i = 0; i < count; i++) {
            branchByRev[i] = CommitCommand.getBranchOfRevision(changelog, i);
        }

        TreeSet<String> touchedBranches = new TreeSet<>();
        for (int r = startRev; r < count; r++) {
            if (outgoing[r]) {
                touchedBranches.add(branchByRev[r]);
            }
        }

        List<String> newBranches = new ArrayList<>();
        for (String b : touchedBranches) {
            if (!remoteBranchHeads.containsKey(b)) {
                newBranches.add(b);
            }
        }
        if (!newBranches.isEmpty() && !allowNewBranch) {
            throw new HgValidationException("abort: push creates new remote branches: " + String.join(", ", newBranches)
                    + " (use 'hg push --new-branch' to create new remote branches)");
        }

        for (String branch : touchedBranches) {
            List<String> oldHeads = remoteBranchHeads.get(branch);
            Set<Integer> candidateRevs = new HashSet<>();
            Set<Integer> oldHeadsKnownRevs = new HashSet<>();
            if (oldHeads != null) {
                for (String hex : oldHeads) {
                    int rev = changelog.findRevision(NodeIdUtil.fromHex(hex));
                    if (rev != -1) {
                        candidateRevs.add(rev);
                        oldHeadsKnownRevs.add(rev);
                    }
                }
            }
            int unsyncedCount = oldHeads == null ? 0 : (oldHeads.size() - oldHeadsKnownRevs.size());
            for (int r = startRev; r < count; r++) {
                if (outgoing[r] && branch.equals(branchByRev[r])) {
                    candidateRevs.add(r);
                }
            }
            Set<Integer> rawNewHeads = topoHeadsWithinSet(changelog, candidateRevs);
            Set<Integer> adjustedNewHeads = applyObsolescenceDiscard(changelog, rawNewHeads, candidateRevs, obsSuccessors, phaseRoots);

            if (oldHeads == null) {
                // Brand-new branch: real hg's rule here is a flat "len(newhs) > 1" with NO
                // bookmark exemption (the `remoteheads is None` branch of checkheads() sets
                // `dhs = list(newhs)` unconditionally, never subtracting `nowarnheads`) -- but it
                // DOES still run the obsolescence-marker exception first, since
                // _postprocessobsolete() applies uniformly to every branch's candidate heads.
                if (adjustedNewHeads.size() > 1) {
                    throw new HgValidationException("abort: push creates new branch '" + branch + "' with multiple heads"
                            + " (merge or see 'hg help push' for details about pushing new heads)");
                }
                continue;
            }

            int newHeadsCount = adjustedNewHeads.size() + unsyncedCount;
            int oldHeadsCount = oldHeads.size();
            if (newHeadsCount > oldHeadsCount) {
                Set<Integer> blamed = new HashSet<>(adjustedNewHeads);
                blamed.removeAll(nowarnRevs);
                blamed.removeAll(oldHeadsKnownRevs);
                if (!blamed.isEmpty()) {
                    throw new HgValidationException("abort: push creates new remote head on branch '" + branch + "'"
                            + " (merge or see 'hg help push' for details about pushing new heads)");
                }
            }
        }
    }

    private void checkHeadsTopological(Revlog changelog, int count, int startRev, List<String> validRemoteHeads,
                                        PhaseRoots phaseRoots, Map<String, List<String>> obsSuccessors,
                                        Set<Integer> nowarnRevs) throws IOException {
        Set<Integer> candidateRevs = new HashSet<>();
        Set<Integer> oldHeadsKnownRevs = new HashSet<>();
        for (String hex : validRemoteHeads) {
            int rev = changelog.findRevision(NodeIdUtil.fromHex(hex));
            if (rev != -1) {
                candidateRevs.add(rev);
                oldHeadsKnownRevs.add(rev);
            }
        }
        int unsyncedCount = validRemoteHeads.size() - oldHeadsKnownRevs.size();
        for (int r = startRev; r < count; r++) {
            if (outgoing[r]) {
                candidateRevs.add(r);
            }
        }
        Set<Integer> rawNewHeads = topoHeadsWithinSet(changelog, candidateRevs);
        Set<Integer> adjustedNewHeads = applyObsolescenceDiscard(changelog, rawNewHeads, candidateRevs, obsSuccessors, phaseRoots);
        int newHeadsCount = adjustedNewHeads.size() + unsyncedCount;
        if (newHeadsCount > validRemoteHeads.size()) {
            Set<Integer> blamed = new HashSet<>(adjustedNewHeads);
            blamed.removeAll(nowarnRevs);
            blamed.removeAll(oldHeadsKnownRevs);
            if (!blamed.isEmpty()) {
                throw new HgValidationException("abort: push creates new remote head"
                        + " (merge or see 'hg help push' for details about pushing new heads)");
            }
        }
    }

    /** Within {@code candidateRevs}, returns the revisions that have no OTHER member of the set
     * as a child (i.e. this set's own topological heads) -- the core of real hg's {@code
     * heads(%ln + %ln)} revset call in {@code discovery._oldheadssummary}/{@code _headssummary}. */
    private Set<Integer> topoHeadsWithinSet(Revlog changelog, Set<Integer> candidateRevs) throws IOException {
        boolean[] isParentWithinSet = new boolean[changelog.getRevisionCount()];
        for (int r : candidateRevs) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(r);
            if (rec.getParent1() >= 0 && candidateRevs.contains(rec.getParent1())) {
                isParentWithinSet[rec.getParent1()] = true;
            }
            if (rec.getParent2() >= 0 && candidateRevs.contains(rec.getParent2())) {
                isParentWithinSet[rec.getParent2()] = true;
            }
        }
        Set<Integer> heads = new HashSet<>();
        for (int r : candidateRevs) {
            if (!isParentWithinSet[r]) {
                heads.add(r);
            }
        }
        return heads;
    }

    /** Real hg's {@code discovery._postprocessobsolete}, simplified to single-revision
     * predecessor/successor pairs (see {@link #checkHeads} doc for the exact rule and its real-hg
     * verification). Drops from {@code rawNewHeads} any revision that (a) is not public, (b) is
     * recorded as an obsolescence predecessor in the local obsstore, and (c) has a successor
     * chain reaching some other revision already present in {@code candidateRevs}. */
    private Set<Integer> applyObsolescenceDiscard(Revlog changelog, Set<Integer> rawNewHeads, Set<Integer> candidateRevs,
                                                   Map<String, List<String>> obsSuccessors, PhaseRoots phaseRoots) throws IOException {
        if (obsSuccessors.isEmpty() || rawNewHeads.isEmpty()) {
            return rawNewHeads;
        }
        Set<String> candidateHexes = new HashSet<>();
        for (int r : candidateRevs) {
            candidateHexes.add(NodeIdUtil.toHex(changelog.getIndexRecord(r).getNodeId()));
        }
        Set<Integer> discardable = new HashSet<>();
        for (int r : rawNewHeads) {
            byte[] nodeBytes = changelog.getIndexRecord(r).getNodeId();
            String hex = NodeIdUtil.toHex(nodeBytes);
            if (!obsSuccessors.containsKey(hex)) {
                continue; // real hg: `r not in obsrevs` -> unconditionally kept as a genuine head
            }
            if (phaseRoots.getPhase(new NodeId(nodeBytes), changelog) == PhaseRoots.Phase.PUBLIC) {
                continue; // real hg: `ispublic(r)` -> unconditionally kept
            }
            if (hasLiveSuccessorAmongCandidates(hex, candidateHexes, obsSuccessors)) {
                discardable.add(r);
            }
        }
        if (discardable.isEmpty()) {
            return rawNewHeads;
        }
        Set<Integer> result = new HashSet<>(rawNewHeads);
        result.removeAll(discardable);
        return result;
    }

    /** BFS over the local obsstore's successor chain starting at {@code predecessorHex} (NOT
     * including the predecessor itself), true if it reaches any node in {@code candidateHexes}
     * -- real hg's {@code pushingmarkerfor}/{@code hasoutmarker}, simplified: real hg tests
     * membership against the full "future common" ancestor closure, hg4j approximates that with
     * this check's own candidate revision set (the pushed revisions plus the known remote
     * heads), which is exactly what a rewritten revision's successor normally lands in. */
    private boolean hasLiveSuccessorAmongCandidates(String predecessorHex, Set<String> candidateHexes,
                                                     Map<String, List<String>> obsSuccessors) {
        Set<String> visited = new HashSet<>();
        visited.add(predecessorHex);
        Deque<String> stack = new ArrayDeque<>(obsSuccessors.getOrDefault(predecessorHex, List.of()));
        visited.addAll(stack);
        while (!stack.isEmpty()) {
            String cur = stack.pop();
            if (candidateHexes.contains(cur)) {
                return true;
            }
            for (String next : obsSuccessors.getOrDefault(cur, List.of())) {
                if (visited.add(next)) {
                    stack.push(next);
                }
            }
        }
        return false;
    }

    /**
     * The bookmark part of a push: Mercurial's {@code _pushdiscoverybookmarks} / {@code _processcompared}. A bookmark that
     * exists on both sides and was advanced locally moves on the remote even without {@code -B}; creating, overwriting and
     * deleting remote bookmarks needs the name given with {@code -B}. Updates go through {@code pushkey}.
     *
     * @return the lines Mercurial prints ({@code updating bookmark X}, {@code exporting bookmark X}, ...), newline separated
     */
    private String pushBookmarks(HgRemoteConnection client, Revlog changelog, Map<String, String> local, Object unused)
            throws IOException {
        Map<String, String> remote;
        try {
            remote = client.listKeys("bookmarks");
        } catch (IOException e) {
            remote = Map.of();
        }
        if (remote == null) {
            remote = Map.of();
        }
        List<String> lines = new ArrayList<>();
        TreeSet<String> explicit = new TreeSet<>(explicitBookmarks);
        TreeSet<String> names = new TreeSet<>(local.keySet());
        names.addAll(remote.keySet());
        Map<String, List<String>> obsSuccessors = null;
        Map<Integer, List<Integer>> children = null;
        List<String[]> updates = new ArrayList<>(); // name, old, new
        for (String b : names) {
            String s = local.get(b);
            String d = remote.get(b);
            if (d != null && d.isEmpty()) {
                d = null;
            }
            if (s == null) {
                if (d != null && explicit.remove(b)) {
                    updates.add(new String[] {b, d, ""}); // deleted locally: delete remotely
                }
                continue;
            }
            int sRev = changelog.findRevision(NodeIdUtil.fromHex(s));
            if (d == null) { // addsrc
                if (explicit.remove(b)) {
                    if (b.contains("@") && !b.endsWith("@")) {
                        lines.add("cannot push divergent bookmark " + b + "!");
                        bookmarkResult = 2;
                    } else if (pushedAncestors != null && (sRev < 0 || !pushedAncestors[sRev])) {
                        lines.add("cannot push bookmark X without its revision: " + b + "!");
                        bookmarkResult = 2;
                    } else {
                        updates.add(new String[] {b, "", s});
                    }
                }
                continue;
            }
            if (s.equals(d)) { // same
                explicit.remove(b);
                continue;
            }
            int dRev = changelog.findRevision(NodeIdUtil.fromHex(d));
            boolean advancedHere = false;
            if (sRev >= 0 && dRev >= 0) {
                if (obsSuccessors == null) {
                    obsSuccessors = loadObsSuccessorMap();
                    children = buildChildrenMap(changelog, changelog.getRevisionCount());
                }
                if (sRev >= dRev) {
                    advancedHere = isInForeground(changelog, dRev, sRev, obsSuccessors, children); // advsrc
                }
            }
            if (advancedHere) {
                explicit.remove(b);
                if (pushedAncestors == null || pushedAncestors[sRev]) {
                    updates.add(new String[] {b, d, s});
                }
            } else if (explicit.remove(b)) { // advdst, diverge or differ: overwritten only when asked for
                if (pushedAncestors == null || (sRev >= 0 && pushedAncestors[sRev])) {
                    updates.add(new String[] {b, d, s});
                }
            }
        }
        if (!explicit.isEmpty()) {
            lines.add("bookmark " + explicit.first() + " does not exist on the local or remote repository!");
            bookmarkResult = 2;
        }
        for (String[] update : updates) {
            // A bookmark must not point at a changeset the remote lacks. Mercurial's bundle2 "bookmarks" part would store such a
            // dangling bookmark (a push of "-B <missing name>" leaves an advanced bookmark's changeset behind); hg4j leaves it out.
            if (!update[2].isEmpty()) {
                int target = changelog.findRevision(NodeIdUtil.fromHex(update[2]));
                if (target >= 0 && !outgoing[target] && !remoteHas[target]) {
                    LOGGER.log(Level.WARNING, "not moving bookmark " + update[0] + ": the remote lacks its changeset");
                    continue;
                }
            }
            String action = !update[1].isEmpty() && !update[2].isEmpty() ? "update" : !update[2].isEmpty() ? "export" : "delete";
            String label = action.equals("update") ? "updating bookmark " + update[0]
                    : action.equals("export") ? "exporting bookmark " + update[0] : "deleting remote bookmark " + update[0];
            boolean ok;
            try {
                ok = client.pushkey("bookmarks", update[0], update[1], update[2]);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "bookmark push failed: " + e.getMessage(), e);
                ok = false;
            }
            if (ok) {
                lines.add(label);
            } else {
                lines.add(label + " failed");
                bookmarkResult = 1;
            }
        }
        return String.join("\n", lines);
    }

    /** Real hg's {@code discovery._nowarnheads}: local bookmarks whose remote counterpart is
     * known locally and whose local position is a valid "forward" move from that remote position
     * (see {@link #isInForeground}) are exempted from being blamed for a new-head rejection.
     * Returns the set of (local) revisions that should never be blamed. Deliberately does not
     * replicate real hg's {@code bookmarks.pushing} config carve-out for brand-new bookmarks
     * pushed via an explicit {@code -B} flag -- hg4j's {@link PushCommand} has no such flag. */
    private Set<Integer> computeNowarnRevs(Revlog changelog, HgRemoteConnection client,
                                            Map<String, List<String>> obsSuccessors,
                                            Map<Integer, List<Integer>> childrenByRev) throws IOException {
        Map<String, String> localBookmarks;
        try {
            localBookmarks = new BookmarkCommand(repository).list();
        } catch (Exception e) {
            return Set.of();
        }
        if (localBookmarks == null || localBookmarks.isEmpty()) {
            return Set.of();
        }
        Map<String, String> remoteBookmarks;
        try {
            remoteBookmarks = client.listKeys("bookmarks");
        } catch (IOException e) {
            return Set.of();
        }
        if (remoteBookmarks == null) {
            remoteBookmarks = Map.of();
        }
        Set<Integer> nowarn = new HashSet<>();
        // bookmarks.pushing: a bookmark named with -B that the remote does not have yet is not blamed for the new head it makes
        for (String name : explicitBookmarks) {
            String localHex = localBookmarks.get(name);
            String remoteHex = remoteBookmarks.get(name);
            if (localHex != null && (remoteHex == null || remoteHex.isEmpty())) {
                int rev = changelog.findRevision(NodeIdUtil.fromHex(localHex));
                if (rev != -1) {
                    nowarn.add(rev);
                }
            }
        }
        for (Map.Entry<String, String> entry : localBookmarks.entrySet()) {
            String name = entry.getKey();
            String localHex = entry.getValue();
            String remoteHex = remoteBookmarks.get(name);
            if (remoteHex == null || remoteHex.isEmpty() || localHex.equals(remoteHex)) {
                continue;
            }
            int localRev = changelog.findRevision(NodeIdUtil.fromHex(localHex));
            int remoteRev = changelog.findRevision(NodeIdUtil.fromHex(remoteHex));
            if (localRev == -1 || remoteRev == -1) {
                continue; // real hg: `rnode in repo` gate -- unknown remote position, skip
            }
            if (isInForeground(changelog, remoteRev, localRev, obsSuccessors, childrenByRev)) {
                nowarn.add(localRev);
            }
        }
        return nowarn;
    }

    /** Real hg's {@code obsutil.foreground}/{@code bookmarks.validdest}: true if {@code
     * targetRev} is reachable from {@code startRev} via a chain that freely alternates
     * changelog-descendant steps and local-obsstore-successor steps. With an empty obsstore this
     * degenerates to a plain descendant (fast-forward) check. */
    private boolean isInForeground(Revlog changelog, int startRev, int targetRev,
                                    Map<String, List<String>> obsSuccessors,
                                    Map<Integer, List<Integer>> childrenByRev) throws IOException {
        if (startRev == targetRev) {
            return true;
        }
        Set<Integer> visited = new HashSet<>();
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(startRev);
        visited.add(startRev);
        while (!stack.isEmpty()) {
            int cur = stack.pop();
            if (cur == targetRev) {
                return true;
            }
            for (int child : childrenByRev.getOrDefault(cur, List.of())) {
                if (visited.add(child)) {
                    stack.push(child);
                }
            }
            String curHex = NodeIdUtil.toHex(changelog.getIndexRecord(cur).getNodeId());
            for (String succHex : obsSuccessors.getOrDefault(curHex, List.of())) {
                int succRev = changelog.findRevision(NodeIdUtil.fromHex(succHex));
                if (succRev != -1 && visited.add(succRev)) {
                    stack.push(succRev);
                }
            }
        }
        return false;
    }

    /** All child revisions of every revision in the local changelog, {@code rev -> [children]}
     * -- the reverse of each revision's parent pointers, used by {@link #isInForeground} to walk
     * descendant edges without recomputing them per bookmark. */
    private Map<Integer, List<Integer>> buildChildrenMap(Revlog changelog, int count) throws IOException {
        Map<Integer, List<Integer>> children = new HashMap<>();
        for (int i = 0; i < count; i++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(i);
            if (rec.getParent1() >= 0) {
                children.computeIfAbsent(rec.getParent1(), k -> new ArrayList<>()).add(i);
            }
            if (rec.getParent2() >= 0) {
                children.computeIfAbsent(rec.getParent2(), k -> new ArrayList<>()).add(i);
            }
        }
        return children;
    }

    /** Reads and decodes this repository's own {@code .hg/store/obsstore} (if any) into a {@code
     * predecessor-hex -> [successor-hex, ...]} map, the local-obsstore analogue of real hg's
     * {@code obsstore.successors}. Empty (never {@code null}) when the repo has no obsstore at
     * all, matching real hg's {@code if repo.obsstore:} guards throughout {@code discovery.py}. */
    private Map<String, List<String>> loadObsSuccessorMap() throws IOException {
        File obsstoreFile = new File(repository.getStoreDir(), "obsstore");
        if (!obsstoreFile.exists() || obsstoreFile.length() == 0) {
            return Map.of();
        }
        byte[] bytes = Files.readAllBytes(obsstoreFile.toPath());
        List<HgObsMarker> markers = HgObsolescenceParser.parse(bytes);
        Map<String, List<String>> map = new HashMap<>();
        for (HgObsMarker marker : markers) {
            String predHex = NodeIdUtil.toHex(marker.getPredecessor());
            List<String> succHexes = map.computeIfAbsent(predHex, k -> new ArrayList<>());
            for (byte[] succ : marker.getSuccessors()) {
                succHexes.add(NodeIdUtil.toHex(succ));
            }
        }
        return map;
    }

    /**
     * Packs every revision of {@code revlog} whose {@code linkRev} falls in {@code [startRev,
     * end)} into changegroup entries -- shared by §1c's filelog packing and §1b's treemanifest
     * dirlog packing (backlog #39, 2026-09-05: these were two independent, near-identical
     * hand-copies of the same rule before being unified here). Each new entry's delta basis is
     * its own real parent's content for the first packed revision, and the previously-packed
     * revision for the rest ("linkRev < startRev 중 가장 최근 것" is NOT used as the first
     * entry's base -- that revision may not actually be this entry's real parent, e.g. the same
     * file/directory modified independently on two different heads). Content is always read via
     * {@link Revlog#getRawRevisionContent} (never the decoded {@code getRevisionContent}) so a
     * censored revision's tombstone bytes transfer as-is, matching real hg's own changegroup
     * packer.
     */
    private static List<ChangegroupParser.ChangeGroupEntry> packRevlogRange(Revlog revlog, Revlog changelog, boolean[] send) throws IOException {
        List<ChangegroupParser.ChangeGroupEntry> entries = new ArrayList<>();
        byte[] prevContent = null;
        byte[] prevNode = new byte[20];
        for (int i = 0; i < revlog.getRevisionCount(); i++) {
            Revlog.IndexRecord rec = revlog.getIndexRecord(i);
            if (rec.getLinkRev() < 0 || rec.getLinkRev() >= send.length || !send[rec.getLinkRev()]) {
                continue;
            }
            ChangegroupParser.ChangeGroupEntry entry = new ChangegroupParser.ChangeGroupEntry();
            entry.node = rec.getNodeId();
            byte[] p1Node = (rec.getParent1() != -1) ? revlog.getIndexRecord(rec.getParent1()).getNodeId() : new byte[20];
            entry.p1 = p1Node;
            entry.p2 = (rec.getParent2() != -1) ? revlog.getIndexRecord(rec.getParent2()).getNodeId() : new byte[20];
            entry.cs = changelog.getIndexRecord(rec.getLinkRev()).getNodeId();
            entry.flags = rec.getFlags();

            byte[] content = revlog.getRawRevisionContent(i);
            byte[] deltaBasis;
            byte[] deltaBaseNode;
            if (entries.isEmpty()) {
                deltaBasis = (rec.getParent1() != -1) ? revlog.getRawRevisionContent(rec.getParent1()) : new byte[0];
                deltaBaseNode = p1Node;
            } else {
                deltaBasis = prevContent;
                deltaBaseNode = prevNode;
            }
            entry.deltabase = deltaBaseNode;
            entry.delta = Revlog.createDelta(deltaBasis, content);
            entries.add(entry);
            prevContent = content;
            prevNode = rec.getNodeId();
        }
        return entries;
    }

    /**
     * Enumerates every directory manifest ("dirlog") a treemanifest repository has ever written,
     * as plain {@code dir/subdir}-style relative paths (matching {@code
     * CommitCommand#writeTreeManifestDir}'s own unencoded {@code meta/<dir>/00manifest.i}
     * convention exactly -- no fncache lookup needed, since treemanifest dirlogs are never
     * registered there, unlike filelogs).
     */
    private static List<String> findTreemanifestDirs(HgRepository repository) {
        List<String> dirs = new ArrayList<>();
        File metaRoot = new File(repository.getStoreDir(), "meta");
        if (metaRoot.isDirectory()) {
            collectTreemanifestDirs(metaRoot, "", dirs);
        }
        return dirs;
    }

    private static void collectTreemanifestDirs(File dir, String relPath, List<String> out) {
        if (!relPath.isEmpty() && new File(dir, "00manifest.i").exists()) {
            out.add(relPath);
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                String childRel = relPath.isEmpty() ? child.getName() : relPath + "/" + child.getName();
                collectTreemanifestDirs(child, childRel, out);
            }
        }
    }
}
