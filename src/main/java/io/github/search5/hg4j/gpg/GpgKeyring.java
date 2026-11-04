package io.github.search5.hg4j.gpg;

import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The public (and optionally secret) OpenPGP keys used to verify and create Mercurial signatures.
 *
 * <h2>Where keys come from</h2>
 * <ul>
 *   <li>{@link #fromPublicKeys(String, byte[])} / {@link #withSecretKeys}: key material handed in by the
 *       caller (binary or ASCII-armored), always supported.</li>
 *   <li>{@link #fromGnupgHome(Path)} / {@link #fromDefaultHome()}: a GnuPG home directory. Read:
 *       {@code pubring.kbx} (GnuPG 2.1+, parsed by {@link KeyboxReader}), the legacy {@code pubring.gpg}
 *       and the legacy {@code secring.gpg}.</li>
 * </ul>
 *
 * <h2>What is NOT readable, and how that is reported</h2>
 * Secret keys of GnuPG 2.1+ live in {@code private-keys-v1.d/} in the private format of {@code gpg-agent},
 * which is not read. If that directory exists and no legacy {@code secring.gpg} supplied secret keys, a note
 * says so (see {@link #getNotes()}); export them with {@code gpg --export-secret-keys} and pass the result to
 * {@link #withSecretKeys}. Notes are never swallowed: the caller decides how to show them.
 */
public final class GpgKeyring {

    /** Text of the note that is dropped again once secret keys are supplied by the caller. */
    private static final String AGENT_NOTE_MARKER = "managed by gpg-agent";

    static {
        BouncyCastleSupport.ensureRegistered();
    }

    private final PGPPublicKeyRingCollection publicKeys;
    private final PGPSecretKeyRingCollection secretKeys;
    private final List<String> notes;

    private GpgKeyring(PGPPublicKeyRingCollection publicKeys, PGPSecretKeyRingCollection secretKeys, List<String> notes) {
        this.publicKeys = publicKeys;
        this.secretKeys = secretKeys;
        this.notes = Collections.unmodifiableList(new ArrayList<>(notes));
    }

    /** @return a keyring without any key; every signature then verifies as "unknown key" */
    public static GpgKeyring empty() {
        return new GpgKeyring(new PGPPublicKeyRingCollection(new ArrayList<>()),
                new PGPSecretKeyRingCollection(new ArrayList<>()), List.of());
    }

    /**
     * @param name a name for error messages (file name, "stdin", ...)
     * @param keys public key material, binary or ASCII-armored
     * @throws HgSigFormatException if {@code keys} is non-empty but holds no public key (this is also what
     *                              happens when a GnuPG keybox is passed by mistake; the message says so)
     */
    public static GpgKeyring fromPublicKeys(String name, byte[] keys) throws IOException {
        return new GpgKeyring(new PGPPublicKeyRingCollection(parsePublicRings(name, keys)),
                new PGPSecretKeyRingCollection(new ArrayList<>()), List.of());
    }

    /** @return a copy of this keyring that also holds the secret keys in {@code secretKeys} (binary or armored) */
    public GpgKeyring withSecretKeys(String name, byte[] secretKeys) throws IOException {
        List<PGPSecretKeyRing> rings = parseSecretRings(name, secretKeys);
        List<PGPSecretKeyRing> merged = new ArrayList<>();
        this.secretKeys.getKeyRings().forEachRemaining(merged::add);
        merged.addAll(rings);
        List<String> remainingNotes = new ArrayList<>(notes);
        remainingNotes.removeIf(n -> n.contains(AGENT_NOTE_MARKER)); // the caller has now supplied secret keys
        return new GpgKeyring(publicKeys, new PGPSecretKeyRingCollection(merged), remainingNotes);
    }

    /** @return the keyring of {@code $GNUPGHOME}, else of {@code ~/.gnupg} */
    public static GpgKeyring fromDefaultHome() throws IOException {
        String env = System.getenv("GNUPGHOME");
        Path home = env != null && !env.isBlank()
                ? Paths.get(env) : Paths.get(System.getProperty("user.home"), ".gnupg");
        return fromGnupgHome(home);
    }

    /**
     * Reads the keys of a GnuPG home directory.
     *
     * @throws IOException if the directory does not exist or holds neither {@code pubring.kbx} nor
     *                     {@code pubring.gpg}, or if a keyring file is corrupt
     */
    public static GpgKeyring fromGnupgHome(Path home) throws IOException {
        if (!Files.isDirectory(home)) {
            throw new IOException("GnuPG home directory does not exist: " + home);
        }
        Path kbx = home.resolve("pubring.kbx");
        Path legacyPub = home.resolve("pubring.gpg");
        Path legacySec = home.resolve("secring.gpg");
        Path agentKeys = home.resolve("private-keys-v1.d");
        if (!Files.isRegularFile(kbx) && !Files.isRegularFile(legacyPub)) {
            throw new IOException("No public keyring in " + home + " (looked for pubring.kbx and pubring.gpg)");
        }

        List<String> notes = new ArrayList<>();
        List<PGPPublicKeyRing> publicRings = new ArrayList<>();
        if (Files.isRegularFile(kbx)) {
            byte[] data = Files.readAllBytes(kbx);
            KeyboxReader.Contents contents = KeyboxReader.read(data, kbx.toString());
            for (byte[] block : contents.keyBlocks()) {
                publicRings.addAll(parsePublicRings(kbx + " (key block)", block));
            }
            if (contents.skippedX509Blobs() > 0) {
                notes.add(kbx.getFileName() + ": " + contents.skippedX509Blobs() + " X.509 certificate blob(s) skipped"
                        + " (not OpenPGP keys)");
            }
            if (contents.skippedUnknownBlobs() > 0) {
                notes.add(kbx.getFileName() + ": " + contents.skippedUnknownBlobs() + " blob(s) of unknown type skipped");
            }
        }
        if (Files.isRegularFile(legacyPub)) {
            publicRings.addAll(parsePublicRings(legacyPub.toString(), Files.readAllBytes(legacyPub)));
        }

        List<PGPSecretKeyRing> secretRings = new ArrayList<>();
        if (Files.isRegularFile(legacySec)) {
            secretRings.addAll(parseSecretRings(legacySec.toString(), Files.readAllBytes(legacySec)));
        }
        if (secretRings.isEmpty() && Files.isDirectory(agentKeys)) {
            notes.add("Secret keys of this GnuPG home are " + AGENT_NOTE_MARKER + " (" + agentKeys.getFileName()
                    + "/) and cannot be read; export the signing key with 'gpg --export-secret-keys' to sign");
        }
        return new GpgKeyring(new PGPPublicKeyRingCollection(dedupePublic(publicRings)),
                new PGPSecretKeyRingCollection(secretRings), notes);
    }

    /** @return explanations of anything that was skipped or cannot be used; never null */
    public List<String> getNotes() {
        return notes;
    }

    /** @return the key (master key or subkey) with this 64-bit key id, or null */
    public PGPPublicKey findPublicKey(long keyId) throws PGPException {
        return publicKeys.getPublicKey(keyId);
    }

    /** @return the ring that holds the key (master or subkey) with this key id, or null */
    public PGPPublicKeyRing findPublicKeyRing(long keyId) throws PGPException {
        return publicKeys.getPublicKeyRing(keyId);
    }

    /**
     * Returns the number of public key rings loaded.
     *
     * @return the count of public key rings
     */
    public int publicKeyRingCount() {
        return publicKeys.size();
    }

    /**
     * Returns the number of secret key rings loaded.
     *
     * @return the count of secret key rings
     */
    public int secretKeyRingCount() {
        return secretKeys.size();
    }

    /**
     * Finds a signing-capable secret key.
     *
     * @param spec like gpg's {@code --local-user}: null/empty picks the first signing key; otherwise a
     *             (suffix of a) hex key id or fingerprint, or a case-insensitive part of a user id
     * @return the key, or null if none matches
     */
    public PGPSecretKey findSigningKey(String spec) {
        String wanted = spec == null ? "" : spec.trim().replace(" ", "").toUpperCase(Locale.ROOT);
        for (Iterator<PGPSecretKeyRing> rings = secretKeys.getKeyRings(); rings.hasNext(); ) {
            PGPSecretKeyRing ring = rings.next();
            PGPSecretKey master = ring.getSecretKey();
            for (Iterator<PGPSecretKey> keys = ring.getSecretKeys(); keys.hasNext(); ) {
                PGPSecretKey key = keys.next();
                if (!key.isSigningKey() || key.isPrivateKeyEmpty() || !matches(key, master, wanted, spec)) {
                    continue;
                }
                return key;
            }
        }
        return null;
    }

    private static boolean matches(PGPSecretKey key, PGPSecretKey master, String wantedHex, String rawSpec) {
        if (wantedHex.isEmpty()) {
            return true;
        }
        String keyIdHex = String.format("%016X", key.getKeyID());
        String fingerprint = toHex(key.getPublicKey().getFingerprint());
        String masterFingerprint = toHex(master.getPublicKey().getFingerprint());
        if (wantedHex.matches("(0X)?[0-9A-F]{8,40}")) {
            String hex = wantedHex.startsWith("0X") ? wantedHex.substring(2) : wantedHex;
            if (keyIdHex.endsWith(hex) || fingerprint.endsWith(hex) || masterFingerprint.endsWith(hex)) {
                return true;
            }
        }
        for (Iterator<String> ids = master.getUserIDs(); ids.hasNext(); ) {
            if (ids.next().toLowerCase(Locale.ROOT).contains(rawSpec.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- parsing

    private static List<PGPPublicKeyRing> parsePublicRings(String name, byte[] data) throws IOException {
        if (data.length == 0) {
            return new ArrayList<>();
        }
        if (KeyboxReader.looksLikeKeybox(data)) {
            throw new HgSigFormatException(name + " is a GnuPG keybox, not an OpenPGP key file; read the GnuPG home"
                    + " with GpgKeyring.fromGnupgHome(...) or export the keys with 'gpg --export'");
        }
        List<PGPPublicKeyRing> rings = new ArrayList<>();
        try (InputStream in = PGPUtil.getDecoderStream(new ByteArrayInputStream(data))) {
            new PGPPublicKeyRingCollection(in, new BcKeyFingerprintCalculator()).getKeyRings().forEachRemaining(rings::add);
        } catch (PGPException | IOException e) {
            throw new HgSigFormatException(name + ": cannot parse OpenPGP public keys: " + e.getMessage(), e);
        }
        if (rings.isEmpty()) {
            throw new HgSigFormatException(name + " holds no OpenPGP public key (" + data.length + " bytes)");
        }
        return rings;
    }

    private static List<PGPSecretKeyRing> parseSecretRings(String name, byte[] data) throws IOException {
        if (data.length == 0) {
            return new ArrayList<>();
        }
        List<PGPSecretKeyRing> rings = new ArrayList<>();
        try (InputStream in = PGPUtil.getDecoderStream(new ByteArrayInputStream(data))) {
            new PGPSecretKeyRingCollection(in, new BcKeyFingerprintCalculator()).getKeyRings().forEachRemaining(rings::add);
        } catch (PGPException | IOException e) {
            throw new HgSigFormatException(name + ": cannot parse OpenPGP secret keys: " + e.getMessage(), e);
        }
        if (rings.isEmpty()) {
            throw new HgSigFormatException(name + " holds no OpenPGP secret key (" + data.length + " bytes)");
        }
        return rings;
    }

    /** The same key may sit in pubring.kbx and in a legacy pubring.gpg; keep the first of each master key id. */
    private static List<PGPPublicKeyRing> dedupePublic(List<PGPPublicKeyRing> rings) {
        Set<Long> seen = new HashSet<>();
        List<PGPPublicKeyRing> unique = new ArrayList<>();
        for (PGPPublicKeyRing ring : rings) {
            if (seen.add(ring.getPublicKey().getKeyID())) {
                unique.add(ring);
            }
        }
        return unique;
    }
}
