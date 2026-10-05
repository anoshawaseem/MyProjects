package com.micomm.email.exception;

import com.micomm.common.dto.ApiResponse;
import com.micomm.email.client.TenantResolutionException;
import com.micomm.email.provider.UnsupportedProviderException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return ApiResponse.error(message);
    }

    @ExceptionHandler(TenantResolutionException.class)
    @ResponseStatus(HttpStatus.FAILED_DEPENDENCY)
    public ApiResponse<Void> handleTenantResolution(TenantResolutionException ex) {
        return ApiResponse.error(ex.getMessage());
    }

    @ExceptionHandler(UnsupportedProviderException.class)
    @ResponseStatus(HttpStatus.FAILED_DEPENDENCY)
    public ApiResponse<Void> handleUnsupportedProvider(UnsupportedProviderException ex) {
        return ApiResponse.error(ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleGeneric(Exception ex) {
        return ApiResponse.error("Internal error: " + ex.getMessage());
    }
}