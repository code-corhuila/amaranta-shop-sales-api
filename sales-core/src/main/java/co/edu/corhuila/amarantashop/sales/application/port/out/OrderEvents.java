package co.edu.corhuila.amarantashop.sales.application.port.out;

import co.edu.corhuila.amarantashop.sales.domain.model.Order;

/** The announcement that an order exists. Today a log line; the broker arrives with the worker (spec section 6). */
public interface OrderEvents {

    void orderCreated(Order order);
}
