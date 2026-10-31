package com.zurimate.appbackup.dto;

/**
 * Envelope for every API response: a human readable {@code message} and the {@code data} payload.
 */
public record ApiResponse<T>(String message, T data) {
}
