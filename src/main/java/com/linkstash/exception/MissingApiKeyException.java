package com.linkstash.exception;

public class MissingApiKeyException extends ApiKeyAuthException {

    public MissingApiKeyException() {
        super("Missing API key");
    }
}
