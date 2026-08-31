package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.util.NodeIdUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * External process SCM hook executor that launches OS shell scripts or binaries
 * and maps repository transaction context data into subprocess environment variables.
 */
public class ProcessHook implements HgHook {
    private static final Logger LOGGER = Logger.getLogger(ProcessHook.class.getName());

    private final List<String> command;
    private final File workingDir;

    public ProcessHook(String command) {
        this(splitCommand(command), null);
    }

    public ProcessHook(List<String> command) {
        this(command, null);
    }

    public ProcessHook(List<String> command, File workingDir) {
        this.command = new ArrayList<>(command);
        this.workingDir = workingDir;
    }

    private static List<String> splitCommand(String cmd) {
        List<String> list = new ArrayList<>();
        if (cmd == null || cmd.trim().isEmpty()) {
            return list;
        }
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = 0;
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            if (c == '"' || c == '\'') {
                if (inQuotes) {
                    if (c == quoteChar) {
                        inQuotes = false;
                        quoteChar = 0;
                    } else {
                        sb.append(c);
                    }
                } else {
                    inQuotes = true;
                    quoteChar = c;
                }
            } else if (Character.isWhitespace(c)) {
                if (inQuotes) {
                    sb.append(c);
                } else {
                    if (sb.length() > 0) {
                        list.add(sb.toString());
                        sb.setLength(0);
                    }
                }
            } else {
                sb.append(c);
            }
        }
        if (sb.length() > 0) {
            list.add(sb.toString());
        }
        return list;
    }

    @Override
    public boolean run(Map<String, Object> context) throws IOException {
        if (command == null || command.isEmpty()) {
            return true;
        }

        ProcessBuilder pb = new ProcessBuilder(command);

        // 1. Resolve working directory
        File dir = workingDir;
        if (dir == null && context.containsKey("repository")) {
            Object repoObj = context.get("repository");
            if (repoObj instanceof HgRepository) {
                dir = ((HgRepository) repoObj).getDirectory();
            }
        }
        if (dir != null) {
            pb.directory(dir);
        }

        // 2. Map SCM transaction context variables into process environments
        Map<String, String> env = pb.environment();
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            if (val == null) continue;

            String envKey = "HG_" + key.toUpperCase();
            if (val instanceof byte[]) {
                env.put(envKey, NodeIdUtil.toHex((byte[]) val));
            } else if (val instanceof HgRepository) {
                env.put(envKey, ((HgRepository) val).getDirectory().getAbsolutePath());
            } else {
                env.put(envKey, String.valueOf(val));
            }
        }

        // 3. Launch process with error stream redirection to prevent deadlock
        try {
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // Consume unified output/error streams to avoid deadlock
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    LOGGER.log(Level.INFO, "[ProcessHook OutErr] " + line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                LOGGER.log(Level.WARNING, "Process hook rejected SCM transaction, exit code: " + exitCode);
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Process hook execution was interrupted", e);
        }
    }
}
