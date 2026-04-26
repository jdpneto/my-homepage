package com.davidneto.homepage.gallery.repository;

import com.davidneto.homepage.gallery.entity.GalleryItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface GalleryItemRepository extends JpaRepository<GalleryItem, Long> {

    Optional<GalleryItem> findByContentHash(String contentHash);

    List<GalleryItem> findByBucketYearAndBucketMonthOrderByTakenAtAscUploadedAtAsc(
            int bucketYear, int bucketMonth);

    Page<GalleryItem> findAllByOrderByUploadedAtDesc(Pageable pageable);

    @Query("select distinct g.bucketYear from GalleryItem g order by g.bucketYear desc")
    List<Integer> findDistinctYearsDesc();

    @Query("""
           select g.bucketMonth as month, count(g) as itemCount
             from GalleryItem g
            where g.bucketYear = :year
         group by g.bucketMonth
         order by g.bucketMonth desc
           """)
    List<MonthSummary> findMonthSummaries(int year);

    interface MonthSummary {
        Integer getMonth();
        Long getItemCount();
    }
}
