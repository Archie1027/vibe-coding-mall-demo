package com.zhicai.platform.write;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.catalog.CheckoutCatalogMapper;
import com.zhicai.platform.catalog.CheckoutCatalogService;
import com.zhicai.platform.inventory.InventoryAvailabilityMapper;
import com.zhicai.platform.inventory.InventoryAvailabilityService;
import com.zhicai.platform.trade.AddressMapper;
import com.zhicai.platform.trade.QuoteMapper;
import com.zhicai.platform.trade.QuoteService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
@ConditionalOnProperty(name="app.customer.writes-enabled",havingValue="true")
public class QuoteWriteConfiguration {
    @Bean CheckoutCatalogService checkoutCatalogService(CheckoutCatalogMapper mapper,ObjectMapper json){return new CheckoutCatalogService(mapper,json);}
    @Bean InventoryAvailabilityService inventoryAvailabilityService(InventoryAvailabilityMapper mapper){return new InventoryAvailabilityService(mapper);}
    @Bean QuoteService quoteService(QuoteMapper mapper,AddressMapper addresses,CheckoutCatalogService catalog,
        InventoryAvailabilityService inventory,ObjectMapper json,Clock clock){return new QuoteService(mapper,addresses,catalog,inventory,json,clock);}
}
