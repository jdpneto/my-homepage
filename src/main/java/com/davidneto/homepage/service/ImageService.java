package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.Image;
import com.davidneto.homepage.entity.OwnerType;
import com.davidneto.homepage.repository.ImageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ImageService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ImageService.class);

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp");

    private final ImageRepository imageRepository;
    private final String uploadDir;

    public ImageService(ImageRepository imageRepository,
                        @Value("${app.upload-dir}") String uploadDir) {
        this.imageRepository = imageRepository;
        this.uploadDir = uploadDir;
    }

    public Image upload(OwnerType ownerType, Long ownerId, MultipartFile file) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String ext = getExtension(originalFilename).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("Unsupported image format. Allowed: jpg, jpeg, png, gif, webp");
        }

        String storedName = UUID.randomUUID() + ext;
        Path dir = Paths.get(uploadDir, "images", ownerType.name(), ownerId.toString());
        Files.createDirectories(dir);
        file.transferTo(dir.resolve(storedName).toFile());

        Image image = new Image();
        image.setOwnerType(ownerType);
        image.setOwnerId(ownerId);
        image.setFilename(originalFilename);
        image.setStoredName(storedName);
        image.setContentType(file.getContentType());
        image.setSize(file.getSize());
        return imageRepository.save(image);
    }

    public List<Image> listByOwner(OwnerType ownerType, Long ownerId) {
        return imageRepository.findByOwnerTypeAndOwnerIdOrderByCreatedAtDesc(ownerType, ownerId);
    }

    @Transactional
    public void delete(Long id) {
        Image image = imageRepository.findById(id).orElseThrow();
        Path filePath = Paths.get(uploadDir, "images",
                image.getOwnerType().name(), image.getOwnerId().toString(), image.getStoredName());
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.warn("Failed to delete image file: {}", filePath, e);
        }
        imageRepository.delete(image);
    }

    @Transactional
    public void deleteAllByOwner(OwnerType ownerType, Long ownerId) {
        imageRepository.deleteByOwnerTypeAndOwnerId(ownerType, ownerId);
        Path dir = Paths.get(uploadDir, "images", ownerType.name(), ownerId.toString());
        try {
            if (Files.exists(dir)) {
                try (var paths = Files.walk(dir)) {
                    paths.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try { Files.delete(path); } catch (IOException e) {
                                    log.warn("Failed to delete file: {}", path, e);
                                }
                            });
                }
            }
        } catch (IOException e) {
            log.warn("Failed to clean up image directory: {}", dir, e);
        }
    }

    public String getImageUrl(Image image) {
        return "/uploads/images/" + image.getOwnerType().name() + "/"
                + image.getOwnerId() + "/" + image.getStoredName();
    }

    private String getExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }
}
