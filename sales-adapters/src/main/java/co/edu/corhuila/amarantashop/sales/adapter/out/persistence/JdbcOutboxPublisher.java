package co.edu.corhuila.amarantashop.sales.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drains Sales' transactional outbox to RabbitMQ. Rows are locked while one small batch is published, which keeps
 * two replicas from publishing the same row concurrently; if the process dies after broker acceptance and before
 * commit, the row can be delivered again. Consumers therefore use the event id as their idempotency key.
 */
public final class JdbcOutboxPublisher {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final RabbitTemplate broker;
    private final ObjectMapper json;
    private final String exchange;

    public JdbcOutboxPublisher(DataSource dataSource, RabbitTemplate broker, ObjectMapper json, String exchange) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.broker = broker;
        this.json = json;
        this.exchange = exchange;
    }

    /** Publishes at most {@code batchSize} pending or failed messages and returns how many reached the broker. */
    public int publishPending(int batchSize) {
        Integer published = transaction.execute(status -> {
            List<Row> rows = jdbc.query("""
                    SELECT id, event_type, aggregate_type, aggregate_id, payload_json::text, metadata_json::text, created_at
                    FROM sales.outbox_messages
                    WHERE status IN ('PENDING', 'FAILED')
                    ORDER BY created_at, id
                    LIMIT ? FOR UPDATE SKIP LOCKED""", (rs, row) -> row(rs), batchSize);
            int delivered = 0;
            for (Row row : rows) {
                try {
                    broker.convertAndSend(exchange, "sales.order.created", message(row));
                    jdbc.update("""
                            UPDATE sales.outbox_messages
                            SET status = 'PUBLISHED', published_at = now(), attempts = attempts + 1, last_error = NULL
                            WHERE id = ?""", row.id());
                    delivered++;
                } catch (RuntimeException | JsonProcessingException failure) {
                    jdbc.update("""
                            UPDATE sales.outbox_messages
                            SET status = 'FAILED', attempts = attempts + 1, last_error = ?
                            WHERE id = ?""", failure.getMessage(), row.id());
                }
            }
            return delivered;
        });
        return published == null ? 0 : published;
    }

    private String message(Row row) throws JsonProcessingException {
        JsonNode payload = json.readTree(row.payloadJson());
        JsonNode metadata = json.readTree(row.metadataJson());
        var envelope = json.createObjectNode();
        envelope.put("eventId", row.id().toString());
        envelope.put("eventType", row.eventType());
        envelope.put("aggregateId", row.aggregateId().toString());
        envelope.put("aggregateType", row.aggregateType());
        envelope.put("occurredAt", row.createdAt().toInstant().toString());
        envelope.put("version", 1);
        envelope.set("payload", payload);
        envelope.set("metadata", metadata);
        return json.writeValueAsString(envelope);
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("event_type"), rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", UUID.class), rs.getString(5), rs.getString(6),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private record Row(UUID id, String eventType, String aggregateType, UUID aggregateId, String payloadJson,
                       String metadataJson, OffsetDateTime createdAt) { }
}
