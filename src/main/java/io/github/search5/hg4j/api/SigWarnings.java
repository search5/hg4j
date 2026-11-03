package io.github.search5.hg4j.api;

import io.github.search5.hg4j.gpg.HgSigResult;

import java.util.List;

/** The messages {@code hgext/gpg.py}'s {@code getkeys} prints for signatures that are not plainly good. */
final class SigWarnings {

    private SigWarnings() {
    }

    /**
     * @param where the {@code <file>:<line>} prefix
     */
    static void addFor(List<String> warnings, String where, HgSigResult r) {
        switch (r.getStatus()) {
            case UNKNOWN_KEY -> warnings.add(where + " Unknown key ID \"" + r.getKeyIdHex() + "\"");
            case BAD -> warnings.add(where + " Bad signature from \"" + r.getUserId() + "\""
                    + (r.getDetail() == null ? "" : " (" + r.getDetail() + ")"));
            case REVOKED_KEY -> warnings.add(where + " Revoked key (signed by: \"" + r.getUserId() + "\")");
            case EXPIRED_SIGNATURE -> warnings.add(where + " Note: Signature has expired (signed by: \""
                    + r.getUserId() + "\")");
            case EXPIRED_KEY -> warnings.add(where + " Note: This key has expired (signed by: \""
                    + r.getUserId() + "\")");
            case GOOD -> { /* nothing to say */ }
        }
    }
}
