package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgProtocolException;
import io.github.search5.hg4j.lib.HgRepository;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import io.github.search5.hg4j.errors.HgLockException;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Downloads a bundle from an externally hosted URL and applies it to a repository — the "bypass"
 * half of real hg's Clonebundles mechanism (see {@code
 * io.github.search5.hg4j.bundle.ClonebundlesManifest} and
 * {@code decisions/mercurial-spec-compliance-requirement.md}'s Clonebundles plan).
 *
 * <p>Unlike every other network-facing command in this codebase, this download is a <b>plain
 * HTTP(S) GET with no wire-protocol framing at all</b> — that's the entire point of the feature:
 * offloading large-clone traffic away from the {@code ?cmd=} wire protocol server to whatever
 * static file host or CDN the {@code clonebundles.manifest} entry names. The only wire-protocol
 * interaction in the whole flow is the earlier manifest lookup itself
 * ({@link io.github.search5.hg4j.transport.HgRemoteClient#fetchClonebundlesManifest()}).</p>
 *
 * <p>Real hg never falls back to a normal pull when this download fails — a clonebundle failure
 * fails the entire clone (server operators rely on this: silently falling back would route
 * exactly the load they offloaded straight back at the origin server). This class matches that:
 * any failure propagates as an exception, never a silent fallback.</p>
 */
public final class ClonebundlesCommand {

    private ClonebundlesCommand() {
    }

    /**
     * Downloads the bundle at {@code url} and applies it to {@code repository} via {@link
     * UnbundleCommand}. Callers are still responsible for the real hg client algorithm's final
     * step — an ordinary incremental pull against the origin server afterward to catch up on
     * anything committed since the bundle was generated (this is just the existing
     * {@link FetchCommand}/{@link PullCommand}, no new code needed for that part).
     *
     * @return the commits imported from the bundle (same as {@link UnbundleCommand#call()})
     */
    public static List<byte[]> downloadAndApply(HgRepository repository, String url) throws IOException, HgLockException {
        File tempFile = File.createTempFile("hg4j-clonebundle-", ".hg");
        try {
            download(url, tempFile);
            return new UnbundleCommand(repository).setBundleFile(tempFile).call();
        } finally {
            tempFile.delete();
        }
    }

    private static void download(String url, File destination) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(60000);
        conn.setUseCaches(false);

        int status = conn.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            throw new HgProtocolException(url, "Clonebundle download failed with HTTP " + status);
        }

        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            conn.disconnect();
        }
    }
}
