package com.github.search5.hg4j.api;

import com.github.search5.hg4j.core.HgRepository;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.github.search5.hg4j.util.SafeFileIO;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Commands for bookmark management (listing, creating, or deleting bookmarks).
 */
public class BookmarkCommand {
    private final HgRepository repository;
    private String bookmarkName;
    private byte[] nodeId;
    private boolean delete = false;
    private boolean active = false;

    public BookmarkCommand(HgRepository repository) {
        this.repository = repository;
    }

    public BookmarkCommand setBookmarkName(String bookmarkName) {
        this.bookmarkName = bookmarkName;
        return this;
    }

    public BookmarkCommand setNodeId(byte[] nodeId) {
        this.nodeId = nodeId;
        return this;
    }

    public BookmarkCommand setDelete(boolean delete) {
        this.delete = delete;
        return this;
    }

    public BookmarkCommand setActive(boolean active) {
        this.active = active;
        return this;
    }

    public Map<String, String> call() throws IOException {
        File bkFile = new File(repository.getHgDir(), "bookmarks");
        File curBkFile = new File(repository.getHgDir(), "bookmarks.current");

        Map<String, String> bookmarks = new LinkedHashMap<>();
        if (bkFile.exists()) {
            List<String> lines = Files.readAllLines(bkFile.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int spaceIdx = line.indexOf(' ');
                if (spaceIdx != -1) {
                    String node = line.substring(0, spaceIdx).trim();
                    String name = line.substring(spaceIdx + 1).trim();
                    bookmarks.put(name, node);
                }
            }
        }

        if (delete) {
            if (bookmarkName == null || bookmarkName.isEmpty()) {
                throw new IllegalArgumentException("Bookmark name must be specified for deletion");
            }
            bookmarks.remove(bookmarkName);
            writeBookmarks(bkFile, bookmarks);
            if (curBkFile.exists()) {
                String cur = Files.readString(curBkFile.toPath(), StandardCharsets.UTF_8).trim();
                if (bookmarkName.equals(cur)) {
                    curBkFile.delete();
                }
            }
            return bookmarks;
        }

        if (active) {
            if (bookmarkName == null || bookmarkName.isEmpty()) {
                if (curBkFile.exists()) {
                    curBkFile.delete();
                }
            } else {
                if (!bookmarks.containsKey(bookmarkName)) {
                    throw new IllegalArgumentException("Bookmark does not exist: " + bookmarkName);
                }
                SafeFileIO.writeStringAtomic(curBkFile, bookmarkName + "\n");
            }
            return bookmarks;
        }

        if (bookmarkName != null && !bookmarkName.isEmpty()) {
            byte[] targetNode = nodeId;
            if (targetNode == null) {
                targetNode = repository.getDirstate().getParent1();
            }
            String hex = NodeIdUtil.toHex(targetNode).substring(0, 40);
            bookmarks.put(bookmarkName, hex);
            writeBookmarks(bkFile, bookmarks);
            return bookmarks;
        }

        return bookmarks;
    }

    public String getActiveBookmark() {
        File curBkFile = new File(repository.getHgDir(), "bookmarks.current");
        if (curBkFile.exists()) {
            try {
                return Files.readString(curBkFile.toPath(), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                // ignore
            }
        }
        return null;
    }

    private void writeBookmarks(File file, Map<String, String> bookmarks) throws IOException {
        if (bookmarks.isEmpty()) {
            if (file.exists()) {
                file.delete();
            }
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : bookmarks.entrySet()) {
            sb.append(entry.getValue()).append(" ").append(entry.getKey()).append("\n");
        }
        SafeFileIO.writeStringAtomic(file, sb.toString());
    }


}
