package com.zurimate.appbackup.exception;

/**
 * A programming or configuration error inside AppBackup (mapped to HTTP 500).
 */
public class InternalException extends RuntimeException{
    public InternalException(String message) {
        super(message);
    }
}
