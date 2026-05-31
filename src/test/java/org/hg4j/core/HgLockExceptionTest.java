package org.hg4j.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to verify the constructor and inheritance structure of HgLockException.
 */
public class HgLockExceptionTest {

    @Test
    public void testMessageConstructor() {
        HgLockException ex = new HgLockException("락 획득 실패");
        assertEquals("락 획득 실패", ex.getMessage());
        assertNull(ex.getCause());
        assertInstanceOf(IOException.class, ex);
    }

    @Test
    public void testMessageAndCauseConstructor() {
        RuntimeException cause = new RuntimeException("원인 예외");
        HgLockException ex = new HgLockException("락 획득 실패", cause);
        assertEquals("락 획득 실패", ex.getMessage());
        assertSame(cause, ex.getCause());
        assertInstanceOf(IOException.class, ex);
    }

    @Test
    public void testThrowAndCatch() {
        assertThrows(HgLockException.class, () -> {
            throw new HgLockException("테스트 예외");
        });
    }

    @Test
    public void testThrowWithCauseAndCatch() {
        assertThrows(HgLockException.class, () -> {
            try {
                throw new RuntimeException("내부 오류");
            } catch (RuntimeException e) {
                throw new HgLockException("래핑된 오류", e);
            }
        });
    }

    @Test
    public void testExceptionIsIOException() {
        // HgLockException extends IOException, so it must be catchable as an IOException
        boolean caught = false;
        try {
            throw new HgLockException("IO 테스트");
        } catch (IOException e) {
            caught = true;
            assertEquals("IO 테스트", e.getMessage());
        }
        assertTrue(caught);
    }
}
