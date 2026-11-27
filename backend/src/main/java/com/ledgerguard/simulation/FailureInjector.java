package com.ledgerguard.simulation;

import com.ledgerguard.common.LedgerGuardProperties;
import org.springframework.stereotype.Component;

/**
 * Controlled failure injection for the rollback simulation and tests.
 *
 * <p>Arming is per-thread (so concurrent real requests are never affected) and is refused unless
 * {@code ledgerguard.simulation.enabled=true}. In a normal deployment, {@link #checkpoint} is a no-op.
 */
@Component
public class FailureInjector {

    public enum FailurePoint {
        AFTER_FIRST_ENTRY
    }

    private final ThreadLocal<FailurePoint> armed = new ThreadLocal<>();
    private final boolean enabled;

    public FailureInjector(LedgerGuardProperties properties) {
        this.enabled = properties.simulation() != null && properties.simulation().enabled();
    }

    public void armForCurrentThread(FailurePoint point) {
        if (!enabled) {
            throw new IllegalStateException("Failure injection is only available when simulation is enabled");
        }
        armed.set(point);
    }

    public void disarm() {
        armed.remove();
    }

    public void checkpoint(FailurePoint point) {
        if (enabled && armed.get() == point) {
            armed.remove();
            throw new InjectedFailureException("Injected failure at " + point);
        }
    }

    public static class InjectedFailureException extends RuntimeException {
        public InjectedFailureException(String message) {
            super(message);
        }
    }
}
