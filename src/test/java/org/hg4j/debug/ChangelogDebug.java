package org.hg4j.debug;

import org.hg4j.core.HgRepository;
import org.hg4j.core.Revlog;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * Changelog index 파싱 디버그용 클래스
 */
public class ChangelogDebug {
    public static void main(String[] args) throws Exception {
        String repoPath = args.length > 0 ? args[0] : "/tmp/hg_java_test";
        HgRepository repo = new HgRepository(new File(repoPath));
        
        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        
        System.out.println("changelog.i size: " + clIdx.length());
        System.out.println("changelog.d size: " + clDat.length());
        
        Revlog changelog = repo.getRevlog(clIdx, clDat);
        System.out.println("Revision count: " + changelog.getRevisionCount());
        
        for (int i = 0; i < changelog.getRevisionCount(); i++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(i);
            byte[] nodeId = rec.getNodeId();
            String hex = bytesToHex(nodeId);
            System.out.println("rev=" + i + " node=" + hex + 
                " offset=" + rec.getOffset() + 
                " compLen=" + rec.getCompLen() +
                " uncompLen=" + rec.getUncompLen() +
                " baseRev=" + rec.getBaseRev());
        }
        
        System.out.println();
        System.out.println("=== Content of each revision ===");
        for (int i = 0; i < changelog.getRevisionCount(); i++) {
            byte[] content = changelog.getRevisionContent(i);
            String text = new String(content, StandardCharsets.UTF_8);
            String firstLine = text.split("\n")[0];
            System.out.println("rev=" + i + " firstLine(manifest)=" + firstLine.substring(0, Math.min(40, firstLine.length())));
        }
    }
    
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }
}
