package org.hg4j.lib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProgressMonitor — 작업 진행도 모니터링 단위 테스트")
public class ProgressMonitorTest {

    @Test
    @DisplayName("NullProgressMonitor는 어떠한 예외 없이 작동함")
    void testNullProgressMonitor() {
        ProgressMonitor monitor = NullProgressMonitor.INSTANCE;
        assertDoesNotThrow(() -> {
            monitor.start("Silent Task", 10);
            monitor.update(3);
            assertFalse(monitor.isCancelled());
            monitor.end();
        });
    }

    @Test
    @DisplayName("TextProgressMonitor가 스트림(Writer)에 진행 상태를 정확히 직렬화하여 기록하는지 검증")
    void testTextProgressMonitor_tracking() {
        StringWriter writer = new StringWriter();
        TextProgressMonitor monitor = new TextProgressMonitor(writer);

        // 1. 작업 시작 (확정 크기 100)
        monitor.start("Merge Task", 100);
        String output = writer.toString();
        assertTrue(output.contains("Merge Task: start (total 100)"), "실제 출력: " + output);

        // 2. 진행 업데이트
        monitor.update(20);
        output = writer.toString();
        assertTrue(output.contains("Merge Task: 20 / 100"), "실제 출력: " + output);

        // 3. 누적 업데이트
        monitor.update(30);
        output = writer.toString();
        assertTrue(output.contains("Merge Task: 50 / 100"), "실제 출력: " + output);

        // 4. 작업 종료
        monitor.end();
        output = writer.toString();
        assertTrue(output.contains("Merge Task: completed"), "실제 출력: " + output);
    }

    @Test
    @DisplayName("TextProgressMonitor 진행 크기를 알 수 없는 작업(UNKNOWN) 상태 검증")
    void testTextProgressMonitor_unknownTotal() {
        StringWriter writer = new StringWriter();
        TextProgressMonitor monitor = new TextProgressMonitor(writer);

        monitor.start("Clone Task", ProgressMonitor.UNKNOWN);
        String output = writer.toString();
        assertTrue(output.contains("Clone Task: start"), "실제 출력: " + output);
        assertFalse(output.contains("total"), "실제 출력: " + output);

        monitor.update(15);
        output = writer.toString();
        assertTrue(output.contains("Clone Task: 15"), "실제 출력: " + output);
        assertFalse(output.contains("/"), "실제 출력: " + output);
    }

    @Test
    @DisplayName("ProgressMonitor 취소(Cancel) 상태 제어 기능 검증")
    void testTextProgressMonitor_cancellation() {
        TextProgressMonitor monitor = new TextProgressMonitor(new StringWriter());
        assertFalse(monitor.isCancelled());

        monitor.cancel();
        assertTrue(monitor.isCancelled());
    }
}
