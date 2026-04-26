package com.linkstash.exception;

public class LinkExpiredException extends RuntimeException {

    public LinkExpiredException() {
        super("Link has expired");
    }
}
