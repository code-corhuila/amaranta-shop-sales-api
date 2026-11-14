package co.edu.corhuila.amarantashop.sales.application.port.out;

import java.util.UUID;

/** There is no order with that id. Answered 404 NOT_FOUND. */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(UUID orderId) {
        super("order " + orderId + " was not found");
    }
}
