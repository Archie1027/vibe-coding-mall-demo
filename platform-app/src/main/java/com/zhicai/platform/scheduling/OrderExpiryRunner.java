package com.zhicai.platform.scheduling;

import com.zhicai.platform.trade.OrderClosingService;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A bounded scan; every order runs in its own lease-fenced transaction. */
public final class OrderExpiryRunner {
    private static final Logger log = LoggerFactory.getLogger(OrderExpiryRunner.class);
    private static final String TASK = "order-expiry";
    private final DatabaseTaskLease leases;
    private final OrderClosingService closing;
    private final Clock clock;
    private OrderClosingService.Cursor cursor;

    public OrderExpiryRunner(DatabaseTaskLease leases, OrderClosingService closing, Clock clock) {
        this.leases = Objects.requireNonNull(leases);
        this.closing = Objects.requireNonNull(closing);
        this.clock = Objects.requireNonNull(clock);
    }

    public synchronized void runOnce() {
        var acquired = leases.acquire(TASK, Duration.ofSeconds(60));
        if (acquired.isEmpty()) return;
        var lease = acquired.get();
        try {
            var cutoff = clock.instant();
            for (int page = 0; page < 10; page++) {
                var candidates = closing.expiredCandidates(cutoff, cursor, 100);
                if (candidates.isEmpty()) { cursor = null; return; }
                for (var candidate : candidates) {
                    try {
                        if (!leases.execute(lease, () -> closing.expire(candidate.id(), UUID.randomUUID().toString()))) return;
                    } catch (RuntimeException failure) {
                        log.warn("Expiry processing failed for order {} ({})", candidate.id(), failure.getClass().getSimpleName());
                    }
                    cursor = new OrderClosingService.Cursor(candidate.expiresAt(), candidate.id());
                }
                if (candidates.size() < 100) { cursor = null; return; }
            }
        } finally {
            leases.release(lease);
        }
    }
}
