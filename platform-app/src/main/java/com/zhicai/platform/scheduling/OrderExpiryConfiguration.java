package com.zhicai.platform.scheduling;

import com.zhicai.platform.inventory.InventoryReservationService;
import com.zhicai.platform.inventory.StockMapper;
import com.zhicai.platform.trade.OrderClosingMapper;
import com.zhicai.platform.trade.OrderClosingService;
import com.zhicai.platform.trade.PaymentClosingCheck;
import com.zhicai.platform.trade.PaymentClosingMapper;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

/** This isolated assembly does not register the internal services as HTTP write beans. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.order.expiry-enabled", havingValue = "true")
public class OrderExpiryConfiguration {
    @Bean ExpirySchedule expirySchedule(JdbcTemplate jdbc, PlatformTransactionManager manager,
            OrderClosingMapper orders, StockMapper stock, PaymentClosingMapper payments, Clock clock) {
        var closing = new OrderClosingService(orders, new InventoryReservationService(stock),
            new PaymentClosingCheck(payments), clock);
        return new ExpirySchedule(new OrderExpiryRunner(new DatabaseTaskLease(jdbc, manager), closing, clock));
    }

    public static final class ExpirySchedule {
        private final OrderExpiryRunner runner;
        ExpirySchedule(OrderExpiryRunner runner) { this.runner = runner; }
        @Scheduled(initialDelay = 30000, fixedDelay = 60000)
        public void run() { runner.runOnce(); }
    }
}
