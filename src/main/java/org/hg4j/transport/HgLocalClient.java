package org.hg4j.transport;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 로컬 파일시스템에 있는 Mercurial 저장소와의 연결을 제공하는 Transport.
 * native hg 서브프로세스를 사용하여 bundle을 생성합니다.
 */
public class HgLocalClient implements HgRemoteConnection, AutoCloseable {

    private final File repoDir;

    public HgLocalClient(String path) {
        this.repoDir = new File(path.startsWith("file://") ? path.substring(7) : path);
    }

    @Override
    public List<String> getCapabilities() throws IOException {
        // 로컬 저장소는 기본 capabilites를 반환
        return List.of("changegroup", "getbundle", "lookup", "pushkey", "branchmap");
    }

    @Override
    public List<String> getHeads() throws IOException {
        List<String> args = new ArrayList<>(Arrays.asList(
                "hg", "--config", "format.usezstd=false",
                "--config", "format.revlog-compression=zlib",
                "heads", "--template", "{node}\n"
        ));
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(repoDir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out;
        try (InputStream is = p.getInputStream()) {
            out = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        }
        try { p.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (out.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> heads = new ArrayList<>();
        for (String line : out.split("\n")) {
            String h = line.trim();
            if (!h.isEmpty()) heads.add(h);
        }
        return heads;
    }

    @Override
    public byte[] getChangegroup(List<String> roots) throws IOException {
        // hg bundle --base <roots> - <heads> 로 changegroup 생성
        return getBundleBytes(roots, null);
    }

    @Override
    public byte[] getBundle(List<String> common, List<String> heads, List<String> bundleCaps) throws IOException {
        return getBundleBytes(common, heads);
    }

    private byte[] getBundleBytes(List<String> common, List<String> heads) throws IOException {
        File bundleFile = File.createTempFile("hg4j_bundle_", ".hg");
        bundleFile.deleteOnExit();
        try {
            List<String> args = new ArrayList<>();
            args.add("hg");
            args.add("--config"); args.add("format.usezstd=false");
            args.add("--config"); args.add("format.revlog-compression=zlib");
            args.add("bundle");
            args.add("--type"); args.add("v1");

            if (common != null && !common.isEmpty()) {
                for (String c : common) {
                    args.add("--base"); args.add(c);
                }
            } else {
                args.add("--all");
            }

            if (heads != null && !heads.isEmpty()) {
                for (String h : heads) {
                    args.add("--rev"); args.add(h);
                }
            }

            args.add(bundleFile.getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(args);
            pb.directory(repoDir);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out;
            try (InputStream is = p.getInputStream()) {
                out = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            }
            int code;
            try { code = p.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); code = -1; }

            // "no changes found" 는 빈 bundle이 아니라 종료 코드 1로 실패할 수 있음
            if (code != 0 && !out.contains("no changes found")) {
                throw new org.hg4j.errors.HgTransportException("hg bundle 실패 (code=" + code + "): " + out, null);
            }

            if (bundleFile.exists() && bundleFile.length() > 0) {
                return Files.readAllBytes(bundleFile.toPath());
            }
            return new byte[0];
        } finally {
            bundleFile.delete();
        }
    }

    @Override
    public String push(byte[] bundleBytes, List<String> heads) throws IOException {
        // push는 현재 미구현 (로컬 push는 직접 unbundle 사용)
        throw new UnsupportedOperationException("로컬 push는 아직 지원되지 않습니다.");
    }

    @Override
    public void close() {
        // 리소스 해제 불필요
    }
}
