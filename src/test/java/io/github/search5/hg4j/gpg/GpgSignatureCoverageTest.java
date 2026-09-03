package io.github.search5.hg4j.gpg;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyConverter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage-focused tests for {@link GpgSignature}, targeting error paths and
 * legacy/compatibility branches not already exercised by {@link GpgSignatureTest}.
 *
 * <p>Every fixture here is real OpenPGP wire data built with Bouncy Castle's PGP
 * APIs (the same library {@link GpgSignature} itself uses), not hand-guessed bytes,
 * so behavior is verified against real OpenPGP packet semantics.</p>
 */
public class GpgSignatureCoverageTest {

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private static byte[] rawSignaturePacketBytes(byte[] content, KeyPair keyPair) throws Exception {
        JcaPGPKeyConverter converter = new JcaPGPKeyConverter().setProvider("BC");
        PGPPublicKey pgpPubKey = converter.getPGPPublicKey(PGPPublicKey.RSA_GENERAL, keyPair.getPublic(), new Date());
        PGPPrivateKey pgpPrivKey = converter.getPGPPrivateKey(pgpPubKey, keyPair.getPrivate());
        PGPSignatureGenerator sGen = new PGPSignatureGenerator(
                new JcaPGPContentSignerBuilder(PGPPublicKey.RSA_GENERAL, HashAlgorithmTags.SHA256).setProvider("BC"));
        sGen.init(PGPSignature.BINARY_DOCUMENT, pgpPrivKey);
        sGen.update(content);
        return sGen.generate().getEncoded();
    }

    /** Old-format OpenPGP Marker packet (tag 10, RFC 4880 5.8): header 0xA8, length 3, body "PGP". */
    private static final byte[] MARKER_PACKET = new byte[]{(byte) 0xA8, 0x03, 0x50, 0x47, 0x50};

