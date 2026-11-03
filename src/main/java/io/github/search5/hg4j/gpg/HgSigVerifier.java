package io.github.search5.hg4j.gpg;

import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPSignatureSubpacketVector;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;

import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;

/**
 * Verifies the signatures of Mercurial's {@code gpg} extension in pure Java.
 *
 * <p>Classification (see {@link HgSigResult.Status}) follows the status words {@code hgext/gpg.py} reads from
 * {@code gpg --verify}. Like the original, trust is NOT evaluated: a good signature from any key in the
 * keyring counts, and it is up to the user to judge the shown user id and fingerprint.</p>
 *
 * <p>Precedence when several conditions hold: unknown key, then bad signature, then revoked key, then
 * expired signature, then expired key, then good.</p>
 */
public final class HgSigVerifier {

    static {
        BouncyCastleSupport.ensureRegistered();
    }

    private HgSigVerifier() {
    }

    /**
     * @param entry   the signature line to check
     * @param keyring the keys to check against
     * @param now     the time that expiry is judged against
     * @throws HgSigFormatException for an unknown signature version (real hg aborts there too)
     */
    public static HgSigResult verify(HgSigEntry entry, GpgKeyring keyring, Instant now) throws IOException {
        return verifyDetached(entry.signedText(), entry.getSignature(), keyring, now);
    }

    /**
     * Verifies a detached OpenPGP signature over arbitrary data.
     *
     * @param signedData the exact bytes that were signed
     * @param signature  the signature, binary or ASCII-armored
     */
    public static HgSigResult verifyDetached(byte[] signedData, byte[] signature, GpgKeyring keyring, Instant now)
            throws IOException {
        PGPSignature parsed;
        try {
            parsed = parse(signature);
        } catch (IOException | PGPException | RuntimeException e) {
            return new HgSigResult(HgSigResult.Status.BAD, 0, "", null,
                    "not a decodable OpenPGP signature: " + e.getMessage());
        }
        return check(signedData, parsed, keyring, now);
    }

    private static HgSigResult check(byte[] text, PGPSignature signature, GpgKeyring keyring, Instant now) {
        try {
            long keyId = signature.getKeyID();
            PGPPublicKey signingKey = keyring.findPublicKey(keyId);
            PGPPublicKeyRing ring = keyring.findPublicKeyRing(keyId);
            if (signingKey == null || ring == null) {
                return new HgSigResult(HgSigResult.Status.UNKNOWN_KEY, keyId, "", null, "key not in the keyring");
            }
            PGPPublicKey master = ring.getPublicKey();
            String userId = firstUserId(master);
            String fingerprint = GpgKeyring.toHex(master.getFingerprint());

            signature.init(new JcaPGPContentVerifierBuilderProvider().setProvider("BC"), signingKey);
            signature.update(text);
            if (!signature.verify()) {
                return new HgSigResult(HgSigResult.Status.BAD, keyId, userId, fingerprint,
                        "signature does not match the signed text");
            }
            if (signingKey.hasRevocation() || master.hasRevocation()) {
                return new HgSigResult(HgSigResult.Status.REVOKED_KEY, keyId, userId, fingerprint, "key was revoked");
            }
            if (signatureExpired(signature, now)) {
                return new HgSigResult(HgSigResult.Status.EXPIRED_SIGNATURE, keyId, userId, fingerprint,
                        "signature has expired");
            }
            if (keyExpired(signingKey, now) || keyExpired(master, now)) {
                return new HgSigResult(HgSigResult.Status.EXPIRED_KEY, keyId, userId, fingerprint, "key has expired");
            }
            return new HgSigResult(HgSigResult.Status.GOOD, keyId, userId, fingerprint, null);
        } catch (PGPException e) {
            return new HgSigResult(HgSigResult.Status.BAD, signature.getKeyID(), "", null,
                    "verification failed: " + e.getMessage());
        }
    }

    private static PGPSignature parse(byte[] bytes) throws IOException, PGPException {
        // getDecoderStream transparently handles both ASCII armor and plain binary packets
        JcaPGPObjectFactory factory = new JcaPGPObjectFactory(
                PGPUtil.getDecoderStream(new java.io.ByteArrayInputStream(bytes)));
        Object first = factory.nextObject();
        if (first instanceof PGPSignatureList list && !list.isEmpty()) {
            return list.get(0);
        }
        throw new PGPException("first packet is not a signature");
    }

    private static boolean signatureExpired(PGPSignature signature, Instant now) {
        PGPSignatureSubpacketVector hashed = signature.getHashedSubPackets();
        long lifetime = hashed == null ? 0 : hashed.getSignatureExpirationTime();
        return lifetime > 0 && signature.getCreationTime().toInstant().plusSeconds(lifetime).isBefore(now);
    }

    private static boolean keyExpired(PGPPublicKey key, Instant now) {
        long valid = key.getValidSeconds();
        return valid > 0 && key.getCreationTime().toInstant().plusSeconds(valid).isBefore(now);
    }

    private static String firstUserId(PGPPublicKey master) {
        Iterator<String> ids = master.getUserIDs();
        return ids.hasNext() ? ids.next() : "";
    }
}
