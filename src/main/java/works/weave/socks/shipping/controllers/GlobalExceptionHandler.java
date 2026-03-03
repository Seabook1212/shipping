package works.weave.socks.shipping.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(basePackages = "works.weave.socks.shipping.controllers")
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class
    })
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception exception, HttpServletRequest request) {
        logger.warn(
                "Request rejected operation=http_request path={} method={} status=400 error_class={} detail={}",
                request.getRequestURI(),
                request.getMethod(),
                exception.getClass().getSimpleName(),
                extractDetail(exception)
        );
        return buildResponse(HttpStatus.BAD_REQUEST, "invalid request");
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientAbort(AsyncRequestNotUsableException exception, HttpServletRequest request) {
        logger.warn(
                "Client disconnected operation=http_request path={} method={} error_class=client_abort detail={}",
                request.getRequestURI(),
                request.getMethod(),
                exception.getClass().getSimpleName()
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(
            NoResourceFoundException exception, HttpServletRequest request) {
        logger.warn(
                "Request not found operation=http_request path={} method={} status=404 error_class={}",
                request.getRequestURI(),
                request.getMethod(),
                exception.getClass().getSimpleName()
        );
        return buildResponse(HttpStatus.NOT_FOUND, "not found");
    }

    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<Map<String, Object>> handleErrorResponse(
            ErrorResponseException exception, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        if (status.is4xxClientError()) {
            logger.warn(
                    "Request failed operation=http_request path={} method={} status={} error_class={}",
                    request.getRequestURI(),
                    request.getMethod(),
                    status.value(),
                    exception.getClass().getSimpleName()
            );
        } else {
            logger.error(
                    "Server error response operation=http_request path={} method={} status={} error_class={}",
                    request.getRequestURI(),
                    request.getMethod(),
                    status.value(),
                    exception.getClass().getSimpleName(),
                    exception
            );
        }
        return buildResponse(status, status.is4xxClientError() ? "request failed" : "internal error");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnhandled(Exception exception, HttpServletRequest request) {
        logger.error(
                "Unhandled server error operation=http_request path={} method={} status=500 error_class={}",
                request.getRequestURI(),
                request.getMethod(),
                exception.getClass().getSimpleName(),
                exception
        );
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "internal error");
    }

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }

    private String extractDetail(Exception exception) {
        if (exception instanceof MethodArgumentNotValidException methodArgumentNotValidException) {
            return methodArgumentNotValidException.getBindingResult().getFieldErrors().stream()
                    .findFirst()
                    .map(error -> error.getField() + ":" + error.getDefaultMessage())
                    .orElse("validation_failed");
        }
        return exception.getMessage() != null ? exception.getMessage() : "n/a";
    }
}
