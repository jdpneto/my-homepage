package com.davidneto.homepage.repository;

import com.davidneto.homepage.entity.SocialLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SocialLinkRepository extends JpaRepository<SocialLink, Long> {
    List<SocialLink> findAllByOrderBySortOrderAsc();
}
