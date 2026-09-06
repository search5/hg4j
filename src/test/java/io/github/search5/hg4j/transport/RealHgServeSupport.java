package io.github.search5.hg4j.transport;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Test-only helper: spawns a real, host-installed {@code hg serve} process against a given
 * repository directory with caller-supplied extra {@code --config} arguments, and detects the
 * port it actually bound to (always requested as {@code -p 0} to avoid port clashes between
 * parallel test runs). Extracted from the working pattern already proven in
 * {@code HgRemoteMockAndServeExtensionTest#testNativeHgExtendedServe} -- shared here so the
 * backlog-22 negotiation-forcing interop tests (which need many differently-configured {@code hg
 * serve} instances, one per forced capability/compression combination) don't each reimplement the
 * port-detection dance.
 */
final class RealHgServeSupport {

    private RealHgServeSupport() {
    }

    static final class ServeHandle implements AutoCloseable {
        final Process process;
        final String url;

        ServeHandle(Process process, String url) {
            this.process = process;
            this.url = url;
        }

        @Override
        public void close() {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Starts {@code hg serve -p 0 --address 127.0.0.1 <extraConfigArgs...>} in {@code repoDir} and
     * waits (up to 8s) for its own "listening at http://..." banner to determine the real port.
     */
    static ServeHandle start(File repoDir, String... extraConfigArgs) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("hg", "serve", "-p", "0", "--address", "127.0.0.1"));
        cmd.addAll(Arrays.asList(extraConfigArgs));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(repoDir);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        List<String> lines = new CopyOnWriteArrayList<>();
        String[] detected = new String[1];
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    lines.add(line);
                    if (line.contains("listening at")) {
                        int idx = line.indexOf("http://");
                        if (idx != -1) {
                            int end = line.indexOf("/", idx + 7);
                            detected[0] = end != -1 ? line.substring(idx, end + 1) : line.substring(idx).trim();
                        }
                    }
                }
            } catch (IOException ignored) {
                // process torn down -- nothing more to read
            }
        }, "real-hg-serve-stdout-reader");
        reader.setDaemon(true);
        reader.start();

        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 8000 && detected[0] == null) {
            Thread.sleep(100);
        }
        if (detected[0] == null) {
            p.destroy();
            throw new AssertionError("hg serve never printed a listening URL within 8s. Output so far: " + lines
                    + " (command: " + cmd + ")");
        }
        String url = detected[0].replaceAll("http://[^:]+:", "http://127.0.0.1:");
        return new ServeHandle(p, url);
    }
}
