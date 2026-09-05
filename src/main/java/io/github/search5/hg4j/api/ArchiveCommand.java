package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Archive command for extracting an unversioned snapshot of a repository revision -- {@code hg
 * archive}'s hg4j equivalent. Supports the same destination-driven type auto-detection real hg
 * documents ({@code hg help archive}): a plain directory ({@code files}, the default when the
 * destination has no recognized archive extension), {@code zip}/{@code uzip} (deflate-compressed /
 * uncompressed zip), and {@code tar}/{@code tgz}/{@code tbz2} (uncompressed / gzip / bzip2
 * compressed tar) -- {@code txz} (lzma) is deliberately not implemented (would need a new {@code
 * org.tukaani:xz} dependency hg4j doesn't otherwise need; documented, honest scope cut).
 *
 * <p>Backlog #39 (requirement-matrix campaign) rewrite -- verified live against real hg 7.2
 * (2026-09-05) three real structural bugs this previously lacked entirely:
 * <ol>
 *   <li>Real hg always writes a {@code .hg_archival.txt} metadata member (repo root hex / archived
 *   node hex / branch name / latest global tag info) into every archive -- hg4j produced none.</li>
 *   <li>Real hg prefixes every member of a zip/tar-family archive with a directory prefix (default:
 *   the destination's own basename with its type-specific suffix stripped, e.g. {@code out.tar.gz}
 *   {@literal ->} {@code out/}) -- hg4j wrote bare paths with no prefix at all. (Directory ({@code
 *   files}) output never gets a prefix -- real hg's own {@code archival.archive()} explicitly
 *   rejects one for that kind -- the destination directory itself already plays that role.)</li>
 *   <li>Real hg preserves the executable bit and materializes real symlinks (manifest flags
 *   {@code x}/{@code l}) in every output kind -- hg4j silently wrote symlink targets as regular
 *   file content with no executable bit anywhere.</li>
 * </ol>
 * Also switched from a hand-rolled, flat-manifest-only parser to {@link
 * HgRepository#getManifestAtCommit(byte[])} (already treemanifest-aware, shared with {@link
 * TreeCommand}/{@link ManifestCommand}) -- the old parser only ever read the root manifest revlog,
 * so a treemanifest repository's nested directories were silently dropped from every archive.
 */
public class ArchiveCommand {
    private final HgRepository repository;
    private String revision = "tip";
    private File destination;
    private String type;
    private String prefix;

    public ArchiveCommand(HgRepository repository) {
        this.repository = repository;
    }

    public ArchiveCommand setRevision(String revision) {
        this.revision = revision;
        return this;
    }

    public ArchiveCommand setDestination(File destination) {
        this.destination = destination;
        return this;
    }

    /** Archive type: {@code files} (directory, default when the destination name matches nothing
     * else), {@code zip}, {@code uzip} (uncompressed zip), {@code tar}, {@code tgz}, or {@code
     * tbz2}. When unset, the type is auto-detected from {@code destination}'s extension, mirroring
     * real hg's own {@code archival.guesskind()} (verified live, 2026-09-05). */
    public ArchiveCommand setType(String type) {
        this.type = type;
        return this;
    }

    /** Directory prefix applied to every member of a zip/tar-family archive (ignored for {@code
     * files} output, which real hg itself rejects a prefix for). {@code null} (default) computes
     * real hg's own default: the destination's basename with its type-specific suffix stripped. */
    public ArchiveCommand setPrefix(String prefix) {
        this.prefix = prefix;
        return this;
    }

    private enum ArchiveType {FILES, ZIP, UZIP, TAR, TGZ, TBZ2}

    /**
     * Executes the archive snapshot extraction.
     *
     * @throws IOException if extraction fails
     */
    public void call() throws IOException {
        if (destination == null) {
            throw new IllegalArgumentException("Destination target must be specified for archive extraction");
        }

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        byte[] node = NodeIdUtil.resolveRevision(changelog, revision);
        if (node == null) {
            throw new IOException("Archive target revision not found: " + revision);
        }
        int commitRev = changelog.findRevision(node);

        Map<String, String> manifestMap = repository.getManifestAtCommit(node);
        String archivalText = buildArchivalMetadata(changelog, commitRev, node);

        ArchiveType archiveType = resolveType();
        String memberPrefix = resolvePrefix(archiveType);

        switch (archiveType) {
            case FILES -> writeDirectoryArchive(manifestMap, archivalText);
            case ZIP -> writeZipArchive(manifestMap, archivalText, memberPrefix, true);
            case UZIP -> writeZipArchive(manifestMap, archivalText, memberPrefix, false);
            case TAR -> writeTarArchive(manifestMap, archivalText, memberPrefix, null);
            case TGZ -> writeTarArchive(manifestMap, archivalText, memberPrefix, "gz");
            case TBZ2 -> writeTarArchive(manifestMap, archivalText, memberPrefix, "bz2");
        }
    }

