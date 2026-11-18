package com.zhicai.platform.write;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;

@Configuration
@ConditionalOnProperty(name = "app.catalog.writes-enabled", havingValue = "true")
public class CatalogWriteConfiguration {
    @Bean CatalogWriteExecutor catalogWriteExecutor(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper json, Clock clock,
        @Value("${app.catalog.replay-key:}") String key) {
        return new CatalogWriteExecutor(jdbc, manager, json, new ReplayCipher(key), clock);
    }
}
