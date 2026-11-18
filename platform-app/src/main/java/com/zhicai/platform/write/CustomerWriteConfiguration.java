package com.zhicai.platform.write;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.identity.AccountType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;

@Configuration
@ConditionalOnProperty(name="app.customer.writes-enabled",havingValue="true")
public class CustomerWriteConfiguration {
    @Bean CatalogWriteExecutor customerWriteExecutor(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper json,Clock clock,@Value("${app.catalog.replay-key:}")String key){
        return new CatalogWriteExecutor(jdbc,manager,json,new ReplayCipher(key),clock,AccountType.CUSTOMER);
    }
}
