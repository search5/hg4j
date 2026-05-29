package org.hg4j;

import org.hg4j.core.HgRepository;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;

public class HgTestUtils {

    public static boolean isHgInstalled() {
        try {
            Process process = new ProcessBuilder("hg", "--version").start();
            process.waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String hg(File repoDir, String... args) throws Exception {
        String[] cmd = new String[args.length + 5];
        cmd[0] = "hg";
        cmd[1] = "--config";
        cmd[2] = "format.usezstd=false";
        cmd[3] = "--config";
        cmd[4] = "format.revlog-compression=zlib";
        System.arraycopy(args, 0, cmd, 5, args.length);
        
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(repoDir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        
        String out;
        try (InputStream is = p.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new AssertionError("hg " + Arrays.toString(args) + " failed with exit code " + code + ": " + out);
        }
        return out;
    }

    public static HgRepository nativeRepo(File dir, Consumer<File> setup) throws Exception {
        if (!dir.exists()) {
            dir.mkdirs();
        }
        hg(dir, "init");

        // Disable zstd compression in the newly initialized native repository for compatibility
        File hgrc = new File(dir, ".hg/hgrc");
        hgrc.getParentFile().mkdirs();
        java.nio.file.Files.writeString(hgrc.toPath(),
                "[format]\nusezstd = false\nrevlog-compression = zlib\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);

        setup.accept(dir);
        return new HgRepository(dir);
    }
}
