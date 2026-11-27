package com.ledgerguard.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "ledgerguard.outbox", name = "enabled", havingValue = "true")
public class OutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);

    private final OutboxPublisher publisher;

    public OutboxScheduler(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${ledgerguard.outbox.poll-interval-ms:1000}")
    public void run() {
        try {
            publisher.publishPending();
        } catch (RuntimeException e) {
            // Never let a publisher failure affect financial posting; just log and try next tick.
            log.error("Outbox publishing run failed", e);
        }
    }
}
