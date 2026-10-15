package co.edu.corhuila.amarantashop.sales.adapter.in.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.slf4j.MDC;

/**
 * The one error envelope of every route: {@code error} is the code clients branch on, {@code details} one entry
 * per invalid field (or the broken rule, under the field "rule", spec OD-2), {@code traceId} finds the log line.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(String error, String message, List<FieldError> details, String traceId) {

    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String INVALID_STATUS_TRANSITION = "INVALID_STATUS_TRANSITION";
    public static final String BUSINESS_RULE_VIOLATION = "BUSINESS_RULE_VIOLATION";
    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    public record FieldError(String field, String message) {
    }

    public static ApiError of(String code, String message, List<FieldError> details) {
        return new ApiError(code, message, details, MDC.get(CorrelationFilter.MDC_KEY));
    }

    public static ApiError of(String code, String message) {
        return of(code, message, List.of());
    }

    /** Raised when the SHAPE of the input is wrong: always a 400 with one entry per field. */
    public static class ValidationException extends RuntimeException {
        private final transient List<FieldError> details;

        public ValidationException(String message, List<FieldError> details) {
            super(message);
            this.details = List.copyOf(details);
        }

        public List<FieldError> details() {
            return details;
        }
    }
}
