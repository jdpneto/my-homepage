package com.davidneto.homepage.gallery.repository;

import com.davidneto.homepage.gallery.entity.GalleryItem;
import com.davidneto.homepage.gallery.entity.MediaKind;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class GalleryItemRepositoryTest {

    @Autowired
    GalleryItemRepository repo;

    private GalleryItem make(String hash, int year, int month, LocalDateTime uploaded) {
        GalleryItem g = new GalleryItem();
        g.setMediaKind(MediaKind.PHOTO);
        g.setStorageKey(UUID.randomUUID());
        g.setOriginalFilename("a.jpg");
        g.setContentType("image/jpeg");
        g.setSizeBytes(1234);
        g.setContentHash(hash);
        g.setBucketYear(year);
        g.setBucketMonth(month);
        g.setBucketSource("EXIF");
        g.setUploadedAt(uploaded);
        return g;
    }

    @Test
    void findByContentHash_returnsExistingItem() {
        repo.save(make("aaaa".repeat(16), 2020, 5, LocalDateTime.now()));
        assertThat(repo.findByContentHash("aaaa".repeat(16))).isPresent();
        assertThat(repo.findByContentHash("missing".repeat(9) + "a")).isEmpty();
    }

    @Test
    void findByBucket_returnsItemsInThatMonth() {
        repo.save(make("h1".repeat(32), 2020, 5, LocalDateTime.now()));
        repo.save(make("h2".repeat(32), 2020, 5, LocalDateTime.now()));
        repo.save(make("h3".repeat(32), 2021, 5, LocalDateTime.now()));

        var items = repo.findByBucketYearAndBucketMonthOrderByTakenAtAscUploadedAtAsc(2020, 5);
        assertThat(items).hasSize(2);
    }

    @Test
    void findRecent_returnsItemsByUploadedAtDesc() {
        repo.save(make("r1".repeat(32), 2020, 5, LocalDateTime.of(2020, 1, 1, 0, 0)));
        repo.save(make("r2".repeat(32), 2020, 5, LocalDateTime.of(2024, 1, 1, 0, 0)));

        var page = repo.findAllByOrderByUploadedAtDesc(PageRequest.of(0, 10));
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).getContentHash()).isEqualTo("r2".repeat(32));
    }
}
