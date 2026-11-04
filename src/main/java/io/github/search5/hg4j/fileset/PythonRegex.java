package io.github.search5.hg4j.fileset;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Compiles the regular expressions of {@code grep()} and describes an invalid one the way Python's {@code re.error} does (the
 * words of the common mistakes: an unclosed group or set, nothing to repeat, a bad escape, an unbalanced parenthesis).
 */
final class PythonRegex {

    private PythonRegex() {
    }

    /** Compiles {@code pattern}; the text and the data it is applied to are one character per byte. */
    static Pattern compile(String pattern) throws FilesetParseException {
        String message = describeError(pattern);
        if (message == null) {
            try {
                return io.github.search5.hg4j.match.Globs.compileUnixLines(pattern);
            } catch (PatternSyntaxException e) {
                message = e.getDescription() + " at position " + Math.max(e.getIndex(), 0);
            }
        }
        throw new FilesetParseException("invalid match pattern: " + message);
    }

    /** The Python error text of an invalid expression, or {@code null} when none of the known mistakes is found. */
    static String describeError(String pat) {
        Deque<Integer> groups = new ArrayDeque<>();
        boolean canRepeat = false; // whether a quantifier has something before it
        boolean afterQuantifier = false;
        int n = pat.length();
        for (int i = 0; i < n; i++) {
            char c = pat.charAt(i);
            if (c == '\\') {
                if (i + 1 >= n) {
                    return "bad escape (end of pattern) at position " + i;
                }
                char d = pat.charAt(i + 1);
                if (Character.isLetter(d) && d < 128 && "abfnrtvxAbBdDsSwWZ".indexOf(d) < 0) {
                    return "bad escape \\" + d + " at position " + i;
                }
                i++;
                canRepeat = true;
                afterQuantifier = false;
                continue;
            }
            if (c == '[') {
                int start = i;
                int j = i + 1;
                if (j < n && pat.charAt(j) == '^') {
                    j++;
                }
                if (j < n && pat.charAt(j) == ']') {
                    j++;
                }
                while (j < n && pat.charAt(j) != ']') {
                    if (pat.charAt(j) == '\\') {
                        j++;
                    }
                    j++;
                }
                if (j >= n) {
                    return "unterminated character set at position " + start;
                }
                i = j;
                canRepeat = true;
                afterQuantifier = false;
                continue;
            }
            if (c == '(') {
                groups.push(i);
                canRepeat = false;
                afterQuantifier = false;
                if (i + 1 < n && pat.charAt(i + 1) == '?') {
                    i++; // skip the '?' of (?:, (?P<n>, (?i) ...
                    canRepeat = false;
                }
                continue;
            }
            if (c == ')') {
                if (groups.isEmpty()) {
                    return "unbalanced parenthesis at position " + i;
                }
                groups.pop();
                canRepeat = true;
                afterQuantifier = false;
                continue;
            }
            if (c == '|') {
                canRepeat = false;
                afterQuantifier = false;
                continue;
            }
            if (c == '*' || c == '+' || c == '?' || c == '{') {
                boolean brace = c == '{';
                if (brace) {
                    int close = pat.indexOf('}', i);
                    if (close < 0 || !pat.substring(i + 1, close).matches("\\d*(,\\d*)?") || pat.substring(i + 1, close).isEmpty()
                            || pat.substring(i + 1, close).equals(",")) {
                        canRepeat = true; // a literal '{'
                        afterQuantifier = false;
                        continue;
                    }
                }
                if (!canRepeat && !afterQuantifier) {
                    return "nothing to repeat at position " + i;
                }
                if (afterQuantifier && !(c == '?' || c == '+')) {
                    return "multiple repeat at position " + i;
                }
                if (afterQuantifier) { // a lazy or possessive marker after a quantifier
                    afterQuantifier = false;
                    continue;
                }
                if (brace) {
                    i = pat.indexOf('}', i);
                }
                afterQuantifier = true;
                canRepeat = false;
                continue;
            }
            canRepeat = true;
            afterQuantifier = false;
        }
        if (!groups.isEmpty()) {
            return "missing ), unterminated subpattern at position " + groups.peek();
        }
        return null;
    }
}
