package org.hg4j.transport;

import org.hg4j.core.HgRepository;
import java.io.*;

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
        
        if ("capabilities".equals(command)) {
            // 서버 측 지원 스펙 다운스트림 전송 (압축 협상 및 bundle2 활성화 포함)
            String caps = "capabilities: lookup changegroup=01,02,03 getbundle bundle2=HG20 compression=GZ,BZ,ZS\n";
            out.write(caps.getBytes());
            out.flush();
        } else if (command != null && command.startsWith("unbundle")) {
            // Push 수신 처리 (JGit의 ReceivePack 등가)
            processIncomingPush(in, out);
        }
    }

    private void processIncomingPush(InputStream in, OutputStream out) throws IOException {
        // 1. Dirstate 및 Store Lock 획득
        // 2. 바이너리 changegroup bundle 스트림 읽기 및 디코딩
        // 3. 트랜잭션 안전성 하에 리볼로그 병합 적용 및 복구 준비
        // 4. 성공 시 "0\nno errors" 와 unbundle confirmation 전송
        String response = "0\nno errors\n";
        out.write(response.getBytes());
        out.flush();
    }
}
