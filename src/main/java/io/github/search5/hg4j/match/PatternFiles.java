package io.github.search5.hg4j.match;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Pattern files such as {@code .hgignore} and the target of {@code include:}: Mercurial's {@code match.readpatternfile}. Every
 * returned entry is {@code kind:pattern} (the file's {@code syntax:} lines decide the kind of lines without one).
 */
public final class PatternFiles {

    private static final Pattern COMMENT = Pattern.compile("((?:^|[^\\\\])(?:\\\\\\\\)*)#.*", Pattern.DOTALL);

    private PatternFiles() {
    }

    public static List<String> read(File file, Consumer<String> warn) throws IOException {
        String[][] syntaxes = {{"re", "relre:"}, {"regexp", "relre:"}, {"glob", "relglob:"}, {"rootglob", "rootglob:"},
                {"include", "include"}, {"subinclude", "subinclude"}};
        String syntax = "relre:";
        List<String> patterns = new ArrayList<>();
        String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1);
        for (String raw : content.split("\n", -1)) {
            String line = raw;
            if (line.contains("#")) {
                java.util.regex.Matcher m = COMMENT.matcher(line);
                if (m.find()) {
                    line = line.substring(0, m.end(1)); // comments behind an even number of escapes
                }
                line = line.replace("\\#", "#");
            }
            line = rstrip(line);
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("syntax:")) {
                String s = line.substring(7).strip();
                String found = null;
                for (String[] pair : syntaxes) {
                    if (pair[0].equals(s)) {
                        found = pair[1];
                    }
                }
                if (found == null) {
                    if (warn != null) {
                        warn.accept(file + ": ignoring invalid syntax '" + s + "'\n");
                    }
                } else {
                    syntax = found;
                }
                continue;
            }
            String lineSyntax = syntax;
            for (String[] pair : syntaxes) {
                if (line.startsWith(pair[1])) {
                    lineSyntax = pair[1];
                    line = line.substring(pair[1].length());
                    break;
                } else if (line.startsWith(pair[0] + ":")) {
                    lineSyntax = pair[1];
                    line = line.substring(pair[0].length() + 1);
                    break;
                }
            }
            patterns.add(lineSyntax + line);
        }
        return patterns;
    }

    private static String rstrip(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }
}
