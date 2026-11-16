package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import org.eclipse.jetty.server.Server;

import java.io.File;
import java.nio.file.Files;

/**
 * A server in its own JVM for {@link ServerPushConcurrencyKillInteropTest}: it serves the repository over HTTP, prints its port,
 * and when a push reaches {@code pretxnchangegroup} (the changesets are appended and the journal and locks are held) it writes the
 * marker file and then blocks, so that the test can kill the whole JVM with {@code SIGKILL} in the middle of the push.
 */
public final class ServerKillHelperMain {
    private ServerKillHelperMain() {
    }

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        File marker = new File(args[1]);
        HgRepository repository = new HgRepository(repoDir);
        HgHttpWireServer wire = new HgHttpWireServer(repository);
        wire.registerPreTxnChangegroupHook(context -> {
            Files.writeString(marker.toPath(), "in transaction\n");
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("interrupted", e);
            }
            return true;
        });
        Server server = HgTestUtils.startServlet(wire);
        System.out.println("PORT " + HgTestUtils.port(server));
        System.out.flush();
        Thread.sleep(Long.MAX_VALUE);
    }
}
