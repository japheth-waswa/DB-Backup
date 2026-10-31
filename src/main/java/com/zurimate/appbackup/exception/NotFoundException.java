package com.zurimate.appbackup.exception;

/**
 * A requested backup job does not exist (mapped to HTTP 404).
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
