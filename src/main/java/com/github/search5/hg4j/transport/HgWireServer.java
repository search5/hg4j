package com.github.search5.hg4j.transport;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.github.search5.hg4j.api.CommitCommand;
import java.io.*;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Mercurial server-side Wire Protocol processor, corresponding to JGit's UploadPack/ReceivePack.
 */
public class HgWireServer {
    private final HgRepository repository;

    public HgWireServer(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Mediates the serve --stdio command pipeline coming from the client.
     * When the client requests 'capabilities', it transmits server specifications.
     * When the client sends 'unbundle', it parses the push data and merges it into the repository.
     */
    public void handleConnection(InputStream in, OutputStream out) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        String command = reader.readLine();
        
        if (command != null && command.startsWith("upgrade ")) {
            String[] parts = command.split(" ");
            if (parts.length >= 3) {
                String token = parts[1];
                String proto = parts[2];
                if (proto.equals("proto=exp-ssh-v2-0003")) {
                    out.write(("upgraded " + token + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    String v2Caps = "capabilities: lookup changegroup=01,02,03 getbundle bundle2=HG20 compression=GZ,BZ,ZS\n";
                    out.write(v2Caps.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.flush();
                }
            }
            return;
        }
        
        if ("capabilities".equals(command)) {
            // Transmit server-side supported specifications downstream (including compression negotiation and bundle2 enablement)
            String caps = "capabilities: lookup changegroup=01,02,03 getbundle bundle2=HG20 compression=GZ,BZ,ZS exp-ssh-v2-0003\n";
            out.write(caps.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
        } else if ("heads".equals(command)) {
            String heads = getRepositoryHeads();
            out.write(heads.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
        } else if (command != null && command.startsWith("unbundle")) {
            // Process incoming push (JGit's ReceivePack equivalent)
            processIncomingPush(in, out);
        }
    }

    private void processIncomingPush(InputStream in, OutputStream out) throws IOException {
        try (com.github.search5.hg4j.lib.HgLock storeLock = repository.lockStore();
             com.github.search5.hg4j.lib.HgLock wlock = repository.lockWorkingCopy()) {
            
            // 1. Parse incoming changegroup bundle from stream
            com.github.search5.hg4j.bundle.ChangegroupParser.ChangegroupBundle bundle = com.github.search5.hg4j.bundle.ChangegroupParser.parseBundle(in);
            
            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");
            File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
            File mfDat = new File(repository.getStoreDir(), "00manifest.d");
            
            File fncacheFile = new File(repository.getStoreDir(), "fncache");
            Set<String> fncachePaths = new LinkedHashSet<>();
            if (fncacheFile.exists()) {
                fncachePaths.addAll(Files.readAllLines(fncacheFile.toPath()));
            }

            // 1a. Apply Changelog
            Revlog changelog = repository.getRevlog(clIdx, clDat);
            for (com.github.search5.hg4j.bundle.ChangegroupParser.ChangeGroupEntry entry : bundle.changelogEntries) {
                int rev = changelog.getRevisionCount();
                changelog.appendChangeGroupEntry(entry, rev);
            }

            // 1b. Apply Manifest
            if (bundle.manifestGroups != null && !bundle.manifestGroups.isEmpty()) {
                for (com.github.search5.hg4j.bundle.ChangegroupParser.ManifestGroup mg : bundle.manifestGroups) {
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
                        mIdx.getParentFile().mkdirs();
                    }
                    Revlog subManifest = (mIdx == mfIdx) ? repository.getManifestRevlog() : repository.getRevlog(mIdx, mDat);
                    for (com.github.search5.hg4j.bundle.ChangegroupParser.ChangeGroupEntry entry : mg.entries) {
                        int linkRev = changelog.findRevision(entry.cs);
                        if (linkRev == -1) {
                            throw new com.github.search5.hg4j.errors.HgCorruptDataException("Missing link commit for manifest");
                        }
                        subManifest.appendChangeGroupEntry(entry, linkRev);
                    }
                }
            } else if (bundle.manifestEntries != null) {
                Revlog manifest = repository.getManifestRevlog();
                for (com.github.search5.hg4j.bundle.ChangegroupParser.ChangeGroupEntry entry : bundle.manifestEntries) {
                    int linkRev = changelog.findRevision(entry.cs);
                    if (linkRev == -1) {
                        throw new com.github.search5.hg4j.errors.HgCorruptDataException("Missing link commit for manifest");
                    }
                    manifest.appendChangeGroupEntry(entry, linkRev);
                }
            }

            // 1c. Apply Filelogs
            for (com.github.search5.hg4j.bundle.ChangegroupParser.FileGroup fg : bundle.fileGroups) {
                String path = fg.path;
                File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
                File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
                
                fncachePaths.add(NodeIdUtil.encodeFname(path + ".i"));
                fncachePaths.add(NodeIdUtil.encodeFname(path + ".d"));
                flIdx.getParentFile().mkdirs();
                
                Revlog filelog = repository.getRevlog(flIdx, flDat);
                for (com.github.search5.hg4j.bundle.ChangegroupParser.ChangeGroupEntry entry : fg.entries) {
                    int linkRev = changelog.findRevision(entry.cs);
                    if (linkRev == -1) {
                        throw new com.github.search5.hg4j.errors.HgCorruptDataException("Missing link commit for filelog: " + path);
                    }
                    filelog.appendChangeGroupEntry(entry, linkRev);
                }
            }

            // Write back fncache
            if (!fncachePaths.isEmpty()) {
                com.github.search5.hg4j.util.SafeFileIO.writeLinesAtomic(fncacheFile, new java.util.ArrayList<>(fncachePaths));
            }

            // Clear cache and write response
            repository.clearRevlogCache();
            String response = "0\nno errors\n";
            out.write(response.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception e) {
            String errResponse = "1\n" + e.getMessage() + "\n";
            out.write(errResponse.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
        }
    }
    /**
     * Real hg's capability-discovery handshake response, sent from the root URL
     * ({@code /?cmd=capabilities}) when the request carries {@code X-HgUpgrade-1}/
     * {@code X-HgProto-1} headers (checked by the caller) — {@code {apibase, apis:
     * {<namespace>: {commands, framingmediatypes}}, v1capabilities}}, verified against a real
     * Mercurial 6.0 server (the last release with a working wireprotocol v2 implementation).
     *
     * @param v1CapabilitiesLine the same string the v1 {@code capabilities} command would return,
     *                           embedded verbatim as {@code v1capabilities}
     */
    public void handleCapabilitiesDiscovery(String v1CapabilitiesLine, OutputStream out) throws IOException {
        java.util.Map<String, Object> apis = new java.util.LinkedHashMap<>();
        apis.put(com.github.search5.hg4j.transport.wireprotov2.Wire2Commands.NAMESPACE,
                com.github.search5.hg4j.transport.wireprotov2.Wire2Commands.namespaceDescriptor());

        java.util.Map<String, Object> descriptor = new java.util.LinkedHashMap<>();
        descriptor.put("apibase", "api/");
        descriptor.put("apis", apis);
        descriptor.put("v1capabilities", v1CapabilitiesLine == null ? "" : v1CapabilitiesLine);

        out.write(com.github.search5.hg4j.transport.wireprotov2.Cbor.encode(descriptor));
        out.flush();
    }

    /**
     * Real hg's per-command wireprotocol v2 HTTP handler, serving
     * {@code POST /api/<namespace>/<ro|rw>/<command>}: reads the frame-based
     * {@code application/mercurial-exp-framing-0006} command-request body, dispatches to
     * {@link com.github.search5.hg4j.transport.wireprotov2.Wire2Commands}, and writes back a
     * framed {@code {status: ok, ...}} (or {@code error}) response — the real wire shape,
     * verified against a live Mercurial 6.0 server, replacing the earlier fictional flat
     * {@code POST /api/<command>} scheme this class used before that verification.
     *
     * @param permission the {@code ro}/{@code rw} URL segment; the caller is responsible for
     *                   authenticating/authorizing it (real hg maps {@code ro}→pull, {@code rw}→push)
     * @param urlCommand the command name from the URL, which must match the frame's own command name
     */
    public void handleWire2Request(String permission, String urlCommand, InputStream in, OutputStream out) throws IOException {
        boolean isMultirequest = "multirequest".equals(urlCommand);
        java.util.List<com.github.search5.hg4j.transport.wireprotov2.Wire2Transport.ParsedCommandRequest> commands =
                com.github.search5.hg4j.transport.wireprotov2.Wire2Transport.readAllCommandRequests(in);

        java.io.ByteArrayOutputStream combined = new java.io.ByteArrayOutputStream();
        if (!commands.isEmpty()) {
            // 실제 hg는 응답 스트림 전체에 stream-settings 프레임을 딱 한 번만 보낸다 —
            // multirequest로 여러 명령을 한 번에 처리할 때도 명령마다 다시 보내지 않는다
            // (real Mercurial 6.0 서버의 heads+known 배치 clone 요청으로 직접 확인, 2026-09-01).
            combined.write(com.github.search5.hg4j.transport.wireprotov2.Wire2Transport.buildStreamSettingsFrame(commands.get(0).requestId));
        }
        for (com.github.search5.hg4j.transport.wireprotov2.Wire2Transport.ParsedCommandRequest cmd : commands) {
            if (!isMultirequest && !cmd.name.equals(urlCommand)) {
                combined.write(com.github.search5.hg4j.transport.wireprotov2.Wire2Transport.buildCommandErrorResponse(
                        cmd.requestId, "command in frame must match command in URL"));
                continue;
            }
            try {
                java.util.List<Object> responseObjects = dispatchWire2Command(cmd.name, cmd.args);
                combined.write(com.github.search5.hg4j.transport.wireprotov2.Wire2Transport.buildCommandResponseFrames(cmd.requestId, responseObjects));
            } catch (com.github.search5.hg4j.errors.HgProtocolException e) {
                combined.write(com.github.search5.hg4j.transport.wireprotov2.Wire2Transport.buildCommandErrorResponse(cmd.requestId, e.getMessage()));
            } catch (Exception e) {
                combined.write(com.github.search5.hg4j.transport.wireprotov2.Wire2Transport.buildCommandErrorResponse(cmd.requestId, String.valueOf(e.getMessage())));
            }
        }
        out.write(combined.toByteArray());
        out.flush();
    }

    private java.util.List<Object> dispatchWire2Command(String command, java.util.Map<String, Object> args) throws IOException {
        switch (command) {
            case "capabilities":
                return com.github.search5.hg4j.transport.wireprotov2.Wire2Commands.capabilities();
            case "heads":
                return com.github.search5.hg4j.transport.wireprotov2.Wire2Commands.heads(repository);
            case "known":
                return com.github.search5.hg4j.transport.wireprotov2.Wire2Commands.known(repository, args);
            case "listkeys":
                return com.github.search5.hg4j.transport.wireprotov2.Wire2Commands.listkeys(repository, args);
            case "lookup":
                return com.github.search5.hg4j.transport.wireprotov2.Wire2Commands.lookup(repository, args);
            case "pushkey":
                return com.github.search5.hg4j.transport.wireprotov2.Wire2Commands.pushkey(repository, args);
            case "branchmap":
                return com.github.search5.hg4j.transport.wireprotov2.Wire2Commands.branchmap(repository);
            case "changesetdata":
                return com.github.search5.hg4j.transport.wireprotov2.Wire2Commands.changesetdata(repository, args);
            case "manifestdata":
                return com.github.search5.hg4j.transport.wireprotov2.Wire2Commands.manifestdata(repository, args);
            case "filesdata":
                return com.github.search5.hg4j.transport.wireprotov2.Wire2Commands.filesdata(repository, args);
            default:
                throw new com.github.search5.hg4j.errors.HgProtocolException("wireprotov2", "unsupported wire protocol v2 command: " + command);
        }
    }

    private String getRepositoryHeads() throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        if (!clIdx.exists()) {
            return "\n";
        }
        Revlog changelog = repository.getRevlog(clIdx, clDat);
        int count = changelog.getRevisionCount();
        if (count == 0) {
            return "\n";
        }

        boolean[] isParent = new boolean[count];
        for (int i = 0; i < count; i++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(i);
            if (rec.getParent1() >= 0 && rec.getParent1() < count) isParent[rec.getParent1()] = true;
            if (rec.getParent2() >= 0 && rec.getParent2() < count) isParent[rec.getParent2()] = true;
        }

        java.util.List<String> heads = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (!isParent[i]) {
                heads.add(NodeIdUtil.toHex(changelog.getIndexRecord(i).getNodeId()));
            }
        }
        if (heads.isEmpty()) {
            return "\n";
        }
        return String.join(" ", heads) + "\n";
    }
}
