package io.github.search5.hg4j.treewalk;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * High-performance Glob-matching path filter for Monorepo Sparse Checkout.
 */
public class SparsePathFilter implements PathFilter {
    private final List<Pattern> patterns = new ArrayList<>();

    public SparsePathFilter(String... globPatterns) {
        if (globPatterns != null) {
            for (String glob : globPatterns) {
                if (glob != null && !glob.isEmpty()) {
                    this.patterns.add(compileGlobToPattern(glob));
                }
            }
        }
    }

    public SparsePathFilter(List<String> globPatterns) {
        if (globPatterns != null) {
            for (String glob : globPatterns) {
                if (glob != null && !glob.isEmpty()) {
                    this.patterns.add(compileGlobToPattern(glob));
                }
            }
        }
    }

    @Override
    public boolean accept(String path) {
        if (patterns.isEmpty()) {
            return true;
        }
        for (Pattern p : patterns) {
            if (p.matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }

    private Pattern compileGlobToPattern(String glob) {
        StringBuilder sb = new StringBuilder("^");
        int len = glob.length();
        for (int i = 0; i < len; i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < len && glob.charAt(i + 1) == '*') {
                    i++; // skip next '*'
                    if (i + 1 < len && glob.charAt(i + 1) == '/') {
                        // "**/" bordering a path separator must only match whole
                        // path segments (zero or more), matching Mercurial's own
                        // glob-to-regex translation (mercurial/match.py _globre:
                        // "a/**/b" -> "a/(?:.*/)?b"). A bare ".*" here would let
                        // the following literal fuse mid-segment (e.g. wrongly
                        // matching "a/xb" for glob "a/**/b"), which real hg does
                        // not do (verified against `hg debugsparse`).
                        sb.append("(?:.*/)?");
                        i++; // skip next '/', it's absorbed into the group above
                    } else {
                        sb.append(".*");
                    }
                } else {
                    sb.append("[^/]*");
                }
            } else if (c == '?') {
                // A single '?' matches any one character, including '/', matching
                // Mercurial's own translation (_globre('?') == '.'); verified
                // against `hg debugsparse` that "a?b" keeps "a/b" in the sparse
                // checkout.
                sb.append(".");
            } else if (c == '.' || c == '\\' || c == '+' || c == '^' || c == '$' || c == '(' || c == ')' || c == '[' || c == ']' || c == '{' || c == '}' || c == '|') {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        // Auto-allow all contents if the pattern represents a directory
        if (!glob.endsWith("**") && !glob.endsWith("*")) {
            if (glob.endsWith("/")) {
                sb.append(".*");
            } else {
                sb.append("(/.*)?");
            }
        }
        sb.append("$");
        return Pattern.compile(sb.toString());
    }
}