    private static String armor(byte[] rawPacketBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ArmoredOutputStream armorOut = new ArmoredOutputStream(out)) {
            armorOut.write(rawPacketBytes);
        }
        // ArmoredOutputStream picks the "-----BEGIN PGP MESSAGE-----" label whenever
        // the first packet written isn't itself a bare signature packet (e.g. a
        // leading Marker packet). GpgSignature.verify() branches purely on the
        // literal "-----BEGIN PGP SIGNATURE-----" marker text, and any BEGIN/END
        // armor label is accepted by the OpenPGP armor decoder regardless of the
        // packets it actually wraps, so normalize the label here to route these
        // fixtures through verify()'s direct-decode branch like real detached
        // signature files do.
        return out.toString("UTF-8")
                .replace("-----BEGIN PGP MESSAGE-----", "-----BEGIN PGP SIGNATURE-----")
                .replace("-----END PGP MESSAGE-----", "-----END PGP SIGNATURE-----");
    }

    // --- sign(): failure path (catch block wrapping into GeneralSecurityException) ---

    @Test
    public void testSignWithNonRsaPrivateKeyThrowsGeneralSecurityException() throws Exception {
        // GpgSignature.sign() only knows how to rebuild the matching RSA public key
        // from an RSAPrivateKey; any other key type leaves the internal PGPPublicKey
        // null, and PGPPrivateKey construction fails with an NPE that must be wrapped.
        KeyPairGenerator dsaGen = KeyPairGenerator.getInstance("DSA");
        dsaGen.initialize(1024, new SecureRandom());
        KeyPair dsaPair = dsaGen.generateKeyPair();

        byte[] content = "content signed with unsupported key type".getBytes("UTF-8");

        GeneralSecurityException ex = assertThrows(GeneralSecurityException.class,
                () -> GpgSignature.sign(content, dsaPair.getPrivate(), "FPR"));
        assertEquals("Failed to generate OpenPGP signature", ex.getMessage());
    }

    // --- verify(): legacy/raw-base64 compatibility fallback branch ---

    @Test
    public void testVerifyLegacyRawBase64FallbackSucceeds() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048, new SecureRandom());
        KeyPair keyPair = keyGen.generateKeyPair();
        byte[] content = "legacy fallback content".getBytes("UTF-8");

        GpgSignature armoredSig = GpgSignature.sign(content, keyPair.getPrivate(), "FPR");

        // Simulate a legacy stored signature: only the raw base64 body, no
        // "-----BEGIN PGP SIGNATURE-----" armor header. verify() must detect the
        // missing marker, fall back to toAsciiArmored() to rebuild a valid armor
        // envelope on the fly, and still verify correctly.
        String[] lines = armoredSig.getSignatureHex().split("\n");
        StringBuilder rawBase64 = new StringBuilder();
        boolean inBody = false;
        for (String line : lines) {
            if (line.startsWith("-----BEGIN")) {
                continue;
            }
            if (line.startsWith("-----END")) {
                break;
            }
            if (line.trim().isEmpty()) {
                inBody = true;
                continue;
            }
            if (line.startsWith("=") && line.length() == 5) {
                continue; // CRC24 checksum line
            }
            if (inBody) {
                rawBase64.append(line.trim());
            }
        }
        assertFalse(rawBase64.toString().isEmpty());

        GpgSignature legacySig = new GpgSignature(rawBase64.toString(), "FPR");
        assertFalse(legacySig.getSignatureHex().contains("-----BEGIN PGP SIGNATURE-----"));

        assertTrue(legacySig.verify(content, keyPair.getPublic()),
                "Legacy raw base64 signature must verify via the ascii-armor fallback path.");
    }

    @Test
    public void testToAsciiArmoredWrapsRawBase64IntoStandardEnvelope() {
        // Direct unit check of the legacy formatter used by the fallback above:
        // it must wrap at 64 chars/line and add proper begin/end markers.
        String raw = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU2Nzg5"; // arbitrary base64 payload
        GpgSignature legacySig = new GpgSignature(raw, "FPR");

        String result = legacySig.toAsciiArmored();
        assertTrue(result.startsWith("-----BEGIN PGP SIGNATURE-----\n"));
        assertTrue(result.endsWith("-----END PGP SIGNATURE-----"));
        assertTrue(result.contains("Version: hg4j PGP/GPG Parity"));
        // Body line must be present verbatim since it is under 64 chars.
        assertTrue(result.contains(raw));
    }

    // --- verify(): PGPObjectFactory skip-marker loop ---

    @Test
    public void testVerifySkipsLeadingMarkerPacketBeforeSignature() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048, new SecureRandom());
        KeyPair keyPair = keyGen.generateKeyPair();
        byte[] content = "content behind a marker packet".getBytes("UTF-8");

        byte[] sigBytes = rawSignaturePacketBytes(content, keyPair);
        byte[] combined = new byte[MARKER_PACKET.length + sigBytes.length];
        System.arraycopy(MARKER_PACKET, 0, combined, 0, MARKER_PACKET.length);
        System.arraycopy(sigBytes, 0, combined, MARKER_PACKET.length, sigBytes.length);

        // The very first object PGPObjectFactory hands back is a PGPMarker, not a
        // PGPSignatureList, forcing GpgSignature.verify() into its "skip markers or
        // other objects until PGPSignatureList is found" loop.
        GpgSignature sig = new GpgSignature(armor(combined), "FPR");
        assertTrue(sig.verify(content, keyPair.getPublic()),
                "verify() must skip a leading Marker packet and still find the real signature.");
    }

    @Test
    public void testVerifyThrowsWhenStreamHasNoSignatureAtAll() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048, new SecureRandom());
        KeyPair keyPair = keyGen.generateKeyPair();
        byte[] content = "no signature present".getBytes("UTF-8");

        // Only a Marker packet, no PGPSignatureList anywhere in the stream: the
        // skip loop drains to null and verify() must surface "no signature found".
        GpgSignature sig = new GpgSignature(armor(MARKER_PACKET), "FPR");

        GeneralSecurityException ex = assertThrows(GeneralSecurityException.class,
                () -> sig.verify(content, keyPair.getPublic()));
        assertEquals("Failed to verify OpenPGP signature", ex.getMessage());
        assertTrue(ex.getCause() instanceof org.bouncycastle.openpgp.PGPException);
        assertTrue(ex.getCause().getMessage().contains("No PGP signature found"));
    }

    // --- verify(): malformed / unsupported-algorithm failure paths (all wrapped) ---

    @Test
    public void testVerifyWithMalformedArmoredBodyThrows() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048, new SecureRandom());
        KeyPair keyPair = keyGen.generateKeyPair();
        byte[] content = "content".getBytes("UTF-8");

        GpgSignature malformed = new GpgSignature(
                "-----BEGIN PGP SIGNATURE-----\nnot valid armor body at all!!\n-----END PGP SIGNATURE-----",
                "FPR");

        GeneralSecurityException ex = assertThrows(GeneralSecurityException.class,
                () -> malformed.verify(content, keyPair.getPublic()));
        assertEquals("Failed to verify OpenPGP signature", ex.getMessage());
    }

    @Test
    public void testVerifyWithEmptyLegacySignatureThrows() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048, new SecureRandom());
        KeyPair keyPair = keyGen.generateKeyPair();
        byte[] content = "content".getBytes("UTF-8");

        // Empty signatureHex takes the legacy fallback branch and produces an
        // armor envelope with no body at all, which BC must reject as invalid.
        GpgSignature empty = new GpgSignature("", "FPR");

        assertThrows(GeneralSecurityException.class, () -> empty.verify(content, keyPair.getPublic()));
    }

    @Test
    public void testVerifyWithUnsupportedPublicKeyAlgorithmThrows() throws Exception {
        // Sign with RSA, then attempt to verify against an EC public key. The
        // internal converter.getPGPPublicKey(RSA_GENERAL, ecKey, ...) call cannot
        // reconcile the algorithm tag with an EC key and must fail cleanly,
        // wrapped as a GeneralSecurityException (real "unsupported algorithm" case).
        KeyPairGenerator rsaGen = KeyPairGenerator.getInstance("RSA");
        rsaGen.initialize(2048, new SecureRandom());
        KeyPair rsaPair = rsaGen.generateKeyPair();
        byte[] content = "content signed with rsa".getBytes("UTF-8");
        GpgSignature sig = GpgSignature.sign(content, rsaPair.getPrivate(), "FPR");

        KeyPairGenerator ecGen = KeyPairGenerator.getInstance("EC");
        ecGen.initialize(256, new SecureRandom());
        PublicKey ecPublicKey = ecGen.generateKeyPair().getPublic();

        assertThrows(GeneralSecurityException.class, () -> sig.verify(content, ecPublicKey));
    }

    // --- fromAsciiArmored(): null input branch ---

    @Test
    public void testFromAsciiArmoredWithNullReturnsNull() {
        assertNull(GpgSignature.fromAsciiArmored(null, "FPR"));
    }

    @Test
    public void testFromAsciiArmoredNormalizesEscapedNewlinesAndTrims() {
        String stored = "  -----BEGIN PGP SIGNATURE-----\\nabc\\n-----END PGP SIGNATURE-----  ";
        GpgSignature restored = GpgSignature.fromAsciiArmored(stored, "FPR");
        assertEquals("-----BEGIN PGP SIGNATURE-----\nabc\n-----END PGP SIGNATURE-----", restored.getSignatureHex());
        assertEquals("FPR", restored.getKeyFingerprint());
    }
}
