package dev.linkledger.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiErrorHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> expected(ApiException ex, HttpServletRequest request) {
        return problem(ex.status(), ex.getMessage(), request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            ConstraintViolationException.class})
    ResponseEntity<ProblemDetail> badRequest(Exception ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request. Check URL, alias, expiration, JSON fields and field lengths.", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> notFound(Exception ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", request);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ProblemDetail> unavailable(Exception ex, HttpServletRequest request) {
        LOG.error("event=database_unavailable requestId={} category={}", request.getAttribute("requestId"), ex.getClass().getSimpleName());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Storage is temporarily unavailable; please retry", request);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail, HttpServletRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setProperty("requestId", request.getAttribute("requestId"));
        return ResponseEntity.status(status).body(body);
    }
}
