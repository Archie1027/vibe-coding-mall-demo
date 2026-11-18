package com.zhicai.platform;

import com.fasterxml.jackson.databind.*;
import com.zhicai.platform.trade.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** No channel adapter, callback, scheduler, migration or daily configuration is enabled. */
@EnabledIfEnvironmentVariable(named="M1_CANCEL_HTTP_MODE",matches="read-only|approved-single-order-cancel")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.flyway.enabled=false","spring.sql.init.mode=never","app.dev-bootstrap.enabled=false",
    "app.catalog.writes-enabled=false","app.customer.writes-enabled=true","app.order.writes-enabled=true","debug=false"})
@Import(MySqlOrderCancelHttpIntegrationTest.GuardConfiguration.class)
class MySqlOrderCancelHttpIntegrationTest {
    static final String URL="jdbc:mysql://127.0.0.1:3306/zhicai_dev?connectionTimeZone=UTC";
    static final Path BATCH=Path.of(".local/acceptance");
    static final String BASELINE_SHA="0000000000000000000000000000000000000000000000000000000000000000";
    static final List<String> RESOURCES=List.of("account","customer_address","stock_balance","checkout_quote","sales_order","sales_order_item","stock_reservation","stock_ledger","payment","idempotency_request","audit_log");
    static final Map<String,Integer> CAPS=Map.ofEntries(
        Map.entry("INSERT account",1),Map.entry("INSERT customer_address",1),Map.entry("INSERT stock_balance",1),
        Map.entry("INSERT checkout_quote",4),Map.entry("INSERT sales_order",4),Map.entry("INSERT sales_order_item",4),
        Map.entry("INSERT stock_reservation",4),Map.entry("INSERT stock_ledger",7),Map.entry("INSERT payment",5),
        Map.entry("INSERT idempotency_request",8),Map.entry("INSERT audit_log",3),
        Map.entry("UPDATE stock_balance",7),Map.entry("UPDATE checkout_quote",4),Map.entry("UPDATE stock_reservation",3),
        Map.entry("UPDATE sales_order",4),Map.entry("UPDATE payment",1),Map.entry("UPDATE idempotency_request",8));
    static final Map<String,Integer> ROW_COUNTS=Map.ofEntries(Map.entry("account",1),Map.entry("customer_address",1),Map.entry("stock_balance",1),
        Map.entry("checkout_quote",4),Map.entry("sales_order",4),Map.entry("sales_order_item",4),Map.entry("stock_reservation",4),
        Map.entry("stock_ledger",7),Map.entry("payment",4),Map.entry("idempotency_request",6),Map.entry("audit_log",3));
    static boolean approved(){return "approved-single-order-cancel".equals(System.getenv("M1_CANCEL_HTTP_MODE"))
        && System.getenv("M1_CANCEL_HTTP_APPROVAL_ID")!=null&&!System.getenv("M1_CANCEL_HTTP_APPROVAL_ID").isBlank();}
    static final MySqlAcceptanceSupport.SqlGuard GUARD=new MySqlAcceptanceSupport.SqlGuard(CAPS,RESOURCES,39,MySqlOrderCancelHttpIntegrationTest::approved);
    static final String REPLAY_KEY=Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
        if(!URL.equals(System.getenv("DB_URL")))throw new IllegalStateException("Canonical local UTC target required");
        p.add("spring.datasource.url",()->URL);p.add("spring.datasource.username",()->System.getenv("DB_USERNAME"));p.add("spring.datasource.password",()->System.getenv("DB_PASSWORD"));
        p.add("spring.datasource.hikari.connection-init-sql",()->"SET time_zone = '+00:00'");p.add("spring.flyway.enabled",()->false);
        p.add("spring.sql.init.mode",()->"never");p.add("app.dev-bootstrap.enabled",()->false);p.add("app.catalog.writes-enabled",()->false);
        p.add("app.customer.writes-enabled",()->true);p.add("app.order.writes-enabled",()->true);p.add("app.catalog.replay-key",()->REPLAY_KEY);
    }
    @TestConfiguration static class GuardConfiguration {
        @Bean static BeanPostProcessor guard(){return new BeanPostProcessor(){
            @Override public Object postProcessAfterInitialization(Object bean,String name){return bean instanceof DataSource source?GUARD.dataSource(source):bean;}
        };}
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired StringRedisTemplate redis;
    @Autowired PlatformTransactionManager manager;
    @Autowired QuoteService quotes;
    @Autowired OrderService orders;
    @Autowired OrderClosingService closing;
    @Autowired PaymentClosingMapper payments;
    final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
    final String run=UUID.randomUUID().toString(),name="取消验收-"+run,otherName="cancel-"+UUID.randomUUID();
    final Map<String,List<String>> columns=new TreeMap<>();
    final Map<String,List<Map<String,String>>> original=new TreeMap<>();
    final Map<String,String> baseline=new TreeMap<>(),claimActors=new LinkedHashMap<>(),claimRoutes=new LinkedHashMap<>();
    final List<OrderModels.Order> created=new ArrayList<>();
    final List<String> checks=new ArrayList<>(),tokens=new ArrayList<>();
    final Set<String> auditTraces=new HashSet<>();
    Path manifest;
    String owner,warehouse,sku,address,balance,outsider,otherPassword,customerToken,otherToken;
    String activePayment;

    @Test void singleBoundedScenario()throws Exception {
        prepare();securityPreflight();
        if(!approved()){
            assertThat(System.getenv("M1_CANCEL_HTTP_MODE")).isEqualTo("read-only");assertThat(GUARD.snapshot()).isEmpty();assertThat(GUARD.locks.get()).isZero();
            assertAllRestored();note(Map.of("stage","read-only-verified","tables",26,"sqlMutationAttempts",0,"lockingSelects",0,"digests",baseline));return;
        }
        assertThat(System.getenv("M1_CANCEL_HTTP_FIXTURE_SHA256")).matches("[A-Fa-f0-9]{64}");
        assertThat(System.getenv("M1_CANCEL_HTTP_IMPLEMENTATION_SHA256")).matches("[A-Fa-f0-9]{64}");
        try{
            note(Map.of("stage","write-intent","run",run,"caps",CAPS,"maxCommittedRows",39,"deleteCap",39));GUARD.armed=true;
            seed();customerToken=login("customer",System.getenv("M1_CANCEL_HTTP_CUSTOMER_PASSWORD"),"customer");otherToken=login(otherName,otherPassword,"customer");
            String foreignKey=claim(outsider,created.get(0).id());
            expect(cancel(created.get(0).id(),"other owner",otherToken,foreignKey),404,"NOT_FOUND");
            assertThat(state(created.get(0).id())).isEqualTo("UNPAID");assertThat(reserved()).isEqualTo(4);
            noPaymentConcurrentCancel();activeClosing();unknownDoesNotRelease();allTerminalCancel();
            logoutAll();assertOldRowsPreserved();
            var frozen=freezeAndValidate();note(Map.of("stage","checks-passed","checks",checks,"sqlAttempts",GUARD.snapshot(),"committedRows",39,"rowProofs",proofs(frozen)));
            cleanup(frozen);assertAllRestored();
            note(Map.of("stage","cleaned-and-verified","tables",26,"digests",baseline,"sqlAttempts",GUARD.snapshot(),"deleteAttempts",GUARD.deletes.get(),"autoIncrementRestored",false));
        }catch(Exception|AssertionError failure){note(Map.of("stage","retained-after-failure","exceptionClass",failure.getClass().getName(),"run",run,"knownOrders",created.stream().map(OrderModels.Order::id).toList(),"sqlAttempts",GUARD.snapshot(),"deleteAttempts",GUARD.deletes.get()));throw failure;}
        finally{GUARD.armed=false;GUARD.cleanup=false;}
    }
    void prepare()throws Exception {
        assertThat(Path.of(System.getenv("M1_CANCEL_HTTP_EVIDENCE_ROOT")).toRealPath()).isEqualTo(BATCH.toRealPath());
        Path directory=BATCH.resolve("run-"+run);Files.createDirectory(directory);manifest=directory.resolve("manifest.jsonl");Files.createFile(manifest);
        var bytes=Files.readAllBytes(BATCH.resolve("baseline-v10.json"));assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).isEqualTo(BASELINE_SHA);
        var reference=json.readTree(bytes);var expected=new TreeSet<String>();for(var row:reference.path("Rows"))expected.add(row.path("table").asText());assertThat(expected).hasSize(26);
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("zhicai_dev");assertThat(jdbc.queryForObject("SELECT @@session.time_zone",String.class)).isEqualTo("+00:00");
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'",String.class)).containsExactlyInAnyOrderElementsOf(expected);
        for(String table:expected){assertThat(table).matches("[a-z_]+");columns.put(table,jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position",String.class,table));}
        baseline.putAll(allDigests());for(var row:reference.path("Rows"))assertThat(baseline.get(row.path("table").asText())).isEqualTo(row.path("count").asInt()+":"+row.path("sha256").asText());
        for(String table:expected)original.put(table,rows(table));
        for(String table:RESOURCES)if(!table.equals("account"))assertThat(rows(table)).isEmpty();
        owner=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM account WHERE account_type='CUSTOMER' AND username='customer' AND enabled=1",String.class);
        warehouse=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM warehouse WHERE enabled=1 AND fulfillment_enabled=1 ORDER BY priority,id LIMIT 1",String.class);
        sku=jdbc.queryForObject("SELECT CAST(s.id AS CHAR) FROM sku s JOIN product p ON p.id=s.product_id JOIN category c ON c.id=p.category_id JOIN brand b ON b.id=p.brand_id WHERE s.enabled=1 AND p.state='ON_SALE' AND c.enabled=1 AND b.enabled=1 ORDER BY s.id LIMIT 1",String.class);
        assertThat(payments.facts("1")).isEqualTo(new PaymentClosingMapper.Facts(0,0,0,0));
        note(Map.of("stage","preflight-verified","mode",System.getenv("M1_CANCEL_HTTP_MODE"),"run",run,"digests",baseline));
    }
    void securityPreflight()throws Exception {
        expect(cancel("1","r",null,UUID.randomUUID().toString()),401,null);
        String staff=login("admin",System.getenv("M1_CANCEL_HTTP_STAFF_PASSWORD"),"staff");
        expect(cancel("1","r",staff,UUID.randomUUID().toString()),403,null);logout(staff);
        String customer=login("customer",System.getenv("M1_CANCEL_HTTP_CUSTOMER_PASSWORD"),"customer");
        expect(cancel("0","r",customer,UUID.randomUUID().toString()),400,null);
        expect(send("POST","/api/v1/orders/1/cancel","{}",customer,UUID.randomUUID().toString()),400,null);
        expect(cancel("1","r",customer,null),400,null);logout(customer);
        assertThat(tokens).isEmpty();assertThat(GUARD.snapshot()).isEmpty();assertThat(GUARD.locks.get()).isZero();
    }
    void seed(){
        otherPassword=UUID.randomUUID().toString();
        assertThat(jdbc.update("INSERT INTO account(account_type,username,password_hash,display_name,enabled) VALUES('CUSTOMER',?,?,?,TRUE)",otherName,passwords.encode(otherPassword),name)).isEqualTo(1);
        outsider=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM account WHERE account_type='CUSTOMER' AND BINARY username=BINARY ?",String.class,otherName);
        assertThat(jdbc.update("INSERT INTO customer_address(customer_id,recipient,phone,province,city,district,detail,is_default) VALUES(CAST(? AS UNSIGNED),?,'10000000007','测试省','测试市','测试区',?,FALSE)",owner,name,name)).isEqualTo(1);
        address=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM customer_address WHERE customer_id=CAST(? AS UNSIGNED) AND BINARY recipient=BINARY ? AND BINARY detail=BINARY ?",String.class,owner,name,name);
        assertThat(jdbc.update("INSERT INTO stock_balance(warehouse_id,sku_id,on_hand,reserved,quarantine) VALUES(CAST(? AS UNSIGNED),CAST(? AS UNSIGNED),4,0,0)",warehouse,sku)).isEqualTo(1);
        balance=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM stock_balance WHERE warehouse_id=CAST(? AS UNSIGNED) AND sku_id=CAST(? AS UNSIGNED)",String.class,warehouse,sku);
        for(int i=0;i<4;i++){final int index=i;created.add(Objects.requireNonNull(tx(false).execute(s->{
            var quote=quotes.create(owner,new QuoteModels.Request(address,List.of(new QuoteModels.Item(sku,1)),null));
            return orders.create(owner,new OrderModels.Create(quote.id(),name+"-"+index),"seed:"+run);
        })));}
        activePayment=insertPayment(1,"PROCESSING",0);insertPayment(2,"SUCCEEDED",1);insertPayment(3,"FAILED",2);insertPayment(3,"CLOSED",3);
        assertThatThrownBy(()->insertPayment(1,"CREATED",4)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(reserved()).isEqualTo(4);assertThat(rows("payment")).hasSize(4);
        checks.add("live MySQL unique generated active_order_id rejects second active attempt without changing four committed payments");
    }
    String insertPayment(int orderIndex,String state,int sequence){return Objects.requireNonNull(tx(false).execute(s->{
        var order=created.get(orderIndex);jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM sales_order WHERE id=CAST(? AS UNSIGNED) FOR UPDATE",String.class,order.id());
        String number="CAN"+run.replace("-","")+sequence;
        assertThat(jdbc.update("INSERT INTO payment(order_id,payment_no,channel,state,amount_minor,currency,channel_transaction_id) VALUES(CAST(? AS UNSIGNED),?,'MOCK',?,CAST(? AS UNSIGNED),'CNY',?)",order.id(),number,state,order.payableMinor(),state.equals("SUCCEEDED")?"txn-"+run:null)).isEqualTo(1);
        return jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM payment WHERE payment_no=?",String.class,number);
    }));}
    void noPaymentConcurrentCancel()throws Exception {
        String id=created.get(0).id(),key=claim(owner,id);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        List<HttpResponse<String>> responses=new ArrayList<>();
        try(var executor=Executors.newFixedThreadPool(2)){
            var futures=new ArrayList<Future<HttpResponse<String>>>();
            for(int i=0;i<2;i++)futures.add(executor.submit(()->{ready.countDown();if(!start.await(5,TimeUnit.SECONDS))throw new AssertionError("start timeout");return cancel(id,name,customerToken,key);}));
            try{assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();}finally{start.countDown();}
            for(var future:futures)responses.add(future.get(25,TimeUnit.SECONDS));
        }
        assertThat(responses.stream().filter(r->r.statusCode()==200).count()).isGreaterThanOrEqualTo(1);
        var success=responses.stream().filter(r->r.statusCode()==200).findFirst().orElseThrow();
        assertOrder(success,id,"CANCELLED",1);for(var response:responses){if(response.statusCode()==200)assertThat(response.body()).isEqualTo(success.body());else expect(response,409,"REQUEST_IN_PROGRESS");}
        var replay=cancel(id,name,customerToken,key);expect(replay,200,null);assertThat(replay.body()).isEqualTo(success.body());
        expect(cancel(id,name+"变更",customerToken,key),409,"IDEMPOTENCY_CONFLICT");
        assertThat(reserved()).isEqualTo(3);assertThat(countRelease(id)).isEqualTo(1);
        checks.add("concurrent same-key HTTP cancel: one state transition, one stock release and exact durable replay; changed reason conflicts");
    }
    void activeClosing()throws Exception {
        String id=created.get(1).id(),key=claim(owner,id);var result=cancel(id,name,customerToken,key);assertOrder(result,id,"CLOSING",1);
        assertThat(reserved()).isEqualTo(3);assertThat(countRelease(id)).isZero();
        var stillActive=tx(false).execute(s->closing.completeClosing(id,"active:"+run));assertThat(stillActive).isEmpty();
        expect(cancel(id,name,customerToken,claim(owner,id)),409,"STATE_CONFLICT");
        tx(false).executeWithoutResult(s->{jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM sales_order WHERE id=CAST(? AS UNSIGNED) FOR UPDATE",String.class,id);
            assertThat(jdbc.update("UPDATE payment SET state='CLOSED',version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=CAST(? AS UNSIGNED) AND order_id=CAST(? AS UNSIGNED) AND state='PROCESSING'",activePayment,id)).isEqualTo(1);});
        var finished=tx(false).execute(s->closing.completeClosing(id,"finish:"+run));assertThat(finished).isPresent();assertThat(finished.orElseThrow().state()).isEqualTo("CANCELLED");
        assertThat(state(id)).isEqualTo("CANCELLED");assertThat(reserved()).isEqualTo(2);assertThat(countRelease(id)).isEqualTo(1);
        var replay=cancel(id,name,customerToken,key);expect(replay,200,null);assertThat(replay.body()).isEqualTo(result.body());
        checks.add("real PROCESSING payment keeps reservation in CLOSING; only persisted CLOSED permits internal final release; original HTTP CLOSING response remains replayable");
    }
    void unknownDoesNotRelease()throws Exception {
        String id=created.get(2).id(),key=claim(owner,id);var before=businessDigests();var result=cancel(id,name,customerToken,key);
        expect(result,503,"DEPENDENCY_UNAVAILABLE");assertThat(json.readTree(result.body()).path("message").asText()).isEqualTo("支付状态尚未证明可关闭");
        assertThat(businessDigests()).isEqualTo(before);assertThat(state(id)).isEqualTo("UNPAID");assertThat(reserved()).isEqualTo(2);assertThat(countRelease(id)).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM idempotency_request WHERE actor_scope=? AND key_hash=?",String.class,"CUSTOMER:"+owner,MySqlAcceptanceSupport.sha(key))).isEqualTo("IN_PROGRESS");
        checks.add("SUCCEEDED local payment inconsistent with UNPAID order is UNKNOWN: precise 503, no release/state change, original claim retained");
    }
    void allTerminalCancel()throws Exception {
        String id=created.get(3).id();assertOrder(cancel(id,name,customerToken,claim(owner,id)),id,"CANCELLED",1);
        assertThat(reserved()).isEqualTo(1);assertThat(countRelease(id)).isEqualTo(1);
        checks.add("all historical FAILED/CLOSED attempts allow immediate cancellation; only the inconsistent payment order retains stock");
    }
    void assertOrder(HttpResponse<String> response,String id,String state,long version)throws Exception {
        expect(response,200,null);var body=json.readTree(response.body());var order=body.path("data");
        assertThat(order.path("id").asText()).isEqualTo(id);assertThat(order.path("customerId").asText()).isEqualTo(owner);
        assertThat(order.path("state").asText()).isEqualTo(state);assertThat(order.path("version").asLong()).isEqualTo(version);assertThat(order.path("items")).hasSize(1);
        assertThat(order.path("payableMinor").isTextual()).isTrue();auditTraces.add(body.path("traceId").asText());
    }
    Map<String,List<Map<String,String>>> freezeAndValidate()throws Exception {
        var frozen=new LinkedHashMap<String,List<Map<String,String>>>();
        for(String table:RESOURCES){var newRows=newRows(table);assertThat(newRows.size()).as(table).isEqualTo(ROW_COUNTS.get(table));frozen.put(table,newRows);}
        var account=frozen.get("account").getFirst();assertThat(account.get("id")).isEqualTo(outsider);assertThat(account.get("username")).isEqualTo(otherName);assertThat(account.get("account_type")).isEqualTo("CUSTOMER");assertThat(passwords.matches(otherPassword,account.get("password_hash"))).isTrue();
        var addr=frozen.get("customer_address").getFirst();assertThat(addr.get("id")).isEqualTo(address);assertThat(addr.get("customer_id")).isEqualTo(owner);assertThat(addr.get("recipient")).isEqualTo(name);assertThat(addr.get("detail")).isEqualTo(name);
        var stock=frozen.get("stock_balance").getFirst();assertThat(stock.get("id")).isEqualTo(balance);assertThat(stock.get("warehouse_id")).isEqualTo(warehouse);assertThat(stock.get("sku_id")).isEqualTo(sku);assertThat(stock.get("on_hand")).isEqualTo("4");assertThat(stock.get("reserved")).isEqualTo("1");
        Set<String> orderIds=new HashSet<>(created.stream().map(OrderModels.Order::id).toList()),lineIds=new HashSet<>();
        for(int i=0;i<4;i++){
            var order=created.get(i);var row=frozen.get("sales_order").stream().filter(r->r.get("id").equals(order.id())).findFirst().orElseThrow();
            assertThat(row.get("customer_id")).isEqualTo(owner);assertThat(row.get("warehouse_id")).isEqualTo(warehouse);assertThat(row.get("remark")).isEqualTo(name+"-"+i);
            assertThat(row.get("state")).isEqualTo(i==2?"UNPAID":"CANCELLED");
            var quote=frozen.get("checkout_quote").stream().filter(r->r.get("id").equals(row.get("quote_id"))).findFirst().orElseThrow();assertThat(quote.get("customer_id")).isEqualTo(owner);assertThat(quote.get("used_order_id")).isEqualTo(order.id());
            var item=frozen.get("sales_order_item").stream().filter(r->r.get("order_id").equals(order.id())).findFirst().orElseThrow();assertThat(item.get("sku_id")).isEqualTo(sku);assertThat(item.get("quantity")).isEqualTo("1");lineIds.add(item.get("id"));
            var reservation=frozen.get("stock_reservation").stream().filter(r->r.get("business_line_id").equals(item.get("id"))).findFirst().orElseThrow();assertThat(reservation.get("state")).isEqualTo(i==2?"HELD":"RELEASED");assertThat(reservation.get("business_type")).isEqualTo("ORDER");
        }
        for(var row:frozen.get("stock_ledger")){assertThat(orderIds).contains(row.get("biz_id"));assertThat(lineIds).contains(row.get("biz_line_id"));assertThat(row.get("biz_type")).isEqualTo("ORDER");assertThat(row.get("warehouse_id")).isEqualTo(warehouse);assertThat(row.get("sku_id")).isEqualTo(sku);assertThat(row.get("action")).isIn("RESERVE","RELEASE");}
        for(var row:frozen.get("payment")){assertThat(orderIds).contains(row.get("order_id"));assertThat(row.get("payment_no")).startsWith("CAN"+run.replace("-",""));assertThat(row.get("channel")).isEqualTo("MOCK");}
        for(var row:frozen.get("idempotency_request")){assertThat(claimActors.get(row.get("key_hash"))).isEqualTo(row.get("actor_scope"));assertThat(claimRoutes.get(row.get("key_hash"))).isEqualTo(row.get("route"));assertThat(row.get("method")).isEqualTo("POST");}
        for(var row:frozen.get("audit_log")){assertThat(row.get("actor_id")).isEqualTo(owner);assertThat(row.get("action")).isEqualTo("ORDER_CANCEL");assertThat(row.get("resource_type")).isEqualTo("ORDER");assertThat(orderIds).contains(row.get("resource_id"));assertThat(auditTraces).contains(row.get("trace_id"));assertThat(row.get("masked_before_after")).doesNotContain(name,"10000000007",otherPassword);}
        assertThat(GUARD.snapshot().entrySet().stream().filter(e->e.getKey().startsWith("INSERT ")).mapToInt(Map.Entry::getValue).sum()).isBetween(40,42);
        return frozen;
    }
    void cleanup(Map<String,List<Map<String,String>>> frozen){
        var transaction=tx(false);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);transaction.setTimeout(30);
        transaction.executeWithoutResult(s->{
            for(String table:RESOURCES){var locked=MySqlAcceptanceSupport.rows(jdbc,table,columns.get(table),true);var expected=new ArrayList<>(original.get(table));expected.addAll(frozen.get(table));assertThat(MySqlAcceptanceSupport.digest(locked)).as(table).isEqualTo(MySqlAcceptanceSupport.digest(expected));}
            GUARD.cleanup=true;
            for(String table:List.of("audit_log","idempotency_request","payment","stock_ledger","stock_reservation","sales_order_item","sales_order","checkout_quote","stock_balance","customer_address","account"))
                for(var row:frozen.get(table))assertThat(jdbc.update("DELETE FROM `"+table+"` WHERE id=CAST(? AS UNSIGNED) AND "+MySqlAcceptanceSupport.hashSql(columns.get(table))+"=?",row.get("id"),row.get("_guard_hash"))).isEqualTo(1);
        });GUARD.cleanup=false;assertThat(GUARD.deletes.get()).isEqualTo(39);
    }
    Map<String,List<Map<String,String>>> proofs(Map<String,List<Map<String,String>>> frozen){var result=new LinkedHashMap<String,List<Map<String,String>>>();frozen.forEach((table,values)->result.put(table,values.stream().map(row->Map.of("id",row.get("id"),"fullRowHash",row.get("_guard_hash"))).toList()));return result;}
    List<Map<String,String>> rows(String table){return MySqlAcceptanceSupport.rows(jdbc,table,columns.get(table),false);}
    List<Map<String,String>> newRows(String table){var ids=new HashSet<>(original.get(table).stream().map(r->r.get("id")).toList());return rows(table).stream().filter(r->!ids.contains(r.get("id"))).toList();}
    void assertOldRowsPreserved(){for(String table:columns.keySet()){
        if(!RESOURCES.contains(table)){assertThat(MySqlAcceptanceSupport.digest(rows(table))).isEqualTo(MySqlAcceptanceSupport.digest(original.get(table)));continue;}
        var ids=new HashSet<>(original.get(table).stream().map(r->r.get("id")).toList());assertThat(MySqlAcceptanceSupport.digest(rows(table).stream().filter(r->ids.contains(r.get("id"))).toList())).isEqualTo(MySqlAcceptanceSupport.digest(original.get(table)));
    }}
    Map<String,String> businessDigests(){var result=new TreeMap<String,String>();for(String table:RESOURCES)if(!table.equals("idempotency_request"))result.put(table,MySqlAcceptanceSupport.digest(rows(table)));return result;}
    Map<String,String> allDigests(){return Objects.requireNonNull(tx(true).execute(s->{var result=new TreeMap<String,String>();for(var entry:columns.entrySet()){int count=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM `"+entry.getKey()+"`",Integer.class));result.put(entry.getKey(),count+":"+MySqlAcceptanceSupport.referenceDigest(jdbc,entry.getKey(),entry.getValue(),count));}return result;}));}
    void assertAllRestored(){assertThat(allDigests()).isEqualTo(baseline);}
    TransactionTemplate tx(boolean readOnly){var tx=new TransactionTemplate(manager);tx.setIsolationLevel(readOnly?TransactionDefinition.ISOLATION_REPEATABLE_READ:TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setReadOnly(readOnly);tx.setTimeout(20);return tx;}
    int reserved(){return Objects.requireNonNull(jdbc.queryForObject("SELECT reserved FROM stock_balance WHERE id=CAST(? AS UNSIGNED)",Integer.class,balance));}
    int countRelease(String id){return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE biz_type='ORDER' AND biz_id=CAST(? AS UNSIGNED) AND action='RELEASE'",Integer.class,id));}
    String state(String id){return jdbc.queryForObject("SELECT state FROM sales_order WHERE id=CAST(? AS UNSIGNED)",String.class,id);}
    String claim(String actor,String id){String key=UUID.randomUUID().toString(),hash=MySqlAcceptanceSupport.sha(key);claimActors.put(hash,"CUSTOMER:"+actor);claimRoutes.put(hash,"/api/v1/orders/"+id+"/cancel");return key;}
    String login(String username,String password,String type)throws Exception {
        assertThat(password!=null&&!password.isBlank()).isTrue();
        String savedHash=jdbc.queryForObject("SELECT password_hash FROM account WHERE account_type=? AND username=? AND enabled=1",String.class,type.equals("staff")?"STAFF":"CUSTOMER",username);
        assertThat(passwords.matches(password,savedHash)).as("Saved credentials must match before HTTP authentication").isTrue();
        var result=send("POST","/api/v1/auth/"+type+"/login",json.writeValueAsString(Map.of("username",username,"password",password)),null,null);expect(result,200,null);
        String token=json.readTree(result.body()).path("data").path("accessToken").asText();assertThat(token.matches("[A-Za-z0-9_-]{43}")).isTrue();assertThat(redis.hasKey("zhicai:auth:session:"+MySqlAcceptanceSupport.sha(token))).isTrue();tokens.add(token);return token;
    }
    void logout(String token)throws Exception {expect(send("POST","/api/v1/auth/logout",null,token,null),200,null);assertThat(redis.hasKey("zhicai:auth:session:"+MySqlAcceptanceSupport.sha(token))).isFalse();tokens.remove(token);}
    void logoutAll()throws Exception {for(String token:List.copyOf(tokens))logout(token);}
    HttpResponse<String> cancel(String id,String reason,String token,String key)throws Exception{return send("POST","/api/v1/orders/"+id+"/cancel",json.writeValueAsString(Map.of("reason",reason)),token,key);}
    HttpResponse<String> send(String method,String route,String body,String token,String key)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+route)).version(HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(20)).header("Accept","application/json");
        if(token!=null)request.header("Authorization","Bearer "+token);if(key!=null)request.header("Idempotency-Key",key);if(body!=null)request.header("Content-Type","application/json");
        return http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    void expect(HttpResponse<String> response,int status,String code)throws Exception {assertThat(response.statusCode()).isEqualTo(status);if(code!=null)assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);}
    void note(Map<String,?> value)throws Exception {Files.writeString(manifest,json.writeValueAsString(value)+"\n",StandardOpenOption.APPEND);}
}
