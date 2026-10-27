package com.actify.invoice.exception;

import com.actify.invoice.dto.ErrorResponse;
import com.actify.invoice.dto.FieldErrorDto;
import com.fasterxml.jackson.databind.JsonMappingException;
import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Turns exceptions into the single ErrorResponse JSON shape.
 * Our own exception messages are written to be safe for clients; anything unexpected
 * gets a generic message so stack traces and internals never leak out.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidInvoiceException.class)
    public ResponseEntity<ErrorResponse> handleInvalidInvoice(InvalidInvoiceException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Invalid invoice file", ex.getMessage(), request);
    }

    @ExceptionHandler(InvoiceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(InvoiceNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "Invoice not found", ex.getMessage(), request);
    }

    // fieldErrors is empty for the extraction flow, so its 422 body is unchanged.
    @ExceptionHandler(InvoiceValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(InvoiceValidationException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "Invoice validation failed", ex.getMessage(), request,
                ex.getFieldErrors());
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(InvalidRequestException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Invalid request", ex.getMessage(), request, ex.getFieldErrors());
    }

    @ExceptionHandler(VersionConflictException.class)
    public ResponseEntity<ErrorResponse> handleVersionConflict(VersionConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "Version conflict", ex.getMessage(), request);
    }

    @ExceptionHandler(InvoiceNotEditableException.class)
    public ResponseEntity<ErrorResponse> handleNotEditable(InvoiceNotEditableException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "Invoice not editable", ex.getMessage(), request);
    }

    @ExceptionHandler(VersionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleVersionNotFound(VersionNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "Invoice version not found", ex.getMessage(), request);
    }

    // One answer for an unknown id, a malformed id and a missing file, so ids cannot be probed.
    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleDocumentNotFound(DocumentNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "Document not found", ex.getMessage(), request);
    }

    // The message never contains a path; details go to the server log only.
    @ExceptionHandler(DocumentStorageException.class)
    public ResponseEntity<ErrorResponse> handleDocumentStorage(DocumentStorageException ex, HttpServletRequest request) {
        log.error("Document storage error on {}", request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", ex.getMessage(), request);
    }

    // Broken JSON, or a value of the wrong type (e.g. "abc" for an amount). Names the field when Jackson knows it.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        List<FieldErrorDto> fieldErrors = Collections.emptyList();
        if (ex.getCause() instanceof JsonMappingException) {
            String field = fieldPath((JsonMappingException) ex.getCause());
            if (!field.isEmpty()) {
                fieldErrors = Collections.singletonList(new FieldErrorDto(field, field + " has an invalid value"));
            }
        }
        return build(HttpStatus.BAD_REQUEST, "Bad request", "Malformed JSON request body", request, fieldErrors);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type",
                "Unsupported Content-Type for this endpoint.", request);
    }

    // More specific than InvoiceExtractionException, so Spring picks this one: the call was never made.
    @ExceptionHandler(QuotaUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleQuota(QuotaUnavailableException ex, HttpServletRequest request) {
        return build(HttpStatus.TOO_MANY_REQUESTS, "Too many requests", ex.getMessage(), request);
    }

    @ExceptionHandler(InvoiceExtractionException.class)
    public ResponseEntity<ErrorResponse> handleExtraction(InvoiceExtractionException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_GATEWAY, "Invoice extraction failed", ex.getMessage(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Invalid invoice file", "File is too large. Maximum size is 10 MB.", request);
    }

    // Missing "file" part, wrong content type (not multipart), or a non-numeric id in the URL.
    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class,
            MultipartException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Bad request",
                "Invalid request. Send a multipart/form-data request with a PDF in the 'file' field, "
                        + "and use a numeric invoice id.", request);
    }

    // ---------------------------------------------------------------- login and users

    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<ErrorResponse> handleLoginFailed(AuthenticationFailedException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "Unauthorized", ex.getMessage(), request);
    }

    @ExceptionHandler(LoginLockedException.class)
    public ResponseEntity<ErrorResponse> handleLoginLocked(LoginLockedException ex, HttpServletRequest request) {
        return build(HttpStatus.TOO_MANY_REQUESTS, "Too many requests", ex.getMessage(), request);
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "User not found", ex.getMessage(), request);
    }

    @ExceptionHandler(UserConflictException.class)
    public ResponseEntity<ErrorResponse> handleUserConflict(UserConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "User conflict", ex.getMessage(), request);
    }

    // ---------------------------------------------------------------- batch workflow

    @ExceptionHandler(BatchNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleBatchNotFound(BatchNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "Batch not found", ex.getMessage(), request);
    }

    @ExceptionHandler(WorkflowConflictException.class)
    public ResponseEntity<ErrorResponse> handleWorkflowConflict(WorkflowConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "Workflow conflict", ex.getMessage(), request);
    }

    // A service-level role check failed. Without this handler the catch-all below would turn it into a 500.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "Forbidden", "You do not have permission to do this.", request);
    }

    // The browser closed the connection while we were still sending (e.g. the reviewer switched invoices while a PDF
    // was loading). Not a server error, and nobody is left to receive a response.
    @ExceptionHandler(ClientAbortException.class)
    public void handleClientAbort(ClientAbortException ex, HttpServletRequest request) {
        log.debug("Client closed the connection during {} {}", request.getMethod(), request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        // Full details go to the server log only, never to the client. The client gets only the reference, so the
        // matching log line can be found ("grep <errorId>").
        String errorId = UUID.randomUUID().toString().substring(0, 8);
        log.error("Unexpected error {} on {} {}", errorId, request.getMethod(), request.getRequestURI(), ex);
        ErrorResponse body = new ErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal server error",
                "An unexpected error occurred.", request.getRequestURI()).withErrorId(errorId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String error, String message,
                                                HttpServletRequest request) {
        return build(status, error, message, request, null);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String error, String message,
                                                HttpServletRequest request, List<FieldErrorDto> fieldErrors) {
        ErrorResponse body = new ErrorResponse(status.value(), error, message, request.getRequestURI(), fieldErrors);
        return ResponseEntity.status(status).body(body);
    }

    // "items[0].quantity" style path, the same format the validator uses.
    private String fieldPath(JsonMappingException ex) {
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference ref : ex.getPath()) {
            if (ref.getFieldName() != null) {
                if (path.length() > 0) {
                    path.append('.');
                }
                path.append(ref.getFieldName());
            } else if (ref.getIndex() >= 0) {
                path.append('[').append(ref.getIndex()).append(']');
            }
        }
        return path.toString();
    }
}
