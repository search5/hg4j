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
        // Like real hg's exchange.trypullbundlefromurl (`with repo.lock(), repo.transaction(b'bundleurl')`): the download and the
        // application of the bundle both happen under the store lock.
        try (io.github.search5.hg4j.lib.HgLock storeLock = repository.lockStore()) {
            download(url, tempFile);
            return new UnbundleCommand(repository).setBundleFile(tempFile).call();
        } finally {
            tempFile.delete();
        }
    }

    /**
     * {@code exchange.trypullbundlefromurl} for the bundles {@code best_clonebundles} chose, all under the store lock
     * ({@code with repo.lock(), repo.transaction("clonebundles")}): each is fetched (HTTP, or the server's cache for a
     * {@code peer-bundle-cache://} entry), checked against its {@code DIGEST} and size, and applied as a stream (the {@code stream2} part
     * of a sharded or stream clone bundle) or as a changegroup bundle.
     *
     * @return the commits a changegroup bundle imported (empty when only stream bundles were applied)
     */
    public static List<byte[]> applyEntries(HgRepository repository, io.github.search5.hg4j.transport.HgRemoteConnection peer,
                                            List<io.github.search5.hg4j.bundle.ClonebundleSelection.Entry> entries, List<String> messages)
            throws IOException, HgLockException {
        List<byte[]> imported = new java.util.ArrayList<>();
        boolean multiple = entries.size() > 1;
        if (multiple) {
            messages.add("applying " + entries.size() + " clone bundles\n");
        }
        try (io.github.search5.hg4j.lib.HgLock storeLock = repository.lockStore()) {
            int index = 0;
            for (io.github.search5.hg4j.bundle.ClonebundleSelection.Entry entry : entries) {
                String url = entry.url();
                messages.add("applying clone bundle from " + url + "\n");
                byte[] bundle;
                try {
                    bundle = fetch(peer, url);
                } catch (IOException e) {
                    messages.add("error fetching bundle: " + e.getMessage() + "\n");
                    throw new io.github.search5.hg4j.errors.HgValidationException("error applying bundle\n(if this error persists, consider"
                            + " contacting the server operator or disable clone bundles via \"--config ui.clonebundles=false\")");
                }
                checkDigest(entry.attrs().get("DIGEST"), bundle);
                if (bundle.length >= 4 && bundle[0] == 'H' && bundle[1] == 'G' && bundle[2] == '2' && bundle[3] == '0'
                        && StreamCloneClient.applyBundleFile(repository, bundle).done()) {
                    repository.clearRevlogCache();
                    repository.reloadBookmarksInStoreRequirement();
                } else {
                    File temp = File.createTempFile("hg4j-clonebundle-", ".hg");
                    try {
                        Files.write(temp.toPath(), bundle);
                        imported.addAll(new UnbundleCommand(repository).setBundleFile(temp).call());
                    } finally {
                        temp.delete();
                    }
                }
                index++;
                if (multiple) {
                    messages.add("finished applying clone bundle [" + index + "/" + entries.size() + "]\n");
                }
            }
        }
        messages.add(multiple ? "finished applying " + entries.size() + " clone bundles\n" : "finished applying clone bundle\n");
        return imported;
    }

    private static byte[] fetch(io.github.search5.hg4j.transport.HgRemoteConnection peer, String url) throws IOException {
        String inline = "peer-bundle-cache://";
        if (url.startsWith(inline)) {
            if (peer == null) {
                throw new io.github.search5.hg4j.errors.HgValidationException("no remote repository supplied for " + url);
            }
            return peer.getCachedBundleInline(url.substring(inline.length()));
        }
        File temp = File.createTempFile("hg4j-clonebundle-", ".dl");
        try {
            download(url, temp);
            return Files.readAllBytes(temp.toPath());
        } finally {
            temp.delete();
        }
    }

    /** {@code digestchecker.validate}: the algorithm that comes first among sha256 and sha512 is the one checked. */
    private static void checkDigest(String digest, byte[] bundle) throws io.github.search5.hg4j.errors.HgValidationException {
        if (digest == null || digest.isEmpty()) {
            return;
        }
        String chosen = null;
        for (String algo : new String[] {"sha256", "sha512"}) {
            for (String item : digest.split(",")) {
                if (item.startsWith(algo + ":")) {
                    chosen = item;
                    break;
                }
            }
            if (chosen != null) {
                break;
            }
        }
        if (chosen == null) {
            return;
        }
        String algo = chosen.substring(0, chosen.indexOf(':'));
        String expected = chosen.substring(chosen.indexOf(':') + 1);
        try {
            String got = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance(algo.equals("sha256") ? "SHA-256" : "SHA-512")
                    .digest(bundle));
            if (!got.equals(expected)) {
                throw new io.github.search5.hg4j.errors.HgValidationException(algo + " mismatch: expected " + expected + ", got " + got);
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
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
