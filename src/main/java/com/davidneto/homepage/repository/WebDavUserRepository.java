package com.davidneto.homepage.repository;

import com.davidneto.homepage.entity.WebDavUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WebDavUserRepository extends JpaRepository<WebDavUser, Long> {
    Optional<WebDavUser> findByUsername(String username);
    List<WebDavUser> findAllByOrderByUsernameAsc();
}
