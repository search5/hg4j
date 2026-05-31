package com.github.search5.hg4j.core;

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
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import java.math.BigInteger;

/**
 * Pure Java SCM commit signing representation providing standard OpenPGP/GPG parity.
 * Uses Bouncy Castle PGP APIs to generate and verify true OpenPGP compliant signatures,
 * ensuring seamless integration with standard GPG keyring and gpg --verify commands.
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
     * Signs the commit content using a standard Java PrivateKey
     * to generate a true OpenPGP (RFC 4880) ASCII-armored digital SCM signature.
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
                
                pgpPubKey = converter.getPGPPublicKey(PGPPublicKey.RSA_GENERAL, virtualPublicKey, new java.util.Date());
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
            PGPPublicKey pgpPubKey = converter.getPGPPublicKey(PGPPublicKey.RSA_GENERAL, publicKey, new java.util.Date());
            
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
