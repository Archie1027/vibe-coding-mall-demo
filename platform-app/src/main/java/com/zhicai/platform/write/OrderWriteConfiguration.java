package com.zhicai.platform.write;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.catalog.CheckoutCatalogService;
import com.zhicai.platform.inventory.InventoryAvailabilityService;
import com.zhicai.platform.inventory.InventoryReservationService;
import com.zhicai.platform.inventory.StockMapper;
import com.zhicai.platform.trade.OrderMapper;
import com.zhicai.platform.trade.OrderService;
import com.zhicai.platform.trade.OrderClosingMapper;
import com.zhicai.platform.trade.OrderClosingService;
import com.zhicai.platform.trade.PaymentClosingMapper;
import com.zhicai.platform.trade.PaymentClosingCheck;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;

@Configuration
@ConditionalOnProperty(name={"app.customer.writes-enabled","app.order.writes-enabled"},havingValue="true")
public class OrderWriteConfiguration {
    @Bean InventoryReservationService inventoryReservationService(StockMapper mapper){return new InventoryReservationService(mapper);}
    @Bean OrderClosingService.PaymentCheck paymentClosingCheck(PaymentClosingMapper mapper){return new PaymentClosingCheck(mapper);}
    @Bean OrderClosingService orderClosingService(OrderClosingMapper mapper,InventoryReservationService inventory,
        @Qualifier("paymentClosingCheck") OrderClosingService.PaymentCheck payments,Clock clock){return new OrderClosingService(mapper,inventory,payments,clock);}
    @Bean OrderService orderService(OrderMapper mapper,CheckoutCatalogService catalog,InventoryAvailabilityService availability,
        InventoryReservationService reservations,ObjectMapper json,Clock clock){
        return new OrderService(mapper,catalog,availability,reservations,json,clock);
    }
}
