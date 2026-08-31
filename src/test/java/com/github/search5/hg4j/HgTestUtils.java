package com.github.search5.hg4j;
import com.github.search5.hg4j.bundle.Bundle2Parser;
import com.github.search5.hg4j.bundle.ChangegroupParser;

import com.github.search5.hg4j.core.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.bundle.ChangegroupParser;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.github.search5.hg4j.api.CommitCommand;
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
}
