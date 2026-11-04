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
 */
public class HgLocalClient implements HgRemoteConnection {

    private final File repoDir;
    private final HgRepository remoteRepo;

    /**
     * Opens the repository at a local path for use as a transport. No authentication is involved and the
     * connection holds no resources beyond the opened repository.
     * @param path a directory path, optionally prefixed with {@code file://}; must not be null
     * @throws IOException if the repository cannot be opened
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

        // the served view: a hidden (secret) changeset is no head, and does not make its parent a non-head
        boolean[] hidden = ServedView.hidden(remoteRepo, changelog);
        boolean[] isParent = new boolean[count];
        for (int i = 0; i < count; i++) {
            if (hidden != null && hidden[i]) {
                continue;
            }
            Revlog.IndexRecord rec = changelog.getIndexRecord(i);
            if (rec.getParent1() >= 0) isParent[rec.getParent1()] = true;
            if (rec.getParent2() >= 0) isParent[rec.getParent2()] = true;
        }

        List<String> heads = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (!isParent[i] && (hidden == null || !hidden[i])) {
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
        boolean[] hiddenRevs = ServedView.hidden(remoteRepo, changelog);
        boolean[] hasSameBranchChild = new boolean[count];
        for (int i = 0; i < count; i++) {
            if (hiddenRevs != null && hiddenRevs[i]) {
                continue;
            }
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
            if (!hasSameBranchChild[i] && (hiddenRevs == null || !hiddenRevs[i])) {
                result.computeIfAbsent(branchByRev[i], b -> new ArrayList<>())
                        .add(NodeIdUtil.toHex(changelog.getIndexRecord(i).getNodeId()));
            }
        }
        return result;
    }

    @Override
    public byte[] getChangegroup(List<String> roots) throws IOException {
        return getBundleFromRoots(roots, null);
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
     * exactly what real hg's own non-ellipses narrow clone server does (verified 2026-09-06
     * against Mercurial 7.2's {@code mercurial/changegroup.py} {@code cgpacker.generatefiles()}:
     * {@code changedfiles = [f for f in changedfiles if self._matcher(f) and not
     * self._oldmatcher(f)]}), and unlike this class's flat-manifest storage, does <em>not</em>
     * filter changelog or manifest revlog content at all -- those two are always sent in full,
     * matching real hg's own behavior for non-treemanifest repositories (a flat manifest revision
     * is one indivisible blob listing every tracked path; only the treemanifest per-directory
     * storage real hg optionally uses can prune individual subtrees via {@code matcher.visitdir()},
     * and hg4j doesn't implement treemanifest). This is what makes narrow clone/pull actually
     * reduce wire transfer size (backlog item 40) instead of only filtering post-hoc, client-side,
     * after downloading everything.
     */
    public byte[] getBundle(List<String> common, List<String> heads, List<String> bundleCaps,
                             HgRemoteConnection.NarrowScope narrowScope) throws IOException {
        return getBundle(common, null, heads, bundleCaps, narrowScope);
    }

    /**
     * The legacy {@code changegroup} / {@code changegroupsubset} commands: the changesets to send are {@code roots::heads} of the
     * original's {@code outgoing(missingroots=..., ancestorsof=...)}, that is the roots themselves and their descendants that are
     * ancestors of {@code heads} (all heads if empty); roots that are empty or the null node mean "all ancestors of the heads".
     */
    public byte[] getBundleFromRoots(List<String> roots, List<String> heads) throws IOException {
        return getBundle(List.of(), roots, heads, null, null);
    }

