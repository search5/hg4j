package org.hg4j.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * High-performance parser for Mercurial subrepository configuration files (.hgsub and .hgsubstate).
 * Seamlessly joins submodule definitions with their state revisions.
 */
public final class HgSubrepoParser {

    /**
     * Parses the combined contents of .hgsub and .hgsubstate into a map of subrepo entries.
     *
     * @param hgsubContent content of the .hgsub file
     * @param hgsubstateContent content of the .hgsubstate file
     * @return map of subrepo path to compiled entry metadata
     * @throws IOException if parsing fails or invalid format is detected
     */
    public static Map<String, HgSubrepoEntry> parseSubrepositories(byte[] hgsubContent, byte[] hgsubstateContent) throws IOException {
        Map<String, HgSubrepoEntry> subrepos = new LinkedHashMap<>();
        
        // 1. Parse .hgsub configurations
        Map<String, String> sources = new LinkedHashMap<>();
        Map<String, Boolean> gitFlags = new LinkedHashMap<>();
        if (hgsubContent != null && hgsubContent.length > 0) {
            String text = new String(hgsubContent, StandardCharsets.UTF_8);
            String[] lines = text.split("\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eqIdx = trimmed.indexOf('=');
                if (eqIdx == -1) {
                    throw new org.hg4j.errors.HgCorruptDataException("Malformed .hgsub entry: missing '=' symbol");
                }
                String path = trimmed.substring(0, eqIdx).trim();
                String rawUrl = trimmed.substring(eqIdx + 1).trim();

                boolean isGit = false;
                if (rawUrl.startsWith("[git]")) {
                    isGit = true;
                    rawUrl = rawUrl.substring("[git]".length()).trim();
                }

                sources.put(path, rawUrl);
                gitFlags.put(path, isGit);
            }
        }

        // 2. Parse .hgsubstate revisions and merge with configurations
        if (hgsubstateContent != null && hgsubstateContent.length > 0) {
            String text = new String(hgsubstateContent, StandardCharsets.UTF_8);
            String[] lines = text.split("\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int spaceIdx = trimmed.indexOf(' ');
                if (spaceIdx == -1) {
                    throw new org.hg4j.errors.HgCorruptDataException("Malformed .hgsubstate entry: missing space between hash and path");
                }
                String revision = trimmed.substring(0, spaceIdx).trim();
                String path = trimmed.substring(spaceIdx + 1).trim();

                String sourceUrl = sources.getOrDefault(path, "");
                boolean isGit = gitFlags.getOrDefault(path, false);

                subrepos.put(path, new HgSubrepoEntry(path, sourceUrl, revision, isGit));
            }
        }

        // 3. Fallback: Add configured subrepos that do not have a recorded state yet
        for (String path : sources.keySet()) {
            if (!subrepos.containsKey(path)) {
                subrepos.put(path, new HgSubrepoEntry(path, sources.get(path), "", gitFlags.getOrDefault(path, false)));
            }
        }

        return subrepos;
    }
}
