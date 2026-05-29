package org.hg4j.transport;

import com.jcraft.jsch.*;
import java.io.File;

/**
 * JSch 라이브러리를 사용하는 기본 SshSessionFactory 구현체입니다.
 */
public class JschSessionFactory implements SshSessionFactory {
    @Override
    public Session openSession(String host, int port, String username, String password, String privateKeyPath, String passphrase) throws Exception {
        JSch jsch = new JSch();
        
        // Integrate SSH Agent and Identity loading for standard agent forwarding
        String userHome = System.getProperty("user.home");
        File knownHostsFile = new File(userHome, ".ssh/known_hosts");
        if (knownHostsFile.exists()) {
            jsch.setKnownHosts(knownHostsFile.getAbsolutePath());
        }

        if (privateKeyPath != null) {
            if (passphrase != null) {
                jsch.addIdentity(privateKeyPath, passphrase);
            } else {
                jsch.addIdentity(privateKeyPath);
            }
        }

        Session session = jsch.getSession(username, host, port);
        if (password != null) {
            session.setPassword(password);
        }

        // Standard strict host key verification configuration
        boolean isLocal = host.equals("localhost") || host.equals("127.0.0.1");
        if (knownHostsFile.exists() && !isLocal) {
            session.setConfig("StrictHostKeyChecking", "ask");
        } else {
            session.setConfig("StrictHostKeyChecking", "no");
        }
        
        // JSch가 ECDH 등 일부 최신 key exchange 수행 중 Bouncy Castle과의 예외(ArrayIndexOutOfBoundsException)를 방지하도록 호환성 높은 kex 알고리즘 목록을 강제 지정함
        session.setConfig("kex", "ecdh-sha2-nistp256,ecdh-sha2-nistp384,diffie-hellman-group14-sha256,diffie-hellman-group-exchange-sha256");
        
        return session;
    }
}
