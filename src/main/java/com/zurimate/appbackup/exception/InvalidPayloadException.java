package com.zurimate.appbackup.exception;

/**
 * The caller sent something AppBackup cannot accept (mapped to HTTP 400 with the message).
 */
public class InvalidPayloadException extends RuntimeException{
    public InvalidPayloadException(String message) {
        super(message);
    }
}
