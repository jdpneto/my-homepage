package com.davidneto.homepage.gallery.service;

import com.davidneto.homepage.gallery.config.GalleryProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Component
public class GalleryStorage {

    private final Path root;

    public GalleryStorage(GalleryProperties props) {
        this.root = Path.of(props.getRootDir()).toAbsolutePath();
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(root.resolve("originals"));
            Files.createDirectories(root.resolve("thumbs"));
            Files.createDirectories(root.resolve("display"));
            Files.createDirectories(root.resolve("_tmp"));
            Files.createDirectories(root.resolve("_drop"));
            Files.createDirectories(root.resolve("_drop/_failed"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path root() { return root; }
    public Path dropDir() { return root.resolve("_drop"); }
    public Path failedDir() { return root.resolve("_drop/_failed"); }

    public Path originalPath(UUID key, String ext) {
        String shard = key.toString().substring(0, 2);
        return root.resolve("originals").resolve(shard).resolve(key + "." + ext);
    }

    public Path thumbPath(UUID key) {
        String shard = key.toString().substring(0, 2);
        return root.resolve("thumbs").resolve(shard).resolve(key + ".jpg");
    }

    public Path displayPath(UUID key) {
        String shard = key.toString().substring(0, 2);
        return root.resolve("display").resolve(shard).resolve(key + ".jpg");
    }

    public Path newTempFile() {
        return root.resolve("_tmp").resolve(UUID.randomUUID() + ".part");
    }

    public void ensureParentDirs(Path p) {
        try {
            Files.createDirectories(p.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void deleteAll(UUID key, String ext) {
        try {
            Files.deleteIfExists(originalPath(key, ext));
            Files.deleteIfExists(thumbPath(key));
            Files.deleteIfExists(displayPath(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
