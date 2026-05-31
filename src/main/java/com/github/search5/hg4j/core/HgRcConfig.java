package com.github.search5.hg4j.core;

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
        parse(content);
    }

    /**
     * Parses INI-format configuration string.
     */
    public void parse(String content) {
        if (content == null || content.isEmpty()) {
            return;
        }

        String[] lines = content.split("\n");
        Map<String, String> currentSection = null;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
                continue;
            }

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                String sectionName = trimmed.substring(1, trimmed.length() - 1).trim().toLowerCase();
                currentSection = sections.computeIfAbsent(sectionName, k -> new LinkedHashMap<>());
            } else if (currentSection != null) {
                int eqIdx = trimmed.indexOf('=');
                if (eqIdx != -1) {
                    String key = trimmed.substring(0, eqIdx).trim();
                    String val = trimmed.substring(eqIdx + 1).trim();
                    currentSection.put(key, val);
                }
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
