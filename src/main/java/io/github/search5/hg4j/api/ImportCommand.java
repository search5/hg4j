package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lib.HgRepository;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Porcelain command corresponding to {@code hg import}: applies a unified-diff patch (as produced
 * by {@code hg export}/{@code hg diff}, or by {@link ExportCommand}) to the working directory and
 * commits the result as a new changeset on top of the current dirstate parent -- matching real
 * {@code hg import}'s default (non-{@code --exact}) behavior of ignoring the patch's own {@code
 * # Node ID}/{@code # Parent} headers and simply committing on top of whatever is currently
 * checked out.
 *
 * <p><b>Backlog #39 (2026-09-05) rewrite:</b> this command used to hand-roll the entire
 * manifest/changelog serialization itself (building a flat {@code path\0hex\n} manifest text
 * directly and computing the new changeset's node hash by hand), which structurally could never
 * work correctly against a treemanifest repository (a treemanifest manifest is a tree of
 * per-directory revlogs under {@code store/meta/}, not one flat {@code 00manifest.i} entry) and
 * skipped every other format-specific behavior {@link CommitCommand} already gets right (sidedata
 * copy-tracing, branch/close-branch {@code extra} encoding, bookmark advancement, phase
 * assignment, undo/rollback bookkeeping, LFS). Fixed by reducing this command to exactly what
 * real {@code hg import} conceptually does: apply the patch to the working directory and dirstate
 * (via the same {@link AddCommand}/{@link RemoveCommand} machinery a manual {@code hg add}/{@code
 * hg rm} would use), then delegate the actual commit to {@link CommitCommand} -- which already
 * handles every repository format this matrix covers.
 *
 * <p>Patch parsing recognizes the classic (non-{@code --git}) unified-diff shape real {@code hg
 * export} emits by default: a {@code --- <old>} / {@code +++ <new>} header pair per file, where
 * either side may be the literal {@code /dev/null} to mark a pure add ({@code --- /dev/null})
 * or delete ({@code +++ /dev/null}), followed by one or more {@code @@ ... @@} hunks. Hunk
 * application matches each hunk's context/removed lines by CONTENT (not by trusting the {@code
 * @@ -a,b +c,d @@} line numbers), the same forgiving strategy the previous implementation used --
 * sufficient for the plain text adds/modifies/deletes this matrix's round trips exercise; binary
 * diffs and {@code --git}-only constructs (mode changes, renames) are out of scope, matching real
 * {@code hg export}'s own default (non-{@code --git}) output, which cannot represent them either.
 */
public class ImportCommand {
    private final HgRepository repository;
    private String patchText;

    public ImportCommand(HgRepository repository) {
        this.repository = repository;
    }

    public ImportCommand setPatchText(String patchText) {
        this.patchText = patchText;
        return this;
    }

    /** One file's worth of parsed patch content: either new full text (add/modify) or a deletion. */
    private static final class ParsedPatch {
        final Map<String, String> modifiedOrAdded = new LinkedHashMap<>();
        final Set<String> deleted = new LinkedHashSet<>();
    }

