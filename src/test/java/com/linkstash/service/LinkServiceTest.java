package com.linkstash.service;

import com.linkstash.dto.CreateLinkRequest;
import com.linkstash.dto.LinkResponse;
import com.linkstash.repository.LinkRepository;
import com.linkstash.domain.Link;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinkServiceTest {

    @Mock
    private LinkRepository linkRepository;

    @InjectMocks
    private LinkService linkService;

    @Test
    void createLink_returnsResponseWithShortCode() {
        ReflectionTestUtils.setField(linkService, "baseUrl", "http://localhost:8080");

        when(linkRepository.existsByShortCode(anyString())).thenReturn(false);
        when(linkRepository.save(any(Link.class))).thenAnswer(inv -> inv.getArgument(0));

        LinkResponse response = linkService.createLink(new CreateLinkRequest("https://example.com", null));

        assertThat(response.shortCode()).isNotNull();
        assertThat(response.shortCode()).hasSize(8);
        assertThat(response.originalUrl()).isEqualTo("https://example.com");
        assertThat(response.shortUrl()).startsWith("http://localhost:8080/");
    }
}
