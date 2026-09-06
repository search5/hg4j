package io.github.search5.hg4j;
import io.github.search5.hg4j.bundle.Bundle2Parser;
import io.github.search5.hg4j.bundle.ChangegroupParser;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.api.CommitCommand;
import jakarta.servlet.http.HttpServlet;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.nio.file.StandardOpenOption;

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

    public static boolean isGitInstalled() {
        try {
            Process process = new ProcessBuilder("git", "--version").start();
            process.waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isSvnInstalled() {
        try {
            Process process = new ProcessBuilder("svn", "--version").start();
            process.waitFor();
            Process adminProcess = new ProcessBuilder("svnadmin", "--version").start();
            adminProcess.waitFor();
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

    /** Same as {@link #hg(File, String...)} but with {@code [subrepos] git:allowed = true} set
     * (real hg 7.2 defaults this to false; git subrepo tests need it enabled). */
    public static String hgGitAllowed(File repoDir, String... args) throws Exception {
        String[] withConfig = new String[args.length + 2];
        withConfig[0] = "--config";
        withConfig[1] = "subrepos.git:allowed=true";
        System.arraycopy(args, 0, withConfig, 2, args.length);
        return hg(repoDir, withConfig);
    }

    /** Same as {@link #hg(File, String...)} but with {@code [subrepos] svn:allowed = true} set
     * (real hg 7.2 defaults this to false too; svn subrepo tests need it enabled). */
    public static String hgSvnAllowed(File repoDir, String... args) throws Exception {
        String[] withConfig = new String[args.length + 2];
        withConfig[0] = "--config";
        withConfig[1] = "subrepos.svn:allowed=true";
        System.arraycopy(args, 0, withConfig, 2, args.length);
        return hg(repoDir, withConfig);
    }

    /** Runs a plain {@code svn} CLI command, forcing {@code LC_ALL=C} (this sandbox's default
     * locale is Korean, and both real hg's own svn-output parsing and these tests' own assertions
     * depend on English messages -- e.g. "Committed revision N."). Used to build the svn subrepo
     * fixtures real hg's own {@code svnsubrepo} class is tested against. */
    public static String svn(File cwd, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "svn";
        System.arraycopy(args, 0, cmd, 1, args.length);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd);
        pb.redirectErrorStream(true);
        pb.environment().put("LC_ALL", "C");
        Process p = pb.start();

        String out;
        try (InputStream is = p.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new AssertionError("svn " + Arrays.toString(args) + " failed with exit code " + code + ": " + out);
        }
        return out;
    }

    /** Creates a fresh local svn repository via {@code svnadmin create} and checks out a working
     * copy from its {@code file://} URL, returning that URL. */
    public static String createSvnRepo(File svnRepoDir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("svnadmin", "create", svnRepoDir.getAbsolutePath());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out;
        try (InputStream is = p.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new AssertionError("svnadmin create failed with exit code " + code + ": " + out);
        }
        return "file://" + svnRepoDir.getAbsolutePath();
    }

    /** Runs a plain {@code git} CLI command with a fixed local identity, for building the git
     * subrepo fixtures real hg's own {@code gitsubrepo} class is tested against. */
    public static String git(File repoDir, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(repoDir);
        pb.redirectErrorStream(true);
        pb.environment().put("GIT_AUTHOR_NAME", "T");
        pb.environment().put("GIT_AUTHOR_EMAIL", "t@example.com");
        pb.environment().put("GIT_COMMITTER_NAME", "T");
        pb.environment().put("GIT_COMMITTER_EMAIL", "t@example.com");
        Process p = pb.start();

        String out;
        try (InputStream is = p.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new AssertionError("git " + Arrays.toString(args) + " failed with exit code " + code + ": " + out);
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
        Files.writeString(hgrc.toPath(),
                "[format]\nusezstd = false\nrevlog-compression = zlib\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        setup.accept(dir);
        return new HgRepository(dir);
    }

    public static ChangegroupParser.ChangegroupBundle createMockBundleFromRepo(HgRepository repo) throws Exception {
        ChangegroupParser.ChangegroupBundle bundle = new ChangegroupParser.ChangegroupBundle();
        bundle.changelogEntries = new ArrayList<>();
        bundle.manifestEntries = new ArrayList<>();
        bundle.fileGroups = new ArrayList<>();

        Revlog cl = new Revlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"));
        for (int i = 0; i < cl.getRevisionCount(); i++) {
            Revlog.IndexRecord rec = cl.getIndexRecord(i);
            ChangegroupParser.ChangeGroupEntry entry = new ChangegroupParser.ChangeGroupEntry();
            entry.node = rec.getNodeId();
            entry.p1 = rec.getParent1() != -1 ? cl.getIndexRecord(rec.getParent1()).getNodeId() : new byte[20];
            entry.p2 = rec.getParent2() != -1 ? cl.getIndexRecord(rec.getParent2()).getNodeId() : new byte[20];
            entry.cs = rec.getNodeId();
            
            byte[] rawContent = cl.getRawRevisionContent(i);
            byte[] delta;
            if (i == 0) {
                delta = Revlog.createSimpleDelta(new byte[0], rawContent);
            } else {
                byte[] prevContent = cl.getRawRevisionContent(i - 1);
                delta = Revlog.createSimpleDelta(prevContent, rawContent);
            }
            entry.delta = delta;
            bundle.changelogEntries.add(entry);
        }

        Revlog mf = new Revlog(new File(repo.getStoreDir(), "00manifest.i"), new File(repo.getStoreDir(), "00manifest.d"));
        for (int i = 0; i < mf.getRevisionCount(); i++) {
            Revlog.IndexRecord rec = mf.getIndexRecord(i);
            ChangegroupParser.ChangeGroupEntry entry = new ChangegroupParser.ChangeGroupEntry();
            entry.node = rec.getNodeId();
            entry.p1 = rec.getParent1() != -1 ? mf.getIndexRecord(rec.getParent1()).getNodeId() : new byte[20];
            entry.p2 = rec.getParent2() != -1 ? mf.getIndexRecord(rec.getParent2()).getNodeId() : new byte[20];
            entry.cs = cl.getIndexRecord(rec.getLinkRev()).getNodeId();
            
            byte[] rawContent = mf.getRawRevisionContent(i);
            byte[] delta;
            if (i == 0) {
                delta = Revlog.createSimpleDelta(new byte[0], rawContent);
            } else {
                byte[] prevContent = mf.getRawRevisionContent(i - 1);
                delta = Revlog.createSimpleDelta(prevContent, rawContent);
            }
            entry.delta = delta;
            bundle.manifestEntries.add(entry);
        }

        File fncacheFile = new File(repo.getStoreDir(), "fncache");
        if (fncacheFile.exists()) {
            List<String> paths = Files.readAllLines(fncacheFile.toPath(), StandardCharsets.UTF_8);
            for (String p : paths) {
                if (p.endsWith(".i")) {
                    String rawPath = p.substring("data/".length(), p.length() - 2);
                    File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), rawPath);
                    File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");

                    Revlog fl = new Revlog(flIdx, flDat);
                    ChangegroupParser.FileGroup fg = new ChangegroupParser.FileGroup();
                    fg.path = rawPath;
                    fg.entries = new ArrayList<>();
                    for (int j = 0; j < fl.getRevisionCount(); j++) {
                        Revlog.IndexRecord rec = fl.getIndexRecord(j);
                        ChangegroupParser.ChangeGroupEntry entry = new ChangegroupParser.ChangeGroupEntry();
                        entry.node = rec.getNodeId();
                        entry.p1 = rec.getParent1() != -1 ? fl.getIndexRecord(rec.getParent1()).getNodeId() : new byte[20];
                        entry.p2 = rec.getParent2() != -1 ? fl.getIndexRecord(rec.getParent2()).getNodeId() : new byte[20];
                        entry.cs = cl.getIndexRecord(rec.getLinkRev()).getNodeId();

                        byte[] rawContent = fl.getRawRevisionContent(j);
                        byte[] delta;
                        if (j == 0) {
                            delta = Revlog.createSimpleDelta(new byte[0], rawContent);
                        } else {
                            byte[] prevContent = fl.getRawRevisionContent(j - 1);
                            delta = Revlog.createSimpleDelta(prevContent, rawContent);
                        }
                        entry.delta = delta;
                        fg.entries.add(entry);
                    }
                    bundle.fileGroups.add(fg);
                }
            }
        }

        return bundle;
    }

    public static byte[] serializeBundleToBytes(ChangegroupParser.ChangegroupBundle bundle) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            for (ChangegroupParser.ChangeGroupEntry entry : bundle.changelogEntries) {
                int totalLen = 4 + 80 + entry.delta.length;
                dos.writeInt(totalLen);
                dos.write(entry.node);
                dos.write(entry.p1);
                dos.write(entry.p2);
                dos.write(entry.cs);
                dos.write(entry.delta);
            }
            dos.writeInt(0);

            for (ChangegroupParser.ChangeGroupEntry entry : bundle.manifestEntries) {
                int totalLen = 4 + 80 + entry.delta.length;
                dos.writeInt(totalLen);
                dos.write(entry.node);
                dos.write(entry.p1);
                dos.write(entry.p2);
                dos.write(entry.cs);
                dos.write(entry.delta);
            }
            dos.writeInt(0);

            for (ChangegroupParser.FileGroup fg : bundle.fileGroups) {
                byte[] pathBytes = fg.path.getBytes(StandardCharsets.UTF_8);
                dos.writeInt(4 + pathBytes.length);
                dos.write(pathBytes);
                for (ChangegroupParser.ChangeGroupEntry entry : fg.entries) {
                    int totalLen = 4 + 80 + entry.delta.length;
                    dos.writeInt(totalLen);
                    dos.write(entry.node);
                    dos.write(entry.p1);
                    dos.write(entry.p2);
                    dos.write(entry.cs);
                    dos.write(entry.delta);
                }
                dos.writeInt(0);
            }
            dos.writeInt(0);
        }
        return baos.toByteArray();
    }

    /**
     * Starts an embedded Jetty server on a random free port hosting {@code servlet} at {@code
     * /*} (mirrors production usage of {@link io.github.search5.hg4j.transport.HgHttpWireServer},
     * which is a plain {@code jakarta.servlet.http.HttpServlet} deployed in whatever container the
     * embedder chooses -- this is that "chosen container" for tests). Stop it with {@link
     * #stop(Server)}, read its port with {@link #port(Server)}.
     */
    public static Server startServlet(HttpServlet servlet) throws Exception {
        Server server = new Server(0);
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(servlet), "/*");
        server.setHandler(context);
        server.start();
        return server;
    }

    public static int port(Server server) {
        return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    public static void stop(Server server) {
        try {
            server.stop();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
