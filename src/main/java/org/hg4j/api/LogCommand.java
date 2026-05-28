package org.hg4j.api;

import org.hg4j.core.HgRepository;
import org.hg4j.core.Revlog;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Traverses changelog revlog and retrieves commit history.
 */
public class LogCommand {
    private final HgRepository repository;

    public LogCommand(HgRepository repository) {
        this.repository = repository;
    }

    public List<HgCommit> call() throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");

        if (!clIdx.exists()) {
            return Collections.emptyList();
        }

        Revlog changelog = repository.getRevlog(clIdx, clDat);
        int totalRevisions = changelog.getRevisionCount();
        List<HgCommit> commits = new ArrayList<>();

        // Return newest commits first
        for (int rev = totalRevisions - 1; rev >= 0; rev--) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(rev);
            byte[] nodeId = rec.getNodeId();

            byte[] content = changelog.getRevisionContent(rev);
            String text = new String(content, StandardCharsets.UTF_8);

            // Parse text
            int firstNewline = text.indexOf('\n');
            if (firstNewline == -1) {
                System.err.println("Warning: Malformed commit text at revision " + rev + ": first newline not found");
                continue;
            }
            String manifestHex = text.substring(0, firstNewline).trim();
            if (manifestHex.length() != 40) {
                System.err.println("Warning: Malformed commit text at revision " + rev + ": invalid manifest hex length");
                continue;
            }
            byte[] manifestNodeId = fromHex(manifestHex);

            int secondNewline = text.indexOf('\n', firstNewline + 1);
            if (secondNewline == -1) {
                System.err.println("Warning: Malformed commit text at revision " + rev + ": second newline not found");
                continue;
            }
            String author = text.substring(firstNewline + 1, secondNewline);

            int thirdNewline = text.indexOf('\n', secondNewline + 1);
            if (thirdNewline == -1) {
                System.err.println("Warning: Malformed commit text at revision " + rev + ": third newline not found");
                continue;
            }
            String dateLine = text.substring(secondNewline + 1, thirdNewline).trim();
            long timestamp = 0;
            int tzOffset = 0;
            String branch = "default";
            
            String datePart;
            String extraPart = null;
            int firstSpace = dateLine.indexOf(' ');
            if (firstSpace != -1) {
                int secondSpace = dateLine.indexOf(' ', firstSpace + 1);
                if (secondSpace != -1) {
                    datePart = dateLine.substring(0, secondSpace);
                    extraPart = dateLine.substring(secondSpace + 1);
                } else {
                    datePart = dateLine;
                }
            } else {
                datePart = dateLine;
            }

            String[] dateParts = datePart.split(" ");
            if (dateParts.length >= 1) {
                try {
                    timestamp = Long.parseLong(dateParts[0]);
                } catch (NumberFormatException ignored) {}
            }
            if (dateParts.length >= 2) {
                try {
                    tzOffset = Integer.parseInt(dateParts[1]);
                } catch (NumberFormatException ignored) {}
            }

            if (extraPart != null && !extraPart.isEmpty()) {
                String[] extraItems = extraPart.split("\0", -1);
                for (String part : extraItems) {
                    int colonIdx = CommitCommand.findUnescapedColon(part);
                    if (colonIdx != -1) {
                        String key = part.substring(0, colonIdx);
                        String val = part.substring(colonIdx + 1);
                        key = CommitCommand.decodeExtraKey(key);
                        val = CommitCommand.decodeExtraKey(val);
                        if ("branch".equals(key)) {
                            branch = val;
                        }
                    }
                }
            }

            int doubleNewline = text.indexOf("\n\n", thirdNewline + 1);
            List<String> files = new ArrayList<>();
            String message = "";
            if (doubleNewline != -1) {
                String filesPart = text.substring(thirdNewline + 1, doubleNewline);
                for (String line : filesPart.split("\n")) {
                    if (!line.isEmpty()) {
                        files.add(line);
                    }
                }
                message = text.substring(doubleNewline + 2);
            } else {
                message = text.substring(thirdNewline + 1);
            }

            commits.add(new HgCommit(rev, nodeId, manifestNodeId, author, timestamp, tzOffset, files, message, branch));
        }

        return commits;
    }

    private static byte[] fromHex(String hex) {
        if (hex == null || hex.length() % 2 != 0) {
            return new byte[0];
        }
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
