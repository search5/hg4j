package com.github.search5.hg4j.transport;

import com.jcraft.jsch.*;
import java.io.File;

/**
 * Default SshSessionFactory implementation that returns an abstracted SshSession using the JSch library.
 */
public class JschSessionFactory implements SshSessionFactory {
    @Override
    public SshSession createSession(String host, int port, String username, String password, String privateKeyPath, String passphrase) throws Exception {
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
        
        // Forces a highly compatible list of key exchange (KEX) algorithms to prevent exceptions (e.g., ArrayIndexOutOfBoundsException) with Bouncy Castle during certain modern key exchanges like ECDH.
        session.setConfig("kex", "ecdh-sha2-nistp256,ecdh-sha2-nistp384,diffie-hellman-group14-sha256,diffie-hellman-group-exchange-sha256");
        
        return new JschSshSession(session);
    }
}
