package org.hg4j.transport;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;

/**
 * 특정 SSH 라이브러리(JSch, Apache MINA SSHD 등)에 의존하지 않는 독립적인 SSH 세션 추상 인터페이스입니다.
 */
public interface SshSession extends AutoCloseable {
    /**
     * SSH 서버에 연결을 확립합니다.
     *
     * @param timeoutMs 연결 타임아웃 밀리초
     * @throws Exception 연결 오류 발생 시
     */
    void connect(int timeoutMs) throws Exception;

    /**
     * 원격 서버에서 실행할 Exec 채널 명령을 요청합니다.
     *
     * @param command 실행할 쉘 명령
     * @param timeoutMs 채널 오픈 타임아웃 밀리초
     * @throws Exception 명령 실행 오류 발생 시
     */
    void executeCommand(String command, int timeoutMs) throws Exception;

    /**
     * 실행 중인 명령의 서버 출력 스트림(표준 출력)을 획득합니다.
     *
     * @return 표준 출력 수신을 위한 InputStream
     * @throws IOException 스트림 획득 오류 시
     */
    InputStream getInputStream() throws IOException;

    /**
     * 실행 중인 명령의 서버 입력 스트림(표준 입력)을 획득합니다.
     *
     * @return 표준 입력 송신을 위한 OutputStream
     * @throws IOException 스트림 획득 오류 시
     */
    OutputStream getOutputStream() throws IOException;

    /**
     * SSH 세션 및 실행 채널을 안전하게 분리 및 종료합니다.
     *
     * @throws IOException 종료 중 I/O 에러 시
     */
    @Override
    void close() throws IOException;
}
