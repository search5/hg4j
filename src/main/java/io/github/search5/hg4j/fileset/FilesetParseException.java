package io.github.search5.hg4j.fileset;

import io.github.search5.hg4j.errors.HgValidationException;

/**
 * Mercurial's {@code error.ParseError}: the text reads {@code parse error at POS: MESSAGE}, or {@code parse error: MESSAGE} when
 * the place is unknown; a hint may follow on its own line.
 */
public class FilesetParseException extends HgValidationException {
    private static final long serialVersionUID = 1L;

    private final String detail;
    private final int position;
    private final String hint;

    /**
     * Creates a parse error whose place is unknown.
     *
     * @param detail the message without the {@code parse error} prefix
     */
    public FilesetParseException(String detail) {
        this(detail, -1, null);
    }

    /**
     * Creates a parse error at a position.
     *
     * @param detail the message without the {@code parse error} prefix
     * @param position the byte position in the expression, or a negative number if unknown
     */
    public FilesetParseException(String detail, int position) {
        this(detail, position, null);
    }

    /**
     * Creates a parse error at a position, with a hint.
     *
     * @param detail the message without the {@code parse error} prefix
     * @param position the byte position in the expression, or a negative number if unknown
     * @param hint the hint Mercurial prints in parentheses on the next line; may be {@code null}
     */
    public FilesetParseException(String detail, int position, String hint) {
        super(position >= 0 ? "parse error at " + position + ": " + detail : "parse error: " + detail);
        this.detail = detail;
        this.position = position;
        this.hint = hint;
    }

    /** The message without the {@code parse error} prefix. */
    public String getDetail() {
        return detail;
    }

    /** The byte position in the expression, or -1. */
    public int getPosition() {
        return position;
    }

    /** The hint Mercurial prints in parentheses on the next line, or {@code null}. */
    public String getHint() {
        return hint;
    }
}
