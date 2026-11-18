package com.davidneto.homepage.cameras;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CameraClipRepository extends JpaRepository<CameraClip, Long> {
    Optional<CameraClip> findByStem(String stem);
}
