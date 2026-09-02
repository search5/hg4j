package com.github.search5.hg4j.lib;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.github.search5.hg4j.util.SafeFileIO;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses and manages Mercurial configuration files (.hg/hgrc, ~/.hgrc) in INI format.
 * Dynamically resolves [ui] username, [paths] default repository, proxy, and TLS settings.
 */
public final class HgRcConfig {
    private final Map<String, Map<String, String>> sections = new LinkedHashMap<>();

    public HgRcConfig() {}

    /**
     * Loads settings from a specific hgrc configuration file.
     *
     * @param file hgrc config file
     * @throws IOException if reading fails
     */
    public void load(File file) throws IOException {
        if (file == null || !file.exists() || !file.isFile()) {
            return;
        }
        String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        parse(content, file.getAbsoluteFile().getParentFile());
    }

    /**
     * Parses INI-format configuration string with no base directory, so a
     * {@code %include} using a relative path cannot be resolved (mirrors calling
     * {@link #parse(String, File)} with a {@code null} directory).
     */
    public void parse(String content) {
        parse(content, null);
    }

    /**
     * Parses INI-format configuration content, honoring the real hgrc directives
     * {@code %include <path>} (loads another config file, resolved relative to
     * {@code baseDir} when the path isn't absolute; a missing included file is
     * silently ignored, matching {@code mercurial/config.py}'s {@code read()}/
     * {@code include()} handling of {@code ENOENT}) and {@code %unset <name>}
     * (removes a previously set key in the current section). Also supports
     * indented continuation lines that append to the previous key's value,
     * joined with {@code "\n"}.
     *
     * @param content the raw hgrc text
     * @param baseDir directory that relative {@code %include} paths are resolved against, or
     *                 {@code null} if unknown (relative includes are then skipped)
     */
    public void parse(String content, File baseDir) {
        if (content == null || content.isEmpty()) {
            return;
        }

        String[] lines = content.split("\n", -1);
        String currentSection = null;
        String pendingKey = null;

        for (String line : lines) {
            boolean isContinuation = pendingKey != null && currentSection != null
                    && !line.isEmpty() && Character.isWhitespace(line.charAt(0)) && !line.trim().isEmpty();
            if (isContinuation) {
                String contValue = line.trim();
                String existing = get(currentSection, pendingKey);
                set(currentSection, pendingKey, (existing != null ? existing + "\n" : "") + contValue);
                continue;
            }
            pendingKey = null;

            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
                continue;
            }

            if (trimmed.startsWith("%include")) {
                String includePath = trimmed.substring("%include".length()).trim();
                if (!includePath.isEmpty()) {
                    File includeFile = new File(includePath);
                    if (!includeFile.isAbsolute() && baseDir != null) {
                        includeFile = new File(baseDir, includePath);
                    }
                    if (includeFile.isAbsolute() || baseDir != null) {
                        try {
                            load(includeFile);
                        } catch (IOException ignored) {
                            // 실제 스펙(mercurial/config.py): ENOENT는 조용히 무시된다.
                        }
                    }
                }
                continue;
            }

            if (trimmed.startsWith("%unset")) {
                String name = trimmed.substring("%unset".length()).trim();
                if (currentSection != null && !name.isEmpty()) {
                    set(currentSection, name, null);
                }
                continue;
            }

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.substring(1, trimmed.length() - 1).trim().toLowerCase();
                sections.computeIfAbsent(currentSection, k -> new LinkedHashMap<>());
                continue;
            }

            int eqIdx = trimmed.indexOf('=');
            if (eqIdx != -1 && currentSection != null) {
                String key = trimmed.substring(0, eqIdx).trim();
                String val = trimmed.substring(eqIdx + 1).trim();
                set(currentSection, key, val);
                pendingKey = key;
            }
        }
    }

    /**
     * Gets a configuration value from a section and key.
     *
     * @param section section name (case-insensitive)
     * @param key key name
     * @return config value, or null if not found
     */
    public String get(String section, String key) {
        if (section == null || key == null) {
            return null;
        }
        Map<String, String> sec = sections.get(section.toLowerCase());
        return sec != null ? sec.get(key) : null;
    }

    /**
     * Gets a configuration value, falling back to a default if not found.
     */
    public String get(String section, String key, String defaultValue) {
        String val = get(section, key);
        return val != null ? val : defaultValue;
    }

    /**
     * Helper to resolve the UI username.
     */
    public String getUsername() {
        return get("ui", "username");
    }

    /**
     * Helper to resolve a path url by name (e.g. "default").
     */
    public String getPath(String name) {
        return get("paths", name);
    }

    /**
     * Returns a snapshot of all key/value pairs in a config section, in the order they
     * were parsed (matching {@code mercurial/config.py}'s insertion-preserving behavior).
     * Used to enumerate e.g. the {@code [paths]} section for {@code hg paths}, where the
     * command itself is responsible for any display-order sorting.
     *
     * @param section section name (case-insensitive)
     * @return an unmodifiable, insertion-ordered map of the section's entries; empty
     *         (never {@code null}) if the section doesn't exist
     */
    public Map<String, String> getSection(String section) {
        if (section == null) {
            return Map.of();
        }
        Map<String, String> sec = sections.get(section.toLowerCase());
        if (sec == null || sec.isEmpty()) {
            return Map.of();
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(sec));
    }

    /**
     * Sets a configuration value for a specific section and key.
     *
     * @param section section name (case-insensitive)
     * @param key key name
     * @param value configuration value
     */
    public void set(String section, String key, String value) {
        if (section == null || key == null) {
            return;
        }
        String sectionName = section.toLowerCase().trim();
        Map<String, String> sec = sections.computeIfAbsent(sectionName, k -> new LinkedHashMap<>());
        if (value == null) {
            sec.remove(key);
        } else {
            sec.put(key, value);
        }
    }

    /**
     * Saves the current configuration to a specific file in INI format.
     * Uses atomic file IO for safety.
     *
     * @param file hgrc config file to save to
     * @throws IOException if writing fails
     */
    public void save(File file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("File cannot be null");
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Map<String, String>> secEntry : sections.entrySet()) {
            if (secEntry.getValue().isEmpty()) {
                continue;
            }
            sb.append("[").append(secEntry.getKey()).append("]\n");
            for (Map.Entry<String, String> entry : secEntry.getValue().entrySet()) {
                sb.append(entry.getKey()).append(" = ").append(entry.getValue()).append("\n");
            }
            sb.append("\n");
        }
        SafeFileIO.writeStringAtomic(file, sb.toString());
    }
}
