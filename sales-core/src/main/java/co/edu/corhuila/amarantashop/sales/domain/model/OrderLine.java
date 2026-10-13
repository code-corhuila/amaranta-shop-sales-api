package co.edu.corhuila.amarantashop.sales.domain.model;

import java.util.UUID;

/**
 * A line of an order. The name and the unit price are a SNAPSHOT of the purchase (rule RN-12): copied from
 * Catalog when the order is placed and never changed, so an order read a year later says what it said that
 * day. It has no meaning outside its order.
 */
public record OrderLine(UUID productId, String productName, Money unitPrice, int quantity) {

    public static final int MIN_QUANTITY = 1;
    public static final int MAX_QUANTITY = 50;

    public OrderLine {
        if (quantity < MIN_QUANTITY || quantity > MAX_QUANTITY) {
            throw new BusinessRuleViolation("INV-ORDER-ITEM-001",
                    "the quantity of a line must be between " + MIN_QUANTITY + " and " + MAX_QUANTITY);
        }
    }

    public Money subtotal() {
        return unitPrice.times(quantity);
    }
}
