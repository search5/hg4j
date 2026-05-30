package org.hg4j.transport;

import org.hg4j.core.HgRepository;
import org.hg4j.core.Revlog;
import org.hg4j.core.NodeIdUtil;
import org.hg4j.api.CommitCommand;
import java.io.*;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * JGit의 UploadPack/ReceivePack에 대응하는 Mercurial 서버측 Wire Protocol 프로세서입니다.
 */
public class HgWireServer {
    private final HgRepository repository;

    public HgWireServer(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * 클라이언트로부터 들어오는 serve --stdio 명령 파이프라인을 중계합니다.
     * 클라이언트가 'capabilities'를 요청하면 서버 사양을 전송하고, 
     * 'unbundle'을 보내면 push 데이터를 파싱해 트랜잭션으로 저장소에 병합합니다.
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
            // 서버 측 지원 스펙 다운스트림 전송 (압축 협상 및 bundle2 활성화 포함)
            String caps = "capabilities: lookup changegroup=01,02,03 getbundle bundle2=HG20 compression=GZ,BZ,ZS exp-ssh-v2-0003\n";
            out.write(caps.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
        } else if ("heads".equals(command)) {
            String heads = getRepositoryHeads();
            out.write(heads.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
        } else if (command != null && command.startsWith("unbundle")) {
            // Push 수신 처리 (JGit의 ReceivePack 등가)
            processIncomingPush(in, out);
        }
    }

    private void processIncomingPush(InputStream in, OutputStream out) throws IOException {
        try (org.hg4j.core.HgLock storeLock = repository.lockStore();
             org.hg4j.core.HgLock wlock = repository.lockWorkingCopy()) {
            
            // 1. Parse incoming changegroup bundle from stream
            org.hg4j.core.ChangegroupParser.ChangegroupBundle bundle = org.hg4j.core.ChangegroupParser.parseBundle(in);
            
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
            for (org.hg4j.core.ChangegroupParser.ChangeGroupEntry entry : bundle.changelogEntries) {
                int rev = changelog.getRevisionCount();
                changelog.appendChangeGroupEntry(entry, rev);
            }

            // 1b. Apply Manifest
            if (bundle.manifestGroups != null && !bundle.manifestGroups.isEmpty()) {
                for (org.hg4j.core.ChangegroupParser.ManifestGroup mg : bundle.manifestGroups) {
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
                    for (org.hg4j.core.ChangegroupParser.ChangeGroupEntry entry : mg.entries) {
                        int linkRev = changelog.findRevision(entry.cs);
                        if (linkRev == -1) {
                            throw new org.hg4j.errors.HgCorruptDataException("Missing link commit for manifest");
                        }
                        subManifest.appendChangeGroupEntry(entry, linkRev);
                    }
                }
            } else if (bundle.manifestEntries != null) {
                Revlog manifest = repository.getManifestRevlog();
                for (org.hg4j.core.ChangegroupParser.ChangeGroupEntry entry : bundle.manifestEntries) {
                    int linkRev = changelog.findRevision(entry.cs);
                    if (linkRev == -1) {
                        throw new org.hg4j.errors.HgCorruptDataException("Missing link commit for manifest");
                    }
                    manifest.appendChangeGroupEntry(entry, linkRev);
                }
            }

            // 1c. Apply Filelogs
            for (org.hg4j.core.ChangegroupParser.FileGroup fg : bundle.fileGroups) {
                String path = fg.path;
                File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
                File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
                
                fncachePaths.add(NodeIdUtil.encodeFname(path + ".i"));
                fncachePaths.add(NodeIdUtil.encodeFname(path + ".d"));
                flIdx.getParentFile().mkdirs();
                
                Revlog filelog = repository.getRevlog(flIdx, flDat);
                for (org.hg4j.core.ChangegroupParser.ChangeGroupEntry entry : fg.entries) {
                    int linkRev = changelog.findRevision(entry.cs);
                    if (linkRev == -1) {
                        throw new org.hg4j.errors.HgCorruptDataException("Missing link commit for filelog: " + path);
                    }
                    filelog.appendChangeGroupEntry(entry, linkRev);
                }
            }

            // Write back fncache
            if (!fncachePaths.isEmpty()) {
                org.hg4j.core.SafeFileIO.writeLinesAtomic(fncacheFile, new java.util.ArrayList<>(fncachePaths));
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
     * HTTP V2 프로토콜 요청(api/v2/<cmd>)을 중계 및 처리하는 전용 엔드포인트 핸들러입니다.
     *
     * @param cmd 호출된 커맨드 이름 (예: "capabilities", "heads", "unbundle" 등)
     * @param acceptHeader 클라이언트로부터 유입된 HTTP Accept 헤더
     * @param in HTTP 요청 본문 입력 스트림
     * @param out HTTP 응답 본문 출력 스트림
     * @throws IOException I/O 오류 발생 시
     */
    public void handleHttpV2Connection(String cmd, String acceptHeader, InputStream in, OutputStream out) throws IOException {
        if (cmd == null || cmd.isEmpty()) {
            throw new IllegalArgumentException("HTTP V2 Command cannot be null or empty");
        }

        // HTTP V2에서는 'application/mercurial-x-api-v2' 헤더 협상이 충족되어야 합니다.
        boolean isV2Mediated = acceptHeader != null && acceptHeader.contains("application/mercurial-x-api-v2");

        if ("capabilities".equalsIgnoreCase(cmd)) {
            // V2 용 capabilities는 V2 전용 규격으로 인코딩하여 반환합니다.
            String caps = "capabilities: lookup changegroup=01,02,03 getbundle bundle2=HG20 compression=GZ,BZ,ZS exp-ssh-v2-0003\n";
            if (isV2Mediated) {
                out.write(("application/mercurial-x-api-v2\n" + caps).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } else {
                out.write(caps.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            out.flush();
        } else if ("heads".equalsIgnoreCase(cmd)) {
            String heads = getRepositoryHeads();
            if (isV2Mediated) {
                out.write("application/mercurial-x-api-v2\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            out.write(heads.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
        } else if ("unbundle".equalsIgnoreCase(cmd)) {
            if (isV2Mediated) {
                out.write("application/mercurial-x-api-v2\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            processIncomingPush(in, out);
        } else {
            if (isV2Mediated) {
                out.write("application/mercurial-x-api-v2\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            String defaultResp = "0\nno errors\n";
            out.write(defaultResp.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
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
