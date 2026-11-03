package io.github.search5.hg4j.gpg;

/**
 * The outcome of verifying one {@link HgSigEntry}, modelled on the status words GnuPG reports and that
 * {@code hgext/gpg.py} reacts to ({@code GOODSIG}, {@code EXPSIG}, {@code EXPKEYSIG}, {@code BADSIG},
 * {@code ERRSIG}).
 */
public final class HgSigResult {

    public enum Status {
        /** valid signature of a valid key (gpg: GOODSIG) */
        GOOD(true),
        /** valid signature that has expired (gpg: EXPSIG); hg still lists it, with a note */
        EXPIRED_SIGNATURE(true),
        /** valid signature made by a key that has expired (gpg: EXPKEYSIG); hg still lists it, with a note */
        EXPIRED_KEY(true),
        /** the key was revoked (gpg: REVKEYSIG); hg's gpg extension ignores such a result, so it is not valid */
        REVOKED_KEY(false),
        /** the signature does not match the signed text, or is not a decodable signature (gpg: BADSIG) */
        BAD(false),
        /** the signing key is not in the keyring (gpg: ERRSIG) */
        UNKNOWN_KEY(false);

        private final boolean valid;

        Status(boolean valid) {
            this.valid = valid;
        }

        /** @return whether Mercurial counts this as a valid signature (it lists the signer) */
        public boolean isValid() {
            return valid;
        }
    }

    private final Status status;
    private final long keyId;
    private final String userId;
    private final String fingerprint;
    private final String detail;

    HgSigResult(Status status, long keyId, String userId, String fingerprint, String detail) {
        this.status = status;
        this.keyId = keyId;
        this.userId = userId;
        this.fingerprint = fingerprint;
        this.detail = detail;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isValid() {
        return status.isValid();
    }

    /** @return the 64-bit id of the key that made the signature, 0 if the signature could not be decoded */
    public long getKeyId() {
        return keyId;
    }

    /** @return the key id as 16 uppercase hex digits */
    public String getKeyIdHex() {
        return String.format("%016X", keyId);
    }

    /** @return the primary user id of the signer, or "" when the key is unknown */
    public String getUserId() {
        return userId;
    }

    /** @return the fingerprint (uppercase hex) of the signer's PRIMARY key, or null when the key is unknown */
    public String getFingerprint() {
        return fingerprint;
    }

    /** @return a human readable reason for a non-GOOD status, or null */
    public String getDetail() {
        return detail;
    }

    @Override
    public String toString() {
        return status + " " + getKeyIdHex() + (userId == null || userId.isEmpty() ? "" : " \"" + userId + "\"");
    }
}
