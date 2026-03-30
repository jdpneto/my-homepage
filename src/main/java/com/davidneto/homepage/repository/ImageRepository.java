package com.davidneto.homepage.repository;

import com.davidneto.homepage.entity.Image;
import com.davidneto.homepage.entity.OwnerType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ImageRepository extends JpaRepository<Image, Long> {

    List<Image> findByOwnerTypeAndOwnerIdOrderByCreatedAtDesc(OwnerType ownerType, Long ownerId);

    @Modifying
    @Transactional
    void deleteByOwnerTypeAndOwnerId(OwnerType ownerType, Long ownerId);
}
