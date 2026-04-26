package com.davidneto.homepage.gallery.controller;

import com.davidneto.homepage.gallery.entity.GalleryItem;
import com.davidneto.homepage.gallery.entity.MediaKind;
import com.davidneto.homepage.gallery.repository.GalleryItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class GalleryApiControllerIT {

    @Autowired WebApplicationContext wac;
    @Autowired GalleryItemRepository repo;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private byte[] tinyJpeg() throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        return baos.toByteArray();
    }

    @Test
    @WithMockUser(roles = "GALLERY_CONTRIBUTOR")
    void upload_acceptsMultipleFilesAndAppliesSharedCaption() throws Exception {
        MockMultipartFile a = new MockMultipartFile("file", "a.jpg", "image/jpeg", tinyJpeg());
        MockMultipartFile b = new MockMultipartFile("file", "b.jpg", "image/jpeg", tinyJpeg());

        // a and b are byte-identical (same generator) so b should dedupe.
        mockMvc.perform(multipart("/mae/api/items").file(a).file(b)
                        .param("uploaderName", "Carlos")
                        .param("caption", "Christmas")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deduped").value(false))
                .andExpect(jsonPath("$[1].deduped").value(true));
    }

    @Test
    @WithMockUser(roles = "GALLERY_CONTRIBUTOR")
    void patchCaption_updatesItem() throws Exception {
        GalleryItem item = persistDummy();
        mockMvc.perform(patch("/mae/api/items/" + item.getId())
                        .contentType("application/json")
                        .content("{\"caption\":\"Aunt Maria, 1985\"}")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caption").value("Aunt Maria, 1985"));
    }

    @Test
    @WithMockUser(roles = "GALLERY_CONTRIBUTOR")
    void delete_forbiddenForContributor() throws Exception {
        GalleryItem item = persistDummy();
        mockMvc.perform(delete("/mae/api/items/" + item.getId()).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"GALLERY_CONTRIBUTOR", "GALLERY_ADMIN"})
    void delete_succeedsForAdmin() throws Exception {
        GalleryItem item = persistDummy();
        mockMvc.perform(delete("/mae/api/items/" + item.getId()).with(csrf()))
                .andExpect(status().isNoContent());
    }

    private GalleryItem persistDummy() {
        GalleryItem item = new GalleryItem();
        item.setMediaKind(MediaKind.PHOTO);
        item.setStorageKey(UUID.randomUUID());
        item.setOriginalFilename("d.jpg");
        item.setContentType("image/jpeg");
        item.setSizeBytes(1);
        item.setContentHash(UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "").substring(0, 32));
        item.setBucketYear(2020);
        item.setBucketMonth(1);
        item.setBucketSource("UPLOAD");
        item.setUploadedAt(LocalDateTime.now());
        return repo.save(item);
    }
}
