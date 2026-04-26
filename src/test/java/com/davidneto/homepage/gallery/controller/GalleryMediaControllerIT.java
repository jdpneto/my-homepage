package com.davidneto.homepage.gallery.controller;

import com.davidneto.homepage.gallery.repository.GalleryItemRepository;
import com.davidneto.homepage.gallery.service.GalleryIngestService;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class GalleryMediaControllerIT {

    @Autowired WebApplicationContext wac;
    @Autowired GalleryItemRepository repo;
    @Autowired GalleryIngestService ingest;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        repo.deleteAll();
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private long ingestSamplePhoto() throws Exception {
        BufferedImage img = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        TiffOutputSet os = new TiffOutputSet();
        os.getOrCreateExifDirectory().add(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, "2010:01:01 00:00:00");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ExifRewriter().updateExifMetadataLossless(baos.toByteArray(), out, os);
        return ingest.ingest(new ByteArrayInputStream(out.toByteArray()), "x.jpg", "image/jpeg", null).itemId();
    }

    @Test
    @WithMockUser(roles = "GALLERY_CONTRIBUTOR")
    void thumb_returnsJpegBytes_andCacheHeaders() throws Exception {
        long id = ingestSamplePhoto();
        mockMvc.perform(get("/mae/media/thumb/" + id))
               .andExpect(status().isOk())
               .andExpect(content().contentType("image/jpeg"))
               .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=31536000")));
    }

    @Test
    @WithMockUser(roles = "GALLERY_CONTRIBUTOR")
    void original_returnsContentDispositionAttachment() throws Exception {
        long id = ingestSamplePhoto();
        mockMvc.perform(get("/mae/media/original/" + id))
               .andExpect(status().isOk())
               .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")));
    }

    @Test
    void thumb_unauthenticated_redirectsToLogin() throws Exception {
        long id = ingestSamplePhoto();
        mockMvc.perform(get("/mae/media/thumb/" + id))
               .andExpect(status().is3xxRedirection());
    }
}
