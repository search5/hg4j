package io.github.search5.hg4j.match;

import io.github.search5.hg4j.errors.HgValidationException;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The translation of patterns to regular expressions, ported from Mercurial's {@code match.py}: {@code _globre}, {@code _regex},
 * {@code reescape}. The result is a regular expression that must match from the start of the path ({@code re.match}), i.e. use
 * {@link java.util.regex.Matcher#lookingAt()}.
 */
public final class Globs {

    /** The characters {@code re.escape} of Python 3.7 and Mercurial's {@code reescape} put a backslash in front of. */
    private static final String REGEX_SPECIAL = "()[]{}?*+-|^$\\.&~# \t\n\r\u000b\f";

    private Globs() {
    }

    /** {@code stringutil.reescape}. */
    public static String reEscape(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (REGEX_SPECIAL.indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String escapeChar(char c) {
        return REGEX_SPECIAL.indexOf(c) >= 0 ? "\\" + c : String.valueOf(c);
    }

    /** {@code match._globre}: an extended glob as a regular expression. */
    public static String globRe(String pat) {
        int i = 0;
        int n = pat.length();
        StringBuilder res = new StringBuilder();
        int group = 0;
        while (i < n) {
            char c = pat.charAt(i++);
            if ("*?[{},\\".indexOf(c) < 0) {
                res.append(escapeChar(c));
            } else if (c == '*') {
                if (i < n && pat.charAt(i) == '*') {
                    i++;
                    if (i < n && pat.charAt(i) == '/') {
                        i++;
                        res.append("(?:.*/)?");
                    } else {
                        res.append(".*");
                    }
                } else {
                    res.append("[^/]*");
                }
            } else if (c == '?') {
                res.append('.');
            } else if (c == '[') {
                int j = i;
                if (j < n && "!]".indexOf(pat.charAt(j)) >= 0) {
                    j++;
                }
                while (j < n && pat.charAt(j) != ']') {
                    j++;
                }
                if (j >= n) {
                    res.append("\\[");
                } else {
                    String stuff = pat.substring(i, j).replace("\\", "\\\\");
                    i = j + 1;
                    if (stuff.startsWith("!")) {
                        stuff = "^" + stuff.substring(1);
                    } else if (stuff.startsWith("^")) {
                        stuff = "\\" + stuff;
                    }
                    // Python reads '[' and '&&' inside a set literally, Java treats them as nested set and intersection
                    stuff = stuff.replace("[", "\\[").replace("&&", "&\\&");
                    res.append('[').append(stuff).append(']');
                }
            } else if (c == '{') {
                group++;
                res.append("(?:");
            } else if (c == '}' && group > 0) {
                res.append(')');
                group--;
            } else if (c == ',' && group > 0) {
                res.append('|');
            } else if (c == '\\') {
                if (i < n) {
                    char p = pat.charAt(i++);
                    res.append(escapeChar(p));
                } else {
                    res.append(escapeChar(c));
                }
            } else {
                res.append(escapeChar(c));
            }
        }
        return res.toString();
    }

    private static final Pattern FLAG_RE = Pattern.compile("^\\(\\?([aiLmsux]+)\\)(.*)", Pattern.DOTALL);

    /** {@code match._regex}: the regular expression of a normalized pattern of any kind; {@code globSuffix} follows globs. */
    public static String regex(String kind, String pat, String globSuffix) {
        if (pat.isEmpty() && (kind.equals("glob") || kind.equals("relpath"))) {
            return "";
        }
        switch (kind) {
            case "re":
                return pat;
            case "filepath":
                throw new IllegalStateException("'filepath:' patterns should not be converted to a regex");
            case "path":
            case "relpath":
                if (pat.equals(".")) {
                    return "";
                }
                return reEscape(pat) + "(?:/|$)";
            case "rootfilesin": {
                String escaped = pat.equals(".") ? "" : reEscape(pat) + "/";
                return escaped + "[^/]+$"; // anything after the directory must be a non-directory
            }
            case "relglob": {
                String globre = globRe(pat);
                if (globre.startsWith("[^/]*")) {
                    return ".*" + globre.substring("[^/]*".length()) + globSuffix;
                }
                return "(?:|.*/)" + globre + globSuffix;
            }
            case "relre": {
                String flag = null;
                java.util.regex.Matcher m = FLAG_RE.matcher(pat);
                if (m.matches()) {
                    flag = m.group(1);
                    pat = m.group(2);
                }
                if (!pat.startsWith("^")) {
                    pat = ".*" + pat;
                }
                if (flag != null) {
                    pat = "(?" + flag + ":" + pat + ")";
                }
                return pat;
            }
            case "glob":
            case "rootglob":
                return globRe(pat) + globSuffix;
            default:
                throw new IllegalStateException("not a regex pattern: " + kind + ":" + pat);
        }
    }

    /**
     * Compiles a pattern written in Python's regular expression syntax. The few differences that matter are translated (named
     * groups {@code (?P<n>...)} / {@code (?P=n)}, and the flags Java has no counterpart for are dropped).
     *
     * @throws PatternSyntaxException if the expression is invalid
     */
    public static Pattern compile(String python) {
        String java = python.replace("(?P<", "(?<").replaceAll("\\(\\?P=(\\w+)\\)", "\\\\k<$1>");
        // flag groups: (?aiLmsux) and (?aiLmsux:...): keep the flags Java knows
        java = java.replaceAll("\\(\\?([aiLmsux]+)([:)])", "(?$1$2");
        java = stripUnknownFlags(java);
        return Pattern.compile(java);
    }

    /** Like {@link #compile} with only {@code \n} as line terminator, as Python's byte patterns have it. */
    public static Pattern compileUnixLines(String python) {
        String java = python.replace("(?P<", "(?<").replaceAll("\\(\\?P=(\\w+)\\)", "\\\\k<$1>");
        java = stripUnknownFlags(java);
        return Pattern.compile(java, Pattern.UNIX_LINES);
    }

    private static String stripUnknownFlags(String regex) {
        java.util.regex.Matcher m = Pattern.compile("\\(\\?([aiLmsux]+)([:)])").matcher(regex);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String kept = m.group(1).replaceAll("[aL]", "");
            String replacement = kept.isEmpty() ? (m.group(2).equals(")") ? "" : "(?:") : "(?" + kept + m.group(2);
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Throws the abort Mercurial prints for a pattern that is no valid expression. */
    static HgValidationException invalid(String kind, String pattern, String source) {
        return new HgValidationException(source == null || source.isEmpty()
                ? "invalid pattern (" + kind + "): " + pattern
                : source + ": invalid pattern (" + kind + "): " + pattern);
    }
}
