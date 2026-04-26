package com.linkstash.exception;

public class InvalidApiKeyException extends ApiKeyAuthException {

    public InvalidApiKeyException() {
        super("Invalid API key");
    }
}
