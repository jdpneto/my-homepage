package com.davidneto.homepage.webdav;

import io.milton.http.fs.SimpleFileContentService;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * WebDAV uploads go to a hidden sibling temp file and are moved into place only once the whole
 * body has arrived. Milton's default opens the target with FileOutputStream, which empties an
 * existing file before reading a single byte — an interrupted re-upload then destroys it.
 *
 * In a protected folder (the camera volume) the final move is also refused when the body was
 * empty and the file exists (measured on the bytes received, not the Content-Length header), or
 * when the clip is kept by then (re-checked right before the move, closing any race with Keep).
 */
public class AtomicFileContentService extends SimpleFileContentService {

    private final BooleanSupplier protectedFolder;

    public AtomicFileContentService() {
        this(() -> false);
    }

    public AtomicFileContentService(BooleanSupplier protectedFolder) {
        this.protectedFolder = protectedFolder;
    }

    @Override
    public void setFileContent(File dest, InputStream in) throws IOException {
        Path target = dest.toPath();
        Path tmp = target.resolveSibling("." + target.getFileName() + ".upload-" + UUID.randomUUID());
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                in.transferTo(out);
            }
            if (protectedFolder.getAsBoolean() && Files.exists(target)) {
                if (Files.size(tmp) == 0) throw new IOException("refused: an existing file cannot be replaced by an empty one");
                if (Files.isRegularFile(keepMarker(target))) throw new IOException("refused: this clip is kept");
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static Path keepMarker(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return file.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + ".keep");
    }
}
