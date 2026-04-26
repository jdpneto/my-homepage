package com.davidneto.homepage.gallery.service;

import com.davidneto.homepage.gallery.config.GalleryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GalleryStorageTest {

    @TempDir Path tmp;
    GalleryStorage storage;

    @BeforeEach
    void setUp() {
        GalleryProperties props = new GalleryProperties();
        props.setRootDir(tmp.toString());
        storage = new GalleryStorage(props);
        storage.init();
    }

    @Test
    void init_createsAllSubdirs() {
        assertThat(Files.isDirectory(tmp.resolve("originals"))).isTrue();
        assertThat(Files.isDirectory(tmp.resolve("thumbs"))).isTrue();
        assertThat(Files.isDirectory(tmp.resolve("display"))).isTrue();
        assertThat(Files.isDirectory(tmp.resolve("_tmp"))).isTrue();
        assertThat(Files.isDirectory(tmp.resolve("_drop"))).isTrue();
        assertThat(Files.isDirectory(tmp.resolve("_drop/_failed"))).isTrue();
    }

    @Test
    void originalPath_usesFirstTwoHexCharsAsSubdir() {
        UUID key = UUID.fromString("ab345678-1234-1234-1234-123456789abc");
        Path p = storage.originalPath(key, "jpg");
        assertThat(p).isEqualTo(tmp.resolve("originals/ab/ab345678-1234-1234-1234-123456789abc.jpg"));
    }

    @Test
    void thumbPath_alwaysJpg() {
        UUID key = UUID.fromString("cd000000-0000-0000-0000-000000000000");
        assertThat(storage.thumbPath(key))
                .isEqualTo(tmp.resolve("thumbs/cd/cd000000-0000-0000-0000-000000000000.jpg"));
    }

    @Test
    void newTempFile_returnsUniquePathInTmpDir() {
        Path a = storage.newTempFile();
        Path b = storage.newTempFile();
        assertThat(a).isNotEqualTo(b);
        assertThat(a.getParent()).isEqualTo(tmp.resolve("_tmp"));
        assertThat(a.getFileName().toString()).endsWith(".part");
    }

    @Test
    void deleteAll_removesEveryDerivativeForAKey() throws Exception {
        UUID key = UUID.randomUUID();
        Files.createDirectories(storage.originalPath(key, "jpg").getParent());
        Files.write(storage.originalPath(key, "jpg"), new byte[]{1});
        Files.createDirectories(storage.thumbPath(key).getParent());
        Files.write(storage.thumbPath(key), new byte[]{1});
        Files.createDirectories(storage.displayPath(key).getParent());
        Files.write(storage.displayPath(key), new byte[]{1});

        storage.deleteAll(key, "jpg");

        assertThat(Files.exists(storage.originalPath(key, "jpg"))).isFalse();
        assertThat(Files.exists(storage.thumbPath(key))).isFalse();
        assertThat(Files.exists(storage.displayPath(key))).isFalse();
    }
}
