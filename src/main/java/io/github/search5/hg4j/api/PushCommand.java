package io.github.search5.hg4j.api;

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

    private final HgRepository repository;
    private String destinationUrl;
    private boolean force = false;
    private boolean allowNewBranch = false;

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

    public String call() throws IOException, HgLockException {
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

        try (HgRemoteConnection client = HgRemoteConnectionFactory.createConnection(resolvedUrl)) {
            List<String> remoteHeads = client.getHeads();

            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");

            // Backlog item 38: mirrors real hg's own client-side push locking its SOURCE repo
            // (mercurial/exchange.py's push(): `lock = pushop.repo.lock()`, default wait=True,
            // waiting up to ui.timeout -- 600s default -- rather than aborting on the very first
            // contended attempt) so this local read-lock waits like real hg's does instead of
            // failing immediately if another local hg4j operation happens to hold it.
            try (HgLock storeLock = repository.lockStore(repository.resolvePushLockTimeoutMs())) {
                Revlog changelog = repository.getRevlog(clIdx, clDat);
                int count = changelog.getRevisionCount();
                if (count == 0) {
                    return "No changesets to push (empty local repository)";
                }

                // Find all local heads
                List<String> localHeads = new ArrayList<>();
                boolean[] isParent = new boolean[count];
                for (int i = 0; i < count; i++) {
                    Revlog.IndexRecord rec = changelog.getIndexRecord(i);
                    if (rec.getParent1() >= 0) isParent[rec.getParent1()] = true;
                    if (rec.getParent2() >= 0) isParent[rec.getParent2()] = true;
                }
                for (int i = 0; i < count; i++) {
                    if (!isParent[i]) {
                        localHeads.add(NodeIdUtil.toHex(changelog.getIndexRecord(i).getNodeId()));
                    }
                }

                // Calculate startRev for new commits to push
                int startRev = 0;
                List<String> validRemoteHeads = new ArrayList<>();
                if (remoteHeads != null) {
                    for (String rh : remoteHeads) {
                        if (rh != null && !rh.equals("0000000000000000000000000000000000000000")) {
                            validRemoteHeads.add(rh);
                        }
                    }
                }

                if (!validRemoteHeads.isEmpty()) {
                    boolean[] remoteKnown = new boolean[count];
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

                    // Find the first revision not known to remote
                    int firstNewRev = count;
                    for (int i = 0; i < count; i++) {
                        if (!remoteKnown[i]) {
                            firstNewRev = i;
                            break;
                        }
                    }
                    startRev = firstNewRev;
                }

                if (startRev >= count) {
                    return "No changesets to push (remote is up-to-date)";
                }

                // Phase Check: Block push if any target commit is in secret phase (E-4 Phases workflow)
                PhaseRoots phaseRoots = repository.getPhaseRoots();
                for (int r = startRev; r < count; r++) {
                    byte[] nodeBytes = changelog.getIndexRecord(r).getNodeId();
                    NodeId nodeId = new NodeId(nodeBytes);
                    if (phaseRoots.isSecret(nodeId, changelog)) {
                        throw new HgValidationException("abort: push includes secret commit: " + nodeId.toHex());
                    }
                }

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
                String version = sidedataCopies ? "05" : (treemanifest ? "03" : "01");
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
                    if (r == startRev) {
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
                            List<ChangegroupParser.ChangeGroupEntry> dirEntries = packRevlogRange(dirlog, changelog, startRev);
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
                for (String path : affectedFiles) {
                    File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
                    File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
                    if (!flIdx.exists()) continue;

                    Revlog fl = repository.getRevlog(flIdx, flDat);
                    List<ChangegroupParser.ChangeGroupEntry> flEntries = packRevlogRange(fl, changelog, startRev);

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
                    bundleBytes = Bundle2Parser.wrapChangegroupInBundle2(cgBytes, version);
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
                String response = client.push(bundleBytes, force ? List.of("force") : remoteHeads);

                // 3a. Sync local bookmarks to remote utilizing pushkey protocol
                try {
                    Map<String, String> localBks = new BookmarkCommand(repository).call();
                    Map<String, String> remoteBks = client.listKeys("bookmarks");
                    for (Map.Entry<String, String> entry : localBks.entrySet()) {
                        String name = entry.getKey();
                        String localHex = entry.getValue();
                        String remoteHex = remoteBks != null ? remoteBks.getOrDefault(name, "") : "";
                        if (!localHex.equals(remoteHex)) {
                            client.pushkey("bookmarks", name, remoteHex, localHex);
                        }
                    }
                } catch (Exception e) {
                    // 북마크 푸시 실패 시 비차단 경고 처리
                    LOGGER.log(Level.WARNING, "Failed to push bookmarks to remote: " + e.getMessage(), e);
                }

                // POST_PUSH hooks trigger
                if (!postPushHooks.isEmpty()) {
                    Map<String, Object> ctx = new HashMap<>();
                    ctx.put("destinationUrl", resolvedUrl);
                    ctx.put("response", response);
                    ctx.put("repository", repository);
                    for (HgHook hook : postPushHooks) {
                        try {
                            hook.run(ctx);
                        } catch (Exception e) {
                            // Non-blocking postScm trigger warning
                        }
                    }
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
            touchedBranches.add(branchByRev[r]);
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
                if (branch.equals(branchByRev[r])) {
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
            candidateRevs.add(r);
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
            localBookmarks = new BookmarkCommand(repository).call();
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
        if (remoteBookmarks == null || remoteBookmarks.isEmpty()) {
            return Set.of();
        }
        Set<Integer> nowarn = new HashSet<>();
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
    private static List<ChangegroupParser.ChangeGroupEntry> packRevlogRange(Revlog revlog, Revlog changelog, int startRev) throws IOException {
        List<ChangegroupParser.ChangeGroupEntry> entries = new ArrayList<>();
        byte[] prevContent = null;
        byte[] prevNode = new byte[20];
        for (int i = 0; i < revlog.getRevisionCount(); i++) {
            Revlog.IndexRecord rec = revlog.getIndexRecord(i);
            if (rec.getLinkRev() < startRev) {
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
