package io.github.search5.hg4j.gpg;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.operator.jcajce.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.security.*;
import java.security.spec.RSAPublicKeySpec;
import java.security.interfaces.EdECKey;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import java.math.BigInteger;
import java.util.Date;

/**
 * Pure Java SCM commit signing representation providing standard OpenPGP/GPG parity.
 * Uses Bouncy Castle PGP APIs to generate and verify true OpenPGP compliant signatures,
 * ensuring seamless integration with standard GPG keyring and gpg --verify commands.
 *
 * @apiNote Used by {@code CommitCommand} to sign a new commit and by {@code LogCommand}/{@code
 *     HgCommit} to expose/verify an existing commit's signature. {@link #sign(byte[], PrivateKey,
 *     PublicKey, String)} supports any OpenPGP-compatible key type (RSA, EC/ECDSA, Ed25519,
 *     Ed448); the private-key-only {@link #sign(byte[], PrivateKey, String)} overload exists for
 *     callers without direct access to the public key but only supports RSA.
 */
public class GpgSignature {
    private final String signatureHex; // Stores the armored ASCII or raw representation
    private final String keyFingerprint;

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public GpgSignature(String signatureHex, String keyFingerprint) {
        this.signatureHex = signatureHex;
        this.keyFingerprint = keyFingerprint;
    }

    public String getSignatureHex() {
        return signatureHex;
    }

    public String getKeyFingerprint() {
        return keyFingerprint;
    }

    /**
     * Resolves the {@link PGPPublicKey} algorithm tag (from {@link org.bouncycastle.bcpg.PublicKeyAlgorithmTags},
     * exposed as constants on {@link PGPPublicKey}) matching a standard Java {@link PublicKey}, so that
     * {@link JcaPGPKeyConverter#getPGPPublicKey(int, PublicKey, Date)} can build a correctly-typed OpenPGP
     * public key regardless of whether the underlying key is RSA, EC (ECDSA) or EdDSA (Ed25519/Ed448).
     *
     * <p>JDK Ed25519/Ed448 keys (via {@code KeyPairGenerator.getInstance("Ed25519")} / {@code "Ed448"}, JDK 15+)
     * report a generic {@code "EdDSA"} algorithm name, so {@link EdECKey#getParams()} is consulted to tell the
     * two curves apart; BouncyCastle-generated keys that already report {@code "Ed25519"}/{@code "Ed448"}
     * directly are also recognized.</p>
     */
    private static int resolvePgpAlgorithmTag(PublicKey publicKey) throws GeneralSecurityException {
        String algorithm = publicKey.getAlgorithm();
        if ("RSA".equalsIgnoreCase(algorithm)) {
            return PGPPublicKey.RSA_GENERAL;
        }
        if ("EC".equalsIgnoreCase(algorithm) || "ECDSA".equalsIgnoreCase(algorithm)) {
            return PGPPublicKey.ECDSA;
        }
        if (publicKey instanceof EdECKey) {
            String curveName = ((EdECKey) publicKey).getParams().getName();
            if ("Ed25519".equalsIgnoreCase(curveName)) {
                return PGPPublicKey.Ed25519;
            }
            if ("Ed448".equalsIgnoreCase(curveName)) {
                return PGPPublicKey.Ed448;
            }
        }
        if ("Ed25519".equalsIgnoreCase(algorithm)) {
            return PGPPublicKey.Ed25519;
        }
        if ("Ed448".equalsIgnoreCase(algorithm)) {
            return PGPPublicKey.Ed448;
        }
        throw new GeneralSecurityException("Unsupported public key algorithm for OpenPGP signing/verification: " + algorithm);
    }

