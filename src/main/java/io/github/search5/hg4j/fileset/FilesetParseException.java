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

    public FilesetParseException(String detail) {
        this(detail, -1, null);
    }

    public FilesetParseException(String detail, int position) {
        this(detail, position, null);
    }

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
