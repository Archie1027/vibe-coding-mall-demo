package com.zhicai.platform;

import com.zhicai.platform.inventory.StockMapper;
import com.zhicai.platform.scheduling.OrderExpiryConfiguration;
import com.zhicai.platform.trade.OrderClosingMapper;
import com.zhicai.platform.trade.OrderClosingService;
import com.zhicai.platform.trade.PaymentClosingMapper;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OrderExpiryConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(OrderExpiryConfiguration.class)
        .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
        .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
        .withBean(OrderClosingMapper.class, () -> mock(OrderClosingMapper.class))
        .withBean(StockMapper.class, () -> mock(StockMapper.class))
        .withBean(PaymentClosingMapper.class, () -> mock(PaymentClosingMapper.class))
        .withBean(Clock.class, Clock::systemUTC);

    @Test void disabledByDefault() {
        context.run(c -> assertThat(c).doesNotHaveBean(OrderExpiryConfiguration.ExpirySchedule.class));
    }

    @Test void enablingScheduleDoesNotExposeOrderClosingServiceAsAWriteBean() {
        context.withPropertyValues("app.order.expiry-enabled=true").run(c -> {
            assertThat(c).hasSingleBean(OrderExpiryConfiguration.ExpirySchedule.class);
            assertThat(c).doesNotHaveBean(OrderClosingService.class);
        });
    }
}
