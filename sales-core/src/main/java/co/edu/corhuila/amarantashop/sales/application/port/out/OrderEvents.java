package co.edu.corhuila.amarantashop.sales.application.port.out;

import co.edu.corhuila.amarantashop.sales.domain.model.Order;

/** An optional in-process observer. Cross-service delivery is durable in the Sales outbox, never this callback. */
public interface OrderEvents {

    void orderCreated(Order order);
}
