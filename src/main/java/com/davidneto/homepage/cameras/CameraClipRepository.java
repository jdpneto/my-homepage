package com.davidneto.homepage.cameras;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CameraClipRepository extends JpaRepository<CameraClip, Long>, JpaSpecificationExecutor<CameraClip> {
    Optional<CameraClip> findByStem(String stem);

    List<CameraClip> findByStartAtBefore(java.time.Instant cutoff);

    /** Clips not kept (per the row), oldest first: the safety net's deletion order. */
    List<CameraClip> findByKeptFalseOrderByStartAtAscIdAsc();

    @Query("select new com.davidneto.homepage.cameras.CameraInfo(c.cameraName, max(c.cameraId)) "
            + "from CameraClip c group by c.cameraName order by c.cameraName")
    List<CameraInfo> findCameras();

    @Query("select c.cameraName, count(c) from CameraClip c group by c.cameraName")
    List<Object[]> countPerCamera();

    @Query("select coalesce(sum(c.sizeBytes), 0) from CameraClip c where c.kept = :kept")
    long sumSizeByKept(boolean kept);

    /** The newest clip of every camera. */
    @Query("select c from CameraClip c where c.startAt = (select max(d.startAt) from CameraClip d where d.cameraName = c.cameraName)")
    List<CameraClip> findNewestPerCamera();
}
