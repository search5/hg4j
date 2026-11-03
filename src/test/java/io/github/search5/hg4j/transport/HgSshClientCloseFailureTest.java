package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.errors.HgProtocolException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fault injection at the SSH boundary (a session whose {@code connect} and {@code close} both fail): a failure to close must not
 * be swallowed, and when it happens while a connection error is being reported it is attached to that error as a suppressed
 * exception instead of replacing it.
 */
class HgSshClientCloseFailureTest {

    private SshSessionFactory original;

    /** Fails to connect, and fails again when it is closed; counts the closes. */
    private static final class FailingSession implements SshSession {
        int closes;

        @Override
        public void connect(int timeoutMs) throws Exception {
            throw new IOException("connect failed");
        }

        @Override
        public void executeCommand(String command, int timeoutMs) {
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public void close() throws IOException {
            closes++;
            throw new IOException("close failed");
        }
    }

    @BeforeEach
    void install() {
        original = HgSshClient.getSshSessionFactory();
    }

    @AfterEach
    void restore() {
        HgSshClient.setSshSessionFactory(original);
    }

    @Test
    @DisplayName("연결에 실패해 정리하다가 close 도 실패하면, 연결 오류가 보고되고 close 실패는 suppressed 로 붙는다")
    void closeFailureDuringAConnectionErrorIsAttachedNotSwallowed() {
        FailingSession session = new FailingSession();
        HgSshClient.setSshSessionFactory((host, port, user, password, key, passphrase) -> session);

        HgSshClient client = new HgSshClient("ssh://user@127.0.0.1/repo");
        HgProtocolException error = assertThrows(HgProtocolException.class, client::getHeads);

        assertTrue(error.getMessage().contains("connect failed"), error.getMessage());
        Throwable cause = error.getCause();
        assertNotNull(cause);
        assertEquals("connect failed", cause.getMessage());
        assertEquals(1, cause.getSuppressed().length, "the failure to close is kept");
        assertEquals("close failed", cause.getSuppressed()[0].getMessage());
        assertEquals(1, session.closes);
    }

    @Test
    @DisplayName("이미 닫힌 클라이언트의 close 는 아무것도 하지 않는다")
    void closingAClientWithoutASessionDoesNothing() {
        HgSshClient client = new HgSshClient("ssh://user@127.0.0.1/repo");
        assertDoesNotThrow(client::close);
    }
}