    /**
     * Signs the commit content using a standard Java PrivateKey and its matching PublicKey
     * to generate a true OpenPGP (RFC 4880) ASCII-armored digital SCM signature.
     *
     * <p>Unlike {@link #sign(byte[], PrivateKey, String)}, this overload works for any key
     * algorithm supported by OpenPGP (RSA, EC/ECDSA, Ed25519, Ed448) since the real public key
     * is supplied directly rather than being reconstructed from the private key alone.</p>
     */
    public static GpgSignature sign(byte[] contentToSign, PrivateKey privateKey, PublicKey publicKey, String fingerprint) throws GeneralSecurityException {
        try {
            JcaPGPKeyConverter converter = new JcaPGPKeyConverter().setProvider("BC");

            int algorithmTag = resolvePgpAlgorithmTag(publicKey);
            PGPPublicKey pgpPubKey = converter.getPGPPublicKey(algorithmTag, publicKey, new Date());
            PGPPrivateKey pgpPrivKey = converter.getPGPPrivateKey(pgpPubKey, privateKey);

            PGPSignatureGenerator sGen = new PGPSignatureGenerator(
                new JcaPGPContentSignerBuilder(algorithmTag, HashAlgorithmTags.SHA256).setProvider("BC")
            );

            sGen.init(PGPSignature.BINARY_DOCUMENT, pgpPrivKey);
            sGen.update(contentToSign);
            PGPSignature signature = sGen.generate();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ArmoredOutputStream armorOut = new ArmoredOutputStream(out)) {
                signature.encode(armorOut);
            }

            String armoredText = out.toString("UTF-8");
            return new GpgSignature(armoredText, fingerprint);
        } catch (Exception e) {
            throw new GeneralSecurityException("Failed to generate OpenPGP signature", e);
        }
    }

    /**
     * Signs the commit content using a standard Java PrivateKey
     * to generate a true OpenPGP (RFC 4880) ASCII-armored digital SCM signature.
     *
     * <p>Kept for backward compatibility: this overload only has the private key to work with,
     * so it can only support RSA (the public key is reconstructed from the RSA private key's
     * modulus using the standard F4 exponent). For EC/Ed25519/Ed448 keys, or whenever the actual
     * public key is available, prefer {@link #sign(byte[], PrivateKey, PublicKey, String)}, which
     * supports every OpenPGP-compatible algorithm.</p>
     */
    public static GpgSignature sign(byte[] contentToSign, PrivateKey privateKey, String fingerprint) throws GeneralSecurityException {
        try {
            JcaPGPKeyConverter converter = new JcaPGPKeyConverter().setProvider("BC");
            
            // Reconstruct RSA PublicKey from PrivateKey Modulus to satisfy PGPPrivateKey construction without NPE
            PGPPublicKey pgpPubKey = null;
            if (privateKey instanceof RSAPrivateKey) {
                RSAPrivateKey rsaPriv = (RSAPrivateKey) privateKey;
                BigInteger modulus = rsaPriv.getModulus();
                BigInteger publicExponent = BigInteger.valueOf(65537); // Standard F4 public exponent
                
                RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, publicExponent);
                KeyFactory kf = KeyFactory.getInstance("RSA");
                PublicKey virtualPublicKey = kf.generatePublic(spec);
                
                pgpPubKey = converter.getPGPPublicKey(PGPPublicKey.RSA_GENERAL, virtualPublicKey, new Date());
            }
            
            PGPPrivateKey pgpPrivKey = converter.getPGPPrivateKey(pgpPubKey, privateKey);
            
            PGPSignatureGenerator sGen = new PGPSignatureGenerator(
                new JcaPGPContentSignerBuilder(PGPPublicKey.RSA_GENERAL, HashAlgorithmTags.SHA256).setProvider("BC")
            );
            
            sGen.init(PGPSignature.BINARY_DOCUMENT, pgpPrivKey);
            sGen.update(contentToSign);
            PGPSignature signature = sGen.generate();
            
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ArmoredOutputStream armorOut = new ArmoredOutputStream(out)) {
                signature.encode(armorOut);
            }
            
            String armoredText = out.toString("UTF-8");
            return new GpgSignature(armoredText, fingerprint);
        } catch (Exception e) {
            throw new GeneralSecurityException("Failed to generate OpenPGP signature", e);
        }
    }

    /**
     * Verifies the commit content signature against a standard Java PublicKey using OpenPGP standards.
     */
    public boolean verify(byte[] signedContent, PublicKey publicKey) throws GeneralSecurityException {
        try {
            byte[] decodedBytes;
            if (this.signatureHex.contains("-----BEGIN PGP SIGNATURE-----")) {
                decodedBytes = this.signatureHex.getBytes("UTF-8");
            } else {
                // Compatibility fallback: wrap raw base64 inside standard armored envelope
                String armored = toAsciiArmored();
                decodedBytes = armored.getBytes("UTF-8");
            }
            
            InputStream in = PGPUtil.getDecoderStream(new ByteArrayInputStream(decodedBytes));
            PGPObjectFactory pgpFact = new PGPObjectFactory(in, new JcaKeyFingerprintCalculator());
            Object obj = pgpFact.nextObject();
            PGPSignatureList sigList;
            
            if (obj instanceof PGPSignatureList) {
                sigList = (PGPSignatureList) obj;
            } else {
                // Skip markers or other objects until PGPSignatureList is found
                while (obj != null && !(obj instanceof PGPSignatureList)) {
                    obj = pgpFact.nextObject();
                }
                if (obj instanceof PGPSignatureList) {
                    sigList = (PGPSignatureList) obj;
                } else {
                    throw new PGPException("No PGP signature found inside signature stream");
                }
            }
            
            if (sigList.isEmpty()) {
                return false;
            }
            
            PGPSignature signature = sigList.get(0);
            
            JcaPGPKeyConverter converter = new JcaPGPKeyConverter().setProvider("BC");
            int algorithmTag = resolvePgpAlgorithmTag(publicKey);
            PGPPublicKey pgpPubKey = converter.getPGPPublicKey(algorithmTag, publicKey, new Date());
            
            signature.init(new JcaPGPContentVerifierBuilderProvider().setProvider("BC"), pgpPubKey);
            signature.update(signedContent);
            
            return signature.verify();
        } catch (Exception e) {
            throw new GeneralSecurityException("Failed to verify OpenPGP signature", e);
        }
    }

    /**
     * Formats the signature into standard OpenPGP ASCII-Armored layout.
     */
    public String toAsciiArmored() {
        if (this.signatureHex.contains("-----BEGIN PGP SIGNATURE-----")) {
            return this.signatureHex;
        }
        // Legacy layout formatter
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN PGP SIGNATURE-----\n");
        sb.append("Version: hg4j PGP/GPG Parity\n\n");
        String sig = this.signatureHex;
        for (int i = 0; i < sig.length(); i += 64) {
            int end = Math.min(i + 64, sig.length());
            sb.append(sig, i, end).append("\n");
        }
        sb.append("-----END PGP SIGNATURE-----");
        return sb.toString();
    }

    /**
     * Parses an OpenPGP ASCII-Armored signature block back to GpgSignature.
     */
    public static GpgSignature fromAsciiArmored(String armored, String fingerprint) {
        if (armored == null) return null;
        String clean = armored.replace("\\n", "\n").trim();
        return new GpgSignature(clean, fingerprint);
    }
}
