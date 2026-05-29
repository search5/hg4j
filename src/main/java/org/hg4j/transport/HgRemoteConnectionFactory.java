package org.hg4j.transport;

import java.io.File;
import java.io.IOException;

/**
 * Factory class to dynamically instantiate the appropriate remote connection
 * client (HTTP, SSH, or Local) based on the target URL protocol.
 */
public class HgRemoteConnectionFactory {

    /**
     * Creates and returns a connection client compatible with the target URL protocol.
     * 
     * @param url the target remote repository URL (ssh://, http://, https://, file://, or local path)
     * @return a concrete {@link HgRemoteConnection} instance
     * @throws IOException if URL protocol is invalid or connection fail
     */
    public static HgRemoteConnection createConnection(String url) throws IOException {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("Connection URL cannot be null or empty.");
        }

        if (url.startsWith("ssh://")) {
            return new HgSshClient(url);
        } else if (url.startsWith("file://")) {
            return new HgLocalClient(url);
        } else if (url.startsWith("http://") || url.startsWith("https://")) {
            return new HgRemoteClient(url);
        } else {
            // 절대/상대 파일 경로로 처리 (로컬 저장소)
            File f = new File(url);
            if (f.exists() && f.isDirectory()) {
                return new HgLocalClient(url);
            }
            // 기본 fallback: HTTP 클라이언트
            return new HgRemoteClient(url);
        }
    }
}