    /**
     * Parses the patch headers, applies its file changes to the working directory + dirstate, and
     * commits the result via {@link CommitCommand}.
     *
     * @throws IOException if patch parsing or commit writing fails
     */
    public void call() throws IOException, HgLockException {
        if (patchText == null || patchText.isEmpty()) {
            throw new IllegalArgumentException("Patch content must not be null or empty for import");
        }

        String[] lines = patchText.split("\n");
        String author = "unknown";
        String dateVal = null;
        StringBuilder descBuilder = new StringBuilder();
        boolean parsingHeaders = true;

        for (String line : lines) {
            if (parsingHeaders) {
                if (line.startsWith("# User ")) {
                    author = line.substring(7).trim();
                } else if (line.startsWith("# Date ")) {
                    dateVal = line.substring(7).trim();
                } else if (line.startsWith("# Node ID ")) {
                    // skip -- real hg import (without --exact) commits on top of the current
                    // dirstate parent regardless of what the patch itself declares.
                } else if (line.startsWith("# Parent ")) {
                    // skip, same reason.
                } else if (line.startsWith("#") || line.isEmpty()) {
                    if (line.isEmpty() && descBuilder.length() > 0) {
                        parsingHeaders = false;
                    }
                } else {
                    if (descBuilder.length() > 0) descBuilder.append("\n");
                    descBuilder.append(line);
                }
            }
        }

        String message = descBuilder.toString().trim();
        if (message.isEmpty()) {
            message = "Imported patch";
        }

        ParsedPatch parsed = parseAndApplyPatch(patchText);

        // Apply to the working directory + dirstate first (each of AddCommand/RemoveCommand takes
        // its own short-lived wlock, mirroring what a manual `hg add`/`hg rm` sequence would do
        // before the final `hg commit`).
        List<String> addedOrModifiedPaths = new ArrayList<>();
        for (Map.Entry<String, String> e : parsed.modifiedOrAdded.entrySet()) {
            String path = e.getKey();
            File wFile = new File(repository.getDirectory(), path);
            File parentDir = wFile.getParentFile();
            if (parentDir != null) {
                parentDir.mkdirs();
            }
            Files.write(wFile.toPath(), e.getValue().getBytes(StandardCharsets.UTF_8));
            addedOrModifiedPaths.add(path);
        }
        if (!addedOrModifiedPaths.isEmpty()) {
            AddCommand addCmd = new AddCommand(repository);
            for (String path : addedOrModifiedPaths) {
                addCmd.addFile(path);
            }
            addCmd.call();
        }
        for (String path : parsed.deleted) {
            new RemoveCommand(repository).setFile(path).setForce(true).call();
        }

        CommitCommand commitCmd = new CommitCommand(repository)
                .setAuthor(author)
                .setMessage(message);
        // A missing/unparseable "# Date" header falls back to the current time at UTC (offset 0)
        // -- NOT CommitCommand's own no-setDate-called default (current time, LOCAL offset) --
        // matching this command's pre-existing (pre-backlog-#39) fallback behavior exactly.
        long fallbackSecs = System.currentTimeMillis() / 1000;
        int fallbackOffset = 0;
        if (dateVal != null) {
            String[] dateParts = dateVal.trim().split("\\s+");
            try {
                fallbackSecs = Long.parseLong(dateParts[0]);
                fallbackOffset = dateParts.length > 1 ? Integer.parseInt(dateParts[1]) : 0;
            } catch (NumberFormatException ignored) {
                // Malformed/unparseable date header -- fall back to "now, UTC" computed above,
                // matching how this command has always tolerated a garbled date.
                fallbackSecs = System.currentTimeMillis() / 1000;
                fallbackOffset = 0;
            }
        }
        commitCmd.setDate(fallbackSecs, fallbackOffset);
        commitCmd.call();
    }

    /** Strips a leading {@code "a/"}/{@code "b/"} prefix if present, else returns {@code s} unchanged. */
    private static String stripAbPrefix(String s) {
        return (s.startsWith("a/") || s.startsWith("b/")) ? s.substring(2) : s;
    }

    /** Strips a trailing tab-separated timestamp some diff producers append after the path
     * (e.g. {@code "a/file\tThu Jan 01 00:00:00 1970 +0000"}), else returns {@code s} unchanged. */
    private static String stripTrailingTimestamp(String s) {
        int tab = s.indexOf('\t');
        return (tab >= 0) ? s.substring(0, tab) : s;
    }

