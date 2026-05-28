package org.hg4j.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HgLockException의 생성자 및 상속 관계를 검증하는 테스트.
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
        // HgLockException은 IOException을 extends하므로 IOException으로 캐치 가능해야 함
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
