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

/**
 * Pure Java transport that provides a connection to a Mercurial repository on the local filesystem.
 * Directly parses and merges the remote repository without invoking a native hg subprocess.
 */
public class HgLocalClient implements HgRemoteConnection {

    private final File repoDir;
    private final HgRepository remoteRepo;

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
        return List.of("changegroup", "getbundle", "lookup", "pushkey", "branchmap");
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
        File clIdx = new File(remoteRepo.getStoreDir(), "00changelog.i");
        File clDat = new File(remoteRepo.getStoreDir(), "00changelog.d");
        File mfIdx = new File(remoteRepo.getStoreDir(), "00manifest.i");
        File mfDat = new File(remoteRepo.getStoreDir(), "00manifest.d");

        if (!clIdx.exists()) {
            return new byte[0];
        }

        Revlog changelog = remoteRepo.getRevlog(clIdx, clDat);
        int count = changelog.getRevisionCount();
        if (count == 0) {
            return new byte[0];
        }

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

        if (startRev >= count) {
            return new byte[0];
        }

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

        // 1a. Pack Changelogs
        // cg1은 각 엔트리의 델타를 "실제 DAG 부모(p1)"가 아니라 "이 그룹 스트림에서 바로
        // 직전에 패킹된 엔트리"를 기준으로 인코딩한다(mercurial/changegroup.py의
        // ChangeGroupPacker01, forcedeltaparentprev=True 실측, 2026-09-01). 다중 head
        // 저장소에서 p1 기준으로 델타를 만들면 실제 hg 및 hg4j 자신의 unbundle 로직과도
        // 어긋나 콘텐츠가 깨진다.
        // incremental pull(startRev > 0)이면 첫 신규 엔트리의 베이스는 양쪽이 이미 공유하는
        // 마지막 공통 리비전(startRev-1)의 콘텐츠여야 한다 — 빈 바이트로 리셋하면 수신측의
        // rev-1 기준 복원과 어긋난다.
        byte[] prevClContent = (startRev > 0) ? changelog.getRevisionContent(startRev - 1) : new byte[0];
        // cg2 이상만 실제로 읽는 명시적 deltabase 필드 -- "이 그룹 스트림에서 바로 직전에
        // 패킹된 엔트리"의 node를 그대로 선언한다(위 delta 계산과 동일한 베이스를 명시적으로
        // 밝히는 것뿐, cg1의 암묵적 규칙과 결과적으로 같은 콘텐츠). 첫 엔트리(startRev==0)는
        // all-zero(널 리비전) 베이스.
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
                    clEntry.sidedata = io.github.search5.hg4j.storage.SidedataCodec.serialize(sidedata);
                }
            }
            bundle.changelogEntries.add(clEntry);
            prevClContent = content;
            prevClNode = clRec.getNodeId();
        }

        // 1b. Pack Manifests
        Revlog manifest = remoteRepo.getRevlog(mfIdx, mfDat);
        Set<String> affectedFiles = new HashSet<>();
        // incremental pull이면 마지막 공통 changelog 리비전(startRev-1)이 가리키는 manifest
        // 콘텐츠를 첫 신규 엔트리의 베이스로 삼는다(changelog와 동일한 이유).
        byte[] prevMfContent = new byte[0];
        // cg2 이상의 명시적 deltabase(위 changelog의 prevClNode와 같은 이유) -- 마지막으로
        // 실제 번들에 추가된 manifest 엔트리의 node. 첫 엔트리는 all-zero(널 리비전) 베이스.
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
            File flIdx = CommitCommand.getFilelogIndex(remoteRepo.getStoreDir(), path);
            File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
            if (!flIdx.exists()) continue;

            Revlog fl = remoteRepo.getRevlog(flIdx, flDat);
            List<ChangegroupParser.ChangeGroupEntry> flEntries = new ArrayList<>();

            // incremental pull이면 이미 공유된 마지막 filelog 리비전(linkRev < startRev 중 가장
            // 최근 것)의 콘텐츠를 첫 신규 엔트리의 베이스로 삼는다.
            // Raw (as-stored) content throughout, not getRevisionContent(): a filelog revision
            // can be censored (Revlog.REVIDX_ISCENSORED), and bundling must transfer its
            // tombstone bytes as-is rather than throwing HgCensoredContentException -- real hg's
            // own changegroup packer likewise always uses rawdata()/`_chunk()`, never the decoded
            // text.
            byte[] prevFlContent = new byte[0];
            // cg2 이상의 명시적 deltabase(changelog/manifest와 동일한 이유). 첫 엔트리는
            // all-zero(널 리비전) 베이스.
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
            return Bundle2Parser.wrapChangegroupInBundle2(cgBytes, version);
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

    @Override
    public String push(byte[] bundleBytes, List<String> heads) throws IOException, HgLockException {
        return pushWithHooks(bundleBytes, heads, List.of(), List.of()).status;
    }

    /** Result of {@link #pushWithHooks}, reported back so wireprotocol server glue can tell an
     * empty/rejected push apart from a genuine import when firing hooks or building the pushres line. */
    public static class PushResult {
        public final String status;
        public final List<String> importedNodeHexes;

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
            Bundle2Parser.ExtractedBundle2 extracted = Bundle2Parser.extractChangegroupDetailed(
                    new ByteArrayInputStream(bundleBytes));
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

            // Apply bundle natively to remoteRepo using transactional API of PullCommand
            PullCommand pullApi = new PullCommand(remoteRepo);
            List<byte[]> imported = pullApi.applyBundle(bundle);

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
