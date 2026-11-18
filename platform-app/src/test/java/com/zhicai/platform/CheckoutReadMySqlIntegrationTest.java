package com.zhicai.platform;

import com.zhicai.platform.catalog.CheckoutCatalogService;
import com.zhicai.platform.catalog.CheckoutCatalogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.inventory.InventoryAvailabilityService;
import com.zhicai.platform.inventory.InventoryAvailabilityMapper;
import com.zhicai.platform.inventory.StockPolicy;
import com.zhicai.platform.trade.QuoteService;
import com.zhicai.platform.trade.QuoteModels;
import com.zhicai.platform.trade.TradeModels;
import com.zhicai.platform.trade.OrderModels;
import com.zhicai.platform.trade.OrderService;
import com.zhicai.platform.trade.OrderMapper;
import com.zhicai.platform.trade.OrderClosingService;
import com.zhicai.platform.trade.OrderClosingMapper;
import com.zhicai.platform.trade.OrderReadMapper;
import com.zhicai.platform.trade.OrderReadService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Current catalog/availability SQL only. Never inserts a quote or seeds stock. */
@EnabledIfEnvironmentVariable(named="M0_MYSQL_INTEGRATION", matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE, properties={
    "spring.flyway.enabled=false", "spring.sql.init.mode=never", "app.dev-bootstrap.enabled=false",
    "app.customer.writes-enabled=false", "app.catalog.writes-enabled=false", "debug=false"})
@Import(CheckoutReadMySqlIntegrationTest.ReadConfiguration.class)
class CheckoutReadMySqlIntegrationTest {
    @TestConfiguration
    static class ReadConfiguration {
        @Bean CheckoutCatalogService checkoutCatalog(CheckoutCatalogMapper mapper,ObjectMapper json) {
            return new CheckoutCatalogService(mapper,json);
        }
        @Bean InventoryAvailabilityService availability(InventoryAvailabilityMapper mapper) {
            return new InventoryAvailabilityService(mapper);
        }
    }
    @Autowired CheckoutCatalogService catalog;
    @Autowired InventoryAvailabilityService inventory;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired OrderReadService orderReads;

    private String enabledSku() {
        return jdbc.queryForObject("SELECT CAST(s.id AS CHAR) FROM sku s JOIN product p ON p.id=s.product_id "
            +"JOIN category c ON c.id=p.category_id JOIN brand b ON b.id=p.brand_id "
            +"WHERE s.enabled=TRUE AND p.state='ON_SALE' AND c.enabled=TRUE AND b.enabled=TRUE ORDER BY s.id LIMIT 1",String.class);
    }

    @Test void realCheckoutProjectionRetainsCurrentPricesAndAllCatalogVersions() {
        var transaction=new TransactionTemplate(manager); transaction.setReadOnly(true);
        transaction.executeWithoutResult(status->{
            String id=enabledSku(); var rows=catalog.skus(List.of(id,id));
            assertThat(rows).hasSize(1); var row=rows.get(id);
            var expected=jdbc.queryForMap("SELECT CAST(s.price_minor AS CHAR) price,s.version sv,p.version pv,c.version cv,b.version bv "
                +"FROM sku s JOIN product p ON p.id=s.product_id JOIN category c ON c.id=p.category_id JOIN brand b ON b.id=p.brand_id WHERE s.id=CAST(? AS UNSIGNED)",id);
            assertThat(row.skuId()).isEqualTo(id); assertThat(row.priceMinor()).isEqualTo(expected.get("price").toString());
            assertThat(row.skuVersion()).isEqualTo(((Number)expected.get("sv")).longValue());
            assertThat(row.productVersion()).isEqualTo(((Number)expected.get("pv")).longValue());
            assertThat(row.categoryVersion()).isEqualTo(((Number)expected.get("cv")).longValue());
            assertThat(row.brandVersion()).isEqualTo(((Number)expected.get("bv")).longValue());
            assertThat(row.productName()).isNotBlank(); assertThat(row.specs()).isNotEmpty();
        });
    }

