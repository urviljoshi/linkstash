package com.linkstash.controller;

import com.linkstash.dto.CreateLinkRequest;
import com.linkstash.dto.LinkResponse;
import com.linkstash.dto.LinkStatsResponse;
import com.linkstash.service.LinkService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
public class LinkController {

    private final LinkService linkService;

    public LinkController(LinkService linkService) {
        this.linkService = linkService;
    }

    @PostMapping("/api/v1/links")
    public ResponseEntity<LinkResponse> createLink(@RequestBody CreateLinkRequest request) {
        LinkResponse response = linkService.createLink(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        String originalUrl = linkService.getOriginalUrl(shortCode);
        linkService.incrementClick(shortCode);
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(originalUrl));
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    @GetMapping("/api/v1/links/{shortCode}/stats")
    public ResponseEntity<LinkStatsResponse> getStats(@PathVariable String shortCode) {
        return ResponseEntity.ok(linkService.getStats(shortCode));
    }

    @DeleteMapping("/api/v1/links/{shortCode}")
    public ResponseEntity<Void> deleteLink(@PathVariable String shortCode) {
        linkService.deleteLink(shortCode);
        return ResponseEntity.noContent().build();
    }
}
