package io.github.search5.hg4j.testsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A throw-away GnuPG home directory driven by the REAL {@code gpg} executable, used as the oracle for the
 * Mercurial signature tests. It never touches the user's own {@code ~/.gnupg}: every call runs with
 * {@code GNUPGHOME} set to the temporary directory. {@link #close()} stops the {@code gpg-agent} that gpg
 * started for it, so no agent outlives the test.
 */
public final class GpgHome implements AutoCloseable {

    /** Exit status, stdout and stderr of one gpg run. */
    public record Result(int exit, byte[] out, String err) {
        public String outText() {
            return new String(out, StandardCharsets.UTF_8);
        }
    }

    private final Path home;

    private GpgHome(Path home) {
        this.home = home;
    }

    public static boolean isAvailable() {
        try {
            Process p = new ProcessBuilder("gpg", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** @param dir a not yet existing directory; created with mode 700 as gpg insists */
    public static GpgHome create(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        return new GpgHome(dir);
    }

    public Path path() {
        return home;
    }

    /**
     * Generates a key and returns the fingerprint of the primary key (uppercase hex).
     *
     * @param algo       e.g. {@code rsa2048} or {@code ed25519}
     * @param expire     e.g. {@code never} or {@code 1d}
     * @param passphrase empty for an unprotected key
     */
    public String generateKey(String userId, String algo, String expire, String passphrase) throws Exception {
        Result r = run(null, "--batch", "--pinentry-mode", "loopback", "--passphrase", passphrase,
                "--quick-generate-key", userId, algo, "sign", expire);
        if (r.exit() != 0) {
            throw new AssertionError("gpg --quick-generate-key failed: " + r.err());
        }
        Result list = run(null, "--batch", "--list-keys", "--with-colons", "--fingerprint", userId);
        for (String line : list.outText().split("\n")) {
            if (line.startsWith("fpr:")) {
                String[] f = line.split(":");
                return f[9];
            }
        }
        throw new AssertionError("no fingerprint listed for " + userId + ": " + list.outText() + list.err());
    }

    public byte[] exportPublic() throws Exception {
        Result r = run(null, "--batch", "--export");
        if (r.exit() != 0) {
            throw new AssertionError("gpg --export failed: " + r.err());
        }
        return r.out();
    }

    public byte[] exportSecret(String passphrase) throws Exception {
        Result r = run(null, "--batch", "--pinentry-mode", "loopback", "--passphrase", passphrase,
                "--export-secret-keys");
        if (r.exit() != 0) {
            throw new AssertionError("gpg --export-secret-keys failed: " + r.err());
        }
        return r.out();
    }

    /** Runs {@code gpg <args>} in this home; stdin may be null. */
    public Result run(byte[] stdin, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("gpg");
        cmd.add("--no-tty");
        for (String a : args) {
            cmd.add(a);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("GNUPGHOME", home.toString());
        pb.environment().put("LC_ALL", "C");
        Process p = pb.start();
        Thread feeder = new Thread(() -> {
            try (var os = p.getOutputStream()) {
                if (stdin != null) {
                    os.write(stdin);
                }
            } catch (IOException ignored) {
                // gpg may exit before reading its input; the exit status tells
            }
        });
        feeder.start();
        byte[] err = null;
        java.io.ByteArrayOutputStream errBuf = new java.io.ByteArrayOutputStream();
        Thread errReader = new Thread(() -> {
            try {
                p.getErrorStream().transferTo(errBuf);
            } catch (IOException ignored) {
                // process ended
            }
        });
        errReader.start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new AssertionError("gpg timed out: " + String.join(" ", cmd));
        }
        feeder.join();
        errReader.join();
        return new Result(p.exitValue(), out, errBuf.toString(StandardCharsets.UTF_8));
    }

    @Override
    public void close() throws Exception {
        // stops the gpg-agent (and dirmngr, if any) started for this home
        ProcessBuilder pb = new ProcessBuilder("gpgconf", "--kill", "all");
        pb.environment().put("GNUPGHOME", home.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().readAllBytes();
        p.waitFor(30, TimeUnit.SECONDS);
    }
}