    @Test void realAvailabilitySqlAgreesWithWholeBasketPolicyWithoutReservingStock() {
        var transaction=new TransactionTemplate(manager); transaction.setReadOnly(true);
        transaction.executeWithoutResult(status->{
            var demands=List.of(new StockPolicy.Demand(enabledSku(),1));
            var warehouses=jdbc.query("SELECT CAST(id AS CHAR),priority,enabled,fulfillment_enabled FROM warehouse ORDER BY id",
                (rs,n)->new StockPolicy.Warehouse(rs.getString(1),rs.getInt(2),rs.getBoolean(3),rs.getBoolean(4)));
            var balances=jdbc.query("SELECT CAST(warehouse_id AS CHAR),CAST(sku_id AS CHAR),on_hand,reserved,quarantine "
                +"FROM stock_balance WHERE sku_id=CAST(? AS UNSIGNED)",
                (rs,n)->new StockPolicy.Balance(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getInt(5)),demands.getFirst().skuId());
            String chosen=null;
            try { chosen=StockPolicy.selectWarehouse(warehouses,demands,balances); }
            catch(ApiException expected) {
                assertThat(expected.code()).isEqualTo("NO_SINGLE_WAREHOUSE");
                assertThatThrownBy(()->inventory.selectWarehouse(demands)).isInstanceOfSatisfying(ApiException.class,
                    error->assertThat(error.code()).isEqualTo(expected.code()));
                return;
            }
            assertThat(inventory.selectWarehouse(demands)).isEqualTo(chosen);
        });
    }

    @Test void quoteWritingIsNotRegisteredBeforeMigrationAndHttpIntegration() {
        assertThat(context.getBeansOfType(QuoteService.class)).isEmpty();
    }

    @Test void orderCoreRemainsClosedAndActualApplicationJsonPreservesItsContract() throws Exception {
        var before=jdbc.queryForObject("SELECT CONCAT((SELECT COUNT(*) FROM sales_order),':',(SELECT COUNT(*) FROM sales_order_item))",String.class);
        assertThat(context.getBeansOfType(OrderService.class)).isEmpty();
        var configuration=context.getBean(org.apache.ibatis.session.SqlSessionFactory.class).getConfiguration();
        for(var statement:List.of("lockQuote","insertOrder","insertItem","markQuoteUsed"))
            assertThat(configuration.hasStatement(OrderMapper.class.getName()+"."+statement)).isTrue();
        var created=java.time.Instant.parse("2026-10-26T00:00:00.123Z");
        var address=new TradeModels.AddressInput("演示收件人","10000000008","省","市","区","演示地址",false);
        var line=new OrderModels.Line("9999999999999999998","3","演示商品",List.of(),2,"100","200","0","200",0,"0");
        var order=new OrderModels.Order("9999999999999999999","ZCfixture","4","1","UNPAID",List.of(line),address,"200","0","0","200","0",created.plusSeconds(1800),created,0);
        var encoded=json.writeValueAsString(order);var tree=json.readTree(encoded);
        assertThat(json.readValue(encoded,OrderModels.Order.class)).isEqualTo(order);
        assertThat(tree.path("id").isTextual()).isTrue();
        assertThat(tree.path("items").get(0).path("id").isTextual()).isTrue();
        assertThat(tree.path("grossMinor").asText()).isEqualTo("200");
        assertThat(tree.path("grossMinor").isTextual()).isTrue();
        assertThat(tree.path("expiresAt").asText()).isEqualTo("2026-10-26T00:30:00.123Z");
        assertThat(tree.has("shipment")).isFalse();
        var image=new CheckoutCatalogService.ImageSnapshot("1","fixture-public.png","image/png",50);
        assertThat(json.readValue(json.writeValueAsString(image),CheckoutCatalogService.ImageSnapshot.class)).isEqualTo(image);
        assertThat(jdbc.queryForObject("SELECT CONCAT((SELECT COUNT(*) FROM sales_order),':',(SELECT COUNT(*) FROM sales_order_item))",String.class)).isEqualTo(before);
    }

