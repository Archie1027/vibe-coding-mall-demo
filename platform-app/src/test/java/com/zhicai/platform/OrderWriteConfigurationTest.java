package com.zhicai.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.catalog.*;
import com.zhicai.platform.inventory.*;
import com.zhicai.platform.trade.*;
import com.zhicai.platform.write.*;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderWriteConfigurationTest {
    @Configuration @EnableTransactionManagement static class Transactions {
        @Bean PlatformTransactionManager transactionManager(){
            var manager=mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            return manager;
        }
    }
    final ApplicationContextRunner context=new ApplicationContextRunner()
        .withUserConfiguration(QuoteWriteConfiguration.class,OrderWriteConfiguration.class,Transactions.class)
        .withBean(ObjectMapper.class,ObjectMapper::new).withBean(Clock.class,Clock::systemUTC)
        .withBean(CheckoutCatalogMapper.class,()->mock(CheckoutCatalogMapper.class))
        .withBean(InventoryAvailabilityMapper.class,()->mock(InventoryAvailabilityMapper.class))
        .withBean(QuoteMapper.class,()->mock(QuoteMapper.class))
        .withBean(AddressMapper.class,()->mock(AddressMapper.class))
        .withBean(StockMapper.class,()->mock(StockMapper.class))
        .withBean(OrderMapper.class,()->mock(OrderMapper.class))
        .withBean(OrderClosingMapper.class,()->mock(OrderClosingMapper.class))
        .withBean(PaymentClosingMapper.class,()->mock(PaymentClosingMapper.class));
    @Test void orderCoreRequiresBothIndependentSwitches(){
        for(var settings:java.util.List.of(new String[]{},new String[]{"app.customer.writes-enabled=true"},
            new String[]{"app.order.writes-enabled=true"}))
            context.withPropertyValues(settings).run(c->{assertThat(c).hasNotFailed().doesNotHaveBean(OrderService.class)
                .doesNotHaveBean(InventoryReservationService.class).doesNotHaveBean(OrderClosingService.class).doesNotHaveBean(OrderClosingService.PaymentCheck.class);});
    }
    @Test void enabledOrderCoreUsesMandatoryTransactionProxies(){
        context.withPropertyValues("app.customer.writes-enabled=true","app.order.writes-enabled=true")
            .run(c->{assertThat(c).hasNotFailed().hasSingleBean(OrderService.class).hasSingleBean(InventoryReservationService.class);
                assertThat(AopUtils.isAopProxy(c.getBean(OrderService.class))).isTrue();
                assertThat(AopUtils.isAopProxy(c.getBean(InventoryReservationService.class))).isTrue();
                assertThat(AopUtils.isAopProxy(c.getBean(OrderClosingService.class))).isTrue();
                assertThat(AopUtils.isAopProxy(c.getBean(OrderClosingService.PaymentCheck.class))).isTrue();
                verifyNoInteractions(c.getBean(OrderMapper.class),c.getBean(StockMapper.class),c.getBean(PaymentClosingMapper.class),c.getBean(OrderClosingMapper.class));
            });
    }
}
