package io.github.search5.hg4j.api;

import java.util.logging.Level;
import java.util.logging.Logger;
import io.github.search5.hg4j.bundle.ChangegroupParser;
import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.transport.HgRemoteConnection;
import io.github.search5.hg4j.transport.HgRemoteConnectionFactory;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.SafeFileIO;
import io.github.search5.hg4j.lib.ProgressMonitor;
import io.github.search5.hg4j.lib.NullProgressMonitor;
import io.github.search5.hg4j.transport.CredentialsProvider;
import io.github.search5.hg4j.errors.HgLockException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import io.github.search5.hg4j.bundle.Bundle2Parser;
import io.github.search5.hg4j.bundle.ClonebundlesManifest;
import io.github.search5.hg4j.errors.HgCorruptDataException;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.phase.PhaseRoots;
import io.github.search5.hg4j.treewalk.HgTreeFilter;
import java.nio.channels.FileChannel;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.InflaterInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

/**
 * Pure network sync command to fetch changesets from a remote repository.
 * Updates local revlog store, bookmarks and phases atomically, but does not modify the working copy dirstate.
 */
public class FetchCommand {
    private static final Logger LOGGER = Logger.getLogger(FetchCommand.class.getName());

    private final HgRepository repository;
    private String sourceUrl;
    private ProgressMonitor monitor = NullProgressMonitor.INSTANCE;
    private HgTreeFilter treeFilter = HgTreeFilter.ALL;
    private CredentialsProvider credentialsProvider;

    public FetchCommand(HgRepository repository) {
        this.repository = repository;
    }

    public FetchCommand setCredentialsProvider(CredentialsProvider credentialsProvider) {
        this.credentialsProvider = credentialsProvider;
        return this;
    }

    public FetchCommand setTreeFilter(HgTreeFilter treeFilter) {
        if (treeFilter != null) {
            this.treeFilter = treeFilter;
        }
        return this;
    }

    public FetchCommand setProgressMonitor(ProgressMonitor monitor) {
        if (monitor != null) {
            this.monitor = monitor;
        }
        return this;
    }

    public FetchCommand setSource(String sourceUrl) {
        this.sourceUrl = sourceUrl;
        return this;
    }

    /**
     * Backlog 30: when the caller hasn't explicitly narrowed this fetch (still the default
     * {@link HgTreeFilter#ALL}), pick up whatever narrowspec the repository itself was narrow
     * cloned with -- so a narrow clone's scope keeps being honored on every later {@code pull},
     * not just at the initial {@code NarrowCloneCommand} call site.
     */
    private void resolveNarrowTreeFilterIfDefault() throws IOException {
        if (this.treeFilter == HgTreeFilter.ALL) {
            this.treeFilter = HgTreeFilter.loadFromRepository(repository);
        }
    }

    /**
     * Backlog item 40: recovers the narrowspec patterns behind {@code treeFilter} -- when it's
     * actually a {@link HgTreeFilter.NarrowSpecFilter} (built by {@link
     * io.github.search5.hg4j.api.NarrowCloneCommand} or {@link #resolveNarrowTreeFilterIfDefault}
     * from the repository's stored narrowspec) and the remote advertised {@link
     * HgRemoteConnection#supportsNarrow()} -- so {@link #downloadChangegroupBundle} can ask the
     * remote to do the filtering itself instead of hg4j fetching the full changegroup and
     * discarding out-of-scope filelogs locally afterward.
     *
     * <p>Returns {@code null} (meaning: request the plain, unfiltered changegroup, exactly like
     * before this backlog item) for any other {@code treeFilter} -- e.g. a plain {@link
     * HgTreeFilter#createPathPrefixFilter} some other caller supplied, which has no narrowspec
     * patterns to forward -- or when the remote doesn't understand the narrow wire arguments.
     * This is a pure bandwidth optimization: {@link #applyBundle} still applies {@code
     * treeFilter} itself locally regardless (harmless no-op for paths the remote already omitted,
     * still correct/needed for a remote that ignored the request or doesn't support it).
     */
    private static HgRemoteConnection.NarrowScope narrowScopeFor(HgRemoteConnection client, HgTreeFilter treeFilter) {
        if (!(treeFilter instanceof HgTreeFilter.NarrowSpecFilter narrowSpecFilter) || !client.supportsNarrow()) {
            return null;
        }
        List<String> includes = new ArrayList<>();
        for (HgTreeFilter.NarrowPattern p : narrowSpecFilter.getIncludes()) {
            includes.add(p.toSpecString());
        }
        List<String> excludes = new ArrayList<>();
        for (HgTreeFilter.NarrowPattern p : narrowSpecFilter.getExcludes()) {
            excludes.add(p.toSpecString());
        }
        return new HgRemoteConnection.NarrowScope(includes, excludes);
    }

