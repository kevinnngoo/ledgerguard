package com.ledgerguard.outbox;

/** Destination for outbox events (a message broker in a real deployment). */
public interface EventPublisher {

    /** Delivers the event or throws. May be called more than once for the same event. */
    void publish(OutboxEvent event);
}
