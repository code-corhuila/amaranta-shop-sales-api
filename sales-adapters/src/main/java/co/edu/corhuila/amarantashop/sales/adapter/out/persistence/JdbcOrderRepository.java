package co.edu.corhuila.amarantashop.sales.adapter.out.persistence;

import co.edu.corhuila.amarantashop.sales.application.port.in.ListMyOrders.OrderPage;
import co.edu.corhuila.amarantashop.sales.application.port.out.OrderRepository;
import co.edu.corhuila.amarantashop.sales.application.port.out.SaveResult;
import co.edu.corhuila.amarantashop.sales.domain.model.Channel;
import co.edu.corhuila.amarantashop.sales.domain.model.FulfillmentMethod;
import co.edu.corhuila.amarantashop.sales.domain.model.Money;
import co.edu.corhuila.amarantashop.sales.domain.model.Order;
import co.edu.corhuila.amarantashop.sales.domain.model.OrderLine;
import co.edu.corhuila.amarantashop.sales.domain.model.OrderStatus;
import co.edu.corhuila.amarantashop.sales.domain.model.PaymentMethod;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The orders of the schema {@code sales}, written for the tables of amaranta-shop-sales-db. It connects as
 * {@code sales_app} and never migrates anything (norm 5.2.1). The order, its lines and its idempotency key are
 * written in ONE transaction, so a failure leaves none of the three (RN-15).
 */
public class JdbcOrderRepository implements OrderRepository {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcOrderRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public Optional<Order> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.query("SELECT order_id FROM sales.idempotency_key WHERE key = ?", (rs, i) -> rs.getObject(1, UUID.class),
                idempotencyKey).stream().findFirst().flatMap(this::findById);
    }

    @Override
    public SaveResult saveNew(Order order, String idempotencyKey) {
        Boolean created = transaction.execute(status -> {
            insertOrder(order);
            insertLines(order);
            int keyed = jdbc.update("INSERT INTO sales.idempotency_key (key, order_id) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
                    idempotencyKey, order.id());
            if (keyed == 0) {
                status.setRollbackOnly(); // the key was already used: the order just written disappears
            }
            return keyed == 1;
        });
        if (Boolean.TRUE.equals(created)) {
            return new SaveResult(order, true);
        }
        return new SaveResult(findByIdempotencyKey(idempotencyKey).orElseThrow(), false);
    }

    @Override
    public Optional<Order> findById(UUID id) {
        List<Row> rows = jdbc.query("SELECT * FROM sales.orders WHERE id = ?", (rs, i) -> row(rs), id);
        return rows.stream().findFirst().map(r -> r.toOrder(linesOf(List.of(id)).getOrDefault(id, List.of())));
    }

    @Override
    public void update(Order order) {
        jdbc.update("UPDATE sales.orders SET status = ?, updated_at = now() WHERE id = ?", order.status().name(), order.id());
    }

    @Override
    public OrderPage findByCustomer(UUID customerId, int page, int limit) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM sales.orders WHERE customer_id = ?", Long.class, customerId);
        List<Row> rows = jdbc.query("SELECT * FROM sales.orders WHERE customer_id = ? ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
                (rs, i) -> row(rs), customerId, limit, (long) (page - 1) * limit);
        var lines = linesOf(rows.stream().map(r -> r.id).toList());
        List<Order> orders = new ArrayList<>();
        rows.forEach(r -> orders.add(r.toOrder(lines.getOrDefault(r.id, List.of()))));
        return new OrderPage(orders, page, limit, total == null ? 0 : total);
    }

    private void insertOrder(Order o) {
        jdbc.update("""
                INSERT INTO sales.orders (id, customer_id, channel, created_by, total_amount, status, fulfillment_method,
                                          payment_method, contact_name, contact_phone, delivery_address, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                o.id(), o.customerId(), o.channel().name(), o.createdBy(), Math.toIntExact(o.total().amount()),
                o.status().name(), o.fulfillmentMethod().name(), o.paymentMethod().name(), o.contactName(),
                o.contactPhone(), o.deliveryAddress().orElse(null), utc(o), utc(o));
    }

    private void insertLines(Order o) {
        jdbc.batchUpdate("""
                INSERT INTO sales.order_items (order_id, product_id, product_name, unit_price_amount, quantity, subtotal_amount)
                VALUES (?, ?, ?, ?, ?, ?)""", o.lines(), o.lines().size(), (ps, line) -> {
            ps.setObject(1, o.id());
            ps.setObject(2, line.productId());
            ps.setString(3, line.productName());
            ps.setInt(4, Math.toIntExact(line.unitPrice().amount()));
            ps.setInt(5, line.quantity());
            ps.setInt(6, Math.toIntExact(line.subtotal().amount()));
        });
    }

    private static OffsetDateTime utc(Order o) {
        return o.createdAt().atOffset(ZoneOffset.UTC);
    }

    /** The lines of several orders in one query, in a stable order (the schema keeps no line number). */
    private java.util.Map<UUID, List<OrderLine>> linesOf(List<UUID> orderIds) {
        var byOrder = new java.util.HashMap<UUID, List<OrderLine>>();
        if (orderIds.isEmpty()) {
            return byOrder;
        }
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT order_id, product_id, product_name, unit_price_amount, quantity FROM sales.order_items
                    WHERE order_id = ANY (?) ORDER BY product_name, product_id""");
            Array ids = con.createArrayOf("uuid", orderIds.toArray());
            ps.setArray(1, ids);
            return ps;
        }, rs -> {
            byOrder.computeIfAbsent(rs.getObject("order_id", UUID.class), k -> new ArrayList<>())
                    .add(new OrderLine(rs.getObject("product_id", UUID.class), rs.getString("product_name"),
                            Money.pesos(rs.getInt("unit_price_amount")), rs.getInt("quantity")));
        });
        return byOrder;
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
                rs.getObject("created_by", UUID.class), Channel.valueOf(rs.getString("channel")),
                OrderStatus.valueOf(rs.getString("status")), rs.getObject("created_at", OffsetDateTime.class),
                FulfillmentMethod.valueOf(rs.getString("fulfillment_method")), PaymentMethod.valueOf(rs.getString("payment_method")),
                rs.getString("contact_name"), rs.getString("contact_phone"), rs.getString("delivery_address"));
    }

    private record Row(UUID id, UUID customerId, UUID createdBy, Channel channel, OrderStatus status,
                       OffsetDateTime createdAt, FulfillmentMethod fulfillment, PaymentMethod payment,
                       String contactName, String contactPhone, String deliveryAddress) {

        Order toOrder(List<OrderLine> lines) {
            return Order.reconstitute(id, customerId, createdBy, channel, lines, status, createdAt.toInstant(),
                    fulfillment, payment, contactName, contactPhone, deliveryAddress);
        }
    }
}
