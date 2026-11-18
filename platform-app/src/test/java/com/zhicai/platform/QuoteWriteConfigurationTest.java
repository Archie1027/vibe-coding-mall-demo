package com.zhicai.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.catalog.*;
import com.zhicai.platform.inventory.*;
import com.zhicai.platform.trade.*;
import com.zhicai.platform.write.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.aop.support.AopUtils;
import java.time.Clock;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuoteWriteConfigurationTest {
    final ApplicationContextRunner context=new ApplicationContextRunner().withUserConfiguration(QuoteWriteConfiguration.class,QuoteApplication.class,Transactions.class)
        .withBean(ObjectMapper.class,ObjectMapper::new).withBean(Clock.class,Clock::systemUTC)
        .withBean(CheckoutCatalogMapper.class,()->mock(CheckoutCatalogMapper.class)).withBean(InventoryAvailabilityMapper.class,()->mock(InventoryAvailabilityMapper.class))
        .withBean(QuoteMapper.class,()->mock(QuoteMapper.class)).withBean(AddressMapper.class,()->mock(AddressMapper.class));
    @Configuration @EnableTransactionManagement static class Transactions {
        @Bean PlatformTransactionManager transactionManager(){var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());return manager;}
    }
    @Test void quoteReadAndWriteServicesAreAbsentByDefault(){context.run(c->{
        assertThat(c).doesNotHaveBean(QuoteService.class).doesNotHaveBean(CheckoutCatalogService.class).doesNotHaveBean(InventoryAvailabilityService.class);
        verifyNoInteractions(c.getBean(QuoteMapper.class),c.getBean(AddressMapper.class),c.getBean(CheckoutCatalogMapper.class),c.getBean(InventoryAvailabilityMapper.class));
    });}
    @Test void enabledServicesUseExistingMappersAndQuoteIsTransactionalProxy(){context.withPropertyValues("app.customer.writes-enabled=true").run(c->{
        assertThat(c).hasNotFailed().hasSingleBean(QuoteService.class).hasSingleBean(CheckoutCatalogService.class).hasSingleBean(InventoryAvailabilityService.class);
        assertThat(AopUtils.isAopProxy(c.getBean(QuoteService.class))).isTrue();assertThat(c).doesNotHaveBean(CatalogWriteExecutor.class);
        verifyNoInteractions(c.getBean(QuoteMapper.class),c.getBean(AddressMapper.class),c.getBean(CheckoutCatalogMapper.class),c.getBean(InventoryAvailabilityMapper.class));
    });}
    @Test void catalogWriterCannotSubstituteForDisabledCustomerWriter(){
        context.withUserConfiguration(CatalogWriteConfiguration.class)
            .withBean(org.springframework.jdbc.core.JdbcTemplate.class,()->new org.springframework.jdbc.core.JdbcTemplate(mock(javax.sql.DataSource.class)))
            .withPropertyValues("app.catalog.writes-enabled=true","app.catalog.replay-key="+java.util.Base64.getEncoder().encodeToString(new byte[32]))
            .run(c->{
                assertThat(c).hasNotFailed().hasBean("catalogWriteExecutor").doesNotHaveBean("customerWriteExecutor");
                var actor=new com.zhicai.platform.identity.AccountView("1","customer","Customer",com.zhicai.platform.identity.AccountType.CUSTOMER,java.util.List.of(),java.util.List.of(),java.util.List.of(),true,0);
                var input=new QuoteModels.Request("2",java.util.List.of(new QuoteModels.Item("3",1)),null);
                assertThatThrownBy(()->c.getBean(QuoteApplication.class).create(actor,input,"key-12345678901234",java.util.UUID.randomUUID().toString()))
                    .isInstanceOf(com.zhicai.platform.common.ApiException.class).extracting("status").isEqualTo(503);
                verifyNoInteractions(c.getBean(org.springframework.jdbc.core.JdbcTemplate.class).getDataSource());
            });
    }
}
