package com.ledgerguard.outbox;

import java.util.UUID;

public record OutboxEvent(UUID id, String aggregateType, UUID aggregateId, String eventType, String payload, int attempts) {}