    public List<byte[]> call() throws IOException, HgLockException {
        if (sourceUrl == null || sourceUrl.isEmpty()) {
            throw new IllegalStateException("Remote source URL must be specified.");
        }
        resolveNarrowTreeFilterIfDefault();

        monitor.start("Fetching changes", 3);
        monitor.update(1);

        try (HgRemoteConnection client = HgRemoteConnectionFactory.createConnection(sourceUrl)) {
            if (this.credentialsProvider != null) {
                client.setCredentialsProvider(this.credentialsProvider);
            }
            List<String> caps = client.getCapabilities();
            List<String> remoteHeads = client.getHeads();
            if (remoteHeads.isEmpty()) {
                monitor.end();
                return new ArrayList<>();
            }

            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");
            Revlog localChangelog = repository.getRevlog(clIdx, clDat);

            // Clonebundles bypass (real hg's own client algorithm, see
            // decisions/mercurial-spec-compliance-requirement.md's Clonebundles plan): only
            // attempted for a genuinely empty local repository (i.e. this call is effectively a
            // clone, not an incremental pull -- matching real hg, which only tries this during
            // `clone`) against a remote that actually advertised the capability. Real hg's own
            // client checks this transport-agnostically (`remote.capable(b'clonebundles')` in
            // `mercurial/exchange.py` works the same for an HTTP or SSH peer) -- previously this
            // was gated on `client instanceof HgRemoteClient` (HTTP only), which meant hg4j never
            // even attempted the bypass over SSH even when the SSH server advertised the
            // capability (backlog item 39 wave 5, wire-matrix track: a real hg4j-vs-real-hg
            // production bug, fixed by moving `supportsClonebundles()`/
            // `fetchClonebundlesManifest()` onto the shared `HgRemoteConnection` interface and
            // implementing them in `HgSshClient` too). A download or apply failure here is NOT
            // caught -- real hg deliberately never falls back to a normal pull on clonebundle
            // failure (see the plan doc for why), so the exception propagates and fails the whole
            // fetch.
            List<byte[]> clonebundleImported = null;
            if (localChangelog.getRevisionCount() == 0 && client.supportsClonebundles()) {
                clonebundleImported = tryApplyClonebundle(client);
                if (clonebundleImported != null) {
                    repository.clearRevlogCache();
                    localChangelog = repository.getRevlog(clIdx, clDat);
                }
            }

            // Calculate proper discovery & early-exit
            List<String> common = new ArrayList<>();
            int count = localChangelog.getRevisionCount();
            
            boolean upToDate = true;
            for (String rHead : remoteHeads) {
                byte[] nodeBytes = NodeIdUtil.fromHex(rHead);
                if (localChangelog.findRevision(nodeBytes) == -1) {
                    upToDate = false;
                } else {
                    common.add(rHead);
                }
            }
            if (upToDate && !remoteHeads.isEmpty() && count > 0) {
                // 새 changeset이 없어도 bookmark/phase는 원격에서 이미 변경됐을 수 있으므로
                // 반드시 동기화한다 — 예전에는 여기서 그냥 리턴해버려서 "새 커밋 없이
                // bookmark만 이동한 pull"이 조용히 무시됐다(2026-09-01 발견·수정).
                syncBookmarksAndPhases(client, localChangelog, new ArrayList<>());
                monitor.end();
                return mergeClonebundleResults(clonebundleImported, new ArrayList<>());
            }

            // Perform true Mercurial Wire Protocol between/known discovery exchange
            if (!upToDate && !remoteHeads.isEmpty() && count > 0) {
                try {
                    List<String> pairs = new ArrayList<>();
                    if (!common.isEmpty()) {
                        pairs.add(common.get(0) + "-" + remoteHeads.get(0));
                    }
                    if (!pairs.isEmpty()) {
                        List<String> betweens = client.between(pairs);
                        LOGGER.log(Level.INFO, "[Fetch Discovery] between query returned " + betweens.size() + " intermediate nodes");
                        for (String btn : betweens) {
                            byte[] btnBytes = NodeIdUtil.fromHex(btn);
                            if (localChangelog.findRevision(btnBytes) != -1) {
                                if (!common.contains(btn)) {
                                    common.add(btn);
                                }
                            }
                        }
                    }
                    
                    if (!common.isEmpty()) {
                        String knownResult = client.known(common);
                        LOGGER.log(Level.INFO, "[Fetch Discovery] known query returned: " + knownResult);
                        List<String> actualCommon = new ArrayList<>();
                        for (int i = 0; i < Math.min(common.size(), knownResult.length()); i++) {
                            if (knownResult.charAt(i) == '1') {
                                actualCommon.add(common.get(i));
                            }
                        }
                        if (!actualCommon.isEmpty()) {
                            common.clear();
                            common.addAll(actualCommon);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed standard between/known discovery negotiation, falling back to leaf match", e);
                }
            }

            for (String hexNode : computeLocalLeafHexes(localChangelog)) {
                if (!common.contains(hexNode)) {
                    common.add(hexNode);
                }
            }

            monitor.update(1);

            DownloadedChangegroup downloaded = downloadChangegroupBundle(client, caps, common, remoteHeads, narrowScopeFor(client, treeFilter));
            if (downloaded == null) {
                syncBookmarksAndPhases(client, localChangelog, new ArrayList<>());
                monitor.end();
                return mergeClonebundleResults(clonebundleImported, new ArrayList<>());
            }

            ChangegroupParser.ChangegroupBundle bundle =
                    ChangegroupParser.parseBundle(new ByteArrayInputStream(downloaded.changegroupBytes), downloaded.cgVersion);
            List<byte[]> results = applyBundle(bundle);

            syncBookmarksAndPhases(client, localChangelog, results);

            monitor.update(1);
            monitor.end();
            return mergeClonebundleResults(clonebundleImported, results);
        }
    }

    /**
     * The local changelog's own "leaf" revisions (those that are nobody's parent) as hex node
     * IDs -- real hg's own cheapest possible approximation of "what the local side already has"
     * to offer the remote as a common-ancestor hint, used both here and by {@link
     * IncomingCommand#call()} (backlog item 39 wave 5, wire-matrix track: extracted so both
     * commands share exactly one implementation instead of {@code IncomingCommand} reimplementing
     * it slightly differently).
     */
    static List<String> computeLocalLeafHexes(Revlog localChangelog) {
        List<String> leaves = new ArrayList<>();
        int count = localChangelog.getRevisionCount();
        if (count == 0) {
            return leaves;
        }
        boolean[] isParent = new boolean[count];
        for (int i = 0; i < count; i++) {
            Revlog.IndexRecord rec = localChangelog.getIndexRecord(i);
            if (rec.getParent1() >= 0 && rec.getParent1() < count) {
                isParent[rec.getParent1()] = true;
            }
            if (rec.getParent2() >= 0 && rec.getParent2() < count) {
                isParent[rec.getParent2()] = true;
            }
        }
        for (int i = 0; i < count; i++) {
            if (!isParent[i]) {
                leaves.add(NodeIdUtil.toHex(localChangelog.getIndexRecord(i).getNodeId()));
            }
        }
        return leaves;
    }

    /** The result of {@link #downloadChangegroupBundle}: a raw changegroup payload already
     * unwrapped from any HG20 (bundle2) or HG10 (bundle1) envelope/compression, plus the
     * changegroup format version it's encoded in. */
    static final class DownloadedChangegroup {
        final byte[] changegroupBytes;
        final String cgVersion;

        DownloadedChangegroup(byte[] changegroupBytes, String cgVersion) {
            this.changegroupBytes = changegroupBytes;
            this.cgVersion = cgVersion;
        }
    }

    /**
     * Shared by {@link #call()} and {@link IncomingCommand#call()} (backlog item 39 wave 5,
     * wire-matrix track): negotiates {@code getbundle} vs. the legacy {@code changegroup} wire
     * command, downloads the bundle, and unwraps it down to a raw changegroup payload + its
     * format version.
     *
     * <p>Preferring {@code getbundle} whenever the remote advertises it (exactly like real hg's
     * own modern client always does) isn't just an optimization here -- it avoids a real
     * landmine in real hg's own <em>legacy</em> {@code changegroup} wire command handler
     * (confirmed independently of hg4j entirely, 2026-09-05, via plain {@code curl} against an
     * unmodified {@code hg serve}): {@code discovery.outgoing()} in {@code mercurial/
     * discovery.py} throws an uncaught {@code ParseError} ("too many revspec arguments
     * specified") server-side -- an HTTP 500, not a clean protocol error -- whenever the {@code
     * changegroup} command's {@code roots} argument is an empty list against a non-empty
     * repository, because it calls {@code repo.revs('::%ln', missingroots, ancestorsof)} with
     * <em>two</em> positional substitution values for a revset expression that only has
     * <em>one</em> {@code %ln} placeholder. {@code IncomingCommand} used to always call {@code
     * getChangegroup(Collections.emptyList())} directly regardless of getbundle support, which
     * hit this on every single real-hg server that had any content at all -- i.e.
     * {@code IncomingCommand} was completely broken against any real, non-empty remote before
     * this fix.
     *
     * @return {@code null} if there is nothing to fetch (an empty response)
     */
    static DownloadedChangegroup downloadChangegroupBundle(HgRemoteConnection client, List<String> caps,
                                                            List<String> common, List<String> remoteHeads) throws IOException {
        return downloadChangegroupBundle(client, caps, common, remoteHeads, null);
    }

    /**
     * Same as the 4-argument overload, but additionally negotiates real hg's narrow clone wire
     * arguments (backlog item 40) when {@code narrowScope} is non-{@code null} -- see {@link
     * HgRemoteConnection#getBundle(List, List, List, HgRemoteConnection.NarrowScope)}'s doc for
     * the real-hg-verified wire shape and measured bandwidth savings. {@code null} preserves the
     * old always-full-changegroup behavior (used by {@link IncomingCommand}, which never narrows).
     */
    static DownloadedChangegroup downloadChangegroupBundle(HgRemoteConnection client, List<String> caps,
                                                            List<String> common, List<String> remoteHeads,
                                                            HgRemoteConnection.NarrowScope narrowScope) throws IOException {
        boolean supportsGetBundle = caps.contains("getbundle") || caps.stream().anyMatch(c -> c.startsWith("getbundle"));

        byte[] bundleBytes;
        if (supportsGetBundle) {
            List<String> bundleCaps = new ArrayList<>();
            boolean supportsBundle2 = caps.contains("bundle2") || caps.stream().anyMatch(c -> c.startsWith("bundle2"));
            if (supportsBundle2) {
                // 실제 스펙(mercurial/exchange.py): 원격은 changegroup 버전 목록과 자신의
                // supportedoutgoingversions()의 교집합 중 max()를 그대로 골라 응답한다
                // (별도 우선순위 없이 단순 숫자 최댓값) — hg4j의 ChangegroupParser가 cg4/cg5
                // 델타 헤더까지 파싱할 수 있게 된 뒤로는(2026-09-03) 04/05까지 광고해야
                // 최신 hg(예: experimental.changegroup4/5=yes 켠 저장소)와 최적 포맷으로
                // 주고받는다. 기본 설정 저장소는 여전히 cg4/cg5를 광고하지 않으므로
                // 대부분은 그대로 cg3로 협상된다(실사용 회귀 없음).
                //
                // 실측(2026-09-03, Bundle2Parser#buildChangegroupBundleCaps 주석 참고): 이
                // changegroup 버전 목록은 평평한 "changegroup=..." 토큰이 아니라
                // "bundle2=<blob>" 토큰 안에 중첩돼야만 실제 hg가 인식한다 — 예전의 평평한
                // 토큰 방식으로는 bundle2 자체는 (bare "HG20" 토큰 덕에) 켜져도 버전
                // 교집합이 항상 비어 사실상 구식 bundle1(cg1)로 계속 폴백되고 있었다.
                bundleCaps.add("HG20");
                bundleCaps.add(Bundle2Parser.buildBundle2CapsToken("01,02,03,04,05"));
                bundleCaps.add("compression=GZ,BZ,ZS");
            }
            bundleBytes = client.getBundle(common, remoteHeads, bundleCaps, narrowScope);
        } else {
            bundleBytes = client.getChangegroup(common);
        }

        if (bundleBytes == null || bundleBytes.length == 0) {
            return null;
        }

        byte[] changegroupBytes = bundleBytes;
        String cgVersion = "01";
        if (bundleBytes.length >= 4 && bundleBytes[0] == 'H' && bundleBytes[1] == 'G' && bundleBytes[2] == '2' && bundleBytes[3] == '0') {
            Bundle2Parser.ExtractedBundle2 ext = Bundle2Parser.extractChangegroupDetailed(new ByteArrayInputStream(bundleBytes));
            changegroupBytes = ext.changegroupBytes;
            cgVersion = ext.cgVersion;
        } else if (bundleBytes.length >= 6 && bundleBytes[0] == 'H' && bundleBytes[1] == 'G' && bundleBytes[2] == '1' && bundleBytes[3] == '0') {
            String comp = new String(bundleBytes, 4, 2, StandardCharsets.US_ASCII);
            ByteArrayInputStream bais = new ByteArrayInputStream(bundleBytes, 6, bundleBytes.length - 6);
            if ("UN".equals(comp)) {
                changegroupBytes = bais.readAllBytes();
            } else if ("GZ".equals(comp)) {
                try (InflaterInputStream iis = new InflaterInputStream(bais)) {
                    changegroupBytes = iis.readAllBytes();
                }
            } else if ("BZ".equals(comp)) {
                byte[] rawData = bais.readAllBytes();
                byte[] bzData = new byte[rawData.length + 2];
                bzData[0] = 'B';
                bzData[1] = 'Z';
                System.arraycopy(rawData, 0, bzData, 2, rawData.length);
                try (BZip2CompressorInputStream bzis =
                             new BZip2CompressorInputStream(new ByteArrayInputStream(bzData))) {
                    changegroupBytes = bzis.readAllBytes();
                }
            } else {
                throw new HgCorruptDataException("Unsupported bundle1 compression format: HG10" + comp);
            }
            cgVersion = "01";
        }

        return new DownloadedChangegroup(changegroupBytes, cgVersion);
    }

    /**
     * Merges a clonebundle's imported commits (if any were applied) with whatever the normal
     * discovery/getbundle path found afterward, oldest-first, so callers that key off {@code
     * results.get(results.size() - 1)} for "the new tip" (e.g. {@link PullCommand}) still see the
     * true most recent head, and callers that only check {@code results.isEmpty()} (e.g. {@link
     * CloneCommand}) correctly see that a clone bootstrapped entirely from a clonebundle did in
     * fact import commits.
     */
    private static List<byte[]> mergeClonebundleResults(List<byte[]> clonebundleImported, List<byte[]> rest) {
        if (clonebundleImported == null || clonebundleImported.isEmpty()) {
            return rest;
        }
        List<byte[]> merged = new ArrayList<>(clonebundleImported);
        merged.addAll(rest);
        return merged;
    }

    /**
     * Real hg's Clonebundles client algorithm, minus the final "reconnect and pull the rest"
     * step (the caller's normal discovery/getbundle logic already does that unconditionally
     * right after this returns, whether or not a bundle was applied here).
     *
     * <ol>
     * <li>Fetch the manifest ({@code ?cmd=clonebundles}) and parse it.</li>
     * <li>Filter to entries hg4j can actually consume ({@link
     * io.github.search5.hg4j.bundle.ClonebundlesManifest#filterSupported}).</li>
     * <li>If nothing usable remains, do nothing (fall through to a normal full pull below —
     * this is not a failure, just "no clonebundle available/suitable").</li>
     * <li>Otherwise take the first remaining entry (real hg does the same absent an explicit
     * {@code ui.clonebundleprefers} sort configuration) and download+apply it via {@link
     * ClonebundlesCommand#downloadAndApply}. Any failure here propagates uncaught.</li>
     * </ol>
     *
     * @return the commits imported from the clonebundle if one was applied (possibly empty, if
     *         the bundle happened to be empty), or {@code null} if no clonebundle was applied at
     *         all (caller must refresh its {@link Revlog} view only in the non-null case)
     */
    private List<byte[]> tryApplyClonebundle(HgRemoteConnection client) throws IOException, HgLockException {
        String manifestText = client.fetchClonebundlesManifest();
        List<ClonebundlesManifest.Entry> entries =
                ClonebundlesManifest.filterSupported(
                        ClonebundlesManifest.parse(manifestText));
        if (entries.isEmpty()) {
            return null;
        }
        String url = entries.get(0).getUrl();
        LOGGER.log(Level.INFO, "Bypassing wire protocol for initial clone via clonebundle: {0}", url);
        return ClonebundlesCommand.downloadAndApply(repository, url);
    }

    /**
     * bookmark/phase 원격 동기화. 새 changeset이 있든 없든(예: bookmark만 이동하고 새
     * 커밋은 없는 pull) 항상 호출돼야 한다 — 예전에는 "새로 받아올 changegroup이 없음"
     * 조기 리턴 경로들이 이 동기화 자체를 건너뛰어서, 커밋 없이 bookmark만 이동한
     * 원격을 pull해도 로컬에 전혀 반영이 안 되는 버그가 있었다(2026-09-01 발견·수정,
     * Track B-3).
     *
     * @param newCommits 이번 fetch로 새로 받아온 커밋(phase를 draft로 표시하는 데 사용).
     *                   새 커밋이 없으면 빈 리스트.
     */
    private void syncBookmarksAndPhases(HgRemoteConnection client, Revlog localChangelog, List<byte[]> newCommits) {
        try {
            // Bookmarks Sync — ancestor(fast-forward) 인지 진짜 divergence인지 구분하는
            // 공용 병합 로직(BookmarkCommand.mergeFromRemote)에 위임한다. 예전에는 여기서
            // "원격이 가리키는 노드를 로컬이 갖고 있으면 무조건 덮어쓰기"만 해서 로컬의
            // 독자적인 bookmark 이동을 조용히 잃어버릴 수 있었다(2026-09-01 수정).
            Map<String, String> remoteBookmarks = client.listKeys("bookmarks");
            BookmarkCommand.mergeFromRemote(repository, remoteBookmarks, null);

            // Phases Sync
            //
            // Real hg's own phase-registration for newly-pulled-in changesets
            // (phases.registernew()/advanceboundary()) only records an explicit phaseroots
            // entry when the target phase is not already implied by the changeset's parents'
            // phase -- a plain descendant of an existing draft/secret changeset inherits that
            // phase via ancestry and needs no root of its own. Calling setPhase() unconditionally
            // for every one of newCommits (as this used to) would append one explicit line per
            // pulled changeset forever, diverging from real hg's minimal-roots phaseroots file
            // (see PhaseCommand/CommitCommand for the same fix applied to local commits).
            PhaseRoots phaseRoots = repository.getPhaseRoots();
            Map<String, String> remotePhases = client.listKeys("phases");
            for (byte[] nodeBytes : newCommits) {
                NodeId nodeId = new NodeId(nodeBytes);
                if (phaseRoots.getPhase(nodeId, localChangelog) != PhaseRoots.Phase.DRAFT) {
                    phaseRoots.setPhase(nodeId, PhaseRoots.Phase.DRAFT, localChangelog);
                }
            }
            if (remotePhases != null && !remotePhases.isEmpty()) {
                for (Map.Entry<String, String> entry : remotePhases.entrySet()) {
                    String hexNode = entry.getKey();
                    int phaseVal = Integer.parseInt(entry.getValue().trim());
                    byte[] nodeBytes = NodeIdUtil.fromHex(hexNode);
                    if (localChangelog.findRevision(nodeBytes) != -1) {
                        PhaseRoots.Phase p = PhaseRoots.Phase.fromValue(phaseVal);
                        NodeId nodeId = new NodeId(nodeBytes);
                        if (phaseRoots.getPhase(nodeId, localChangelog) != p) {
                            phaseRoots.setPhase(nodeId, p, localChangelog);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to synchronize remote bookmarks or phases during fetch", e);
        }
    }

    public List<byte[]> applyBundle(ChangegroupParser.ChangegroupBundle bundle) throws IOException, HgLockException {
        return applyBundle(bundle, 0, null);
    }

    /**
     * Same as {@link #applyBundle(ChangegroupParser.ChangegroupBundle)}, but acquires the store/
     * working-copy locks with a caller-supplied wait timeout instead of failing immediately on
     * contention -- used by the push/unbundle apply path (backlog item 38, both the server
     * direction, {@code HgLocalClient#pushWithHooks}, and the local-peer/{@code file://} direction
     * it shares) so a genuinely concurrent push waits like real hg's own {@code repo.lock()}
     * ({@code wait=True} default) instead of aborting on the very first contended attempt.
     *
     * @param lockTimeoutMs how long to wait for the store/wlock to clear, in milliseconds --
     *                      {@code 0} preserves the original fail-fast behavior.
     */
    public List<byte[]> applyBundle(ChangegroupParser.ChangegroupBundle bundle, int lockTimeoutMs) throws IOException, HgLockException {
        return applyBundle(bundle, lockTimeoutMs, null);
    }

    /**
     * Runs once the store/working-copy locks are actually held, before ANY part of the incoming
     * bundle is applied -- the exact point real hg's own {@code exchange.unbundle()} re-validates
     * a push against a race (backlog item 38: {@code mercurial/bundle2_part_handlers.py}'s {@code
     * check:heads}/{@code check:updated-heads} part handlers, run while processing the bundle2
     * envelope inside the just-acquired transaction/lock). Throwing here aborts the apply with
     * nothing yet written (no journal entries exist at this point), so the locks release cleanly
     * via the enclosing try-with-resources and the repository is left exactly as it was.
     */
    @FunctionalInterface
    public interface PostLockValidator {
        void validate() throws IOException;
    }

    /**
     * Same as {@link #applyBundle(ChangegroupParser.ChangegroupBundle, int)}, but additionally
     * runs {@code postLockValidator} (if non-null) immediately after the store/working-copy locks
     * are acquired and before any mutation begins -- see {@link PostLockValidator}'s doc.
     */
    public List<byte[]> applyBundle(ChangegroupParser.ChangegroupBundle bundle, int lockTimeoutMs,
                                     PostLockValidator postLockValidator) throws IOException, HgLockException {
        resolveNarrowTreeFilterIfDefault();
        List<byte[]> importedCommits = new ArrayList<>();
        if (bundle.changelogEntries.isEmpty()) {
            return importedCommits;
        }

        File dirstateFile = new File(repository.getDirectory(), ".hg/dirstate");
        byte[] dirstateBackup = dirstateFile.exists() ? Files.readAllBytes(dirstateFile.toPath()) : null;
        File fncacheFile = new File(repository.getStoreDir(), "fncache");
        byte[] fncacheBackup = fncacheFile.exists() ? Files.readAllBytes(fncacheFile.toPath()) : null;
        File journalFile = new File(repository.getStoreDir(), "journal");

        Map<File, Long> fileSizes = new HashMap<>();

        try (HgLock storeLock = repository.lockStore(lockTimeoutMs);
             HgLock wlock = repository.lockWorkingCopy(lockTimeoutMs)) {

            if (postLockValidator != null) {
                postLockValidator.validate();
            }

            Files.deleteIfExists(journalFile.toPath());
            
            if (dirstateFile.exists()) {
                File dirstateBackupFile = new File(repository.getDirectory(), ".hg/dirstate.backup");
                Files.copy(dirstateFile.toPath(), dirstateBackupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                appendToJournal(journalFile, "dirstate");
            }
            if (fncacheFile.exists()) {
                File fncacheBackupFile = new File(repository.getStoreDir(), "fncache.backup");
                Files.copy(fncacheFile.toPath(), fncacheBackupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                appendToJournal(journalFile, "fncache");
            }

            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");
            File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
            File mfDat = new File(repository.getStoreDir(), "00manifest.d");

            long clIdxLen = clIdx.exists() ? clIdx.length() : 0L;
            long clDatLen = clDat.exists() ? clDat.length() : 0L;
            long mfIdxLen = mfIdx.exists() ? mfIdx.length() : 0L;
            long mfDatLen = mfDat.exists() ? mfDat.length() : 0L;

            fileSizes.put(clIdx, clIdxLen);
            fileSizes.put(clDat, clDatLen);
            fileSizes.put(mfIdx, mfIdxLen);
            fileSizes.put(mfDat, mfDatLen);

            appendToJournal(journalFile, "store/00changelog.i\t" + clIdxLen);
            appendToJournal(journalFile, "store/00changelog.d\t" + clDatLen);
            appendToJournal(journalFile, "store/00manifest.i\t" + mfIdxLen);
            appendToJournal(journalFile, "store/00manifest.d\t" + mfDatLen);

            Revlog changelog = repository.getRevlog(clIdx, clDat);
            for (ChangegroupParser.ChangeGroupEntry entry : bundle.changelogEntries) {
                int rev = changelog.getRevisionCount();
                changelog.appendChangeGroupEntry(entry, rev);
                importedCommits.add(entry.node);
            }

            Set<String> fncachePaths = new LinkedHashSet<>();
            if (fncacheFile.exists()) {
                fncachePaths.addAll(Files.readAllLines(fncacheFile.toPath()));
            }

            if (bundle.manifestGroups != null && !bundle.manifestGroups.isEmpty()) {
                for (ChangegroupParser.ManifestGroup mg : bundle.manifestGroups) {
                    File mIdx, mDat;
                    if (mg.path == null || mg.path.isEmpty()) {
                        mIdx = mfIdx;
                        mDat = mfDat;
                    } else {
                        String storeRel = "meta/" + mg.path + "/00manifest";
                        mIdx = new File(repository.getStoreDir(), NodeIdUtil.encodeFname(storeRel + ".i"));
                        mDat = new File(repository.getStoreDir(), NodeIdUtil.encodeFname(storeRel + ".d"));
                        
                        fncachePaths.add(NodeIdUtil.encodeFname(storeRel + ".i"));
                        fncachePaths.add(NodeIdUtil.encodeFname(storeRel + ".d"));

                        if (!fileSizes.containsKey(mIdx)) {
                            long idxLen = mIdx.exists() ? mIdx.length() : 0L;
                            fileSizes.put(mIdx, idxLen);
                            String storeRelIdx = "store/" + NodeIdUtil.encodeFname(storeRel + ".i");
                            appendToJournal(journalFile, storeRelIdx + "\t" + idxLen);
                        }
                        if (!fileSizes.containsKey(mDat)) {
                            long datLen = mDat.exists() ? mDat.length() : 0L;
                            fileSizes.put(mDat, datLen);
                            String storeRelDat = "store/" + NodeIdUtil.encodeFname(storeRel + ".d");
                            appendToJournal(journalFile, storeRelDat + "\t" + datLen);
                        }
                        mIdx.getParentFile().mkdirs();
                    }
                    Revlog subManifest = (mIdx == mfIdx) ? repository.getManifestRevlog() : repository.getRevlog(mIdx, mDat);
                    for (ChangegroupParser.ChangeGroupEntry entry : mg.entries) {
                        int linkRev = changelog.findRevision(entry.cs);
                        if (linkRev == -1) {
                            throw new HgCorruptDataException("Missing link commit for manifest: " + NodeIdUtil.toHex(entry.cs));
                        }
                        subManifest.appendChangeGroupEntry(entry, linkRev);
                    }
                }
            } else {
                Revlog manifest = repository.getManifestRevlog();
                for (ChangegroupParser.ChangeGroupEntry entry : bundle.manifestEntries) {
                    int linkRev = changelog.findRevision(entry.cs);
                    if (linkRev == -1) {
                        throw new HgCorruptDataException("Missing link commit for manifest: " + NodeIdUtil.toHex(entry.cs));
                    }
                    manifest.appendChangeGroupEntry(entry, linkRev);
                }
            }

            for (ChangegroupParser.FileGroup fg : bundle.fileGroups) {
                String path = fg.path;
                if (treeFilter != null && !treeFilter.accept(path)) {
                    continue;
                }
                File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
                File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");

                if (!fileSizes.containsKey(flIdx)) {
                    long idxLen = flIdx.exists() ? flIdx.length() : 0L;
                    fileSizes.put(flIdx, idxLen);
                    String storeRelIdx = "store/" + NodeIdUtil.encodeFname(path + ".i");
                    appendToJournal(journalFile, storeRelIdx + "\t" + idxLen);
                }
                if (!fileSizes.containsKey(flDat)) {
                    long datLen = flDat.exists() ? flDat.length() : 0L;
                    fileSizes.put(flDat, datLen);
                    String storeRelDat = "store/" + NodeIdUtil.encodeFname(path + ".d");
                    appendToJournal(journalFile, storeRelDat + "\t" + datLen);
                }

                flIdx.getParentFile().mkdirs();
                Revlog filelog = repository.getRevlog(flIdx, flDat);

                for (ChangegroupParser.ChangeGroupEntry entry : fg.entries) {
                    int linkRev = changelog.findRevision(entry.cs);
                    if (linkRev == -1) {
                        throw new HgCorruptDataException("Missing link commit for file revision: " + NodeIdUtil.toHex(entry.cs));
                    }
                    filelog.appendChangeGroupEntry(entry, linkRev);
                }

                String rawPath = "data/" + path.replace('\\', '/');
                fncachePaths.add(rawPath + ".i");
                if (!filelog.isInline()) {
                    // Backlog #43: applying this changegroup may have pushed a previously-inline
                    // filelog past real hg's 131072-byte inline threshold
                    // (Revlog.enforceInlineSize(), called from inside appendChangeGroupEntry()
                    // above), splitting it into a separate .d file -- real hg's own fncache tracks
                    // BOTH the .i and .d path for any non-inline data/meta revlog, confirmed live
                    // against real hg 7.2 (see the identical fix in CommitCommand for the local
                    // commit path, and GcCommand's own fncache rebuild for the same convention).
                    fncachePaths.add(rawPath + ".d");
                }
            }

            if (!fncachePaths.isEmpty()) {
                SafeFileIO.writeLinesAtomic(fncacheFile, new ArrayList<>(fncachePaths));
            }

            try {
                Files.deleteIfExists(journalFile.toPath());
                Files.deleteIfExists(new File(repository.getDirectory(), ".hg/dirstate.backup").toPath());
                Files.deleteIfExists(new File(repository.getStoreDir(), "fncache.backup").toPath());
            } catch (Exception ignored) {}

            // hg rollback으로 이번 pull을 되돌릴 수 있도록 undo 정보를 남긴다. 예전에는
            // CommitCommand만 undo 정보를 썼기 때문에 pull 직후에는 rollback이 아예 동작하지
            // 않았다(가장 흔한 실사용 시나리오인데도) — 2026-09-01 수정, Track B-4.
            if (!fileSizes.isEmpty()) {
                try {
                    CommitCommand.writeUndoInfo(repository, fileSizes, dirstateBackup);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to write undo info for rollback after pull", e);
                }
            }

            return importedCommits;

        } catch (Exception t) {
            for (Map.Entry<File, Long> sizeEntry : fileSizes.entrySet()) {
                File file = sizeEntry.getKey();
                long origSize = sizeEntry.getValue();
                if (origSize == 0) {
                    try {
                        Files.deleteIfExists(file.toPath());
                    } catch (Exception ignored) {}
                } else {
                    try (FileChannel outChan = FileChannel.open(file.toPath(), StandardOpenOption.WRITE)) {
                        outChan.truncate(origSize);
                        outChan.force(true);
                    } catch (Exception ignored) {}
                }
            }
            if (fncacheBackup != null) {
                try {
                    SafeFileIO.writeAtomic(fncacheFile, fncacheBackup);
                } catch (Exception ignored) {}
            } else {
                try {
                    Files.deleteIfExists(fncacheFile.toPath());
                } catch (Exception ignored) {}
            }
            if (dirstateBackup != null) {
                try {
                    SafeFileIO.writeAtomic(dirstateFile, dirstateBackup);
                } catch (Exception ignored) {}
            } else {
                try {
                    Files.deleteIfExists(dirstateFile.toPath());
                } catch (Exception ignored) {}
            }
            try {
                Files.deleteIfExists(journalFile.toPath());
                Files.deleteIfExists(new File(repository.getDirectory(), ".hg/dirstate.backup").toPath());
                Files.deleteIfExists(new File(repository.getStoreDir(), "fncache.backup").toPath());
            } catch (Exception ignored) {}
            repository.clearRevlogCache();
            throw t;
        }
    }

    private void appendToJournal(File journalFile, String entry) throws IOException {
        Files.writeString(journalFile.toPath(), entry + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try (FileChannel fc = FileChannel.open(journalFile.toPath(), StandardOpenOption.WRITE)) {
            fc.force(true);
        }
    }
}
