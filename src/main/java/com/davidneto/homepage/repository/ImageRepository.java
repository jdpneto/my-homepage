package com.davidneto.homepage.repository;

import com.davidneto.homepage.entity.Image;
import com.davidneto.homepage.entity.OwnerType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImageRepository extends JpaRepository<Image, Long> {

    List<Image> findByOwnerTypeAndOwnerIdOrderByCreatedAtDesc(OwnerType ownerType, Long ownerId);

    void deleteByOwnerTypeAndOwnerId(OwnerType ownerType, Long ownerId);
}
