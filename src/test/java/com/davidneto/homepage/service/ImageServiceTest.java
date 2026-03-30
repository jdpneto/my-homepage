package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.Image;
import com.davidneto.homepage.entity.OwnerType;
import com.davidneto.homepage.repository.ImageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImageServiceTest {

    @Mock
    private ImageRepository imageRepository;

    private ImageService imageService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        imageService = new ImageService(imageRepository, tempDir.toString());
    }

    @Test
    void upload_savesFileAndRecord() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", new byte[]{1, 2, 3});
        Image saved = new Image();
        saved.setId(1L);
        when(imageRepository.save(any())).thenReturn(saved);

        Image result = imageService.upload(OwnerType.BLOG_POST, 1L, file);

        assertThat(result.getId()).isEqualTo(1L);
        ArgumentCaptor<Image> captor = ArgumentCaptor.forClass(Image.class);
        verify(imageRepository).save(captor.capture());
        Image captured = captor.getValue();
        assertThat(captured.getFilename()).isEqualTo("photo.png");
        assertThat(captured.getOwnerType()).isEqualTo(OwnerType.BLOG_POST);
        assertThat(captured.getOwnerId()).isEqualTo(1L);
        assertThat(captured.getContentType()).isEqualTo("image/png");
        assertThat(captured.getSize()).isEqualTo(3);
        // Verify file was written to disk
        Path ownerDir = tempDir.resolve("images/BLOG_POST/1");
        assertThat(Files.list(ownerDir).count()).isEqualTo(1);
    }

    @Test
    void upload_rejectsUnsupportedFormat() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "malware.exe", "application/octet-stream", new byte[]{1});

        assertThatThrownBy(() -> imageService.upload(OwnerType.BLOG_POST, 1L, file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported image format");
    }

    @Test
    void listByOwner_returnsImages() {
        List<Image> images = List.of(new Image());
        when(imageRepository.findByOwnerTypeAndOwnerIdOrderByCreatedAtDesc(
                OwnerType.BLOG_POST, 1L)).thenReturn(images);

        List<Image> result = imageService.listByOwner(OwnerType.BLOG_POST, 1L);

        assertThat(result).hasSize(1);
    }

    @Test
    void delete_removesFileAndRecord() throws IOException {
        Image image = new Image();
        image.setId(1L);
        image.setOwnerType(OwnerType.BLOG_POST);
        image.setOwnerId(1L);
        image.setStoredName("abc-123.png");
        when(imageRepository.findById(1L)).thenReturn(Optional.of(image));

        // Create the file on disk so we can verify deletion
        Path imageDir = tempDir.resolve("images/BLOG_POST/1");
        Files.createDirectories(imageDir);
        Files.write(imageDir.resolve("abc-123.png"), new byte[]{1});

        imageService.delete(1L);

        verify(imageRepository).delete(image);
        assertThat(Files.exists(imageDir.resolve("abc-123.png"))).isFalse();
    }

    @Test
    void deleteAllByOwner_removesDirectoryAndRecords() throws IOException {
        // Create files on disk
        Path imageDir = tempDir.resolve("images/BLOG_POST/1");
        Files.createDirectories(imageDir);
        Files.write(imageDir.resolve("img1.png"), new byte[]{1});
        Files.write(imageDir.resolve("img2.png"), new byte[]{2});

        imageService.deleteAllByOwner(OwnerType.BLOG_POST, 1L);

        verify(imageRepository).deleteByOwnerTypeAndOwnerId(OwnerType.BLOG_POST, 1L);
        assertThat(Files.exists(imageDir)).isFalse();
    }
}
