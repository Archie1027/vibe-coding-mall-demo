package com.zhicai.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.write.*;
import com.zhicai.platform.catalog.BrandWriteService;
import com.zhicai.platform.trade.AddressService;
import com.zhicai.platform.trade.CartService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogWriteConfigurationTest {
    final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(CatalogWriteConfiguration.class)
        .withBean(JdbcTemplate.class, () -> new JdbcTemplate(mock(javax.sql.DataSource.class)))
        .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
        .withBean(ObjectMapper.class, ObjectMapper::new).withBean(Clock.class, Clock::systemUTC);
    @Test void writesAreDisabledByDefault() { context.run(c -> assertThat(c).doesNotHaveBean(CatalogWriteExecutor.class)); }
    @Test void enabledWritesRequireAnExplicitValidKey() {
        context.withPropertyValues("app.catalog.writes-enabled=true").run(c -> assertThat(c).hasFailed());
        context.withPropertyValues("app.catalog.writes-enabled=true", "app.catalog.replay-key=invalid").run(c -> assertThat(c).hasFailed());
    }
    @Test void validKeyBuildsWriterWithoutConnectingToDatabase() {
        context.withPropertyValues("app.catalog.writes-enabled=true", "app.catalog.replay-key=" + Base64.getEncoder().encodeToString(new byte[32]))
            .run(c -> { assertThat(c).hasSingleBean(CatalogWriteExecutor.class); verifyNoInteractions(c.getBean(JdbcTemplate.class).getDataSource()); });
    }
    @Test void customerAndCatalogProvidersStayQualifiedWhenBothBeansExistOrOnlyCustomerEnabled() {
        var both=context.withUserConfiguration(CustomerWriteConfiguration.class,BrandWriteApplication.class,CustomerDataApplication.class)
            .withBean(BrandWriteService.class,()->mock(BrandWriteService.class))
            .withBean(AddressService.class,()->mock(AddressService.class)).withBean(CartService.class,()->mock(CartService.class))
            .withPropertyValues("app.customer.writes-enabled=true","app.catalog.replay-key="+Base64.getEncoder().encodeToString(new byte[32]));
        both.run(c->{
            assertThat(c).hasNotFailed().hasBean("customerWriteExecutor").doesNotHaveBean("catalogWriteExecutor");
            var actor=new com.zhicai.platform.identity.AccountView("1","admin","Admin",com.zhicai.platform.identity.AccountType.STAFF,java.util.List.of("catalog:write"),java.util.List.of(),java.util.List.of(),true,0);
            assertThatThrownBy(()->c.getBean(BrandWriteApplication.class).save(actor,null,"品牌",true,null,"key-12345678901234",java.util.UUID.randomUUID().toString()))
                .isInstanceOf(com.zhicai.platform.common.ApiException.class).extracting("status").isEqualTo(503);
        });
        both.withPropertyValues("app.catalog.writes-enabled=true").run(c->{
            assertThat(c).hasNotFailed().hasBean("catalogWriteExecutor").hasBean("customerWriteExecutor");
            assertThat(c.getBean("catalogWriteExecutor")).isNotSameAs(c.getBean("customerWriteExecutor"));
        });
    }
}
