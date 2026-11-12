package co.edu.corhuila.amarantashop.sales.domain.model;

/** Recorded as an intention: payment is not processed here, a person marks the order as paid (spec section 6). */
public enum PaymentMethod {
    CASH,
    CARD,
    TRANSFER
}
