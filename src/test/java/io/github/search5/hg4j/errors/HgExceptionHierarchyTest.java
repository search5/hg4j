package io.github.search5.hg4j.errors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;

/**
 * Phase 14: 전체 도메인 예외 계층 TDD 테스트.
 *
 * <pre>
 * HgException (RuntimeException)
 * ├── HgRepositoryNotFoundException
 * ├── HgCorruptDataException
 * ├── HgRevisionNotFoundException
 * ├── HgMergeConflictException
 * ├── HgLockException
 * └── HgTransportException
 *     ├── HgAuthException
 *     └── HgProtocolException
 * </pre>
 */
@DisplayName("Phase 14 — 도메인 예외 계층 전체 테스트")
public class HgExceptionHierarchyTest {

    // ─────────────────────────────────────────────────────────────
    // HgException (최상위 기반 클래스)
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("HgException 기반 클래스")
    class HgExceptionBaseTest {

        @Test
        @DisplayName("메시지만으로 생성 가능")
        void testCreateWithMessage() {
            HgException ex = new HgException("base error");
            assertEquals("base error", ex.getMessage());
            assertNull(ex.getCause());
            assertInstanceOf(Exception.class, ex);
        }

        @Test
        @DisplayName("메시지 + 원인 예외로 생성 가능")
        void testCreateWithMessageAndCause() {
            Throwable cause = new IllegalStateException("root cause");
            HgException ex = new HgException("wrapped", cause);
            assertEquals("wrapped", ex.getMessage());
            assertEquals(cause, ex.getCause());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // HgRepositoryNotFoundException
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("HgRepositoryNotFoundException")
    class HgRepositoryNotFoundExceptionTest {

        @Test
        @DisplayName("IOException 서브클래스임을 확인")
        void testIsIOException() {
            HgRepositoryNotFoundException ex = new HgRepositoryNotFoundException("/no/such/path");
            assertInstanceOf(IOException.class, ex);
        }

        @Test
        @DisplayName("경로가 메시지에 포함됨")
        void testPathInMessage() {
            HgRepositoryNotFoundException ex = new HgRepositoryNotFoundException("/repo/path");
            assertTrue(ex.getMessage().contains("/repo/path"));
        }

        @Test
        @DisplayName("메시지 + 원인 예외 생성자")
        void testWithCause() {
            Throwable cause = new IOException("disk error");
            HgRepositoryNotFoundException ex =
                    new HgRepositoryNotFoundException("/repo/path", cause);
            assertNotNull(ex.getCause());
            assertEquals(cause, ex.getCause());
        }

        @Test
        @DisplayName("경로 접근자 getPath() 반환값 확인")
        void testGetPath() {
            HgRepositoryNotFoundException ex = new HgRepositoryNotFoundException("/opt/hg/repo");
            assertEquals("/opt/hg/repo", ex.getPath());
        }

        @Test
        @DisplayName("catch IOException으로 잡힘")
        void testCatchAsIOException() {
            assertThrows(IOException.class, () -> {
                throw new HgRepositoryNotFoundException("/missing");
            });
        }
    }

    // ─────────────────────────────────────────────────────────────
    // HgCorruptDataException
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("HgCorruptDataException")
    class HgCorruptDataExceptionTest {

        @Test
        @DisplayName("IOException 서브클래스임을 확인")
        void testIsIOException() {
            HgCorruptDataException ex = new HgCorruptDataException("checksum mismatch");
            assertInstanceOf(IOException.class, ex);
        }

        @Test
        @DisplayName("메시지 보존")
        void testMessage() {
            HgCorruptDataException ex = new HgCorruptDataException("revlog index corrupt");
            assertEquals("revlog index corrupt", ex.getMessage());
        }

        @Test
        @DisplayName("메시지 + 원인 예외 생성자")
        void testWithCause() {
            Throwable cause = new IOException("read error");
            HgCorruptDataException ex = new HgCorruptDataException("corrupt data", cause);
            assertEquals(cause, ex.getCause());
        }

        @Test
        @DisplayName("catch IOException으로 잡힘")
        void testCatchAsIOException() {
            assertThrows(IOException.class, () -> {
                throw new HgCorruptDataException("delta apply failed");
            });
        }
    }

    // ─────────────────────────────────────────────────────────────
    // HgRevisionNotFoundException
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("HgRevisionNotFoundException")
    class HgRevisionNotFoundExceptionTest {

        @Test
        @DisplayName("IOException 서브클래스임을 확인")
        void testIsIOException() {
            HgRevisionNotFoundException ex =
                    new HgRevisionNotFoundException("abcdef1234567890abcdef1234567890abcdef12");
            assertInstanceOf(IOException.class, ex);
        }

        @Test
        @DisplayName("노드 ID가 메시지에 포함됨")
        void testNodeIdInMessage() {
            String nodeId = "abcdef1234567890abcdef1234567890abcdef12";
            HgRevisionNotFoundException ex = new HgRevisionNotFoundException(nodeId);
            assertTrue(ex.getMessage().contains(nodeId));
        }

        @Test
        @DisplayName("리비전 번호로 생성")
        void testWithRevNumber() {
            HgRevisionNotFoundException ex = new HgRevisionNotFoundException(42);
            assertTrue(ex.getMessage().contains("42"));
        }

        @Test
        @DisplayName("메시지 + 원인 예외 생성자")
        void testWithMessageAndCause() {
            Throwable cause = new IOException("io error");
            HgRevisionNotFoundException ex =
                    new HgRevisionNotFoundException("Revision not found: abc", cause);
            assertTrue(ex.getMessage().contains("abc"));
            assertEquals(cause, ex.getCause());
        }

        @Test
        @DisplayName("catch IOException으로 잡힘")
        void testCatchAsIOException() {
            assertThrows(IOException.class, () -> {
                throw new HgRevisionNotFoundException("deadbeef");
            });
        }
    }

    // ─────────────────────────────────────────────────────────────
    // HgMergeConflictException
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("HgMergeConflictException")
    class HgMergeConflictExceptionTest {

        @Test
        @DisplayName("HgException 서브클래스임을 확인")
        void testIsHgException() {
            HgMergeConflictException ex =
                    new HgMergeConflictException("src/main.java", "both sides modified");
            assertInstanceOf(HgException.class, ex);
        }

        @Test
        @DisplayName("충돌 파일 경로가 메시지에 포함됨")
        void testFilePathInMessage() {
            HgMergeConflictException ex =
                    new HgMergeConflictException("src/Foo.java", "conflicting changes");
            assertTrue(ex.getMessage().contains("src/Foo.java"));
        }

        @Test
        @DisplayName("충돌 파일 경로 접근자")
        void testGetConflictPath() {
            HgMergeConflictException ex =
                    new HgMergeConflictException("lib/Bar.java", "merge conflict");
            assertEquals("lib/Bar.java", ex.getConflictPath());
        }

        @Test
        @DisplayName("메시지 + 원인 예외 생성자")
        void testWithCause() {
            Throwable cause = new IOException("underlying error");
            HgMergeConflictException ex =
                    new HgMergeConflictException("src/Conflict.java", "both modified", cause);
            assertEquals("src/Conflict.java", ex.getConflictPath());
            assertEquals(cause, ex.getCause());
            assertTrue(ex.getMessage().contains("src/Conflict.java"));
        }

        @Test
        @DisplayName("catch HgException으로 잡힘")
        void testCatchAsHgException() {
            assertThrows(HgException.class, () -> {
                throw new HgMergeConflictException("README.md", "conflict");
            });
        }
    }

    // ─────────────────────────────────────────────────────────────
    // HgLockException
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("HgLockException")
    class HgLockExceptionTest {

        @Test
        @DisplayName("HgException 서브클래스임을 확인")
        void testIsHgException() {
            HgLockException ex = new HgLockException("store.lock", "locked by pid 1234");
            assertInstanceOf(HgException.class, ex);
        }

        @Test
        @DisplayName("락 이름이 메시지에 포함됨")
        void testLockNameInMessage() {
            HgLockException ex = new HgLockException("wlock", "timeout");
            assertTrue(ex.getMessage().contains("wlock"));
        }

        @Test
        @DisplayName("락 이름 접근자")
        void testGetLockName() {
            HgLockException ex = new HgLockException("store.lock", "another process holds lock");
            assertEquals("store.lock", ex.getLockName());
        }

        @Test
        @DisplayName("메시지 + 원인 예외 생성자")
        void testWithCause() {
            Throwable cause = new IOException("permission denied");
            HgLockException ex = new HgLockException("store.lock", "failed", cause);
            assertEquals(cause, ex.getCause());
        }

        @Test
        @DisplayName("catch HgException으로 잡힘")
        void testCatchAsHgException() {
            assertThrows(HgException.class, () -> {
                throw new HgLockException("store.lock", "held");
            });
        }
    }

    // ─────────────────────────────────────────────────────────────
    // HgTransportException (transport 예외 기반)
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("HgTransportException")
    class HgTransportExceptionTest {

        @Test
        @DisplayName("IOException 서브클래스임을 확인")
        void testIsIOException() {
            HgTransportException ex =
                    new HgTransportException("ssh://host/repo", "connection refused");
            assertInstanceOf(IOException.class, ex);
        }

        @Test
        @DisplayName("원격 URL이 메시지에 포함됨")
        void testRemoteUrlInMessage() {
            HgTransportException ex =
                    new HgTransportException("http://example.com/hg", "timeout");
            assertTrue(ex.getMessage().contains("http://example.com/hg"));
        }

        @Test
        @DisplayName("원격 URL 접근자")
        void testGetRemoteUrl() {
            String url = "ssh://server/repo";
            HgTransportException ex = new HgTransportException(url, "failed");
            assertEquals(url, ex.getRemoteUrl());
        }

        @Test
        @DisplayName("메시지 + 원인 예외 생성자")
        void testWithCause() {
            Throwable cause = new IOException("network error");
            HgTransportException ex =
                    new HgTransportException("http://example.com/hg", "failed", cause);
            assertEquals(cause, ex.getCause());
        }

        @Test
        @DisplayName("catch IOException으로 잡힘")
        void testCatchAsIOException() {
            assertThrows(IOException.class, () -> {
                throw new HgTransportException("ssh://host", "refused");
            });
        }
    }

    // ─────────────────────────────────────────────────────────────
    // HgAuthException (HgTransportException 서브클래스)
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("HgAuthException")
    class HgAuthExceptionTest {

        @Test
        @DisplayName("HgTransportException 서브클래스임을 확인")
        void testIsHgTransportException() {
            HgAuthException ex = new HgAuthException("ssh://host/repo", "admin");
            assertInstanceOf(HgTransportException.class, ex);
        }

        @Test
        @DisplayName("IOException으로도 잡힘 (계층 전파)")
        void testCatchAsIOException() {
            assertThrows(IOException.class, () -> {
                throw new HgAuthException("http://server/hg", "user");
            });
        }

        @Test
        @DisplayName("사용자명이 메시지에 포함됨")
        void testUsernameInMessage() {
            HgAuthException ex = new HgAuthException("ssh://host/repo", "johndoe");
            assertTrue(ex.getMessage().contains("johndoe"));
        }

        @Test
        @DisplayName("사용자명 접근자")
        void testGetUsername() {
            HgAuthException ex = new HgAuthException("ssh://host/repo", "alice");
            assertEquals("alice", ex.getUsername());
        }

        @Test
        @DisplayName("메시지 + 원인 예외 생성자")
        void testWithCause() {
            Throwable cause = new IOException("auth failed");
            HgAuthException ex = new HgAuthException("ssh://host", "bob", cause);
            assertEquals(cause, ex.getCause());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // HgProtocolException (HgTransportException 서브클래스)
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("HgProtocolException")
    class HgProtocolExceptionTest {

        @Test
        @DisplayName("HgTransportException 서브클래스임을 확인")
        void testIsHgTransportException() {
            HgProtocolException ex =
                    new HgProtocolException("http://server/hg", "invalid capabilities header");
            assertInstanceOf(HgTransportException.class, ex);
        }

        @Test
        @DisplayName("IOException으로도 잡힘 (계층 전파)")
        void testCatchAsIOException() {
            assertThrows(IOException.class, () -> {
                throw new HgProtocolException("ssh://host", "bad protocol");
            });
        }

        @Test
        @DisplayName("프로토콜 메시지 보존")
        void testProtocolMessagePreserved() {
            HgProtocolException ex =
                    new HgProtocolException("http://server/hg", "unexpected wire protocol v3");
            assertTrue(ex.getMessage().contains("unexpected wire protocol v3"));
        }

        @Test
        @DisplayName("메시지 + 원인 예외 생성자")
        void testWithCause() {
            Throwable cause = new IOException("read error");
            HgProtocolException ex =
                    new HgProtocolException("http://server/hg", "corrupt frame", cause);
            assertEquals(cause, ex.getCause());
        }

        @Test
        @DisplayName("catch HgTransportException으로도 잡힘")
        void testCatchAsHgTransportException() {
            assertThrows(HgTransportException.class, () -> {
                throw new HgProtocolException("http://server/hg", "error");
            });
        }
    }
}
