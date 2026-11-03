package io.github.search5.hg4j.gpg;

import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.operator.PBESecretKeyDecryptor;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;

import java.io.IOException;
import java.time.Instant;
import java.util.Date;

/**
 * Creates the signatures of Mercurial's {@code gpg} extension.
 *
 * <p>What {@code hg sign} does with the external {@code gpg}: run {@code gpg --sign --detach-sign} over the
 * text {@code hex(node) + "\n"} and keep the BINARY detached signature, base64-encoded on one line. This
 * class produces the same thing with Bouncy Castle: a version 4 binary-document signature (type 0x00) packet
 * with a creation time and the issuer's fingerprint (hashed) and key id (unhashed), i.e. what gpg writes.
 * The result is accepted by {@code gpg --verify} and therefore by {@code hg sigcheck}.</p>
 */
public final class HgSigner {

    static {
        BouncyCastleSupport.ensureRegistered();
    }

    private HgSigner() {
    }

    /**
     * @param nodeHex    the changeset to sign, 40 hex digits
     * @param key        a signing-capable secret key, see {@link GpgKeyring#findSigningKey(String)}
     * @param passphrase the key's passphrase; null or empty for an unprotected key
     * @param now        the signature creation time
     * @throws HgSigFormatException if the key cannot sign, or the passphrase is wrong
     */
    public static HgSigEntry sign(String nodeHex, PGPSecretKey key, char[] passphrase, Instant now)
            throws IOException {
        byte[] text = HgSigEntry.signedText(nodeHex, HgSigEntry.VERSION_0);
        return new HgSigEntry(nodeHex, HgSigEntry.VERSION_0, signDetached(text, key, passphrase, now));
    }

    /**
     * Signs arbitrary data with a detached binary OpenPGP signature (what {@code gpg --detach-sign} writes):
     * a version 4 binary-document signature (type 0x00) with a creation time and the issuer's fingerprint
     * (hashed) and key id (unhashed).
     *
     * @return the signature packet bytes
     * @throws HgSigFormatException if the key cannot sign, or the passphrase is wrong
     */
    public static byte[] signDetached(byte[] data, PGPSecretKey key, char[] passphrase, Instant now)
            throws IOException {
        if (!key.isSigningKey()) {
            throw new HgSigFormatException("key " + String.format("%016X", key.getKeyID()) + " cannot sign");
        }
        PGPPublicKey publicKey = key.getPublicKey();
        try {
            PBESecretKeyDecryptor decryptor = new JcePBESecretKeyDecryptorBuilder(
                    new JcaPGPDigestCalculatorProviderBuilder().setProvider("BC").build())
                    .setProvider("BC").build(passphrase == null ? new char[0] : passphrase);
            PGPPrivateKey privateKey = key.extractPrivateKey(decryptor);

            PGPSignatureGenerator generator = new PGPSignatureGenerator(
                    new JcaPGPContentSignerBuilder(publicKey.getAlgorithm(), hashFor(publicKey)).setProvider("BC"));
            generator.init(PGPSignature.BINARY_DOCUMENT, privateKey);

            PGPSignatureSubpacketGenerator hashed = new PGPSignatureSubpacketGenerator();
            hashed.setSignatureCreationTime(false, Date.from(now));
            hashed.setIssuerFingerprint(false, key);
            generator.setHashedSubpackets(hashed.generate());
            PGPSignatureSubpacketGenerator unhashed = new PGPSignatureSubpacketGenerator();
            unhashed.setIssuerKeyID(false, key.getKeyID());
            generator.setUnhashedSubpackets(unhashed.generate());

            generator.update(data);
            return generator.generate().getEncoded();
        } catch (PGPException e) {
            throw new HgSigFormatException("cannot sign with key " + String.format("%016X", key.getKeyID())
                    + ": " + e.getMessage(), e);
        }
    }

    /**
     * Like {@link #signDetached} but ASCII-armored ({@code -----BEGIN PGP SIGNATURE-----}) with LF line
     * endings, the form git stores in a commit's {@code gpgsig} header and that hg4j stores in a changeset's
     * {@code gpgsig} extra.
     */
    public static String signDetachedArmored(byte[] data, PGPSecretKey key, char[] passphrase, Instant now)
            throws IOException {
        byte[] packet = signDetached(data, key, passphrase, now);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (org.bouncycastle.bcpg.ArmoredOutputStream armor = new org.bouncycastle.bcpg.ArmoredOutputStream(out)) {
            armor.write(packet);
        }
        return out.toString(java.nio.charset.StandardCharsets.US_ASCII).replace("\r\n", "\n").stripTrailing();
    }

    private static int hashFor(PGPPublicKey key) {
        return key.getAlgorithm() == PublicKeyAlgorithmTags.RSA_GENERAL
                || key.getAlgorithm() == PublicKeyAlgorithmTags.RSA_SIGN
                ? HashAlgorithmTags.SHA256 : HashAlgorithmTags.SHA512;
    }
}
