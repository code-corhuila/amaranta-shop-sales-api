package co.edu.corhuila.amarantashop.sales.adapter.in.http;

import co.edu.corhuila.amarantashop.sales.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.amarantashop.sales.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.amarantashop.sales.application.port.out.CatalogUnavailableException;
import co.edu.corhuila.amarantashop.sales.application.port.out.OrderNotFoundException;
import co.edu.corhuila.amarantashop.sales.domain.model.BusinessRuleViolation;
import co.edu.corhuila.amarantashop.sales.domain.model.InvalidStatusTransition;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The ONLY place where an error becomes a status code: the domain raises typed errors and does not know what a
 * 422 is. The detail of an unexpected error goes to the log only, never to the client.
 */
@RestControllerAdvice
public class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(ValidationException.class)
    ResponseEntity<ApiError> validation(ValidationException e) {
        return respond(HttpStatus.BAD_REQUEST, ApiError.of(ApiError.VALIDATION_ERROR, e.getMessage(), e.details()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return respond(HttpStatus.BAD_REQUEST, ApiError.of(ApiError.VALIDATION_ERROR, "the body is not valid JSON"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> mediaType(HttpMediaTypeNotSupportedException e) {
        return respond(HttpStatus.BAD_REQUEST, ApiError.of(ApiError.VALIDATION_ERROR, "the content type must be application/json"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> badArgument(MethodArgumentTypeMismatchException e) {
        return respond(HttpStatus.BAD_REQUEST, ApiError.of(ApiError.VALIDATION_ERROR, "the request has invalid fields",
                List.of(new FieldError(e.getName(), "has an invalid format"))));
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<ApiError> forbidden(ForbiddenException e) {
        return respond(HttpStatus.FORBIDDEN, ApiError.of(ApiError.FORBIDDEN, e.getMessage()));
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ResponseEntity<ApiError> notFound(OrderNotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, ApiError.of(ApiError.NOT_FOUND, "the order does not exist"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noRoute(NoResourceFoundException e) {
        return respond(HttpStatus.NOT_FOUND, ApiError.of(ApiError.NOT_FOUND, "no such route"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException e) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, ApiError.of(ApiError.NOT_FOUND, "method not allowed on this route"));
    }

    @ExceptionHandler(InvalidStatusTransition.class)
    ResponseEntity<ApiError> invalidTransition(InvalidStatusTransition e) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, ApiError.of(ApiError.INVALID_STATUS_TRANSITION, e.getMessage(),
                List.of(new FieldError("rule", e.ruleId()))));
    }

    @ExceptionHandler(BusinessRuleViolation.class)
    ResponseEntity<ApiError> businessRule(BusinessRuleViolation e) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, ApiError.of(ApiError.BUSINESS_RULE_VIOLATION, e.getMessage(),
                List.of(new FieldError("rule", e.ruleId()))));
    }

    @ExceptionHandler(CatalogUnavailableException.class)
    ResponseEntity<ApiError> catalogUnavailable(CatalogUnavailableException e) {
        log.error("catalog is unavailable", e);
        return respond(HttpStatus.SERVICE_UNAVAILABLE,
                ApiError.of(ApiError.SERVICE_UNAVAILABLE, "the stock service is not available right now, try again"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("unhandled error", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ApiError.of(ApiError.INTERNAL_ERROR, "unexpected error"));
    }

    private static ResponseEntity<ApiError> respond(HttpStatus status, ApiError body) {
        return ResponseEntity.status(status).body(body);
    }
}
