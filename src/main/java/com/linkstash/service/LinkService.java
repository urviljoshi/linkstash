package com.linkstash.service;

import com.linkstash.domain.Link;
import com.linkstash.dto.CreateLinkRequest;
import com.linkstash.dto.LinkResponse;
import com.linkstash.dto.LinkStatsResponse;
import com.linkstash.repository.LinkRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.NoSuchElementException;

@Service
public class LinkService {

    private static final String BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final LinkRepository linkRepository;

    @Value("${linkstash.base-url}")
    private String baseUrl;

    public LinkService(LinkRepository linkRepository) {
        this.linkRepository = linkRepository;
    }

    public LinkResponse createLink(CreateLinkRequest request) {
        if (request.url() == null || !request.url().startsWith("http")) {
            throw new IllegalArgumentException("Invalid URL");
        }

        String shortCode;
        while (true) {
            shortCode = generateCode();
            if (!linkRepository.existsByShortCode(shortCode)) {
                break;
            }
        }

        Link link = new Link();
        link.setShortCode(shortCode);
        link.setOriginalUrl(request.url());
        link.setCreatedAt(Instant.now());
        link.setClickCount(0L);
        linkRepository.save(link);

        return new LinkResponse(shortCode, baseUrl + "/" + shortCode, request.url());
    }

    public String getOriginalUrl(String shortCode) {
        Link link = linkRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new NoSuchElementException("Short code not found: " + shortCode));
        return link.getOriginalUrl();
    }

    public void incrementClick(String shortCode) {
        Link link = linkRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new NoSuchElementException("Short code not found: " + shortCode));
        link.setClickCount(link.getClickCount() + 1);
        linkRepository.save(link);
    }

    public LinkStatsResponse getStats(String shortCode) {
        Link link = linkRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new NoSuchElementException("Short code not found: " + shortCode));
        return new LinkStatsResponse(link.getShortCode(), link.getOriginalUrl(), link.getClickCount(), link.getCreatedAt());
    }

    public void deleteLink(String shortCode) {
        Link link = linkRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new NoSuchElementException("Short code not found: " + shortCode));
        linkRepository.delete(link);
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(BASE62.charAt(RANDOM.nextInt(BASE62.length())));
        }
        return sb.toString();
    }
}
