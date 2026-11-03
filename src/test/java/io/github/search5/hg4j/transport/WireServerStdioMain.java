package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.lib.HgRepository;
import java.io.File;

/** Serves one SSH-protocol-v1 session of an hg4j repository on stdin/stdout. */
public class WireServerStdioMain {
    public static void main(String[] a) throws Exception {
        new HgSshWireServer(new HgRepository(new File(a[0]))).handleConnection(System.in, System.out);
        System.out.flush();
    }
}
