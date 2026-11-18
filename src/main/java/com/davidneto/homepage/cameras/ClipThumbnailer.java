package com.davidneto.homepage.cameras;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Thumbnails in {@code app.cameras.thumbs-dir}, named by a hash of the clip stem. Regenerable. */
@Component
public class ClipThumbnailer {

    private static final Logger log = LoggerFactory.getLogger(ClipThumbnailer.class);

    private final CameraProperties props;
    private final ClipFiles files;
    private final FrameExtractor extractor;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public ClipThumbnailer(CameraProperties props, ClipFiles files, FrameExtractor extractor) {
        this.props = props;
        this.files = files;
        this.extractor = extractor;
    }

    public Path pathFor(String stem) {
        return props.getThumbsDir().resolve(sha1(stem) + ".jpg");
    }

    /** The thumbnail, created if missing; empty if it cannot be made. */
    public Optional<Path> ensure(String stem) {
        Path jpg = pathFor(stem);
        try {
            if (Files.isRegularFile(jpg) && Files.size(jpg) > 0) return Optional.of(jpg);
            Files.createDirectories(jpg.getParent());
            Path tmp = jpg.resolveSibling(jpg.getFileName() + ".tmp.jpg");
            Files.deleteIfExists(tmp);
            extractor.extract(files.mp4(stem), tmp);
            if (!Files.isRegularFile(tmp) || Files.size(tmp) == 0) {
                Files.deleteIfExists(tmp);
                throw new IOException("no frame produced");
            }
            Files.move(tmp, jpg, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(jpg);
        } catch (IOException | RuntimeException e) {
            if (warned.add(stem)) log.warn("no thumbnail for {}: {}", stem, e.toString());
            return Optional.empty();
        }
    }

    public void delete(String stem) {
        try {
            Files.deleteIfExists(pathFor(stem));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha1(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
