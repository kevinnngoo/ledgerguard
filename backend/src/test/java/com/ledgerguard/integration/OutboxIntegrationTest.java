package com.ledgerguard.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerguard.account.AccountView;
import com.ledgerguard.outbox.OutboxEvent;
import com.ledgerguard.outbox.OutboxPublisher;
import com.ledgerguard.outbox.OutboxPublisher.EventStatus;
import com.ledgerguard.outbox.SimulatedBroker;
import com.ledgerguard.support.IntegrationTestBase;
import com.ledgerguard.transaction.TransactionDtos.DepositRequest;
import com.ledgerguard.transaction.TransactionDtos.OperationResult;
import com.ledgerguard.transaction.TransactionStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Financial posting must not depend on event delivery; events must survive broker outages. */
class OutboxIntegrationTest extends IntegrationTestBase {

    @Autowired OutboxPublisher publisher;
    @Autowired SimulatedBroker broker;

    @AfterEach
    void restoreBroker() {
        broker.setAvailable(true);
    }

    @Test
    void postingWritesAnEventInTheSameTransactionAndThePublisherDeliversIt() {
        UUID txId = deposit("Outbox happy path");
        List<EventStatus> events = publisher.eventsForAggregate(txId);
        assertThat(events).extracting(EventStatus::eventType).containsExactly("TransactionPosted");
        assertThat(events.get(0).published()).isFalse();

        publisher.publishPendingFor(txId);

        assertThat(publisher.eventsForAggregate(txId)).allSatisfy(e -> assertThat(e.published()).isTrue());
        assertThat(broker.deliveryCount(events.get(0).id())).isEqualTo(1);
    }

    @Test
    void brokerOutageDoesNotAffectPostingAndEventsRecover() {
        drain(); // clear the backlog left by other test classes so this event is in the next batch
        broker.setAvailable(false);
        UUID txId = deposit("Outbox outage");
        assertThat(transactionService.get(txId).transaction().status()).isEqualTo(TransactionStatus.POSTED);

        publisher.publishPendingFor(txId);
        EventStatus pending = publisher.eventsForAggregate(txId).get(0);
        assertThat(pending.published()).isFalse();
        assertThat(pending.attempts()).isEqualTo(1);
        assertThat(pending.lastError()).contains("unavailable");

        broker.setAvailable(true);
        publisher.retryNow(txId);
        publisher.publishPendingFor(txId);

        EventStatus recovered = publisher.eventsForAggregate(txId).get(0);
        assertThat(recovered.published()).isTrue();
        assertThat(recovered.attempts()).isEqualTo(2);
        assertLedgerHealthy();
    }

    @Test
    void consumerIgnoresDuplicateDeliveries() {
        UUID txId = deposit("Outbox duplicate");
        publisher.publishPendingFor(txId);
        EventStatus event = publisher.eventsForAggregate(txId).get(0);
        long ignoredBefore = broker.duplicatesIgnored();

        // At-least-once delivery: simulate the publisher re-sending after a lost acknowledgement.
        broker.publish(new OutboxEvent(event.id(), "Transaction", txId, event.eventType(), "{}", 1));

        assertThat(broker.deliveryCount(event.id())).isEqualTo(2);
        assertThat(broker.duplicatesIgnored()).isEqualTo(ignoredBefore + 1);
    }

    private UUID deposit(String name) {
        AccountView account = newAccount(name);
        OperationResult result = transactionService.deposit(
                new DepositRequest(account.id(), usd("42"), null, null), key(), "operator");
        return result.body().id();
    }

    private void drain() {
        for (int i = 0; i < 100 && publisher.publishPending().published() > 0; i++) {
            // keep publishing until the backlog of eligible events is empty
        }
    }
}
