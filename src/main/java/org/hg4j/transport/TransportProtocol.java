package org.hg4j.transport;

import java.io.IOException;

/**
 * Transport protocol 플러그인을 위한 인터페이스입니다.
 * 새 프로토콜(예: custom://) 지원을 추가할 수 있는 유연한 추상화를 제공합니다.
 */
public interface TransportProtocol {
    /**
     * 해당 URL을 이 프로토콜 핸들러가 처리할 수 있는지 여부를 반환합니다.
     */
    boolean canHandle(String url);

    /**
     * 지정된 URL에 대응되는 HgRemoteConnection 인스턴스를 생성하여 반환합니다.
     */
    HgRemoteConnection open(String url) throws IOException;
}
