package co.edu.corhuila.amarantashop.sales.application.usecase;

import co.edu.corhuila.amarantashop.sales.application.port.in.PlaceOrder;
import co.edu.corhuila.amarantashop.sales.application.port.out.IdGenerator;
import co.edu.corhuila.amarantashop.sales.application.port.out.OrderEvents;
import co.edu.corhuila.amarantashop.sales.application.port.out.OrderRepository;
import co.edu.corhuila.amarantashop.sales.application.port.out.ProductStock;
import co.edu.corhuila.amarantashop.sales.application.port.out.SaveResult;
import co.edu.corhuila.amarantashop.sales.application.port.out.StockLine;
import co.edu.corhuila.amarantashop.sales.domain.model.Order;
import co.edu.corhuila.amarantashop.sales.domain.model.OrderLine;
import co.edu.corhuila.amarantashop.sales.domain.model.OrderRequest;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * The checkout. It reserves before the order exists, because the lines need the name and the price only Catalog
 * knows (spec DP-3), but it decides first everything that needs no Catalog data, so a closed shop never touches
 * the stock.
 *
 * <p>Once the order is saved its stock is spoken for, so a failing event does not release it.
 */
public final class PlaceOrderService implements PlaceOrder {

    private static final System.Logger LOG = System.getLogger(PlaceOrderService.class.getName());

    private final OrderRepository orders;
    private final ProductStock stock;
    private final IdGenerator ids;
    private final OrderEvents events;
    private final Clock clock;

    public PlaceOrderService(OrderRepository orders, ProductStock stock, IdGenerator ids, OrderEvents events,
                             Clock clock) {
        this.orders = orders;
        this.stock = stock;
        this.ids = ids;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public PlacedOrder execute(PlaceOrderCommand command) {
        String key = command.idempotencyKey();
        OrderRequest request = command.request();

        // A retry of a request that already created an order gets that order back and touches no stock (RN-15).
        Optional<Order> existing = orders.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return new PlacedOrder(existing.get(), true);
        }

        Order.ensureCanBePlaced(request, clock); // no Catalog call if a rule that needs none is broken

        List<StockLine> reserved = stock.reserve(request.lines());

        List<OrderLine> lines = reserved.stream()
                .map(l -> new OrderLine(l.productId(), l.productName(), l.unitPrice(), l.quantity()))
                .toList();
        SaveResult saved = orders.saveNew(Order.place(ids.newId(), request, lines, clock), key);

        try {
            events.orderCreated(saved.order());
        } catch (RuntimeException eventFailure) {
            LOG.log(System.Logger.Level.WARNING, "the order exists but its event was not published", eventFailure);
        }
        return new PlacedOrder(saved.order(), !saved.created());
    }
}
