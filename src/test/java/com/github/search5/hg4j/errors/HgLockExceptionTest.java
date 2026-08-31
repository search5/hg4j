package com.github.search5.hg4j.errors;

import org.junit.jupiter.api.Test;
import com.github.search5.hg4j.errors.HgLockException;
import com.github.search5.hg4j.errors.HgException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to verify the constructor and inheritance structure of HgLockException.
 */
public class HgLockExceptionTest {

    @Test
    public void testMessageConstructor() {
        HgLockException ex = new HgLockException("lock", "락 획득 실패");
        assertTrue(ex.getMessage().contains("락 획득 실패"));
        assertNull(ex.getCause());
        assertInstanceOf(HgException.class, ex);
    }

    @Test
    public void testMessageAndCauseConstructor() {
        RuntimeException cause = new RuntimeException("원인 예외");
        HgLockException ex = new HgLockException("lock", "락 획득 실패", cause);
        assertTrue(ex.getMessage().contains("락 획득 실패"));
        assertSame(cause, ex.getCause());
        assertInstanceOf(HgException.class, ex);
    }

    @Test
    public void testThrowAndCatch() {
        assertThrows(HgLockException.class, () -> {
            throw new HgLockException("lock", "테스트 예외");
        });
    }

    @Test
    public void testThrowWithCauseAndCatch() {
        assertThrows(HgLockException.class, () -> {
            try {
                throw new RuntimeException("내부 오류");
            } catch (RuntimeException e) {
                throw new HgLockException("lock", "래핑된 오류", e);
            }
        });
    }

    @Test
    public void testExceptionIsHgException() {
        boolean caught = false;
        try {
            throw new HgLockException("lock", "IO 테스트");
        } catch (HgException e) {
            caught = true;
            assertTrue(e.getMessage().contains("IO 테스트"));
        }
        assertTrue(caught);
    }
}

