package co.edu.corhuila.amarantashop.sales.adapter.in.http;

/** The role in a valid token may not do this: 403, before any stock is touched (CA-C2.3). */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
