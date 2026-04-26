package com.linkstash.service;

import com.linkstash.domain.Link;
import com.linkstash.dto.CreateLinkRequest;
import com.linkstash.dto.LinkResponse;
import com.linkstash.dto.LinkStatsResponse;
import com.linkstash.exception.LinkExpiredException;
import com.linkstash.repository.LinkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.NoSuchElementException;

@Service
public class LinkService {

    private static final String BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final LinkRepository linkRepository;
    private final String baseUrl;

    public LinkService(LinkRepository linkRepository,
                       @org.springframework.beans.factory.annotation.Value("${linkstash.base-url}") String baseUrl) {
        this.linkRepository = linkRepository;
        this.baseUrl = baseUrl;
    }

    @Transactional
    public LinkResponse createLink(CreateLinkRequest request) {
        String shortCode = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            String candidate = generateCode();
            if (!linkRepository.existsByShortCode(candidate)) {
                shortCode = candidate;
                break;
            }
        }
        if (shortCode == null) {
            throw new IllegalStateException("Unable to generate unique short code");
        }

        Link link = new Link();
        link.setShortCode(shortCode);
        link.setOriginalUrl(request.url());
        link.setCreatedAt(Instant.now());
        link.setClickCount(0L);
        link.setExpiresAt(request.expiresAt());
        linkRepository.save(link);

        return new LinkResponse(shortCode, baseUrl + "/" + shortCode, request.url(), link.getExpiresAt());
    }

    @Transactional
    public String resolveRedirect(String shortCode) {
        Link link = linkRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new NoSuchElementException("Short code not found: " + shortCode));

        if (link.getExpiresAt() != null && Instant.now().isAfter(link.getExpiresAt())) {
            throw new LinkExpiredException("Link has expired: " + shortCode);
        }

        link.setClickCount(link.getClickCount() + 1);
        linkRepository.save(link);

        return link.getOriginalUrl();
    }

    public LinkStatsResponse getStats(String shortCode) {
        Link link = linkRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new NoSuchElementException("Short code not found: " + shortCode));
        return new LinkStatsResponse(link.getShortCode(), link.getOriginalUrl(), link.getClickCount(), link.getCreatedAt(), link.getExpiresAt());
    }

    @Transactional
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
