package com.zurimate.appbackup.config;


import com.zurimate.appbackup.dto.ApiResponse;
import com.zurimate.appbackup.utils.Helpers;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    @ResponseBody
    @ExceptionHandler(value = {ConstraintViolationException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleException(ConstraintViolationException constraintViolationException) {
        return Helpers.parseResponse(constraintViolationException, Helpers.extractViolationsFromException(constraintViolationException));
    }

    @ResponseBody
    @ExceptionHandler(value = {Exception.class})
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiResponse<Void> handleException(Exception exception) {
        return Helpers.parseResponse(exception, "Internal server error");
    }

}
