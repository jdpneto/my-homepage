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

/**
 * WebDAV uploads go to a hidden sibling temp file and are moved into place only once the whole
 * body has arrived. Milton's default opens the target with FileOutputStream, which empties an
 * existing file before reading a single byte — an interrupted re-upload then destroys it.
 */
public class AtomicFileContentService extends SimpleFileContentService {

    @Override
    public void setFileContent(File dest, InputStream in) throws IOException {
        Path target = dest.toPath();
        Path tmp = target.resolveSibling("." + target.getFileName() + ".upload-" + UUID.randomUUID());
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                in.transferTo(out);
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
}
