package co.edu.corhuila.amarantashop.sales.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboxMessageJsonTest {

    @Test
    void wrapsTheDurableOutboxColumnsInTheSharedOrderCreatedEnvelope() throws Exception {
        UUID eventId = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID orderId = UUID.fromString("00000000-0000-4000-8000-000000000002");
        String encoded = OutboxMessageJson.envelope(new ObjectMapper(), eventId, "OrderCreated", "Order", orderId,
                "{\"customerId\":\"customer-1\",\"totalCOP\":90000}",
                "{\"correlationId\":\"trace-1\",\"userId\":\"user-1\"}",
                OffsetDateTime.parse("2026-11-14T20:00:00Z"));

        var event = new ObjectMapper().readTree(encoded);

        assertThat(event.path("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(event.path("eventType").asText()).isEqualTo("OrderCreated");
        assertThat(event.path("aggregateId").asText()).isEqualTo(orderId.toString());
        assertThat(event.path("version").asInt()).isEqualTo(1);
        assertThat(event.path("payload").path("totalCOP").asInt()).isEqualTo(90_000);
        assertThat(event.path("metadata").path("correlationId").asText()).isEqualTo("trace-1");
    }
}
