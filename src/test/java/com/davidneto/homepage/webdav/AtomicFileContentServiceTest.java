package com.davidneto.homepage.webdav;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AtomicFileContentServiceTest {

    @TempDir Path dir;

    @Test
    void writesNewFile() throws Exception {
        Path f = dir.resolve("a.mp4");
        new AtomicFileContentService().setFileContent(f.toFile(), new ByteArrayInputStream(new byte[]{1, 2, 3}));
        assertThat(Files.readAllBytes(f)).containsExactly(1, 2, 3);
        assertThat(dir.toFile().list()).containsExactly("a.mp4");
    }

    @Test
    void interruptedUploadLeavesTheExistingFileIntactAndNoTemp() throws Exception {
        Path f = dir.resolve("a.mp4");
        Files.write(f, new byte[]{9, 9, 9});
        InputStream broken = new InputStream() {
            int n;
            @Override
            public int read() throws IOException {
                if (n++ < 2) return 1;
                throw new IOException("client went away");
            }
        };
        assertThrows(IOException.class, () -> new AtomicFileContentService().setFileContent(f.toFile(), broken));
        assertThat(Files.readAllBytes(f)).containsExactly(9, 9, 9);
        assertThat(dir.toFile().list()).containsExactly("a.mp4");
    }

    @Test
    void replacesExistingFileWhenComplete() throws Exception {
        Path f = dir.resolve("a.mp4");
        Files.write(f, new byte[]{9});
        new AtomicFileContentService().setFileContent(f.toFile(), new ByteArrayInputStream(new byte[]{4, 5}));
        assertThat(Files.readAllBytes(f)).containsExactly(4, 5);
    }

    @Test
    void protectedFolderRefusesEmptyOverwriteMeasuredOnTheBytes() throws Exception {
        Path f = dir.resolve("a.mp4");
        Files.write(f, new byte[]{9, 9});
        AtomicFileContentService svc = new AtomicFileContentService(() -> true);
        assertThrows(IOException.class, () -> svc.setFileContent(f.toFile(), new ByteArrayInputStream(new byte[0])));
        assertThat(Files.readAllBytes(f)).containsExactly(9, 9);
        assertThat(dir.toFile().list()).containsExactly("a.mp4");
    }

    @Test
    void protectedFolderRechecksKeepJustBeforeReplacing() throws Exception {
        Path f = dir.resolve("a.mp4");
        Files.write(f, new byte[]{9});
        AtomicFileContentService svc = new AtomicFileContentService(() -> true);
        InputStream becomesKeptMidUpload = new InputStream() {
            int n;
            @Override
            public int read() throws IOException {
                if (n == 1) Files.createFile(dir.resolve("a.keep")); // kept while the body streams
                return n++ < 3 ? 7 : -1;
            }
        };
        assertThrows(IOException.class, () -> svc.setFileContent(f.toFile(), becomesKeptMidUpload));
        assertThat(Files.readAllBytes(f)).containsExactly(9);
    }

    @Test
    void unprotectedFolderMayWriteEmptyFiles() throws Exception {
        Path f = dir.resolve("a.txt");
        Files.write(f, new byte[]{9});
        new AtomicFileContentService(() -> false).setFileContent(f.toFile(), new ByteArrayInputStream(new byte[0]));
        assertThat(Files.size(f)).isZero();
    }
}
