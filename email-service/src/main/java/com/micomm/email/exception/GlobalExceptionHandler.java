package com.micomm.email.exception;

import com.micomm.common.dto.ApiResponse;
import com.micomm.email.client.TenantResolutionException;
import com.micomm.email.log.EmailLogNotFoundException;
import com.micomm.email.notification.NotificationNotFoundException;
import com.micomm.email.provider.UnsupportedProviderException;
import com.micomm.email.send.Idempotency;
import com.micomm.email.send.SendNotQueuedException;
import com.micomm.email.template.EmailTemplateNotFoundException;
import com.micomm.email.tenant.TenantModules;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
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

    // Without this, the catch-all handler below would turn ClubAccessGuard denials into 500s.
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleAccessDenied(AccessDeniedException ex) {
        return ApiResponse.error(ex.getMessage());
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

    @ExceptionHandler(EmailTemplateNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleTemplateNotFound(EmailTemplateNotFoundException ex) {
        return ApiResponse.error(ex.getMessage());
    }

    @ExceptionHandler(EmailLogNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleEmailLogNotFound(EmailLogNotFoundException ex) {
        return ApiResponse.error(ex.getMessage());
    }

    @ExceptionHandler(NotificationNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNotificationNotFound(NotificationNotFoundException ex) {
        return ApiResponse.error(ex.getMessage());
    }

    @ExceptionHandler(TenantModules.ModuleNotEnabledException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleModuleNotEnabled(TenantModules.ModuleNotEnabledException ex) {
        return ApiResponse.error(ex.getMessage());
    }

    @ExceptionHandler(TenantModules.ModuleNotSupportedException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleModuleNotSupported(TenantModules.ModuleNotSupportedException ex) {
        return ApiResponse.error(ex.getMessage());
    }

    // e.g. a second template for the same module template type (uq_notification_templates_key).
    @ExceptionHandler(DuplicateKeyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleDuplicateKey(DuplicateKeyException ex) {
        return ApiResponse.error("A record with this key already exists");
    }

    @ExceptionHandler(Idempotency.IdempotencyConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleIdempotencyConflict(Idempotency.IdempotencyConflictException ex) {
        return ApiResponse.error(ex.getMessage());
    }

    @ExceptionHandler(Idempotency.IdempotencyKeyReusedException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ApiResponse<Void> handleIdempotencyKeyReused(Idempotency.IdempotencyKeyReusedException ex) {
        return ApiResponse.error(ex.getMessage());
    }

    @ExceptionHandler(SendNotQueuedException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiResponse<Void> handleSendNotQueued(SendNotQueuedException ex) {
        return ApiResponse.error(ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException ex) {
        return ApiResponse.error(ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleGeneric(Exception ex) {
        return ApiResponse.error("Internal error: " + ex.getMessage());
    }
}