package com.zurimate.appbackup.dto;

public record ApiResponse<T>(String message, T data) {
}
