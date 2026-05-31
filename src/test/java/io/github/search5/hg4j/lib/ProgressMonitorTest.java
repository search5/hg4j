package io.github.search5.hg4j.lib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProgressMonitor — Unit tests for progress monitoring")
public class ProgressMonitorTest {

    @Test
    @DisplayName("NullProgressMonitor operates without throwing any exceptions")
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
    @DisplayName("Verify that TextProgressMonitor correctly serializes and writes progress status to a stream (Writer)")
    void testTextProgressMonitor_tracking() {
        StringWriter writer = new StringWriter();
        TextProgressMonitor monitor = new TextProgressMonitor(writer);

        // 1. Start task (defined size 100)
        monitor.start("Merge Task", 100);
        String output = writer.toString();
        assertTrue(output.contains("Merge Task: start (total 100)"), "Actual output: " + output);

        // 2. Update progress
        monitor.update(20);
        output = writer.toString();
        assertTrue(output.contains("Merge Task: 20 / 100"), "Actual output: " + output);

        // 3. Cumulative update
        monitor.update(30);
        output = writer.toString();
        assertTrue(output.contains("Merge Task: 50 / 100"), "Actual output: " + output);

        // 4. End task
        monitor.end();
        output = writer.toString();
        assertTrue(output.contains("Merge Task: completed"), "Actual output: " + output);
    }

    @Test
    @DisplayName("Verify TextProgressMonitor status with an UNKNOWN total size task")
    void testTextProgressMonitor_unknownTotal() {
        StringWriter writer = new StringWriter();
        TextProgressMonitor monitor = new TextProgressMonitor(writer);

        monitor.start("Clone Task", ProgressMonitor.UNKNOWN);
        String output = writer.toString();
        assertTrue(output.contains("Clone Task: start"), "Actual output: " + output);
        assertFalse(output.contains("total"), "Actual output: " + output);

        monitor.update(15);
        output = writer.toString();
        assertTrue(output.contains("Clone Task: 15"), "Actual output: " + output);
        assertFalse(output.contains("/"), "Actual output: " + output);
    }

    @Test
    @DisplayName("Verify ProgressMonitor cancellation state control functionality")
    void testTextProgressMonitor_cancellation() {
        TextProgressMonitor monitor = new TextProgressMonitor(new StringWriter());
        assertFalse(monitor.isCancelled());

        monitor.cancel();
        assertTrue(monitor.isCancelled());
    }
}
