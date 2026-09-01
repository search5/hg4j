package com.github.search5.hg4j.api;

import com.github.search5.hg4j.bundle.ChangegroupParser;
import com.github.search5.hg4j.lib.HgLock;
import com.github.search5.hg4j.transport.HgRemoteConnection;
import com.github.search5.hg4j.transport.HgRemoteConnectionFactory;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.errors.HgLockException;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import com.github.search5.hg4j.errors.HgValidationException;
import com.github.search5.hg4j.lib.NodeId;
import com.github.search5.hg4j.phase.PhaseRoots;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Porcelain command to push local commits to a remote Mercurial repository.
 * Compiles a dynamic binary changegroup bundle of new revisions and transfers it securely.
 */
public class PushCommand {
    private static final Logger LOGGER = Logger.getLogger(PushCommand.class.getName());

    private final HgRepository repository;
    private String destinationUrl;
    
    private final List<HgHook> prePushHooks = new ArrayList<>();
    private final List<HgHook> postPushHooks = new ArrayList<>();

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

            try (HgLock storeLock = repository.lockStore()) {
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

                // 1. Pack changesets startRev ~ tip into changegroup bundle
                ChangegroupParser.ChangegroupBundle bundle = new ChangegroupParser.ChangegroupBundle();
                bundle.changelogEntries = new ArrayList<>();
                bundle.manifestEntries = new ArrayList<>();
                bundle.fileGroups = new ArrayList<>();

                // 1a. Pack Changelogs
                for (int r = startRev; r < count; r++) {
                    Revlog.IndexRecord clRec = changelog.getIndexRecord(r);
                    ChangegroupParser.ChangeGroupEntry clEntry = new ChangegroupParser.ChangeGroupEntry();
                    clEntry.node = clRec.getNodeId();
                    clEntry.p1 = (clRec.getParent1() != -1) ? changelog.getIndexRecord(clRec.getParent1()).getNodeId() : new byte[20];
                    clEntry.p2 = (clRec.getParent2() != -1) ? changelog.getIndexRecord(clRec.getParent2()).getNodeId() : new byte[20];
                    clEntry.cs = clRec.getNodeId();

                    byte[] content = changelog.getRevisionContent(r);
                    byte[] baseContent = new byte[0];
                    if (clRec.getParent1() != -1) {
                        baseContent = changelog.getRevisionContent(clRec.getParent1());
                    }
                    clEntry.delta = Revlog.createDelta(baseContent, content);
                    bundle.changelogEntries.add(clEntry);
                }

                // 1b. Pack Manifests
                Revlog manifest = repository.getManifestRevlog();
                Set<String> affectedFiles = new HashSet<>();
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
                    mfEntry.p1 = (mfRec.getParent1() != -1) ? manifest.getIndexRecord(mfRec.getParent1()).getNodeId() : new byte[20];
                    mfEntry.p2 = (mfRec.getParent2() != -1) ? manifest.getIndexRecord(mfRec.getParent2()).getNodeId() : new byte[20];
                    mfEntry.cs = changelog.getIndexRecord(r).getNodeId();

                    byte[] content = manifest.getRevisionContent(mfRev);
                    byte[] baseContent = new byte[0];
                    if (mfRec.getParent1() != -1) {
                        baseContent = manifest.getRevisionContent(mfRec.getParent1());
                    }
                    mfEntry.delta = Revlog.createDelta(baseContent, content);
                    bundle.manifestEntries.add(mfEntry);
                }

                // 1c. Pack Filelogs
                for (String path : affectedFiles) {
                    File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
                    File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
                    if (!flIdx.exists()) continue;

                    Revlog fl = repository.getRevlog(flIdx, flDat);
                    List<ChangegroupParser.ChangeGroupEntry> flEntries = new ArrayList<>();

                    for (int i = 0; i < fl.getRevisionCount(); i++) {
                        Revlog.IndexRecord flRec = fl.getIndexRecord(i);
                        // Only pack revision if its linkRev is in our push range
                        if (flRec.getLinkRev() >= startRev) {
                            ChangegroupParser.ChangeGroupEntry flEntry = new ChangegroupParser.ChangeGroupEntry();
                            flEntry.node = flRec.getNodeId();
                            flEntry.p1 = (flRec.getParent1() != -1) ? fl.getIndexRecord(flRec.getParent1()).getNodeId() : new byte[20];
                            flEntry.p2 = (flRec.getParent2() != -1) ? fl.getIndexRecord(flRec.getParent2()).getNodeId() : new byte[20];
                            flEntry.cs = changelog.getIndexRecord(flRec.getLinkRev()).getNodeId();

                            // Raw (as-stored) content, not getRevisionContent(): a filelog
                            // revision can be censored (Revlog.REVIDX_ISCENSORED), and bundling
                            // must transfer its tombstone bytes as-is rather than throwing
                            // HgCensoredContentException -- real hg's own changegroup packer
                            // likewise always uses rawdata()/`_chunk()`, never the decoded text.
                            byte[] content = fl.getRawRevisionContent(i);
                            byte[] baseContent = new byte[0];
                            if (flRec.getParent1() != -1) {
                                baseContent = fl.getRawRevisionContent(flRec.getParent1());
                            }
                            flEntry.delta = Revlog.createDelta(baseContent, content);
                            flEntries.add(flEntry);
                        }
                    }

                    if (!flEntries.isEmpty()) {
                        ChangegroupParser.FileGroup fg = new ChangegroupParser.FileGroup();
                        fg.path = path;
                        fg.entries = flEntries;
                        bundle.fileGroups.add(fg);
                    }
                }

                // 2. Serialize bundle to binary bytes
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (DataOutputStream dos = new DataOutputStream(baos)) {
                    // Write HG10UN magic bytes for uncompressed bundle1 format compatibility with native hg
                    dos.write("HG10UN".getBytes(StandardCharsets.US_ASCII));

                    // Changelog group
                    for (ChangegroupParser.ChangeGroupEntry entry : bundle.changelogEntries) {
                        writeEntryChunk(dos, entry);
                    }
                    writeTerminalChunk(dos);

                    // Manifest group
                    for (ChangegroupParser.ChangeGroupEntry entry : bundle.manifestEntries) {
                        writeEntryChunk(dos, entry);
                    }
                    writeTerminalChunk(dos);

                    // File groups
                    for (ChangegroupParser.FileGroup fg : bundle.fileGroups) {
                        writePathChunk(dos, fg.path);
                        for (ChangegroupParser.ChangeGroupEntry entry : fg.entries) {
                            writeEntryChunk(dos, entry);
                        }
                        writeTerminalChunk(dos);
                    }
                    writeTerminalChunk(dos);
                }

                // 3. Dispatch bundle to remote destination
                String response = client.push(baos.toByteArray(), remoteHeads);

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

    private void writeEntryChunk(DataOutputStream dos, ChangegroupParser.ChangeGroupEntry entry) throws IOException {
        int totalLen = 4 + 80 + entry.delta.length;
        dos.writeInt(totalLen);
        dos.write(entry.node);
        dos.write(entry.p1);
        dos.write(entry.p2);
        dos.write(entry.cs);
        dos.write(entry.delta);
    }

    private void writePathChunk(DataOutputStream dos, String path) throws IOException {
        byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
        int totalLen = 4 + pathBytes.length;
        dos.writeInt(totalLen);
        dos.write(pathBytes);
    }

    private void writeTerminalChunk(DataOutputStream dos) throws IOException {
        dos.writeInt(0);
    }
}
