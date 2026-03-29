package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.SocialLink;
import com.davidneto.homepage.repository.SocialLinkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SocialLinkServiceTest {

    @Mock
    private SocialLinkRepository socialLinkRepository;

    @InjectMocks
    private SocialLinkService socialLinkService;

    @Test
    void getAllSorted_returnsSortedLinks() {
        SocialLink link = new SocialLink();
        link.setPlatform("linkedin");
        link.setUrl("https://linkedin.com/in/test");
        link.setDisplayName("LinkedIn");
        link.setSortOrder(1);

        when(socialLinkRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(link));

        List<SocialLink> result = socialLinkService.getAllSorted();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPlatform()).isEqualTo("linkedin");
    }
}
