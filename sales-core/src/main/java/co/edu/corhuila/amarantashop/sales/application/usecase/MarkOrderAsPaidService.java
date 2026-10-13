package co.edu.corhuila.amarantashop.sales.application.usecase;

import co.edu.corhuila.amarantashop.sales.application.port.in.MarkOrderAsPaid;
import co.edu.corhuila.amarantashop.sales.application.port.out.OrderNotFoundException;
import co.edu.corhuila.amarantashop.sales.application.port.out.OrderRepository;
import co.edu.corhuila.amarantashop.sales.domain.model.Order;
import java.util.UUID;

/** Marking as paid never touches the lines or the stock (CA-C5.3): the aggregate decides, the repository writes. */
public final class MarkOrderAsPaidService implements MarkOrderAsPaid {

    private final OrderRepository orders;

    public MarkOrderAsPaidService(OrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public Order execute(UUID orderId) {
        Order order = orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        order.markAsPaid();
        orders.update(order);
        return order;
    }
}
