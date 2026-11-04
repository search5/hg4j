package io.github.search5.hg4j.match;

/**
 * A pattern split into its kind and text, as Mercurial's {@code (kind, pattern, source)} triples: {@code source} is the pattern
 * file the pattern came from (empty for one given directly).
 */
public record KindPattern(String kind, String pattern, String source) {

    public KindPattern(String kind, String pattern) {
        this(kind, pattern, "");
    }
}