    private ParsedPatch parseAndApplyPatch(String patchText) throws IOException {
        ParsedPatch result = new ParsedPatch();
        String[] lines = patchText.split("\n", -1);

        String currentFile = null;
        boolean currentIsDelete = false;
        List<String> currentLines = new ArrayList<>();
        // Backlog #39 (2026-09-05): whether the file's current tail (as of the last hunk applied)
        // lacks a trailing newline -- real diff/hg's `\ No newline at end of file` marker, which
        // {@link DiffCommand#generateUnifiedDiff} now also emits. Re-evaluated (not OR'd) after
        // every hunk: only the hunk that actually touches the true end of file can carry this
        // marker at all, so whichever hunk was applied MOST RECENTLY correctly reflects the
        // file's current trailing-newline state.
        boolean currentFileNoTrailingNewline = false;

        int idx = 0;
        while (idx < lines.length) {
            String line = lines[idx];

            if (line.startsWith("--- ")) {
                String oldSpec = stripTrailingTimestamp(line.substring(4).trim());
                String newSpec = null;
                if (idx + 1 < lines.length && lines[idx + 1].startsWith("+++ ")) {
                    newSpec = stripTrailingTimestamp(lines[idx + 1].substring(4).trim());
                }
                boolean oldIsNull = "/dev/null".equals(oldSpec);
                boolean newIsNull = newSpec == null || "/dev/null".equals(newSpec);
                String oldPath = oldIsNull ? null : stripAbPrefix(oldSpec);
                String newPath = newIsNull ? null : stripAbPrefix(newSpec);

                currentFile = (newPath != null) ? newPath : oldPath;
                currentIsDelete = (newPath == null);
                currentLines = new ArrayList<>();
                currentFileNoTrailingNewline = false;
                if (currentFile != null) {
                    File wFile = new File(repository.getDirectory(), currentFile);
                    if (!oldIsNull && wFile.exists()) {
                        currentLines.addAll(Files.readAllLines(wFile.toPath(), StandardCharsets.UTF_8));
                    }
                    if (currentIsDelete) {
                        result.deleted.add(currentFile);
                    }
                }
                idx += (newSpec != null) ? 2 : 1;
                continue;
            }

            if (currentFile != null && line.startsWith("@@ ")) {
                idx++;
                List<String> hunkOld = new ArrayList<>();
                List<String> hunkNew = new ArrayList<>();
                // Tracks which side(s) the MOST RECENTLY appended content line belongs to, so a
                // following "\ No newline at end of file" marker can be attributed correctly (a
                // " " context line belongs to both sides at once; "-"/"+"  belong to only one).
                boolean lastLineAffectsOld = false;
                boolean lastLineAffectsNew = false;
                boolean hunkNewNoTrailingNewline = false;
                while (idx < lines.length) {
                    String hunkLine = lines[idx];
                    if (hunkLine.startsWith("diff ") || hunkLine.startsWith("--- ") || hunkLine.startsWith("+++ ") || hunkLine.startsWith("@@ ")) {
                        break;
                    }
                    if (hunkLine.startsWith("\\")) {
                        // "\ No newline at end of file" marker -- not a content line, but tells us
                        // the line(s) just appended lack a trailing newline in the real file.
                        if (lastLineAffectsNew) {
                            hunkNewNoTrailingNewline = true;
                        }
                        idx++;
                        continue;
                    }
                    if (hunkLine.startsWith("-")) {
                        hunkOld.add(hunkLine.substring(1));
                        lastLineAffectsOld = true;
                        lastLineAffectsNew = false;
                    } else if (hunkLine.startsWith("+")) {
                        hunkNew.add(hunkLine.substring(1));
                        lastLineAffectsOld = false;
                        lastLineAffectsNew = true;
                    } else if (hunkLine.startsWith(" ")) {
                        hunkOld.add(hunkLine.substring(1));
                        hunkNew.add(hunkLine.substring(1));
                        lastLineAffectsOld = true;
                        lastLineAffectsNew = true;
                    } else {
                        break;
                    }
                    idx++;
                }
                currentFileNoTrailingNewline = hunkNewNoTrailingNewline;

                if (!currentIsDelete) {
                    int matchIdx;
                    if (hunkOld.isEmpty()) {
                        matchIdx = currentLines.size();
                    } else {
                        matchIdx = -1;
                        for (int c = 0; c <= currentLines.size() - hunkOld.size(); c++) {
                            boolean match = true;
                            for (int h = 0; h < hunkOld.size(); h++) {
                                if (!currentLines.get(c + h).equals(hunkOld.get(h))) {
                                    match = false;
                                    break;
                                }
                            }
                            if (match) {
                                matchIdx = c;
                                break;
                            }
                        }
                    }

                    if (matchIdx != -1) {
                        for (int r = 0; r < hunkOld.size(); r++) {
                            currentLines.remove(matchIdx);
                        }
                        currentLines.addAll(matchIdx, hunkNew);
                    } else {
                        currentLines.addAll(hunkNew);
                    }

                    StringBuilder sb = new StringBuilder();
                    for (int li = 0; li < currentLines.size(); li++) {
                        sb.append(currentLines.get(li));
                        boolean isLastLine = li == currentLines.size() - 1;
                        if (!isLastLine || !currentFileNoTrailingNewline) {
                            sb.append("\n");
                        }
                    }
                    result.modifiedOrAdded.put(currentFile, sb.toString());
                }
                continue;
            }
            idx++;
        }
        return result;
    }
}
