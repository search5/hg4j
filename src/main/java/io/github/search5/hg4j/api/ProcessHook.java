package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;

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
import java.nio.charset.StandardCharsets;

/**
 * External process SCM hook executor that launches OS shell scripts or binaries
 * and maps repository transaction context data into subprocess environment variables.
 *
 * @apiNote The ready-made {@link HgHook} implementation for the common case of running an
 *     external script/binary as a hook (real hg's own {@code hooks.<name> = <shell command>}
 *     style); register it via {@link Hg#registerHook} against the appropriate {@link
 *     HgHookType}. Context values are exposed to the subprocess as {@code HG_<KEY>} environment
 *     variables, mirroring real hg's own hook environment convention.
 */
public class ProcessHook implements HgHook {
    private static final Logger LOGGER = Logger.getLogger(ProcessHook.class.getName());

    private final List<String> command;
    private final File workingDir;

    /**
     * Creates a new hook that runs the given shell command line, run in the repository directory
     * by default.
     *
     * @param command the shell command line to run, split on whitespace with basic
     *     single/double-quote support (see {@link #splitCommand(String)})
     */
    public ProcessHook(String command) {
        this(splitCommand(command), null);
    }

    /**
     * Creates a new hook that runs the given already-tokenized command, run in the repository
     * directory by default.
     *
     * @param command the command and its arguments, as separate tokens (no shell parsing applied)
     */
    public ProcessHook(List<String> command) {
        this(command, null);
    }

    /**
     * Creates a new hook that runs the given already-tokenized command in a specific working
     * directory.
     *
     * @param command the command and its arguments, as separate tokens (no shell parsing applied)
     * @param workingDir the directory the subprocess should run in, or {@code null} to fall back
     *     to the repository's directory (resolved from the hook context at run time)
     */
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
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
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
