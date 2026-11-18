package com.zhicai.platform.write;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.identity.AccountType;
import com.zhicai.platform.trade.MockPaymentMapper;
import com.zhicai.platform.trade.MockPaymentService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@Profile(MockPaymentConfiguration.PROFILE)
@ConditionalOnProperty(name = MockPaymentConfiguration.PROPERTY, havingValue = "true")
public class MockPaymentConfiguration {
    public static final String PROFILE = "(dev | test) & !prod & !production";
    public static final String PROPERTY = "app.payment.mock-enabled";

    public static boolean enabled(Environment environment) {
        return environment.acceptsProfiles(Profiles.of(PROFILE)) && "true".equalsIgnoreCase(environment.getProperty(PROPERTY));
    }

    @Bean
    MockPaymentService mockPaymentService(MockPaymentMapper mapper, Clock clock) {
        return new MockPaymentService(mapper, clock);
    }

    @Bean
    MockPaymentApplication mockPaymentApplication(MockPaymentService service, JdbcTemplate jdbc,
        PlatformTransactionManager manager, ObjectMapper json, Clock clock, @Value("${app.catalog.replay-key:}") String key) {
        var cipher = new ReplayCipher(key);
        Integer ready = jdbc.queryForObject("""
            SELECT COUNT(*) FROM flyway_schema_history
            WHERE version='11' AND script='V11__mock_payment_event.sql' AND success=1
              AND EXISTS (SELECT 1 FROM information_schema.tables
                          WHERE table_schema=DATABASE() AND table_name='payment_event' AND table_type='BASE TABLE')
            """, Integer.class);
        if (!Integer.valueOf(1).equals(ready))
            throw new IllegalStateException("MOCK payment requires the approved V11 migration and payment_event table");
        return new MockPaymentApplication(service,
            new CatalogWriteExecutor(jdbc, manager, json, cipher, clock, AccountType.CUSTOMER),
            new CatalogWriteExecutor(jdbc, manager, json, cipher, clock, AccountType.STAFF), json);
    }
}
