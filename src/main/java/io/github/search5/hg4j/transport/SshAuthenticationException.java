package io.github.search5.hg4j.transport;

import java.io.IOException;

/**
 * Thrown by an {@link SshSession} implementation when the SSH server rejects every authentication
 * method that was offered (wrong password, wrong or missing key, unacceptable key passphrase).
 *
 * <p>This type exists so that callers such as {@link HgSshClient} can tell an authentication
 * failure apart from other connection failures without referring to the exception types of a
 * particular SSH library, which keeps {@link SshSession} implementations interchangeable.</p>
 */
public class SshAuthenticationException extends IOException {

    private static final long serialVersionUID = 1L;

    public SshAuthenticationException(String message) {
        super(message);
    }

    public SshAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
