package io.github.search5.hg4j.gpg;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parser and writer of Mercurial's signature list format ({@code .hgsigs}, {@code .hg/localsigs}).
 *
 * <p>Parsing follows {@code hgext/gpg.py}'s {@code sigwalk}: one entry per line, split on a space into at
 * most three fields, empty lines ignored and not counted in the reported line numbers. Where the original
 * would crash with an unhandled {@code ValueError} (a line with fewer than three fields) or accept garbage
 * (a node that is not 40 hex digits, a signature that is not base64), this parser throws
 * {@link HgSigFormatException} naming the line.</p>
 */
public final class HgSigsFile {

    private static final Pattern NODE = Pattern.compile("[0-9a-f]{40}");

    private HgSigsFile() {
    }

    /** An entry together with the 1-based number of its line (empty lines are not counted). */
    public record Line(HgSigEntry entry, int lineNumber) {
    }

    /**
     * @param content the file content
     * @param context a name for error messages, e.g. {@code ".hgsigs|1a2b3c4d5e6f"} or {@code "localsigs"}
     */
    public static List<Line> parse(String content, String context) throws HgSigFormatException {
        List<Line> lines = new ArrayList<>();
        int ln = 1;
        for (String raw : content.split("\r?\n|\r", -1)) {
            if (raw.isEmpty()) {
                continue;
            }
            String[] parts = raw.split(" ", 3);
            if (parts.length != 3) {
                throw new HgSigFormatException(context + ":" + ln + " malformed signature line (expected"
                        + " '<node> <version> <base64>'): " + abbreviate(raw));
            }
            if (!NODE.matcher(parts[0]).matches()) {
                throw new HgSigFormatException(context + ":" + ln + " node is not 40 lowercase hex digits: "
                        + abbreviate(parts[0]));
            }
            byte[] signature;
            try {
                signature = Base64.getDecoder().decode(parts[2]);
            } catch (IllegalArgumentException e) {
                throw new HgSigFormatException(context + ":" + ln + " signature is not valid base64", e);
            }
            lines.add(new Line(new HgSigEntry(parts[0], parts[1], signature), ln));
            ln++;
        }
        return lines;
    }

    /** @return the lines to append to a file: each entry's {@link HgSigEntry#toLine()} plus {@code "\n"} */
    public static byte[] format(List<HgSigEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (HgSigEntry e : entries) {
            sb.append(e.toLine()).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static String abbreviate(String s) {
        return s.length() <= 80 ? s : s.substring(0, 77) + "...";
    }
}
