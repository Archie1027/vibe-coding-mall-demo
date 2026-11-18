package com.zhicai.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.trade.MockPaymentMapper;
import com.zhicai.platform.trade.MockPaymentService;
import com.zhicai.platform.web.MockPaymentController;
import com.zhicai.platform.write.MockPaymentApplication;
import com.zhicai.platform.write.MockPaymentConfiguration;
import java.time.Clock;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MockPaymentConfigurationTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(MockPaymentConfiguration.class, MockPaymentController.class)
        .withBean(JdbcTemplate.class, () -> jdbc)
        .withBean(MockPaymentMapper.class, () -> mock(MockPaymentMapper.class))
        .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withBean(Clock.class, Clock::systemUTC)
        .withPropertyValues("app.catalog.replay-key=" + Base64.getEncoder().encodeToString(new byte[32]));

    @Test void defaultAndProductionDoNotRegisterControllerOrReadDatabase() {
        profiles("dev").run(c -> {
            assertThat(c).doesNotHaveBean(MockPaymentController.class).doesNotHaveBean(MockPaymentService.class);
            assertThat(MockPaymentConfiguration.enabled(c.getEnvironment())).isFalse();
        });
        context.withPropertyValues("app.payment.mock-enabled=true").run(c -> {
            assertThat(c).doesNotHaveBean(MockPaymentController.class);
            assertThat(MockPaymentConfiguration.enabled(c.getEnvironment())).isFalse();
        });
        for (String prod : new String[] {"prod", "production"}) {
            profiles("dev", prod).withPropertyValues("app.payment.mock-enabled=true").run(c -> {
                assertThat(c).doesNotHaveBean(MockPaymentController.class);
                assertThat(MockPaymentConfiguration.enabled(c.getEnvironment())).isFalse();
            });
        }
        verify(jdbc, never()).queryForObject(anyString(), eq(Integer.class));
    }

    @Test void devSwitchWithV11WiresOnlyTheMockSurface() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        profiles("dev").withPropertyValues("app.payment.mock-enabled=true").run(c -> {
            assertThat(c).hasSingleBean(MockPaymentController.class).hasSingleBean(MockPaymentApplication.class)
                .hasSingleBean(MockPaymentService.class).doesNotHaveBean("catalogWriteExecutor")
                .doesNotHaveBean("customerWriteExecutor");
            assertThat(MockPaymentConfiguration.enabled(c.getEnvironment())).isTrue();
        });
    }

    @Test void devSwitchWithoutV11FailsBeforeExposingRoutes() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        profiles("dev").withPropertyValues("app.payment.mock-enabled=true").run(c ->
            assertThat(c).getFailure().hasRootCauseMessage("MOCK payment requires the approved V11 migration and payment_event table"));
    }

    private ApplicationContextRunner profiles(String... profiles) {
        return context.withInitializer(c -> c.getEnvironment().setActiveProfiles(profiles));
    }
}
