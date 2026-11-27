package com.ledgerguard.outbox;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * In-process stand-in for a message broker plus one idempotent downstream consumer.
 *
 * <p>The broker can be switched "down" to simulate an outage. The consumer de-duplicates by
 * event ID, which is what at-least-once delivery requires of every consumer.
 * State is in memory only; a real consumer would persist processed event IDs transactionally.
 */
@Component
public class SimulatedBroker implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SimulatedBroker.class);

    private final AtomicBoolean available = new AtomicBoolean(true);
    private final Map<UUID, Integer> deliveries = new ConcurrentHashMap<>();
    private final AtomicLong duplicatesIgnored = new AtomicLong();

    @Override
    public void publish(OutboxEvent event) {
        if (!available.get()) {
            throw new BrokerUnavailableException("Simulated broker is unavailable");
        }
        int count = deliveries.merge(event.id(), 1, Integer::sum);
        if (count > 1) {
            duplicatesIgnored.incrementAndGet();
            log.info("Consumer ignored duplicate delivery of event {} ({})", event.id(), event.eventType());
        } else {
            log.info("Published event {} {} for {} {}", event.id(), event.eventType(), event.aggregateType(), event.aggregateId());
        }
    }

    public void setAvailable(boolean value) {
        available.set(value);
    }

    public boolean isAvailable() {
        return available.get();
    }

    public int deliveryCount(UUID eventId) {
        return deliveries.getOrDefault(eventId, 0);
    }

    public long duplicatesIgnored() {
        return duplicatesIgnored.get();
    }

    public static class BrokerUnavailableException extends RuntimeException {
        public BrokerUnavailableException(String message) {
            super(message);
        }
    }
}
