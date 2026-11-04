package io.github.search5.hg4j.lib;

import io.github.search5.hg4j.errors.HgLockException;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Real hg's {@code cmd_impls.clone.copy_store}: copies the store of a repository into another {@code .hg} directory, with hard links
 * when both sides are on the same device and plain copies otherwise.
 *
 * <p>What is copied is the store's {@code copylist}: {@code requires} and {@code 00changelog.i} of the source {@code .hg}, and from
 * its {@code store/} the bookmarks, narrowspec, {@code data}, {@code meta}, the manifest and changelog revlogs, {@code phaseroots}
 * (not when the source is publishing), {@code obsstore}, {@code requires}, {@code dh}, {@code fncache} and the file index. The lock
 * of the destination store is taken the moment {@code data} is copied and handed back to the caller, who holds it for what follows.</p>
 */
public final class StoreCopy {

    // mercurial/store.py: _data, plus the entries the fncache and fileindex stores add to it
    private static final String[] STORE_ENTRIES = {
            "bookmarks", "narrowspec", "data", "meta", "00manifest.d", "00manifest.i", "00changelog.d", "00changelog.i",
            "phaseroots", "obsstore", "requires", "dh", "fncache"};

    private StoreCopy() {
    }

    /**
     * @param source      the repository whose store is copied (for a share, the store it points to)
     * @param destHgDir   the {@code .hg} directory that receives the copy
     * @param publishing  whether the source is publishing (then {@code phaseroots} is left out)
     * @return the lock on {@code destHgDir/store}, or {@code null} when there was no {@code data} to copy
     */
    public static HgLock copyStore(HgRepository source, File destHgDir, boolean publishing) throws IOException, HgLockException {
        File sourceStore = source.getStoreDir();
        File sourceHgDir = sourceStore.getParentFile();
        List<String[]> copylist = new ArrayList<>();           // {path relative to .hg}
        copylist.add(new String[] {"requires"});
        copylist.add(new String[] {"00changelog.i"});
        for (String entry : STORE_ENTRIES) {
            copylist.add(new String[] {"store/" + entry});
        }
        String[] storeFiles = sourceStore.list();
        if (storeFiles != null && source.getRequirements().contains("fileindex-v1")) {
            for (String name : storeFiles) {
                if (name.startsWith("fileindex")) {
                    copylist.add(new String[] {"store/" + name});
                }
            }
        }
        HgLock destLock = null;
        try {
            Boolean hardlink = null;
            for (String[] item : copylist) {
                String f = item[0];
                if (publishing && f.endsWith("phaseroots")) {
                    continue;
                }
                File src = new File(sourceHgDir, f);
                File dst = new File(destHgDir, f);
                File dstBase = dst.getParentFile();
                if (!dstBase.exists() && !dstBase.mkdirs()) {
                    throw new IOException("cannot create " + dstBase);
                }
                if (!src.exists()) {
                    continue;
                }
                if (f.endsWith("data")) {
                    destLock = new HgLock(new File(dstBase, "lock"), 0, true);
                }
                hardlink = copyFiles(src.toPath(), dst.toPath(), hardlink);
            }
            return destLock;
        } catch (IOException | RuntimeException | HgLockException e) {
            if (destLock != null) {
                try {
                    destLock.close();
                } catch (IOException ignored) {
                }
            }
            throw e;
        }
    }

    /** Real hg's {@code util.copyfiles}: a directory tree with hard links if the source and the destination share a device. */
    private static Boolean copyFiles(Path src, Path dst, Boolean hardlink) throws IOException {
        if (Files.isDirectory(src)) {
            if (hardlink == null) {
                hardlink = sameDevice(src, dst.getParent());
            }
            Files.createDirectory(dst);
            try (var children = Files.list(src)) {
                for (Path child : (Iterable<Path>) children::iterator) {
                    hardlink = copyFiles(child, dst.resolve(child.getFileName().toString()), hardlink);
                }
            }
        } else {
            if (hardlink == null) {
                hardlink = sameDevice(src.getParent(), dst.getParent());
            }
            if (hardlink) {
                try {
                    Files.createLink(dst, src);
                } catch (FileAlreadyExistsException exists) {
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING); // hg: shutil.copy over the existing file
                } catch (IOException | UnsupportedOperationException cannotLink) {
                    hardlink = false;
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return hardlink;
    }

    private static boolean sameDevice(Path a, Path b) throws IOException {
        return Files.getFileStore(a).equals(Files.getFileStore(b));
    }
}
