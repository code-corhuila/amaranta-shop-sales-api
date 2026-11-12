package co.edu.corhuila.amarantashop.sales.application.port.out;

import co.edu.corhuila.amarantashop.sales.domain.model.Order;

/** {@code created} is false when the idempotency key already belonged to another order, which is then returned. */
public record SaveResult(Order order, boolean created) {
}
