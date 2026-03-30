package com.davidneto.homepage.controller;

import com.davidneto.homepage.config.SecurityConfig;
import com.davidneto.homepage.entity.Image;
import com.davidneto.homepage.entity.OwnerType;
import com.davidneto.homepage.service.ImageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ImageApiController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "app.admin.username=admin",
        "app.admin.password=testpass",
        "app.upload-dir=/tmp/test-uploads"
})
class ImageApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ImageService imageService;

    @Test
    void upload_requiresAuthentication() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.png", "image/png", new byte[]{1});

        mockMvc.perform(multipart("/admin/api/images")
                        .file(file)
                        .param("ownerType", "BLOG_POST")
                        .param("ownerId", "1")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void upload_returnsImageJson() throws Exception {
        Image image = new Image();
        image.setId(1L);
        image.setFilename("test.png");
        image.setStoredName("abc-123.png");
        image.setOwnerType(OwnerType.BLOG_POST);
        image.setOwnerId(1L);
        when(imageService.upload(eq(OwnerType.BLOG_POST), eq(1L), any())).thenReturn(image);
        when(imageService.getImageUrl(image)).thenReturn("/uploads/images/BLOG_POST/1/abc-123.png");

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/admin/api/images")
                        .file(file)
                        .param("ownerType", "BLOG_POST")
                        .param("ownerId", "1")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.filename").value("test.png"))
                .andExpect(jsonPath("$.url").value("/uploads/images/BLOG_POST/1/abc-123.png"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void list_returnsImagesForOwner() throws Exception {
        Image image = new Image();
        image.setId(1L);
        image.setFilename("photo.jpg");
        image.setStoredName("uuid.jpg");
        image.setOwnerType(OwnerType.BLOG_POST);
        image.setOwnerId(1L);
        when(imageService.listByOwner(OwnerType.BLOG_POST, 1L)).thenReturn(List.of(image));
        when(imageService.getImageUrl(image)).thenReturn("/uploads/images/BLOG_POST/1/uuid.jpg");

        mockMvc.perform(get("/admin/api/images")
                        .param("ownerType", "BLOG_POST")
                        .param("ownerId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].filename").value("photo.jpg"))
                .andExpect(jsonPath("$[0].url").value("/uploads/images/BLOG_POST/1/uuid.jpg"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_removesImageAndReturnsOk() throws Exception {
        mockMvc.perform(delete("/admin/api/images/1").with(csrf()))
                .andExpect(status().isOk());

        verify(imageService).delete(1L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void upload_rejectsUnsupportedFormat() throws Exception {
        when(imageService.upload(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Unsupported image format"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "malware.exe", "application/octet-stream", new byte[]{1});

        mockMvc.perform(multipart("/admin/api/images")
                        .file(file)
                        .param("ownerType", "BLOG_POST")
                        .param("ownerId", "1")
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unsupported image format"));
    }
}
