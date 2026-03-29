package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.SocialLink;
import com.davidneto.homepage.repository.SocialLinkRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class SocialLinkService {

    private final SocialLinkRepository socialLinkRepository;

    public SocialLinkService(SocialLinkRepository socialLinkRepository) {
        this.socialLinkRepository = socialLinkRepository;
    }

    public List<SocialLink> getAllSorted() {
        return socialLinkRepository.findAllByOrderBySortOrderAsc();
    }

    public Optional<SocialLink> getById(Long id) {
        return socialLinkRepository.findById(id);
    }

    public SocialLink save(SocialLink link) {
        return socialLinkRepository.save(link);
    }

    public void delete(Long id) {
        socialLinkRepository.deleteById(id);
    }
}
