package com.jreinhal.mercenary.service;

public class SecureIngestionException extends RuntimeException {
    public SecureIngestionException(String message) {
        super(message);
    }

    public SecureIngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
