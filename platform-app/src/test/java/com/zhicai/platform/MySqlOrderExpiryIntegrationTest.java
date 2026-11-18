package com.zhicai.platform;

import com.fasterxml.jackson.databind.*;
import com.zhicai.platform.catalog.*;
import com.zhicai.platform.inventory.*;
import com.zhicai.platform.scheduling.*;
import com.zhicai.platform.trade.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Single bounded candidate. The launcher binds approval, source and baseline before this can write. */
@EnabledIfEnvironmentVariable(named="M1_EXPIRY_MODE",matches="read-only|approved-single-expiry")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={
    "spring.flyway.enabled=false","spring.sql.init.mode=never","app.dev-bootstrap.enabled=false",
    "app.catalog.writes-enabled=false","app.customer.writes-enabled=false","app.order.writes-enabled=false","app.order.expiry-enabled=false"})
@Import(MySqlOrderExpiryIntegrationTest.GuardConfiguration.class)
class MySqlOrderExpiryIntegrationTest {
    static final String URL="jdbc:mysql://127.0.0.1:3306/zhicai_dev?connectionTimeZone=UTC";
    static final Path BASELINE=Path.of(".local/acceptance");
    static final String BASELINE_SHA="0000000000000000000000000000000000000000000000000000000000000000";
    static boolean approved(){return "approved-single-expiry".equals(System.getenv("M1_EXPIRY_MODE"))
        && System.getenv("M1_EXPIRY_APPROVAL")!=null&&!System.getenv("M1_EXPIRY_APPROVAL").isBlank();}
    static final MySqlAcceptanceSupport.SqlGuard GUARD=new MySqlAcceptanceSupport.SqlGuard(
        OrderExpiryAcceptanceGuardTest.CAPS,OrderExpiryAcceptanceGuardTest.RESOURCES,15,MySqlOrderExpiryIntegrationTest::approved);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
        if(!URL.equals(System.getenv("DB_URL")))throw new IllegalStateException("Canonical local target required");
        p.add("spring.datasource.url",()->URL);p.add("spring.datasource.username",()->System.getenv("DB_USERNAME"));
        p.add("spring.datasource.password",()->System.getenv("DB_PASSWORD"));
        p.add("spring.datasource.hikari.connection-init-sql",()->"SET time_zone = '+00:00'");
    }
    @TestConfiguration static class GuardConfiguration {
        @Bean static BeanPostProcessor guard(){return new BeanPostProcessor(){
            @Override public Object postProcessAfterInitialization(Object bean,String name){return bean instanceof DataSource ds?GUARD.dataSource(ds):bean;}
        };}
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper json;
    @Autowired CheckoutCatalogMapper catalogMapper;
    @Autowired InventoryAvailabilityMapper availabilityMapper;
    @Autowired StockMapper stockMapper;
    @Autowired QuoteMapper quoteMapper;
    @Autowired AddressMapper addressMapper;
    @Autowired OrderMapper orderMapper;
    @Autowired OrderClosingMapper closingMapper;
    @Autowired PaymentClosingMapper paymentMapper;
    final Map<String,List<String>> columns=new TreeMap<>();
    final Map<String,Set<String>> owned=new TreeMap<>();
    final String run=UUID.randomUUID().toString(),label="到期验收-"+run;
    final List<OrderModels.Order> orders=new ArrayList<>();
    final MutableClock clock=new MutableClock();
    JsonNode reference;
    Path manifest;
    String owner,warehouse,sku,address,balance,payment,leaseOwner;
    static final class MutableClock extends Clock {
        Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){if(!ZoneOffset.UTC.equals(zone))throw new IllegalArgumentException();return this;}
        public Instant instant(){return now;}
    }
    TransactionTemplate tx(boolean readOnly){var t=new TransactionTemplate(manager);t.setReadOnly(readOnly);
        t.setIsolationLevel(readOnly?TransactionDefinition.ISOLATION_REPEATABLE_READ:TransactionDefinition.ISOLATION_READ_COMMITTED);t.setTimeout(30);return t;}
    void note(String stage)throws Exception {Files.writeString(manifest,json.writeValueAsString(Map.of("stage",stage,"run",run,
        "sqlAttempts",GUARD.snapshot(),"deletes",GUARD.deletes.get(),"orders",orders.stream().map(OrderModels.Order::id).toList()))+"\n",StandardOpenOption.APPEND);}
    void baseline(){tx(true).executeWithoutResult(s->{
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'",String.class))
            .containsExactlyInAnyOrderElementsOf(columns.keySet());
        for(var row:reference.path("Rows")){String table=row.path("table").asText();int count=jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Integer.class);
            assertThat(count).as(table).isEqualTo(row.path("count").asInt());
            assertThat(MySqlAcceptanceSupport.referenceDigest(jdbc,table,columns.get(table),count)).as(table).isEqualTo(row.path("sha256").asText());}
    });}
    @Test void singleScenario()throws Exception {
        var root=Path.of(System.getenv("M1_EXPIRY_EVIDENCE"));Files.createDirectories(root);
        manifest=root.resolve("manifest.jsonl");Files.createFile(manifest);
        byte[] bytes=Files.readAllBytes(BASELINE);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).isEqualTo(BASELINE_SHA);
        reference=json.readTree(bytes);assertThat(reference.path("Rows")).hasSize(26);
        for(var row:reference.path("Rows")){String table=row.path("table").asText();assertThat(table).matches("[a-z_]+");
            columns.put(table,jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position",String.class,table));}
        baseline();
        for(String table:OrderExpiryAcceptanceGuardTest.RESOURCES){assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Integer.class)).isZero();owned.put(table,new HashSet<>());}
        owner=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM account WHERE username='customer' AND account_type='CUSTOMER' AND enabled=1",String.class);
        warehouse=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM warehouse WHERE enabled=1 AND fulfillment_enabled=1 ORDER BY priority,id LIMIT 1",String.class);
        sku=jdbc.queryForObject("SELECT CAST(s.id AS CHAR) FROM sku s JOIN product p ON p.id=s.product_id JOIN category c ON c.id=p.category_id JOIN brand b ON b.id=p.brand_id WHERE s.enabled=1 AND p.state='ON_SALE' AND c.enabled=1 AND b.enabled=1 ORDER BY s.id LIMIT 1",String.class);
        var inventory=new InventoryReservationService(stockMapper);
        var closing=new OrderClosingService(closingMapper,inventory,new PaymentClosingCheck(paymentMapper),clock);
        assertThat(closing.expiredCandidates(clock.instant(),null,100)).isEmpty();
        if(!approved()){assertThat(System.getenv("M1_EXPIRY_MODE")).isEqualTo("read-only");baseline();assertThat(GUARD.snapshot()).isEmpty();assertThat(GUARD.locks.get()).isZero();note("read-only-verified");return;}
        try {
            // A permanent marker is created by the launcher before this JVM; never auto-retry this mode.
            var approval=json.readTree(Files.readAllBytes(Path.of(System.getenv("M1_EXPIRY_ATTEMPT"))));
            assertThat(approval.path("ApprovalId").asText()).isEqualTo(System.getenv("M1_EXPIRY_APPROVAL"));
            assertThat(approval.path("Invocation").asText()).isEqualTo(root.toString());
            assertThat(approval.path("Identity").asText()).isEqualTo(System.getenv("M1_EXPIRY_IDENTITY"));
            note("write-intent");GUARD.armed=true;
            seed(inventory);
            assertThat(closing.expiredCandidates(clock.instant(),null,100)).isEmpty();
            clock.now=clock.now.plusSeconds(1800);
            assertThat(closing.expiredCandidates(clock.instant(),null,100).stream().map(OrderClosingService.Candidate::id).toList())
                .containsExactlyElementsOf(orders.stream().map(OrderModels.Order::id).toList());
            var leaseJdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())){
                @Override public int update(String sql,Object... args){
                    if(sql.startsWith("INSERT INTO task_lease")){assertThat(args[0]).isEqualTo("order-expiry");leaseOwner=(String)args[1];owned.get("task_lease").add("order-expiry");}
                    return super.update(sql,args);
                }};
            new OrderExpiryRunner(new DatabaseTaskLease(leaseJdbc,manager),closing,clock).runOnce();
            var frozen=Objects.requireNonNull(tx(true).execute(s->validate()));
            tx(true).executeWithoutResult(s->{for(var row:reference.path("Rows")){
                String table=row.path("table").asText();if(OrderExpiryAcceptanceGuardTest.RESOURCES.contains(table))continue;
                int count=jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Integer.class);
                assertThat(count).isEqualTo(row.path("count").asInt());
                assertThat(MySqlAcceptanceSupport.referenceDigest(jdbc,table,columns.get(table),count)).isEqualTo(row.path("sha256").asText());
            }});
            var proofs=new TreeMap<String,List<Map<String,String>>>();
            frozen.forEach((table,rows)->proofs.put(table,rows.stream().map(row->Map.of("key",row.get(table.equals("task_lease")?"task_name":"id"),"fullRowHash",row.get("_guard_hash"))).toList()));
            Files.writeString(root.resolve("cleanup-proofs.json"),json.writeValueAsString(proofs),StandardOpenOption.CREATE_NEW);
            assertThat(GUARD.snapshot()).containsExactlyInAnyOrderEntriesOf(OrderExpiryAcceptanceGuardTest.CAPS);
            note("checks-passed");
            var cleanup=tx(false);cleanup.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            cleanup.executeWithoutResult(s->{var locked=OrderExpiryCleanupLock.lockAll(jdbc,columns,MySqlOrderExpiryIntegrationTest::approved);
                OrderExpiryCleanupDelete.deleteAll(jdbc,GUARD,columns,frozen,locked,owned,leaseOwner);});
            assertThat(GUARD.deletes.get()).isEqualTo(15);baseline();note("cleaned-and-verified");
        }catch(Exception|AssertionError failure){note("retained-after-failure");throw failure;}
        finally{GUARD.armed=false;GUARD.cleanup=false;}
    }
    void seed(InventoryReservationService inventory){
        var catalog=new CheckoutCatalogService(catalogMapper,json);var availability=new InventoryAvailabilityService(availabilityMapper);
        var quotes=new QuoteService(quoteMapper,addressMapper,catalog,availability,json,clock);
        var service=new OrderService(orderMapper,catalog,availability,inventory,json,clock);
        tx(false).executeWithoutResult(s->{
            assertThat(jdbc.update("INSERT INTO customer_address(customer_id,recipient,phone,province,city,district,detail,is_default) VALUES(CAST(? AS UNSIGNED),?,'10000000007','测试省','测试市','测试区',?,FALSE)",owner,label,label)).isEqualTo(1);
            address=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM customer_address WHERE BINARY recipient=BINARY ?",String.class,label);owned.get("customer_address").add(address);
            assertThat(jdbc.update("INSERT INTO stock_balance(warehouse_id,sku_id,on_hand,reserved,quarantine) VALUES(CAST(? AS UNSIGNED),CAST(? AS UNSIGNED),2,0,0)",warehouse,sku)).isEqualTo(1);
            balance=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM stock_balance WHERE warehouse_id=CAST(? AS UNSIGNED) AND sku_id=CAST(? AS UNSIGNED)",String.class,warehouse,sku);owned.get("stock_balance").add(balance);
            for(int i=0;i<2;i++){var q=quotes.create(owner,new QuoteModels.Request(address,List.of(new QuoteModels.Item(sku,1)),null));owned.get("checkout_quote").add(q.id());
                var order=service.create(owner,new OrderModels.Create(q.id(),label+"-"+i),"seed:"+run);orders.add(order);owned.get("sales_order").add(order.id());}
            var second=orders.get(1);jdbc.queryForObject("SELECT id FROM sales_order WHERE id=CAST(? AS UNSIGNED) FOR UPDATE",Long.class,second.id());
            assertThat(jdbc.update("INSERT INTO payment(order_id,payment_no,channel,state,amount_minor,currency) VALUES(CAST(? AS UNSIGNED),?,'MOCK','PROCESSING',CAST(? AS UNSIGNED),'CNY')",second.id(),"EX"+run.replace("-",""),second.payableMinor())).isEqualTo(1);
            payment=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM payment WHERE payment_no=?",String.class,"EX"+run.replace("-",""));owned.get("payment").add(payment);
        });
    }
    Map<String,List<Map<String,String>>> validate(){
        var frozen=new TreeMap<String,List<Map<String,String>>>();
        for(String table:OrderExpiryAcceptanceGuardTest.RESOURCES){var rows=MySqlAcceptanceSupport.rows(jdbc,table,columns.get(table),false);
            assertThat(rows).hasSize(OrderExpiryAcceptanceGuardTest.CAPS.get("INSERT "+table));frozen.put(table,rows);}
        var addressRow=frozen.get("customer_address").getFirst();assertThat(addressRow).containsEntry("id",address).containsEntry("customer_id",owner).containsEntry("recipient",label).containsEntry("detail",label);
        assertThat(frozen.get("stock_balance").getFirst()).containsEntry("id",balance).containsEntry("warehouse_id",warehouse).containsEntry("sku_id",sku).containsEntry("on_hand","2").containsEntry("reserved","1");
        var lines=new HashMap<String,String>();
        for(int i=0;i<2;i++){String id=orders.get(i).id();var row=frozen.get("sales_order").stream().filter(r->id.equals(r.get("id"))).findFirst().orElseThrow();
            assertThat(row).containsEntry("customer_id",owner).containsEntry("warehouse_id",warehouse).containsEntry("remark",label+"-"+i).containsEntry("state",i==0?"CANCELLED":"CLOSING").containsEntry("version","1");
            var quote=frozen.get("checkout_quote").stream().filter(r->r.get("id").equals(row.get("quote_id"))).findFirst().orElseThrow();assertThat(owned.get("checkout_quote")).contains(quote.get("id"));assertThat(quote).containsEntry("customer_id",owner).containsEntry("used_order_id",id);
            var item=frozen.get("sales_order_item").stream().filter(r->id.equals(r.get("order_id"))).findFirst().orElseThrow();assertThat(item).containsEntry("sku_id",sku).containsEntry("quantity","1");owned.get("sales_order_item").add(item.get("id"));lines.put(item.get("id"),id);
            var reservation=frozen.get("stock_reservation").stream().filter(r->item.get("id").equals(r.get("business_line_id"))).findFirst().orElseThrow();assertThat(reservation).containsEntry("business_type","ORDER").containsEntry("warehouse_id",warehouse).containsEntry("sku_id",sku).containsEntry("quantity","1").containsEntry("state",i==0?"RELEASED":"HELD");owned.get("stock_reservation").add(reservation.get("id"));
        }
        for(var row:frozen.get("stock_ledger")){assertThat(lines.get(row.get("biz_line_id"))).isEqualTo(row.get("biz_id"));assertThat(row).containsEntry("biz_type","ORDER").containsEntry("warehouse_id",warehouse).containsEntry("sku_id",sku);
            if("RELEASE".equals(row.get("action")))assertThat(row.get("biz_id")).isEqualTo(orders.getFirst().id());else assertThat(row).containsEntry("action","RESERVE").containsEntry("trace_id","seed:"+run);
            owned.get("stock_ledger").add(row.get("id"));}
        assertThat(frozen.get("stock_ledger").stream().filter(r->"RELEASE".equals(r.get("action"))).count()).isEqualTo(1);
        assertThat(frozen.get("payment").getFirst()).containsEntry("id",payment).containsEntry("order_id",orders.get(1).id()).containsEntry("payment_no","EX"+run.replace("-","")).containsEntry("state","PROCESSING").containsEntry("channel","MOCK");
        OrderExpiryCleanupEvidence.verify(frozen,frozen,owned,leaseOwner);return frozen;
    }
}
