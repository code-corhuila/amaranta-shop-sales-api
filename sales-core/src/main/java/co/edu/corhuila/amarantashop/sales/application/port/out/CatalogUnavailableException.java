package co.edu.corhuila.amarantashop.sales.application.port.out;

/** Catalog could not be reached or did not answer in time. Answered 503 SERVICE_UNAVAILABLE, never retried silently. */
public class CatalogUnavailableException extends RuntimeException {

    public CatalogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
