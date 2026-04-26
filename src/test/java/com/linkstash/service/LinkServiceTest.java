package com.linkstash.service;

import com.linkstash.dto.CreateLinkRequest;
import com.linkstash.dto.LinkResponse;
import com.linkstash.repository.LinkRepository;
import com.linkstash.domain.Link;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinkServiceTest {

    @Mock
    private LinkRepository linkRepository;

    private LinkService linkService;

    @BeforeEach
    void setUp() {
        linkService = new LinkService(linkRepository, "http://localhost:8080");
    }

    @Test
    void createLink_returnsResponseWithShortCode() {
        when(linkRepository.existsByShortCode(anyString())).thenReturn(false);
        when(linkRepository.save(any(Link.class))).thenAnswer(inv -> inv.getArgument(0));

        LinkResponse response = linkService.createLink(new CreateLinkRequest("https://example.com", null));

        assertThat(response.shortCode()).isNotNull();
        assertThat(response.shortCode()).hasSize(8);
        assertThat(response.originalUrl()).isEqualTo("https://example.com");
        assertThat(response.shortUrl()).startsWith("http://localhost:8080/");
    }
}
