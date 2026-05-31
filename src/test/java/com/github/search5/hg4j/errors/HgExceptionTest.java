package com.github.search5.hg4j.errors;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD 1단계: 도메인 예외 계층의 최상위 예외 클래스 단위 테스트
 */
public class HgExceptionTest {

    @Test
    public void testHgExceptionCreation() {
        String msg = "Repository corruption detected";
        HgException ex = new HgException(msg);

        assertEquals(msg, ex.getMessage());
        assertNull(ex.getCause());
        assertTrue(ex instanceof Exception);
    }

    @Test
    public void testHgExceptionWithCause() {
        String msg = "Lock acquisition failed";
        Exception cause = new Exception("File locked by process 1234");
        HgException ex = new HgException(msg, cause);

        assertEquals(msg, ex.getMessage());
        assertEquals(cause, ex.getCause());
    }
}
