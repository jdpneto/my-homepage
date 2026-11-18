package com.davidneto.homepage.cameras;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CameraClipRepository extends JpaRepository<CameraClip, Long>, JpaSpecificationExecutor<CameraClip> {
    Optional<CameraClip> findByStem(String stem);

    List<CameraClip> findByStartAtBefore(java.time.Instant cutoff);

    @Query("select distinct c.cameraName from CameraClip c order by c.cameraName")
    List<String> findCameraNames();

    @Query("select coalesce(sum(c.sizeBytes), 0) from CameraClip c where c.kept = :kept")
    long sumSizeByKept(boolean kept);

    /** The newest clip of every camera. */
    @Query("select c from CameraClip c where c.startAt = (select max(d.startAt) from CameraClip d where d.cameraName = c.cameraName)")
    List<CameraClip> findNewestPerCamera();
}
