package com.zurimate.appbackup.config;

import com.zurimate.appbackup.dto.ApiResponse;
import com.zurimate.appbackup.exception.InvalidPayloadException;
import com.zurimate.appbackup.exception.NotFoundException;
import com.zurimate.appbackup.utils.Helpers;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * Maps exceptions to JSON error responses. Client errors are logged without stack traces and
 * never echo the request body, which may contain database passwords.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidPayloadException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleInvalidPayload(InvalidPayloadException exception) {
        return clientError(exception.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleConstraintViolation(ConstraintViolationException exception) {
        return clientError(Helpers.extractViolationsFromException(exception));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleInvalidBody(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(e -> "%s %s".formatted(e.getField(), e.getDefaultMessage()))
                .collect(Collectors.joining(" | "));
        return clientError(message);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleUnreadable(Exception exception) {
        // the parser message can echo parts of the body (credentials included), so keep it generic
        return clientError("Malformed request body or parameter");
    }

    @ExceptionHandler({NotFoundException.class, NoResourceFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiResponse<Void> handleNotFound(Exception exception) {
        return clientError(exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiResponse<Void> handleException(Exception exception) {
        return Helpers.parseResponse(exception, "Internal server error");
    }

    private static ApiResponse<Void> clientError(String message) {
        log.warn("Rejected request: {}", message);
        return new ApiResponse<>(message, null);
    }
}
