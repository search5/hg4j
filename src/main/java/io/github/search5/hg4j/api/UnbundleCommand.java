package io.github.search5.hg4j.api;

import io.github.search5.hg4j.bundle.Bundle2Parser;
import io.github.search5.hg4j.bundle.ChangegroupParser;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lib.HgRepository;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import io.github.search5.hg4j.errors.HgCorruptDataException;
import java.util.zip.InflaterInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

/**
 * Porcelain command corresponding to {@code hg unbundle} — applies a local bundle file (as
 * produced by {@code hg bundle}, or received over the wire) to the current repository. Supports
 * the same HG10UN/HG10GZ/HG10BZ and HG20(bundle2) container formats that {@link FetchCommand}
 * already decodes when pulling over the network.
 */
public class UnbundleCommand {
    private final HgRepository repository;
    private File bundleFile;

    /**
     * Creates the command.
     * @param repository the repository to apply the bundle to; must not be {@code null}
     */
    public UnbundleCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Sets the bundle file to apply.
     * @param bundleFile an existing bundle file; must be set before {@link #call()}
     * @return {@code this}
     */
    public UnbundleCommand setBundleFile(File bundleFile) {
        this.bundleFile = bundleFile;
        return this;
    }

    /**
     * Applies the bundle file to the repository.
     * <p>
     * Supports HG10UN, HG10GZ, HG10BZ and HG20 (bundle2) files. Only the store lock is held (waiting up to the
     * repository's push lock timeout), the working copy is not updated.
     * @return the node ids (20 bytes each) of the imported changesets
     * @throws IllegalStateException if no bundle file was set or it does not exist
     * @throws io.github.search5.hg4j.errors.HgCorruptDataException (an {@link IOException}) if the file header or the
     *         compression format is not recognized
     * @throws IOException if reading the file or writing the repository fails
     * @throws HgLockException if the store lock cannot be acquired
     */
    public List<byte[]> call() throws IOException, HgLockException {
        if (bundleFile == null || !bundleFile.exists()) {
            throw new IllegalStateException("Bundle file must exist: " + bundleFile);
        }
        byte[] bundleBytes = Files.readAllBytes(bundleFile.toPath());

        byte[] changegroupBytes = bundleBytes;
        String cgVersion = "01";
        if (bundleBytes.length >= 4 && bundleBytes[0] == 'H' && bundleBytes[1] == 'G'
                && bundleBytes[2] == '2' && bundleBytes[3] == '0') {
            Bundle2Parser.ExtractedBundle2 ext = Bundle2Parser.extractChangegroupDetailed(new ByteArrayInputStream(bundleBytes));
            changegroupBytes = ext.changegroupBytes;
            cgVersion = ext.cgVersion;
        } else if (bundleBytes.length >= 6 && bundleBytes[0] == 'H' && bundleBytes[1] == 'G'
                && bundleBytes[2] == '1' && bundleBytes[3] == '0') {
            String comp = new String(bundleBytes, 4, 2, StandardCharsets.US_ASCII);
            ByteArrayInputStream bais = new ByteArrayInputStream(bundleBytes, 6, bundleBytes.length - 6);
            if ("UN".equals(comp)) {
                changegroupBytes = bais.readAllBytes();
            } else if ("GZ".equals(comp)) {
                try (InflaterInputStream iis = new InflaterInputStream(bais)) {
                    changegroupBytes = iis.readAllBytes();
                }
            } else if ("BZ".equals(comp)) {
                byte[] rawData = bais.readAllBytes();
                byte[] bzData = new byte[rawData.length + 2];
                bzData[0] = 'B';
                bzData[1] = 'Z';
                System.arraycopy(rawData, 0, bzData, 2, rawData.length);
                try (BZip2CompressorInputStream bzis =
                             new BZip2CompressorInputStream(new ByteArrayInputStream(bzData))) {
                    changegroupBytes = bzis.readAllBytes();
                }
            } else {
                throw new HgCorruptDataException("Unsupported bundle1 compression format: HG10" + comp);
            }
            cgVersion = "01";
        } else {
            throw new HgCorruptDataException("Unrecognized bundle file header: " + bundleFile);
        }

        ChangegroupParser.ChangegroupBundle bundle = ChangegroupParser.parseBundle(
                new ByteArrayInputStream(changegroupBytes), cgVersion);
        FetchCommand fetch = new FetchCommand(repository).setWorkingCopyLock(false); // real hg: `unbundle` holds only the store lock
        return fetch.applyBundle(bundle);
    }
}