    private ArchiveType resolveType() {
        if (type != null) {
            return switch (type.toLowerCase(Locale.ROOT)) {
                case "files" -> ArchiveType.FILES;
                case "zip" -> ArchiveType.ZIP;
                case "uzip" -> ArchiveType.UZIP;
                case "tar" -> ArchiveType.TAR;
                case "tgz" -> ArchiveType.TGZ;
                case "tbz2" -> ArchiveType.TBZ2;
                default -> throw new IllegalArgumentException("unknown archive type '" + type + "'");
            };
        }
        String lower = destination.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tbz2") || lower.endsWith(".tar.bz2")) {
            return ArchiveType.TBZ2;
        }
        if (lower.endsWith(".tgz") || lower.endsWith(".tar.gz")) {
            return ArchiveType.TGZ;
        }
        if (lower.endsWith(".tar")) {
            return ArchiveType.TAR;
        }
        if (lower.endsWith(".zip")) {
            return ArchiveType.ZIP;
        }
        return ArchiveType.FILES;
    }

    private static List<String> suffixesFor(ArchiveType t) {
        return switch (t) {
            case TAR -> List.of(".tar");
            case TBZ2 -> List.of(".tbz2", ".tar.bz2");
            case TGZ -> List.of(".tgz", ".tar.gz");
            case ZIP, UZIP -> List.of(".zip");
            case FILES -> List.of();
        };
    }

    /** {@code null} for {@link ArchiveType#FILES} (never prefixed); otherwise the tidied prefix
     * (no trailing slash -- callers append {@code "/"} themselves) real hg's {@code
     * archival.tidyprefix()} would compute. */
    private String resolvePrefix(ArchiveType archiveType) {
        if (archiveType == ArchiveType.FILES) {
            return null;
        }
        if (prefix != null) {
            return stripTrailingSlash(prefix);
        }
        String name = destination.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        for (String suffix : suffixesFor(archiveType)) {
            if (lower.endsWith(suffix)) {
                return stripTrailingSlash(name.substring(0, name.length() - suffix.length()));
            }
        }
        return stripTrailingSlash(name);
    }

    private static String stripTrailingSlash(String s) {
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String memberPath(String prefix, String path) {
        return (prefix == null || prefix.isEmpty()) ? path : prefix + "/" + path;
    }

    private void writeDirectoryArchive(Map<String, String> manifestMap, String archivalText) throws IOException {
        destination.mkdirs();
        Files.write(new File(destination, ".hg_archival.txt").toPath(), archivalText.getBytes(StandardCharsets.UTF_8));

        for (Map.Entry<String, String> entry : manifestMap.entrySet()) {
            String path = entry.getKey();
            FlaggedContent fc = resolveEntry(path, entry.getValue());
            File targetFile = new File(destination, path);
            targetFile.getParentFile().mkdirs();
            if (fc.symlink) {
                Files.deleteIfExists(targetFile.toPath());
                try {
                    Files.createSymbolicLink(targetFile.toPath(), Path.of(new String(fc.content, StandardCharsets.UTF_8).trim()));
                    continue;
                } catch (Exception unsupportedSymlink) {
                    // Filesystem/platform can't create real symlinks (e.g. Windows without the
                    // privilege) -- fall through and write the link target as plain content,
                    // matching hg4j's own UpdateCommand fallback for the same situation.
                }
            }
            Files.write(targetFile.toPath(), fc.content);
            if (fc.executable) {
                targetFile.setExecutable(true, false);
            }
        }
    }

    private void writeZipArchive(Map<String, String> manifestMap, String archivalText, String prefix, boolean compress) throws IOException {
        File parent = destination.getAbsoluteFile().getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(destination)) {
            zos.setMethod(compress ? java.util.zip.ZipEntry.DEFLATED : java.util.zip.ZipEntry.STORED);

            ZipArchiveEntry metaEntry = new ZipArchiveEntry(memberPath(prefix, ".hg_archival.txt"));
            byte[] metaBytes = archivalText.getBytes(StandardCharsets.UTF_8);
            metaEntry.setUnixMode(0100644);
            zos.putArchiveEntry(metaEntry);
            zos.write(metaBytes);
            zos.closeArchiveEntry();

            for (Map.Entry<String, String> entry : manifestMap.entrySet()) {
                String path = entry.getKey();
                FlaggedContent fc = resolveEntry(path, entry.getValue());
                ZipArchiveEntry ze = new ZipArchiveEntry(memberPath(prefix, path));
                if (fc.symlink) {
                    ze.setUnixMode(0120777);
                    zos.putArchiveEntry(ze);
                    zos.write(fc.content);
                } else {
                    ze.setUnixMode(fc.executable ? 0100755 : 0100644);
                    zos.putArchiveEntry(ze);
                    zos.write(fc.content);
                }
                zos.closeArchiveEntry();
            }
        }
    }

    private void writeTarArchive(Map<String, String> manifestMap, String archivalText, String prefix, String compression) throws IOException {
        File parent = destination.getAbsoluteFile().getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (OutputStream fileOut = new BufferedOutputStream(new FileOutputStream(destination));
             OutputStream compressedOut = wrapCompression(fileOut, compression);
             TarArchiveOutputStream tos = new TarArchiveOutputStream(compressedOut)) {
            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);

            byte[] metaBytes = archivalText.getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry metaEntry = new TarArchiveEntry(memberPath(prefix, ".hg_archival.txt"));
            metaEntry.setMode(0100644);
            metaEntry.setSize(metaBytes.length);
            tos.putArchiveEntry(metaEntry);
            tos.write(metaBytes);
            tos.closeArchiveEntry();

            for (Map.Entry<String, String> entry : manifestMap.entrySet()) {
                String path = entry.getKey();
                FlaggedContent fc = resolveEntry(path, entry.getValue());
                String memberName = memberPath(prefix, path);
                if (fc.symlink) {
                    TarArchiveEntry te = new TarArchiveEntry(memberName, TarArchiveEntry.LF_SYMLINK);
                    te.setLinkName(new String(fc.content, StandardCharsets.UTF_8).trim());
                    te.setMode(0120777);
                    tos.putArchiveEntry(te);
                    tos.closeArchiveEntry();
                } else {
                    TarArchiveEntry te = new TarArchiveEntry(memberName);
                    te.setMode(fc.executable ? 0100755 : 0100644);
                    te.setSize(fc.content.length);
                    tos.putArchiveEntry(te);
                    tos.write(fc.content);
                    tos.closeArchiveEntry();
                }
            }
        }
    }

    private static OutputStream wrapCompression(OutputStream out, String compression) throws IOException {
        if (compression == null) {
            return out;
        }
        return switch (compression) {
            case "gz" -> new GzipCompressorOutputStream(out);
            case "bz2" -> new BZip2CompressorOutputStream(out);
            default -> throw new IllegalArgumentException("unknown tar compression: " + compression);
        };
    }

    private record FlaggedContent(byte[] content, boolean executable, boolean symlink) {
    }

    private FlaggedContent resolveEntry(String path, String hexAndFlag) throws IOException {
        String fileHex = hexAndFlag.substring(0, 40);
        boolean executable = hexAndFlag.length() > 40 && hexAndFlag.charAt(40) == 'x';
        boolean symlink = hexAndFlag.length() > 40 && hexAndFlag.charAt(40) == 'l';
        byte[] content = getFileRevisionContent(repository, path, fileHex);
        return new FlaggedContent(content, executable, symlink);
    }

    private byte[] getFileRevisionContent(HgRepository repository, String path, String nodeHex) throws IOException {
        File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        if (!flIdx.exists()) {
            throw new HgRepositoryNotFoundException("Filelog index does not exist for: " + path);
        }
        Revlog filelog = repository.getRevlog(flIdx, flDat);
        int rev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(nodeHex.substring(0, 40)));
        if (rev == -1) {
            throw new HgRevisionNotFoundException("File revision not found: " + path + " @ " + nodeHex);
        }
        return filelog.getRevisionContent(rev);
    }

    // -- .hg_archival.txt metadata (repo/node/branch always; tag/latesttag block real hg's own
    // archival.py._defaultmetatemplate computes via the {latesttag}/{latesttagdistance}/
    // {changessincelatesttag} template keywords) --

    private record LatestTagInfo(List<String> tags, int distance) {
    }

    private String buildArchivalMetadata(Revlog changelog, int commitRev, byte[] commitNode) throws IOException {
        String rootHex = NodeIdUtil.toHex(changelog.getIndexRecord(0).getNodeId());
        String nodeHex = NodeIdUtil.toHex(commitNode);
        String branch = CommitCommand.getBranchOfRevision(changelog, commitRev);

        StringBuilder sb = new StringBuilder();
        sb.append("repo: ").append(rootHex).append('\n');
        sb.append("node: ").append(nodeHex).append('\n');
        sb.append("branch: ").append(branch).append('\n');

        LatestTagInfo info = computeLatestTagInfo(changelog, commitRev, nodeHex);
        if (info.distance() == 0) {
            for (String tag : info.tags()) {
                sb.append("tag: ").append(tag).append('\n');
            }
        } else {
            for (String tag : info.tags()) {
                sb.append("latesttag: ").append(tag).append('\n');
            }
            sb.append("latesttagdistance: ").append(info.distance()).append('\n');
            // Simplification (documented): real hg's changessincelatesttag counts the full
            // multi-parent ancestor DAG minus the tag's own ancestors, which can differ from the
            // single-parent-chain distance across a merge. For linear ancestry (the case hg4j's
            // own requirement-matrix scenarios exercise) the two are identical, since every
            // ancestor between the tag and the target lies on that one chain.
            sb.append("changessincelatesttag: ").append(info.distance()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Walks the target revision's first-parent ancestry (documented simplification -- real hg's
     * own algorithm is a full multi-parent "longest path to the latest tag" DP over the whole DAG,
     * which only diverges from a plain first-parent walk across a merge whose two sides carry
     * different tags) to find the nearest globally-tagged ancestor, exactly mirroring real hg
     * {@code archival.py}/{@code templatekw.py}'s {@code latesttag}/{@code latesttagdistance}
     * keywords (verified live against real hg 7.2, 2026-09-05): if the target revision itself
     * carries one or more global tags, those are returned with distance 0 (the archival template
     * then renders {@code tag: <name>} lines instead of the {@code latesttag}/distance block); if
     * none is ever found, {@code ["null"]} is returned with the distance from the virtual "-1"
     * (pre-root) revision -- i.e. the target's own 1-based depth along the first-parent chain.
     */
    private LatestTagInfo computeLatestTagInfo(Revlog changelog, int commitRev, String commitHex) throws IOException {
        Map<String, List<String>> tagsByHex = loadGlobalTagsAtRevision(changelog);

        List<String> ownTags = tagsByHex.get(commitHex);
        if (ownTags != null && !ownTags.isEmpty()) {
            List<String> sorted = new ArrayList<>(ownTags);
            Collections.sort(sorted);
            return new LatestTagInfo(sorted, 0);
        }

        int walkRev = commitRev;
        int distance = 0;
        while (true) {
            int parent1 = changelog.getIndexRecord(walkRev).getParent1();
            distance += 1;
            if (parent1 == -1) {
                return new LatestTagInfo(List.of("null"), distance);
            }
            walkRev = parent1;
            String walkHex = NodeIdUtil.toHex(changelog.getIndexRecord(walkRev).getNodeId());
            List<String> tags = tagsByHex.get(walkHex);
            if (tags != null && !tags.isEmpty()) {
                List<String> sorted = new ArrayList<>(tags);
                Collections.sort(sorted);
                return new LatestTagInfo(sorted, distance);
            }
        }
    }

    /** Global tags ({@code .hgtags}, not {@code .hg/localtags} -- real hg's own {@code
     * latesttag} keyword only ever considers global tags), inverted to {@code node hex -> tag
     * names}. Real hg resolves this from a repo-wide cache built by reading {@code .hgtags} at
     * every open head and merging the results -- i.e. NOT from the archived revision's own
     * manifest (an older revision predating the tag commit still shows up as tagged, verified
     * live against real hg 7.2: archiving a revision that a *later* commit tagged still reports
     * {@code tag: <name>}). This reads {@code .hgtags} from the repository's current tip instead
     * (documented single-head simplification -- correct whenever the repo has exactly one open
     * head, which is what hg4j's own requirement-matrix scenarios use; a real multi-head merge of
     * divergent {@code .hgtags} contents is out of scope). Returns an empty map when {@code
     * .hgtags} isn't tracked at the tip at all. */
    private Map<String, List<String>> loadGlobalTagsAtRevision(Revlog changelog) throws IOException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (changelog.getRevisionCount() == 0) {
            return result;
        }
        byte[] tipNode = changelog.getIndexRecord(changelog.getRevisionCount() - 1).getNodeId();
        Map<String, String> tipManifest = repository.getManifestAtCommit(tipNode);
        String hgtagsEntry = tipManifest.get(".hgtags");
        if (hgtagsEntry == null) {
            return result;
        }
        byte[] content = getFileRevisionContent(repository, ".hgtags", hgtagsEntry.substring(0, 40));
        Map<String, String> nameToHex = new LinkedHashMap<>();
        for (String rawLine : new String(content, StandardCharsets.UTF_8).split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int spaceIdx = line.indexOf(' ');
            if (spaceIdx == -1) {
                continue;
            }
            String hex = line.substring(0, spaceIdx).trim();
            String name = line.substring(spaceIdx + 1).trim();
            if (name.isEmpty() || hex.length() != 40) {
                continue;
            }
            nameToHex.put(name, hex); // last line for a name wins, mirroring TagsCommand.readTagFile
        }
        for (Map.Entry<String, String> e : nameToHex.entrySet()) {
            result.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        return result;
    }
}
