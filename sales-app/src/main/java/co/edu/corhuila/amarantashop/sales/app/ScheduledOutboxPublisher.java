package co.edu.corhuila.amarantashop.sales.app;

import co.edu.corhuila.amarantashop.sales.adapter.out.persistence.JdbcOutboxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** The timer is enabled only when SALES_OUTBOX_PUBLISHER_ENABLED=true; local HTTP runs need no broker. */
final class ScheduledOutboxPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduledOutboxPublisher.class);
    private final JdbcOutboxPublisher publisher;
    private final int batchSize;

    ScheduledOutboxPublisher(JdbcOutboxPublisher publisher, int batchSize) {
        this.publisher = publisher;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${sales.outbox.publisher.delay-ms:5000}")
    void publish() {
        int published = publisher.publishPending(batchSize);
        if (published > 0) {
            LOG.info("outbox published count={}", published);
        }
    }
}
