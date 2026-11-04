package io.github.search5.hg4j.shape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The subset of TOML that {@code server-shapes} uses: comments, {@code key = value} pairs, {@code [[array of tables]]}, basic and literal
 * strings, integers, booleans and (possibly multi-line) arrays of those. Anything else is a parse error. A duplicate key is an error, as
 * in TOML. The result is nested {@code Map}/{@code List} with {@code String}, {@code Long} and {@code Boolean} leaves.
 */
final class MiniToml {

    /** A syntax error, with the 1-based line it was found on. */
    static final class TomlError extends Exception {
        TomlError(int line, String message) {
            super("TOML parse error at line " + line + ": " + message);
        }
    }

    private final String text;
    private int pos;
    private int line = 1;

    private MiniToml(String text) {
        this.text = text;
    }

    static Map<String, Object> parse(String text) throws TomlError {
        return new MiniToml(text).document();
    }

    private Map<String, Object> document() throws TomlError {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> current = root;
        while (true) {
            skipBlank();
            if (pos >= text.length()) {
                return root;
            }
            if (text.startsWith("[[", pos)) {
                pos += 2;
                String name = bareKey();
                skipSpaces();
                expect("]]");
                @SuppressWarnings("unchecked")
                List<Object> tables = (List<Object>) root.computeIfAbsent(name, k -> new ArrayList<>());
                if (!(tables instanceof ArrayList)) {
                    throw new TomlError(line, "`" + name + "` is not an array of tables");
                }
                current = new LinkedHashMap<>();
                tables.add(current);
                endOfLine();
            } else if (text.charAt(pos) == '[') {
                throw new TomlError(line, "plain tables are not supported");
            } else {
                String key = bareKey();
                skipSpaces();
                expect("=");
                skipSpaces();
                Object value = value();
                if (current.containsKey(key)) {
                    throw new TomlError(line, "duplicate key `" + key + "`");
                }
                current.put(key, value);
                endOfLine();
            }
        }
    }

    private void skipSpaces() {
        while (pos < text.length() && (text.charAt(pos) == ' ' || text.charAt(pos) == '\t')) {
            pos++;
        }
    }

    /** Blank space, newlines and comments between statements. */
    private void skipBlank() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == '\n') {
                line++;
                pos++;
            } else if (c == ' ' || c == '\t' || c == '\r') {
                pos++;
            } else if (c == '#') {
                while (pos < text.length() && text.charAt(pos) != '\n') {
                    pos++;
                }
            } else {
                return;
            }
        }
    }

    private void endOfLine() throws TomlError {
        skipSpaces();
        if (pos < text.length() && text.charAt(pos) == '#') {
            while (pos < text.length() && text.charAt(pos) != '\n') {
                pos++;
            }
        }
        if (pos < text.length() && text.charAt(pos) == '\r') {
            pos++;
        }
        if (pos < text.length()) {
            if (text.charAt(pos) != '\n') {
                throw new TomlError(line, "expected a newline, found `" + text.charAt(pos) + "`");
            }
            pos++;
            line++;
        }
    }

    private void expect(String token) throws TomlError {
        if (!text.startsWith(token, pos)) {
            throw new TomlError(line, "expected `" + token + "`");
        }
        pos += token.length();
    }

    private String bareKey() throws TomlError {
        int start = pos;
        while (pos < text.length() && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '-' || text.charAt(pos) == '_')) {
            pos++;
        }
        if (start == pos) {
            throw new TomlError(line, "expected a key");
        }
        return text.substring(start, pos);
    }

    private Object value() throws TomlError {
        if (pos >= text.length()) {
            throw new TomlError(line, "expected a value");
        }
        char c = text.charAt(pos);
        if (c == '"') {
            return basicString();
        } else if (c == '\'') {
            int end = text.indexOf('\'', pos + 1);
            int newline = text.indexOf('\n', pos + 1);
            if (end < 0 || newline >= 0 && newline < end) {
                throw new TomlError(line, "unterminated string");
            }
            String s = text.substring(pos + 1, end);
            pos = end + 1;
            return s;
        } else if (c == '[') {
            return array();
        } else if (text.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        } else if (text.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        } else if (c == '-' || c == '+' || Character.isDigit(c)) {
            int start = pos;
            pos++;
            while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '_')) {
                pos++;
            }
            try {
                return Long.parseLong(text.substring(start, pos).replace("_", ""));
            } catch (NumberFormatException e) {
                throw new TomlError(line, "invalid number");
            }
        }
        throw new TomlError(line, "unexpected `" + c + "`, expected a value");
    }

    private String basicString() throws TomlError {
        StringBuilder sb = new StringBuilder();
        pos++;
        while (true) {
            if (pos >= text.length() || text.charAt(pos) == '\n') {
                throw new TomlError(line, "unterminated string");
            }
            char c = text.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= text.length()) {
                throw new TomlError(line, "unterminated string");
            }
            char e = text.charAt(pos++);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case 'u', 'U' -> {
                    int digits = e == 'u' ? 4 : 8;
                    if (pos + digits > text.length()) {
                        throw new TomlError(line, "invalid unicode escape");
                    }
                    try {
                        sb.appendCodePoint(Integer.parseInt(text.substring(pos, pos + digits), 16));
                    } catch (IllegalArgumentException bad) {
                        throw new TomlError(line, "invalid unicode escape");
                    }
                    pos += digits;
                }
                default -> throw new TomlError(line, "invalid escape `\\" + e + "`");
            }
        }
    }

    private List<Object> array() throws TomlError {
        List<Object> items = new ArrayList<>();
        pos++;
        while (true) {
            skipBlank();
            if (pos >= text.length()) {
                throw new TomlError(line, "unterminated array");
            }
            if (text.charAt(pos) == ']') {
                pos++;
                return items;
            }
            items.add(value());
            skipBlank();
            if (pos < text.length() && text.charAt(pos) == ',') {
                pos++;
            } else if (pos < text.length() && text.charAt(pos) != ']') {
                throw new TomlError(line, "expected `,` or `]` in an array");
            }
        }
    }
}
