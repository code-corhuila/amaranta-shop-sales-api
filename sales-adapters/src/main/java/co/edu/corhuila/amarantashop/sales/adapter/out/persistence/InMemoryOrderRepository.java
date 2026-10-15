package co.edu.corhuila.amarantashop.sales.adapter.out.persistence;

import co.edu.corhuila.amarantashop.sales.application.port.in.ListMyOrders.OrderPage;
import co.edu.corhuila.amarantashop.sales.application.port.out.OrderRepository;
import co.edu.corhuila.amarantashop.sales.application.port.out.SaveResult;
import co.edu.corhuila.amarantashop.sales.domain.model.Order;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An order repository in memory: what the service uses when no DATABASE_URL is set, to run it without a database
 * and to test the HTTP layer. It honours the same contract as the PostgreSQL one, the idempotency key included.
 */
public class InMemoryOrderRepository implements OrderRepository {

    private final Map<UUID, Order> byId = new ConcurrentHashMap<>();
    private final Map<String, UUID> byKey = new ConcurrentHashMap<>();

    @Override
    public Optional<Order> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(byKey.get(idempotencyKey)).map(byId::get);
    }

    @Override
    public synchronized SaveResult saveNew(Order order, String idempotencyKey) {
        UUID existing = byKey.get(idempotencyKey);
        if (existing != null) {
            return new SaveResult(byId.get(existing), false);
        }
        byId.put(order.id(), order);
        byKey.put(idempotencyKey, order.id());
        return new SaveResult(order, true);
    }

    @Override
    public Optional<Order> findById(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public void update(Order order) {
        byId.put(order.id(), order);
    }

    @Override
    public OrderPage findByCustomer(UUID customerId, int page, int limit) {
        List<Order> mine = byId.values().stream().filter(o -> o.customerId().equals(customerId))
                .sorted(Comparator.comparing(Order::createdAt).reversed()).toList();
        int from = Math.min((page - 1) * limit, mine.size());
        return new OrderPage(mine.subList(from, Math.min(from + limit, mine.size())), page, limit, mine.size());
    }

    public Collection<Order> all() {
        return List.copyOf(byId.values());
    }
}