    private byte[] getBundle(List<String> common, List<String> missingRoots, List<String> heads, List<String> bundleCaps,
                             HgRemoteConnection.NarrowScope narrowScope) throws IOException {
        HgTreeFilter narrowFilter = null;
        if (narrowScope != null) {
            List<NarrowPattern> includes = new ArrayList<>();
            for (String p : narrowScope.includePatterns) {
                includes.add(HgTreeFilter.internalNarrowPattern(p));
            }
            List<NarrowPattern> excludes = new ArrayList<>();
            for (String p : narrowScope.excludePatterns) {
                excludes.add(HgTreeFilter.internalNarrowPattern(p));
            }
            narrowFilter = HgTreeFilter.createNarrowSpecFilter(includes, excludes);
        }
        File clIdx = new File(remoteRepo.getStoreDir(), "00changelog.i");
        File clDat = new File(remoteRepo.getStoreDir(), "00changelog.d");
        File mfIdx = new File(remoteRepo.getStoreDir(), "00manifest.i");
        File mfDat = new File(remoteRepo.getStoreDir(), "00manifest.d");

        // 버그 수정(2026-09-08, llm-wiki/known-bugs-registry.md의 `HgLocalClient.getBundle()`
        // 항목 참고): 여기서(그리고 아래 "startRev >= count"에서도) 예전엔 `new byte[0]`을
        // 그대로 반환했다 -- 실제 hg 클라이언트에게는 "정상적으로 봉투에 감싸인, 아무 내용도
        // 없는 changegroup/bundle2 응답"과 "스트림이 잘렸다"를 구분할 방법이 전혀 없는
        // 무효한 응답이다(HTTP 경로: "stream ended unexpectedly (got 0 bytes, expected 4)"로
        // 즉시 abort, SSH raw-stream 경로: 4바이트 길이 헤더를 영원히 기다리며 hang). 커밋이
        // 0개인 갓 `hg init`된 저장소(yona로 새 Mercurial 프로젝트를 만들면 항상 이 상태)를
        // 최초로 clone하는 시나리오가 정확히 이 경로를 타서 100% 재현됐다. 실제 hg 자신의
        // `exchange.getbundlechunks()`(mercurial/exchange.py)는 보낼 게 하나도 없어도 절대
        // 조기 반환하지 않고 항상 같은 청크 생성 경로를 타 "그룹이 비어 있음을 뜻하는 종료
        // 청크만 있는" 올바른 changegroup을 만든다 -- 아래로 그냥 흘려보내면(다음의 changelog/
        // manifest/filelog 루프들은 count==0이면 그냥 0번 반복하고 끝난다는 것을 확인했다)
        // `ChangegroupParser.writeBundle`이 그 "종료 청크만 있는" 올바른 빈 changegroup을
        // 만들고, 이어서 (bundle2 요청 시) `Bundle2Parser.wrapChangegroupInBundle2`이 이를
        // 정상적인 HG20 봉투로 감싸거나 (레거시 요청 시) "HG10UN" 접두사가 그대로 붙는다 --
        // 클라이언트 입장에서는 "커밋이 없는 원격"과 "커밋은 있지만 이미 다 common이라
        // 새로 보낼 게 없는 원격"이 완전히 동일한, 유효한 빈 changegroup으로 보인다(실제
        // hg도 이 둘을 구분하지 않는다). `remoteRepo.getRevlog()`는 idxFile이 아예 없어도
        // (일반 v1 저장소 기준) 디스크에 아무것도 안 쓰고 revisionCount=0인 빈 Revlog를
        // 안전하게 만들어준다는 것도 `RevlogIndex`(idxFile.exists()가 false면 v1 분기는
        // 아무 side effect 없이 기본 빈 상태로 남는다) 실측으로 확인했다.
        Revlog changelog = remoteRepo.getRevlog(clIdx, clDat);
        int count = changelog.getRevisionCount();

        // The changesets to send are exactly ancestors(heads) - ancestors(common), like real hg's
        // `outgoing` (mercurial/discovery.py outgoing.missing). `heads` empty means "all heads".
        // (Before 2026-11-03 this assumed a linear history, "everything after the first revision that is
        // not common", and did not look at `heads` at all: `clone -r <rev>` then transferred the whole
        // repository, and a branched history sent revisions the client did not ask for.)
        // Parents always have a lower revision number than their children, so one downward sweep
        // marks all ancestors.
        boolean[] inCommon = ancestorsInclusive(changelog, count, nodesToRevs(changelog, common, false));
        boolean[] wanted;
        if (heads == null || heads.isEmpty()) {
            wanted = new boolean[count];
            java.util.Arrays.fill(wanted, true);
        } else {
            wanted = ancestorsInclusive(changelog, count, nodesToRevs(changelog, heads, true));
        }
        boolean[] hiddenRevs = ServedView.hidden(remoteRepo, changelog);
        if (hiddenRevs != null) {
            for (int r = 0; r < count; r++) {
                if (hiddenRevs[r]) {
                    wanted[r] = false; // secret changesets are never sent
                }
            }
        }
        boolean[] descendantsOfRoots = null;
        if (missingRoots != null) {
            // roots::heads: the roots and everything below them (only roots we have, and not the null node)
            List<Integer> rootRevs = nodesToRevs(changelog, missingRoots, false);
            if (!rootRevs.isEmpty()) {
                descendantsOfRoots = new boolean[count];
                for (int r = 0; r < count; r++) {
                    Revlog.IndexRecord record = changelog.getIndexRecord(r);
                    descendantsOfRoots[r] = rootRevs.contains(r)
                            || (record.getParent1() >= 0 && descendantsOfRoots[record.getParent1()])
                            || (record.getParent2() >= 0 && descendantsOfRoots[record.getParent2()]);
                }
            }
        }
        boolean[] outgoing = new boolean[count];
        List<Integer> revs = new ArrayList<>();
        for (int r = 0; r < count; r++) {
            if (descendantsOfRoots != null ? (wanted[r] && descendantsOfRoots[r]) : (wanted[r] && !inCommon[r])) {
                outgoing[r] = true;
                revs.add(r);
            }
        }

        // (An empty `revs` -- a freshly `hg init`ed repository, or a client that is already up to date --
        // still produces a VALID, empty changegroup below; see the 2026-09-08 note in
        // known-bugs-registry.md for why returning zero bytes is wrong.)

        // 백로그 26번: bundleCaps에서 실제로 클라이언트가 요청한 changegroup 버전을 협상한다
        // (실제 스펙, mercurial/exchange.py 실측): 클라이언트가 "HG2"로 시작하는 토큰을 하나도
        // 보내지 않았으면(bundle2 미요청 -- 예: bundleCaps==null, 또는 changegroupsubset 같은
        // 순수 legacy 호출) 버전은 무조건 "01"이고 응답도 HG20 봉투 없이 맨 cg1 청크 그대로
        // 나간다. bundle2를 요청했으면 클라이언트가 자신의 bundle2= 블롭 안에 실어 보낸
        // changegroup=01,02,... 목록과 hg4j가 실제로 패킹할 수 있는 버전 집합의 교집합 중
        // 최댓값을 고르고(교집합이 비었거나 목록 자체가 없으면 실제 hg와 동일하게 "01"로
        // 기본값 유지), 응답은 항상 HG20 봉투로 감싼다(버전이 결국 "01"이 되더라도) --
        // Bundle2Parser#decodeChangegroupVersions/#requestsBundle2의 문서 참고.
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
        // cg5(sidedata를 나를 수 있는 유일한 버전)일 때만, 그리고 이 저장소 자체가 changelog
        // sidedata를 실제로 쓰고 있을 때만(exp-copies-sidedata-changeset) changelog 엔트리에
        // SD_FILES sidedata를 실어 보낸다 -- 백로그 19가 로컬 커밋에 이미 쓰고 있는 것을
        // getbundle 응답 경로에서도 손실 없이 그대로 전달(round-trip)하기 위함.
        boolean packChangelogSidedata = "05".equals(version) && remoteRepo.isSidedataCopies();

        // Build bundle
        ChangegroupParser.ChangegroupBundle bundle = new ChangegroupParser.ChangegroupBundle();
        bundle.changelogEntries = new ArrayList<>();
        bundle.manifestEntries = new ArrayList<>();
        bundle.fileGroups = new ArrayList<>();

        // Delta bases follow real hg's cgpacker.group(): the group starts from the PARENT of its first
        // revision (which the receiver already has), and every later entry is a delta against the entry
        // packed just before it (cg1 forces delta-parent-prev; for cg2+ the base is declared explicitly,
        // here too the previous entry). On a linear history the parent is simply the last common revision,
        // which is what the old startRev-1 shortcut computed.

        // 1a. Pack Changelogs
        byte[] prevClContent = new byte[0];
        byte[] prevClNode = new byte[20];
        if (!revs.isEmpty()) {
            int parent = changelog.getIndexRecord(revs.get(0)).getParent1();
            if (parent >= 0) {
                prevClContent = changelog.getRevisionContent(parent);
                prevClNode = changelog.getIndexRecord(parent).getNodeId();
            }
        }
        for (int r : revs) {
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

        // 1b. Pack Manifests: one entry per distinct manifest node of the outgoing changesets, in changelog order.
        Revlog manifest = remoteRepo.getRevlog(mfIdx, mfDat);
        Set<String> affectedFiles = new TreeSet<>(NodeIdUtil.UTF8_STRING_COMPARATOR); // real hg sends file groups sorted by name
        Set<String> packedManifests = new HashSet<>();
        byte[] prevMfContent = new byte[0];
        byte[] deltaBaseMfNode = new byte[20];
        boolean firstManifest = true;
        // `cgpacker.generatefiles`: a file revision is sent when an outgoing changeset changes the file to it and its own linkrev is not
        // one of the receiver's, even if that linkrev is another changeset (one stripped later, as in a rebase whose result has the same
        // file content as the original). It is linked to the first outgoing changeset that changes the file to it.
        Map<String, byte[]> linkNodes = new HashMap<>();
        for (int r : revs) {
            byte[] clContent = changelog.getRevisionContent(r);
            String clText = new String(clContent, StandardCharsets.UTF_8);
            String[] clLines = clText.split("\n");
            Set<String> changedHere = new HashSet<>();
            for (int i = 3; i < clLines.length; i++) {
                String line = clLines[i].trim();
                if (line.isEmpty()) break;
                affectedFiles.add(line);
                changedHere.add(line);
            }
            {
                int mfRevHere = manifest.findRevision(NodeIdUtil.fromHex(clLines[0].trim().substring(0, 40)));
                if (mfRevHere != -1 && !changedHere.isEmpty()) {
                    for (String mfLine : new String(manifest.getRevisionContent(mfRevHere), StandardCharsets.ISO_8859_1).split("\n")) {
                        int nul = mfLine.indexOf('\0');
                        if (nul > 0 && mfLine.length() >= nul + 41) {
                            String name = new String(mfLine.substring(0, nul).getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                            if (changedHere.contains(name)) {
                                linkNodes.putIfAbsent(name + "\0" + mfLine.substring(nul + 1, nul + 41), changelog.getIndexRecord(r).getNodeId());
                            }
                        }
                    }
                }
            }

            byte[] mfNode = NodeIdUtil.fromHex(clLines[0].trim().substring(0, 40));
            int mfRev = manifest.findRevision(mfNode);
            if (mfRev == -1) continue;
            if (!packedManifests.add(NodeIdUtil.toHex(mfNode))) continue; // same manifest as an earlier outgoing changeset

            Revlog.IndexRecord mfRec = manifest.getIndexRecord(mfRev);
            if (firstManifest) {
                firstManifest = false;
                if (mfRec.getParent1() != -1) { // the group starts from the first manifest's parent
                    prevMfContent = manifest.getRevisionContent(mfRec.getParent1());
                    deltaBaseMfNode = manifest.getIndexRecord(mfRec.getParent1()).getNodeId();
                }
            }
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

        // 1c. Pack Filelogs: the file revisions whose linkrev is an outgoing changeset.
        for (String path : affectedFiles) {
            if (narrowFilter != null && !narrowFilter.accept(path)) {
                // Backlog 40: this is the actual bandwidth-saving step -- real hg's own
                // cgpacker.generatefiles() prunes exactly this way (see this method's javadoc).
                // Manifests/changelog above stay unfiltered on purpose.
                continue;
            }
            File flIdx = CommitCommand.getFilelogIndex(remoteRepo.getStoreDir(), path);
            File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
            if (!flIdx.exists()) continue;

            Revlog fl = remoteRepo.getRevlog(flIdx, flDat);
            List<ChangegroupParser.ChangeGroupEntry> flEntries = new ArrayList<>();

            // Raw (as-stored) content throughout, not getRevisionContent(): a filelog revision
            // can be censored (Revlog.REVIDX_ISCENSORED), and bundling must transfer its
            // tombstone bytes as-is rather than throwing HgCensoredContentException -- real hg's
            // own changegroup packer likewise always uses rawdata()/`_chunk()`, never the decoded
            // text. The group starts from the parent of its first file revision.
            byte[] prevFlContent = new byte[0];
            byte[] deltaBaseFlNode = new byte[20];
            boolean firstFileRev = true;
            for (int i = 0; i < fl.getRevisionCount(); i++) {
                Revlog.IndexRecord flRec = fl.getIndexRecord(i);
                int linkRev = flRec.getLinkRev();
                byte[] linkNode = null;
                if (linkRev < 0 || linkRev >= count) {
                    continue;
                }
                if (!outgoing[linkRev]) {
                    byte[] first = inCommon[linkRev] ? null : linkNodes.get(path + "\0" + NodeIdUtil.toHex(flRec.getNodeId()).substring(0, 40));
                    if (first == null) {
                        continue;
                    }
                    linkNode = first;
                }
                if (firstFileRev) {
                    firstFileRev = false;
                    if (flRec.getParent1() != -1) {
                        prevFlContent = fl.getRawRevisionContent(flRec.getParent1());
                        deltaBaseFlNode = fl.getIndexRecord(flRec.getParent1()).getNodeId();
                    }
                }
                ChangegroupParser.ChangeGroupEntry flEntry = new ChangegroupParser.ChangeGroupEntry();
                flEntry.node = flRec.getNodeId();
                flEntry.p1 = (flRec.getParent1() != -1) ? fl.getIndexRecord(flRec.getParent1()).getNodeId() : new byte[20];
                flEntry.p2 = (flRec.getParent2() != -1) ? fl.getIndexRecord(flRec.getParent2()).getNodeId() : new byte[20];
                flEntry.cs = linkNode != null ? linkNode : changelog.getIndexRecord(linkRev).getNodeId();
                flEntry.deltabase = deltaBaseFlNode;
                flEntry.flags = flRec.getFlags();

                byte[] content = fl.getRawRevisionContent(i);
                flEntry.delta = Revlog.createDelta(prevFlContent, content);
                flEntries.add(flEntry);
                prevFlContent = content;
                deltaBaseFlNode = flRec.getNodeId();
            }

            if (!flEntries.isEmpty()) {
                ChangegroupParser.FileGroup fg = new ChangegroupParser.FileGroup();
                fg.path = path;
                fg.entries = flEntries;
                bundle.fileGroups.add(fg);
            }
        }

        if (!"01".equals(version)) {
            // cg2 and later name the delta base explicitly, and the original does not always send "a delta against the entry before"
            // (that is cg1's forced rule): it reuses the delta the revlog stores when its base is one the receiver has, and sends
            // a full text for what is stored as one (see reselectDeltas).
            reselectDeltas(changelog, bundle.changelogEntries, false);
            bundle.manifestEntries.sort(java.util.Comparator.comparingInt(e -> manifest.findRevision(e.node)));
            reselectDeltas(manifest, bundle.manifestEntries, true);
            for (ChangegroupParser.FileGroup group : bundle.fileGroups) {
                File flIdx = CommitCommand.getFilelogIndex(remoteRepo.getStoreDir(), group.path);
                File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
                reselectDeltas(remoteRepo.getRevlog(flIdx, flDat), group.entries, true);
            }
        }

        // Serialize to binary bytes at the negotiated version (백로그 26번: 예전엔 cg1
        // "HG10UN"으로 항상 고정 -- 이제 ChangegroupParser.writeBundle을 통해 실제로 협상된
        // 버전(01~05)으로 패킹한다).
        ByteArrayOutputStream cgOut = new ByteArrayOutputStream();
        ChangegroupParser.writeBundle(cgOut, bundle, version);
        byte[] cgBytes = cgOut.toByteArray();

        if (usebundle2) {
            // 실제 hg 스펙(exchange.getbundlechunks의 usebundle2 분기, mercurial/exchange.py
            // 실측): 클라이언트가 bundle2를 요청했으면 버전이 결국 "01"이 되더라도 응답은
            // 항상 HG20 봉투로 감싼다.
            return Bundle2Parser.wrapChangegroupInBundle2(cgBytes, version, null, bundle.changelogEntries.size());
        }

        // legacy(비-bundle2) 요청 -- hg4j 자체 "HG10UN" 파일 관례(file:// 역할, HgRemoteClient
        // 등 기존 호출자들이 계속 기대하는 프리픽스; 실제 와이어로 나갈 땐
        // Wire1Commands#stripHg10Prefix가 벗겨낸다)를 그대로 유지한다. 이 경로는 version이
        // 언제나 "01"이므로(위 협상 로직 참고) cg1 그대로다.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.write("HG10UN".getBytes(StandardCharsets.US_ASCII));
            dos.write(cgBytes);
        }
        return baos.toByteArray();
    }

    /** Changelog revisions of the given nodes. Unknown nodes are skipped when {@code mustExist} is false (the
     * client's `common` list legitimately names nodes this server never had), else they are an error. */
    private static List<Integer> nodesToRevs(Revlog changelog, List<String> nodes, boolean mustExist) throws IOException {
        List<Integer> revs = new ArrayList<>();
        if (nodes == null) {
            return revs;
        }
        for (String hex : nodes) {
            if (hex == null || hex.isEmpty() || hex.chars().allMatch(ch -> ch == '0')) {
                continue; // the null node: "nothing" for common, nothing to ask for in heads
            }
            int rev = changelog.findRevision(NodeIdUtil.fromHex(hex));
            if (rev != -1) {
                revs.add(rev);
            } else if (mustExist) {
                throw new io.github.search5.hg4j.errors.HgRevisionNotFoundException(hex);
            }
        }
        return revs;
    }

    /** {@code marked[r]} is true for every given revision and all of its ancestors. */
    private static boolean[] ancestorsInclusive(Revlog changelog, int count, List<Integer> start) {
        boolean[] marked = new boolean[count];
        for (int r : start) {
            marked[r] = true;
        }
        for (int i = count - 1; i >= 0; i--) {
            if (marked[i]) {
                Revlog.IndexRecord rec = changelog.getIndexRecord(i);
                if (rec.getParent1() >= 0) marked[rec.getParent1()] = true;
                if (rec.getParent2() >= 0) marked[rec.getParent2()] = true;
            }
        }
        return marked;
    }

    @Override
    public String push(byte[] bundleBytes, List<String> heads) throws IOException, HgLockException {
        return pushWithHooks(bundleBytes, heads, List.of(), List.of()).status;
    }

    /** Result of {@link #pushWithHooks}, reported back so wireprotocol server glue can tell an
     * empty/rejected push apart from a genuine import when firing hooks or building the pushres line. */
    public static class PushResult {
        /**
         * Human-readable outcome of the push; {@code "no changes found"} when the bundle was empty.
         */
        public final String status;
        /**
         * Hex node ids of the changesets that were imported, in the list order produced by the import.
         */
        public final List<String> importedNodeHexes;

        /** File revisions added by the changegroup and the number of distinct files they belong to (for the "added ..." summary). */
        public final int fileRevisions;
        /**
         * Number of distinct files the added file revisions belong to.
         */
        public final int files;

        /**
         * Creates a result with zero file revisions and files.
         * @param status the outcome text
         * @param importedNodeHexes hex node ids of the imported changesets
         */
        public PushResult(String status, List<String> importedNodeHexes) {
            this(status, importedNodeHexes, 0, 0);
        }

        /**
         * Creates a result.
         * @param status the outcome text
         * @param importedNodeHexes hex node ids of the imported changesets
         * @param fileRevisions number of file revisions added
         * @param files number of distinct files those revisions belong to
         */
        public PushResult(String status, List<String> importedNodeHexes, int fileRevisions, int files) {
            this.status = status;
            this.importedNodeHexes = importedNodeHexes;
            this.fileRevisions = fileRevisions;
            this.files = files;
        }
    }

    /**
     * Same as {@link #push}, but also runs server-side {@link io.github.search5.hg4j.api.HgHook}
     * callbacks around applying the incoming changegroup -- real hg's {@code pretxnchangegroup}
     * (abort-capable, sees the pending node hexes before anything is written) and {@code
     * changegroup} (notification-only, sees the actually-imported node hexes) hooks. A pre-hook
     * returning {@code false} aborts before {@link io.github.search5.hg4j.api.PullCommand#applyBundle}
     * runs, so nothing lands.
     */
    public PushResult pushWithHooks(byte[] bundleBytes, List<String> heads,
                                     List<HgHook> preHooks,
                                     List<HgHook> postHooks) throws IOException, HgLockException {
        return pushWithHooks(bundleBytes, heads, preHooks, postHooks, null);
    }

    /**
     * Same as {@link #pushWithHooks(byte[], List, List, List)}, with one more validator that runs right after the store lock
     * is taken and the heads check has passed, before anything is changed (the {@code check:bookmarks},
     * {@code check:updated-heads} and {@code check:phases} parts of a bundle2 push).
     */
    public PushResult pushWithHooks(byte[] bundleBytes, List<String> heads,
                                     List<HgHook> preHooks,
                                     List<HgHook> postHooks,
                                     FetchCommand.PostLockValidator extraValidator) throws IOException, HgLockException {
        return pushWithHooks(bundleBytes, heads, preHooks, List.of(), postHooks, extraValidator);
    }

    /**
     * Same, with {@code preTxnHooks}: Mercurial's {@code pretxnchangegroup}. They run after the changegroup has been applied (and see
     * the nodes it added); if one returns {@code false} everything is undone with {@link io.github.search5.hg4j.api.RollbackCommand} and the push fails with
     * {@link io.github.search5.hg4j.errors.HgHookRejectedException} ({@code pretxnchangegroup hook failed}).
     */
    public PushResult pushWithHooks(byte[] bundleBytes, List<String> heads,
                                     List<HgHook> preHooks,
                                     List<HgHook> preTxnHooks,
                                     List<HgHook> postHooks,
                                     FetchCommand.PostLockValidator extraValidator) throws IOException, HgLockException {
        if (bundleBytes == null || bundleBytes.length == 0) {
            return new PushResult("no changes found", List.of());
        }
        // The apply, the pretxnchangegroup hooks and the rollback after a rejecting hook run in one lock hold, like real hg's
        // transaction: with the locks released in between, another push could land first and the rollback would undo that one.
        int timeout = remoteRepo.resolvePushLockTimeoutMs();
        PushResult result;
        try (io.github.search5.hg4j.lib.HgLock wlock = remoteRepo.lockWorkingCopy(timeout);
             io.github.search5.hg4j.lib.HgLock storeLock = remoteRepo.lockStore(timeout)) {
            result = pushWithHooksLocked(bundleBytes, heads, preHooks, preTxnHooks, postHooks, extraValidator);
        }
        return result;
    }


    private PushResult pushWithHooksLocked(byte[] bundleBytes, List<String> heads,
                                     List<HgHook> preHooks,
                                     List<HgHook> preTxnHooks,
                                     List<HgHook> postHooks,
                                     FetchCommand.PostLockValidator extraValidator) throws IOException, HgLockException {

        // Backup remote dirstate before push to preserve bare status
        File dirstateFile = new File(remoteRepo.getDirectory(), ".hg/dirstate");
        byte[] dirstateBackup = dirstateFile.exists() ? Files.readAllBytes(dirstateFile.toPath()) : null;

        byte[] changegroupBytes = bundleBytes;
        String cgVersion = "01";
        // Backlog item 38 ("PushRaced"-equivalent): if the incoming bundle2 envelope carries a
        // `check:heads` part, it's the authoritative source for what the pushing client computed
        // its push against -- captured here (bundle2-only) and used to build the post-lock race
        // validator below, alongside the plain wire `heads` arg for non-bundle2 pushes.
        Bundle2Parser.ExtractedBundle2 extracted = null;
        if (bundleBytes.length >= 4 && bundleBytes[0] == 'H' && bundleBytes[1] == 'G' && bundleBytes[2] == '2' && bundleBytes[3] == '0') {
            // 백로그 26번: Wire1Commands.capabilitiesString()이 이제 bundle2=를 광고하므로
            // (getbundle 버전 협상을 가능케 하려고) 실제 hg 클라이언트의 push도 더는 맨 cg1
            // 바이트가 아니라 HG20/bundle2 봉투(mercurial/exchange.py의 _pushbundle2)로 body를
            // 감싸 보낸다 -- exchange._forcebundle1이 remote.capable('bundle2')를 그대로
            // 따르기 때문(실측, 2026-09-04: 이 광고를 추가하기 전엔 realHgPushesToHg4jServedOverHttp
            // 등 기존 push interop 테스트가 이미 통과했다는 것 자체가 "그때는 항상 HG10만
            // 왔다"는 증거였는데, 광고를 추가하자 즉시 "0\n... not a Mercurial bundle" 실패로
            // 재현·확인됨). Bundle2Parser는 이미 이 봉투를 파싱하는 유틸(원래는 getbundle
            // 응답을 읽는 클라이언트 쪽 용도)을 갖고 있어 그대로 재사용한다.
            extracted = Bundle2Parser.extractChangegroupDetailed(new ByteArrayInputStream(bundleBytes));
            changegroupBytes = extracted.changegroupBytes;
            cgVersion = extracted.cgVersion;
        } else if (bundleBytes.length >= 6 && bundleBytes[0] == 'H' && bundleBytes[1] == 'G' && bundleBytes[2] == '1' && bundleBytes[3] == '0') {
            String comp = new String(bundleBytes, 4, 2, StandardCharsets.US_ASCII);
            ByteArrayInputStream bais = new ByteArrayInputStream(bundleBytes, 6, bundleBytes.length - 6);
            if ("UN".equals(comp)) {
                changegroupBytes = bais.readAllBytes();
            } else if ("GZ".equals(comp)) {
                try (java.util.zip.InflaterInputStream inflater = new java.util.zip.InflaterInputStream(bais)) {
                    changegroupBytes = inflater.readAllBytes();
                }
            } else if ("BZ".equals(comp)) {
                // for HG10BZ the "BZ" in the header is the first two bytes of the bzip2 stream itself ("BZh9...")
                try (org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream bz =
                             new org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(
                                     new ByteArrayInputStream(bundleBytes, 4, bundleBytes.length - 4))) {
                    changegroupBytes = bz.readAllBytes();
                }
            } else {
                throw new HgCorruptDataException("Unsupported compression format in local push: HG10" + comp);
            }
        }

        try (ByteArrayInputStream bais = new ByteArrayInputStream(changegroupBytes)) {
            ChangegroupParser.ChangegroupBundle bundle =
                    ChangegroupParser.parseBundle(bais, cgVersion);

            int fileRevisions = 0;
            int filesTouched = 0;
            if (bundle.fileGroups != null) {
                for (ChangegroupParser.FileGroup group : bundle.fileGroups) {
                    if (group.entries != null && !group.entries.isEmpty()) {
                        filesTouched++;
                        fileRevisions += group.entries.size();
                    }
                }
            }
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
                        throw new IOException("prechangegroup hook failed"); // the original's wording for a failing hook
                    }
                }
            }

            // Apply bundle natively to remoteRepo using transactional API of PullCommand.
            // Backlog item 38: this is the SERVER direction of a concurrent push (Wire1Commands's
            // unbundle -- HTTP and SSH both -- and the file:// local-peer role reach this exact
            // same code path). Real hg's own server-side unbundle apply (mercurial/exchange.py's
            // unbundle(): `with repo.lock(), repo.transaction(...)`) waits for the target repo's
            // store lock (repo.lock() default wait=True, timeout from ui.timeout -- 600s default)
            // rather than failing on the very first contended attempt; confirmed live against
            // real hg 7.2 (2026-09-04): with the remote's store lock artificially held and
            // ui.timeout=2 configured on the server, a real `hg push` over HTTP waited ~2s before
            // the request failed (surfaced to the real-hg client as "abort: HTTP Error 500", since
            // real hg's own wireprotov1server.unbundle() does not specially catch
            // error.LockHeld/LockUnavailable -- it's an unhandled exception that the WSGI/CGI
            // layer turns into a 500). hg4j's own equivalent (this pullApi.applyBundle call) used
            // to lock with timeoutMs=0 (immediate fail-fast) unconditionally -- passing the
            // repository's own resolvePushLockTimeoutMs() (mirrors ui.timeout) here makes it wait
            // like real hg's does, while every OTHER lockStore()/lockWorkingCopy() caller
            // (commit, update, rebase, ...) is intentionally left untouched -- see
            // HgRepository#lockStore(int)'s doc.
            PullCommand pullApi = new PullCommand(remoteRepo);
            int lockTimeoutMs = remoteRepo.resolvePushLockTimeoutMs();
            // a bundle2 check:heads part and a bundle1 `heads` argument are reported with the original's two wordings
            FetchCommand.PostLockValidator headsCheck = buildPushRaceValidator(
                    extracted != null ? extracted.checkHeadsRaw : null, heads,
                    extracted != null ? PUSH_RACED_MESSAGE : "repository changed while uploading changes - please try again");
            FetchCommand.PostLockValidator raceCheck = headsCheck;
            if (extraValidator != null) {
                raceCheck = () -> {
                    if (headsCheck != null) {
                        headsCheck.validate();
                    }
                    extraValidator.validate();
                };
            }
            List<byte[]> imported = pullApi.applyBundle(bundle, lockTimeoutMs, raceCheck);

            // Restore remote dirstate to preserve bare repo status
            if (dirstateBackup != null) {
                Files.write(dirstateFile.toPath(), dirstateBackup);
            } else {
                Files.deleteIfExists(dirstateFile.toPath());
            }

            if (!preTxnHooks.isEmpty()) {
                List<String> appliedHexes = new ArrayList<>();
                for (byte[] node : imported) {
                    appliedHexes.add(NodeIdUtil.toHex(node));
                }
                Map<String, Object> txnCtx = new HashMap<>();
                txnCtx.put("repository", remoteRepo);
                txnCtx.put("nodes", appliedHexes);
                for (HgHook hook : preTxnHooks) {
                    if (!hook.run(txnCtx)) {
                        try (io.github.search5.hg4j.lib.HgLock lock = remoteRepo.lockStore(remoteRepo.resolvePushLockTimeoutMs())) {
                            new io.github.search5.hg4j.api.RollbackCommand(remoteRepo).call();
                        }
                        // the rollback restores the dirstate it recorded, which for a bare server is not the one it had
                        if (dirstateBackup != null) {
                            Files.write(dirstateFile.toPath(), dirstateBackup);
                        } else {
                            Files.deleteIfExists(dirstateFile.toPath());
                        }
                        throw new io.github.search5.hg4j.errors.HgHookRejectedException("pretxnchangegroup hook failed", true);
                    }
                }
            }
            publishImported(imported);

            // like real hg's checkrequireslfs hook: a repository that received an LFS revision requires lfs from now on
            if (bundle.fileGroups != null && bundle.fileGroups.stream().anyMatch(group -> group.entries != null
                    && group.entries.stream().anyMatch(entry -> (entry.flags & io.github.search5.hg4j.storage.Revlog.REVIDX_EXTSTORED) != 0))) {
                io.github.search5.hg4j.lfs.HgLfsConfig.addRequirement(remoteRepo);
            }

            List<String> importedNodeHexes = new ArrayList<>();
            for (byte[] node : imported) {
                importedNodeHexes.add(NodeIdUtil.toHex(node));
            }
            if (!postHooks.isEmpty()) {
                remoteRepo.afterLock(() -> {
                    Map<String, Object> postCtx = new HashMap<>();
                    postCtx.put("repository", remoteRepo);
                    postCtx.put("nodes", importedNodeHexes);
                    for (HgHook hook : postHooks) {
                        hook.run(postCtx);
                    }
                });
            }

            return new PushResult("push successful, imported " + imported.size() + " changesets natively", importedNodeHexes, fileRevisions, filesTouched);
        }
    }

    /**
     * {@code phases.registernew}: a publishing repository makes the changesets it receives public; a non-publishing one records
     * them as draft (a changeset whose parents are public needs a draft root of its own, otherwise it would count as public).
     */
    private void publishImported(List<byte[]> imported) throws IOException {
        if (imported == null || imported.isEmpty()) {
            return;
        }
        remoteRepo.clearRevlogCache();
        io.github.search5.hg4j.storage.Revlog changelog = io.github.search5.hg4j.transport.wireprotov1.RepoSupport.changelog(remoteRepo);
        io.github.search5.hg4j.phase.PhaseRoots roots = remoteRepo.getPhaseRoots();
        if (!io.github.search5.hg4j.transport.wireprotov1.RepoSupport.isPublishing(remoteRepo)) {
            // parents first: a child of a draft or secret changeset inherits that phase and needs no root of its own
            for (byte[] node : imported) {
                if (roots.getPhase(new NodeId(node), changelog) == io.github.search5.hg4j.phase.PhaseRoots.Phase.PUBLIC) {
                    roots.setPhase(new NodeId(node), io.github.search5.hg4j.phase.PhaseRoots.Phase.DRAFT, changelog);
                }
            }
            return;
        }
        for (int i = imported.size() - 1; i >= 0; i--) {
            byte[] node = imported.get(i);
            if (roots.getPhase(new NodeId(node), changelog) != io.github.search5.hg4j.phase.PhaseRoots.Phase.PUBLIC) {
                io.github.search5.hg4j.api.Hg.wrap(remoteRepo).phase().setRevision(NodeIdUtil.toHex(node)).setPhase(0).call();
            }
        }
    }

    /**
     * {@code exchange.check_heads} for a bundle1 {@code unbundle}: the client's {@code heads} argument (the heads it saw, a hash of
     * them or {@code force}) against the current ones, reported as {@code repository changed while <context> - please try again}.
     * The original runs it twice: {@code preparing changes} before it reads the payload and {@code uploading changes} under the lock.
     */
    public void checkWireHeads(List<String> wireHeads, String context) throws IOException {
        FetchCommand.PostLockValidator validator = buildPushRaceValidator(
                null, wireHeads, "repository changed while " + context + " - please try again");
        if (validator != null) {
            validator.validate();
        }
    }

    /**
     * The delta base and delta of every entry of one group, chosen the way {@code revlog._emit_revisions} chooses them in its
     * standard mode: the revision's stored delta parent if the receiver can use it (it has been sent before in this group, or it
     * is an ancestor of the history the receiver already has), a full text when the revlog stores the revision as one (a changelog
     * never stores deltas), otherwise the first usable parent, the revision before the group, or a full text.
     *
     * @param storesDeltaChains false for the changelog: it is stored as full texts, so every entry is a full text
     */
    private static void reselectDeltas(Revlog log, List<ChangegroupParser.ChangeGroupEntry> entries, boolean storesDeltaChains)
            throws IOException {
        if (entries.isEmpty()) {
            return;
        }
        List<Integer> revs = new ArrayList<>();
        for (ChangegroupParser.ChangeGroupEntry entry : entries) {
            revs.add(log.findRevision(entry.node));
        }
        java.util.Set<Integer> sent = new HashSet<>(revs);
        // the history the receiver is assumed to have: the ancestors of the parents that are not themselves sent
        java.util.Deque<Integer> work = new java.util.ArrayDeque<>();
        java.util.Set<Integer> available = new HashSet<>();
        for (int rev : revs) {
            Revlog.IndexRecord record = log.getIndexRecord(rev);
            for (int parent : new int[]{record.getParent1(), record.getParent2()}) {
                if (parent >= 0 && !sent.contains(parent) && available.add(parent)) {
                    work.add(parent);
                }
            }
        }
        while (!work.isEmpty()) {
            Revlog.IndexRecord record = log.getIndexRecord(work.poll());
            for (int parent : new int[]{record.getParent1(), record.getParent2()}) {
                if (parent >= 0 && available.add(parent)) {
                    work.add(parent);
                }
            }
        }
        int prevRev = log.getIndexRecord(revs.get(0)).getParent1();
        java.util.Set<Integer> emitted = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            int rev = revs.get(i);
            Revlog.IndexRecord record = log.getIndexRecord(rev);
            int p1 = record.getParent1();
            int p2 = record.getParent2();
            int base;
            if (!storesDeltaChains) {
                base = -1;
            } else {
                int deltaParent = log.getDeltaParent(rev);
                if (deltaParent != -1 && (emitted.contains(deltaParent) || available.contains(deltaParent))) {
                    base = deltaParent;
                } else if (deltaParent == -1) {
                    base = -1;
                } else if (p1 != -1 && (emitted.contains(p1) || available.contains(p1))) {
                    base = p1;
                } else if (p2 != -1 && (emitted.contains(p2) || available.contains(p2))) {
                    base = p2;
                } else {
                    base = prevRev;
                }
                // _candelta: no delta from or to a censored, ellipsis or externally stored revision
                int unfit = Revlog.REVIDX_ISCENSORED | Revlog.REVIDX_EXTSTORED | 0x4000;
                if (base != -1 && ((log.getIndexRecord(base).getFlags() & unfit) != 0 || (record.getFlags() & unfit) != 0)) {
                    base = -1;
                }
            }
            ChangegroupParser.ChangeGroupEntry entry = entries.get(i);
            entry.deltabase = base == -1 ? new byte[20] : log.getIndexRecord(base).getNodeId();
            entry.delta = log.revDiff(base, rev);
            emitted.add(rev);
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
     * Backlog item 38 ("PushRaced"-equivalent server-side race re-check): builds the validator
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
    private FetchCommand.PostLockValidator buildPushRaceValidator(List<byte[]> checkHeadsPartRaw, List<String> wireHeads, String racedMessage) {
        if (checkHeadsPartRaw != null) {
            List<String> expectedHex = new ArrayList<>(checkHeadsPartRaw.size());
            for (byte[] node : checkHeadsPartRaw) {
                expectedHex.add(NodeIdUtil.toHex(node));
            }
            return () -> validatePushNotRaced(expectedHex, racedMessage);
        }
        // Defensive sanitization: PushCommand itself already treats a null entry or the all-zero
        // "no real head" sentinel node as "not a real head" when computing its OWN local
        // validRemoteHeads (see PushCommand#call()) -- but the RAW, unfiltered head list is what
        // actually goes out over the wire as this heads= argument (a separate, pre-existing
        // quirk, unrelated to backlog 38, that PushCommandTest deliberately exercises via a
        // HgRemoteConnection test double). Since this argument was previously never consumed
        // server-side, that quirk was harmless; now that it feeds the race check, the same
        // filtering is applied here so a stray null/sentinel entry can't either NPE or be
        // compared against a real head hex.
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
            return () -> validatePushNotRacedHashed(expectedDigestHex, racedMessage);
        }
        return () -> validatePushNotRaced(sanitized, racedMessage);
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

    private void validatePushNotRaced(List<String> expectedHeadHex, String racedMessage) throws IOException {
        // An empty repository's only "head" is the null node (real hg: repo.heads() == [nullid], which is also what a client
        // sends back), so the null node is left out on both sides and "no heads" equals "[nullid]".
        TreeSet<String> expected = new TreeSet<>();
        for (String h : expectedHeadHex) {
            if (!NULL_NODE_HEX.equalsIgnoreCase(h)) {
                expected.add(h.toLowerCase());
            }
        }
        TreeSet<String> current = new TreeSet<>();
        for (String h : new HgLocalClient(remoteRepo).getHeads()) {
            if (!NULL_NODE_HEX.equalsIgnoreCase(h)) {
                current.add(h.toLowerCase());
            }
        }
        if (!expected.equals(current)) {
            throw new HgPushRacedException(racedMessage);
        }
    }

    private void validatePushNotRacedHashed(String expectedDigestHex, String racedMessage) throws IOException {
        List<String> currentHex = new ArrayList<>(new HgLocalClient(remoteRepo).getHeads());
        if (currentHex.isEmpty()) {
            // real hg's repo.heads() of an empty repository is [nullid], and that is what a client hashes
            currentHex.add(NULL_NODE_HEX);
        }
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
                throw new HgPushRacedException(racedMessage);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 unavailable for push-race hashed-heads check", e);
        }
    }

    @Override
    public String lookup(String key) throws IOException {
        File clIdx = new File(remoteRepo.getStoreDir(), "00changelog.i");
        File clDat = new File(remoteRepo.getStoreDir(), "00changelog.d");
        try {
            return NodeIdUtil.toHex(NodeIdUtil.resolveRevision(remoteRepo.getRevlog(clIdx, clDat), key));
        } catch (IOException | RuntimeException e) {
            throw new io.github.search5.hg4j.errors.HgValidationException("unknown revision '" + key + "'");
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
        } else if ("obsolete".equals(namespace)) {
            if (io.github.search5.hg4j.obsolete.ObsStore.exchangeEnabled(remoteRepo)) {
                map.putAll(io.github.search5.hg4j.obsolete.ObsStore.listMarkers(remoteRepo));
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
    public byte[] getCachedBundleInline(String path) throws IOException {
        return HgRemoteConnection.decodeInlineBundle(io.github.search5.hg4j.transport.wireprotov1.Wire1Commands
                .getCachedBundleInline(remoteRepo, Map.of("path", path)).getPayload());
    }

    @Override
    public boolean pushkey(String namespace, String key, String oldVal, String newVal) throws IOException {
        if ("bookmarks".equals(namespace)) {
            // real hg's pushkey takes wlock and lock around the compare and the write: otherwise two pushes both see the old value
            int timeout = remoteRepo.resolvePushLockTimeoutMs();
            try (io.github.search5.hg4j.lib.HgLock wlock = remoteRepo.lockWorkingCopy(timeout);
                 io.github.search5.hg4j.lib.HgLock storeLock = remoteRepo.lockStore(timeout)) {
                return pushBookmarkKey(key, oldVal, newVal);
            } catch (io.github.search5.hg4j.errors.HgLockException e) {
                throw new IOException(e.getMessage(), e); // the wire interface only knows IOException
            }
        }
        if ("phases".equals(namespace)) {
            return pushPhaseKey(key, oldVal, newVal);
        }
        if ("obsolete".equals(namespace) && io.github.search5.hg4j.obsolete.ObsStore.exchangeEnabled(remoteRepo)) {
            try {
                return io.github.search5.hg4j.obsolete.ObsStore.pushMarker(remoteRepo, key, oldVal, newVal);
            } catch (io.github.search5.hg4j.errors.HgLockException e) {
                throw new IOException(e.getMessage(), e);
            }
        }
        return false;
    }

    /**
     * {@code phases.pushphase}: under the store lock only, a changeset moves to a lower phase number when it is at the old phase now; if it
     * is at the new phase already (a race with the same result) that is success too; anything else is refused.
     */
    private boolean pushPhaseKey(String key, String oldVal, String newVal) throws IOException {
        int oldPhase;
        int newPhase;
        if (oldVal == null || newVal == null) {
            return false;
        }
        try {
            oldPhase = Math.abs(Integer.parseInt(oldVal.trim()));
            newPhase = Math.abs(Integer.parseInt(newVal.trim()));
        } catch (NumberFormatException e) {
            return false;
        }
        int timeout = remoteRepo.resolvePushLockTimeoutMs();
        try (io.github.search5.hg4j.lib.HgLock storeLock = remoteRepo.lockStore(timeout)) {
            int current = new io.github.search5.hg4j.api.PhaseCommand(remoteRepo).setRevision(key).call();
            if (current == oldPhase && newPhase < oldPhase) {
                new io.github.search5.hg4j.api.PhaseCommand(remoteRepo).setRevision(key).setPhase(newPhase).call();
                return true;
            }
            return current == newPhase;
        } catch (io.github.search5.hg4j.errors.HgLockException e) {
            throw new IOException(e.getMessage(), e);
        } catch (IOException unknown) {
            return false; // a node the repository does not have
        }
    }

    private boolean pushBookmarkKey(String key, String oldVal, String newVal) throws IOException {
        {
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
