package co.edu.corhuila.amarantashop.sales.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Maps a durable outbox row to the platform event envelope sent over RabbitMQ. */
final class OutboxMessageJson {

    private OutboxMessageJson() { }

    static String envelope(ObjectMapper json, UUID id, String eventType, String aggregateType, UUID aggregateId,
                           String payloadJson, String metadataJson, OffsetDateTime createdAt)
            throws JsonProcessingException {
        JsonNode payload = json.readTree(payloadJson);
        JsonNode metadata = json.readTree(metadataJson);
        var envelope = json.createObjectNode();
        envelope.put("eventId", id.toString());
        envelope.put("eventType", eventType);
        envelope.put("aggregateId", aggregateId.toString());
        envelope.put("aggregateType", aggregateType);
        envelope.put("occurredAt", createdAt.toInstant().toString());
        envelope.put("version", 1);
        envelope.set("payload", payload);
        envelope.set("metadata", metadata);
        return json.writeValueAsString(envelope);
    }
}
