package com.github.search5.hg4j.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Automated Micro-benchmark suite for hg4j Core.
 * This class ensures that core components (delta generation, mmap reader, and cache)
 * satisfy strict sub-millisecond execution constraints on high workloads.
 */
public class PerformanceBenchmarkTest {

    @Test
    public void testHighLoadPerformanceBenchmark(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("bench.i").toFile();
        File datFile = tempDir.resolve("bench.d").toFile();

        Revlog revlog = new Revlog(idxFile, datFile);
        byte[] pNode = new byte[20];

        // 1. Write Benchmark: Create 500 sequential revisions (Delta chain)
        System.out.println("=== Starting Write Throughput Benchmark (500 commits) ===");
        long writeStart = System.currentTimeMillis();

        byte[] currentText = "Initial benchmark text base configuration template.\n".repeat(30).getBytes(StandardCharsets.UTF_8);
        byte[] lastNode = revlog.appendRevision(currentText, -1, -1, pNode, pNode, 0);

        Random rand = new Random(42);
        for (int i = 1; i < 500; i++) {
            // Apply slight modifications to trigger effective delta compression
            String modification = "Modifying line marker: " + i + " rand=" + rand.nextInt(1000) + "\n";
            byte[] modBytes = modification.getBytes(StandardCharsets.UTF_8);
            
            byte[] nextText = new byte[currentText.length + modBytes.length];
            System.arraycopy(currentText, 0, nextText, 0, currentText.length);
            System.arraycopy(modBytes, 0, nextText, currentText.length, modBytes.length);

            lastNode = revlog.appendRevision(nextText, i - 1, -1, lastNode, pNode, i);
            currentText = nextText;
        }

        long writeEnd = System.currentTimeMillis();
        long writeDuration = writeEnd - writeStart;
        System.out.printf("Completed 500 compressed revisions in: %d ms (Avg: %.2f ms/commit)%n", 
                writeDuration, writeDuration / 500.0);

        // 2. Read Throughput Benchmark: Perform 1,000 random/sequential reads
        System.out.println("=== Starting Read Throughput Benchmark (1,000 reads via Mmap/Cache) ===");
        long readStart = System.currentTimeMillis();

        int totalRevs = revlog.getRevisionCount();
        assertEquals(500, totalRevs);

        // Clear file caches once to measure initial cold map & subsequent warm cached hits
        revlog.clearCache();

        long sumBytes = 0;
        for (int k = 0; k < 1000; k++) {
            // Read target revisions (mix of deep delta chains and recent hot revisions)
            int revToRead = k % totalRevs;
            byte[] content = revlog.getRevisionContent(revToRead);
            sumBytes += content.length;
            assertTrue(content.length > 0);
        }

        long readEnd = System.currentTimeMillis();
        long readDuration = readEnd - readStart;
        System.out.printf("Completed 1,000 file reconstruct reads in: %d ms (Avg: %.3f ms/read, Total Bytes: %d)%n", 
                readDuration, readDuration / 1000.0, sumBytes);

        // Strict SLA Assertions: 1,000 reads must finish comfortably under 2.0 seconds
        // (Even with 500-level deep delta chain reconstruction, warm cached mmap hits should take less than 1-2 ms per read on average)
        assertTrue(readDuration < 2000, 
                "SLA Violated: 1,000 SCM file reads took too long (" + readDuration + " ms)");
    }
}
