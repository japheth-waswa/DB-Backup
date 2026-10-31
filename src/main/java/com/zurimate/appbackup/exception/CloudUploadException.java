package com.zurimate.appbackup.exception;

/** Raised by cloud strategies; {@code retryable} tells the caller whether another attempt makes sense. */
public class CloudUploadException extends RuntimeException {
    private final boolean retryable;

    public CloudUploadException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
