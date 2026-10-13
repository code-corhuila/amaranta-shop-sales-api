package co.edu.corhuila.amarantashop.sales.application.port.in;

import co.edu.corhuila.amarantashop.sales.domain.model.Order;
import java.util.UUID;

/** A person confirms the payment: the receptionist only (spec DP-5). The role is checked by the HTTP adapter. */
public interface MarkOrderAsPaid {

    /** @throws co.edu.corhuila.amarantashop.sales.application.port.out.OrderNotFoundException if the order does not exist */
    Order execute(UUID orderId);
}
