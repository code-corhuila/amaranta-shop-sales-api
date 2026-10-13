package co.edu.corhuila.amarantashop.sales.domain.model;

/**
 * The statuses exist in the schema, but only {@code PENDING -> PAID} is reachable: cancelling and fulfilling
 * an order are out of scope for this cut (spec section 6).
 */
public enum OrderStatus {
    PENDING,
    PAID,
    CANCELLED,
    FULFILLED
}
