package io.github.search5.hg4j.api;

import io.github.search5.hg4j.bundle.Bundle2;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.shape.StoreShards;
import io.github.search5.hg4j.transport.wireprotov1.StreamClone;
import io.github.search5.hg4j.util.SafeFileIO;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code hg debug::sharded-stream-bundles} ({@code bundle2.write_repo_sharded_stream_bundles}): one streaming bundle for every shard of
 * the store (the shards of {@code .hg/store/server-shapes}, the top level files with one of them), all taken in lockstep from the same
 * state of the repository, which is why the whole run holds the working copy lock and then the store lock. The bundles are written to
 * {@code server.peer-bundle-cache-root} (relative to {@code .hg}, {@code bundle-cache} by default) as
 * {@code hg-sharded-<group>-<shard fingerprint>.hg}.
 */
public final class ShardedStreamBundlesCommand {

    private final HgRepository repository;
    private String groupId;

    /**
     * Creates the command.
     * @param repository the repository to write bundles for; must not be {@code null}
     */
    public ShardedStreamBundlesCommand(HgRepository repository) {
        this.repository = repository;
    }

    /** The id of the group (eight hex digits, fresh unless set): the bundles of one run share it. */
    public ShardedStreamBundlesCommand setGroupId(String groupId) {
        this.groupId = groupId;
        return this;
    }

    /** @return the files written, shard by shard in the order of their names */
    public List<File> call() throws IOException, HgLockException {
        try (HgLock wlock = repository.lockWorkingCopy();
             HgLock lock = repository.lockStore()) {
            if (!"true".equalsIgnoreCase(repository.getConfig().get("experimental", "server.stream-narrow-clones", "false"))
                    && !"1".equals(repository.getConfig().get("experimental", "server.stream-narrow-clones", "false"))) {
                throw new HgValidationException("server does not support narrow stream clones");
            }
            StoreShards shards = StoreShards.fromRepository(repository);
            String group = groupId != null ? groupId : makeUid();
            String root = repository.getConfig().get("server", "peer-bundle-cache-root", "bundle-cache");
            List<File> written = new ArrayList<>();
            for (StoreShards.ShardShape info : shards.shardedBundleInfo()) {
                String fingerprint = info.fingerprint().toText();
                String filename = "hg-sharded-" + group + "-" + fingerprint + ".hg";
                StreamClone.Stream2 stream = StreamClone.stream2(repository,
                        new StreamClone.ShardSelection(info.matcher()::matches, info.topLevel()));
                Map<String, String> params = new LinkedHashMap<>();
                params.put("bytecount", String.valueOf(stream.byteCount()));
                params.put("filecount", String.valueOf(stream.fileCount()));
                params.put("requirements", stream.requirements());
                params.put("bundle-group-id", group);
                params.put("shard-id", new String(info.fingerprint().toBytes(), StandardCharsets.ISO_8859_1));
                if (info.topLevel()) {
                    params.put("bundle-group-top-level", "1");
                }
                Bundle2.Writer bundle = new Bundle2.Writer(0);
                bundle.addPart("stream2", true, params, Map.of(), stream.payload());
                File target = new File(new File(repository.getHgDir(), root), filename);
                target.getParentFile().mkdirs();
                SafeFileIO.writeAtomic(target, bundle.toBytes(null));
                written.add(target);
            }
            return written;
        }
    }

    /** {@code docketmod.make_uid}: eight hex digits. */
    private static String makeUid() {
        byte[] random = new byte[4];
        new SecureRandom().nextBytes(random);
        return java.util.HexFormat.of().formatHex(random);
    }
}