    @Test void closingCoreIsNotRegisteredAndExpiryCandidatesAreReadOnly() throws Exception {
        assertThat(context.getBeansOfType(OrderClosingService.class)).isEmpty();
        var configuration=context.getBean(org.apache.ibatis.session.SqlSessionFactory.class).getConfiguration();
        for(var statement:List.of("lockOwned","lockAny","items","markCancelled","expired"))
            assertThat(configuration.hasStatement(OrderClosingMapper.class.getName()+"."+statement)).isTrue();
        var transaction=new TransactionTemplate(manager);transaction.setReadOnly(true);
        transaction.executeWithoutResult(status->{
            var tables=List.of("sales_order","sales_order_item","stock_balance","stock_reservation","stock_ledger");
            var before=tables.stream().map(table->jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).toList();
            assertThat(before).containsOnly(0L);
            var mapper=context.getBean(OrderClosingMapper.class);
            var cutoff=java.time.Instant.now();
            assertThat(mapper.expired(cutoff,null,null,20)).isEmpty();
            assertThat(mapper.expired(cutoff,java.time.Instant.parse("2026-10-26T00:00:00Z"),"1",20)).isEmpty();
            assertThat(tables.stream().map(table->jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).toList()).isEqualTo(before);
        });
        var result=new OrderClosingService.Result("9999999999999999999","CANCELLED",1,
            java.time.Instant.parse("2026-10-27T01:02:03.123Z"),"不再需要");
        var encoded=json.writeValueAsString(result);var tree=json.readTree(encoded);
        assertThat(json.readValue(encoded,OrderClosingService.Result.class)).isEqualTo(result);
        assertThat(tree.path("id").isTextual()).isTrue();
        assertThat(tree.path("cancelledAt").asText()).isEqualTo("2026-10-27T01:02:03.123Z");
    }

    @Test void orderReadMapperRunsAgainstV8WithoutWriting() {
        var owner=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM account WHERE account_type='CUSTOMER' ORDER BY id LIMIT 1",String.class);
        var expected=jdbc.queryForObject("SELECT COUNT(*) FROM sales_order WHERE customer_id=CAST(? AS UNSIGNED)",Long.class,owner);
        var transaction=new TransactionTemplate(manager);transaction.setReadOnly(true);
        transaction.executeWithoutResult(status->{
            var statements=context.getBean(org.apache.ibatis.session.SqlSessionFactory.class).getConfiguration();
            for(var name:List.of("count","list","find","lines"))
                assertThat(statements.hasStatement(OrderReadMapper.class.getName()+"."+name)).isTrue();
            var result=orderReads.list(owner,1,20,null);
            assertThat(result.total()).isEqualTo(expected);
            assertThat(result.items()).allSatisfy(order->assertThat(order.customerId()).isEqualTo(owner));
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_order WHERE customer_id=CAST(? AS UNSIGNED)",Long.class,owner)).isEqualTo(expected);
    }

    @Test void actualApplicationMapperRoundTripsSnapshotAndUsesIsoTimeAndStringMoney() throws Exception {
        var sku=catalog.skus(List.of(enabledSku())).values().iterator().next();
        var created=java.time.Instant.parse("2026-10-25T01:02:03.123Z"); var expires=created.plusSeconds(300);
        var address=new TradeModels.Address("1","演示收件人","10000000008","省","市","区","演示地址",false,0);
        var line=new QuoteModels.Line(sku.skuId(),sku.productName(),sku.specs(),1,sku.priceMinor(),sku.priceMinor(),"0",sku.priceMinor());
        var snapshot=new QuoteModels.Snapshot(1,"1","1",address,"1",List.of(new QuoteModels.SnapshotLine(sku,line)),
            sku.priceMinor(),"0","0",sku.priceMinor(),null,created,expires);
        assertThat(json.readValue(json.writeValueAsString(snapshot),QuoteModels.Snapshot.class)).isEqualTo(snapshot);
        var quote=new QuoteModels.Quote("9999999999999999999","1",List.of(line),sku.priceMinor(),"0","0",sku.priceMinor(),null,expires);
        var tree=json.readTree(json.writeValueAsString(quote));
        assertThat(tree.get("expiresAt").asText()).isEqualTo("2026-10-25T01:07:03.123Z");
        assertThat(tree.get("id").isTextual()).isTrue(); assertThat(tree.get("grossMinor").isTextual()).isTrue();
        assertThat(tree.has("couponId")).isFalse();
    }
}
