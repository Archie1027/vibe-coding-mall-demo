package com.zhicai.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.trade.*;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
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
import static org.assertj.core.api.Assertions.*;

/** Real browser candidate. Unapproved SQL is rejected before the driver. */
@EnabledIfEnvironmentVariable(named="M1_CANCEL_BROWSER_MODE",matches="read-only|approved-single-order-cancel-browser")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "server.address=127.0.0.1","spring.flyway.enabled=false","spring.sql.init.mode=never","app.dev-bootstrap.enabled=false",
    "app.catalog.writes-enabled=false","app.customer.writes-enabled=true","app.order.writes-enabled=true","debug=false"})
@Import(MySqlOrderCancelBrowserIntegrationTest.GuardConfiguration.class)
class MySqlOrderCancelBrowserIntegrationTest {
    static final String URL="jdbc:mysql://127.0.0.1:3306/zhicai_dev?connectionTimeZone=UTC";
    static final Path BATCH=Path.of(".local/acceptance");
    static final String BASELINE_SHA="0000000000000000000000000000000000000000000000000000000000000000";
    static final List<String> RESOURCES=List.of("customer_address","stock_balance","checkout_quote","sales_order",
        "sales_order_item","stock_reservation","stock_ledger","payment","idempotency_request","audit_log");
    static final Map<String,Integer> CAPS=Map.ofEntries(
        Map.entry("INSERT customer_address",1),Map.entry("INSERT stock_balance",1),Map.entry("INSERT checkout_quote",2),
        Map.entry("INSERT sales_order",2),Map.entry("INSERT sales_order_item",2),Map.entry("INSERT stock_reservation",2),
        Map.entry("INSERT stock_ledger",4),Map.entry("INSERT payment",1),Map.entry("INSERT idempotency_request",2),Map.entry("INSERT audit_log",2),
        Map.entry("UPDATE stock_balance",4),Map.entry("UPDATE checkout_quote",2),Map.entry("UPDATE stock_reservation",2),
        Map.entry("UPDATE sales_order",3),Map.entry("UPDATE payment",1),Map.entry("UPDATE idempotency_request",2));
    static final Map<String,Integer> ROW_COUNTS=Map.ofEntries(
        Map.entry("customer_address",1),Map.entry("stock_balance",1),Map.entry("checkout_quote",2),Map.entry("sales_order",2),
        Map.entry("sales_order_item",2),Map.entry("stock_reservation",2),Map.entry("stock_ledger",4),Map.entry("payment",1),
        Map.entry("idempotency_request",2),Map.entry("audit_log",2));
    static boolean approved(){
        String approval=System.getenv("M1_CANCEL_BROWSER_APPROVAL_ID");
        return "approved-single-order-cancel-browser".equals(System.getenv("M1_CANCEL_BROWSER_MODE"))
            && approval!=null&&!approval.isBlank();
    }
    static final MySqlAcceptanceSupport.SqlGuard GUARD=new MySqlAcceptanceSupport.SqlGuard(CAPS,RESOURCES,19,MySqlOrderCancelBrowserIntegrationTest::approved);
    static final String REPLAY_KEY=Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties){
        if(!URL.equals(System.getenv("DB_URL")))throw new IllegalStateException("Canonical local UTC target required");
        properties.add("spring.datasource.url",()->URL);
        properties.add("spring.datasource.username",()->System.getenv("DB_USERNAME"));
        properties.add("spring.datasource.password",()->System.getenv("DB_PASSWORD"));
        properties.add("spring.datasource.hikari.connection-init-sql",()->"SET time_zone = '+00:00'");
        properties.add("spring.flyway.enabled",()->false);properties.add("spring.sql.init.mode",()->"never");
        properties.add("app.dev-bootstrap.enabled",()->false);properties.add("app.catalog.writes-enabled",()->false);
        properties.add("app.customer.writes-enabled",()->true);properties.add("app.order.writes-enabled",()->true);
        properties.add("app.catalog.replay-key",()->REPLAY_KEY);
    }
    @TestConfiguration static class GuardConfiguration {
        @Bean static BeanPostProcessor guard(){return new BeanPostProcessor(){
            @Override public Object postProcessAfterInitialization(Object bean,String name){
                return bean instanceof DataSource source?GUARD.dataSource(source):bean;
            }
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
    final String runId=UUID.randomUUID().toString(),name="网页取消验收-"+runId;
    final Map<String,List<String>> columns=new TreeMap<>();
    final Map<String,List<Map<String,String>>> original=new TreeMap<>();
    final Map<String,String> baseline=new TreeMap<>();
    final List<OrderModels.Order> created=new ArrayList<>();
    Path runDirectory,manifest;
    String owner,warehouse,sku,address,balance,payment;

    @Test void singleBoundedScenario()throws Exception {
        prepare();
        try{
            if(!approved()){
                assertThat(System.getenv("M1_CANCEL_BROWSER_MODE")).isEqualTo("read-only");
                runBrowser(false);
                assertThat(GUARD.snapshot()).isEmpty();assertThat(GUARD.locks.get()).isZero();assertRestored();
                note(Map.of("stage","read-only-verified","tables",26,"sqlMutationAttempts",0,"lockingSelects",0,"digests",baseline));
                return;
            }
            note(Map.of("stage","write-intent","runId",runId,"caps",CAPS,"rows",19));GUARD.armed=true;
            seed();
            var browserResult=runBrowser(true);
            assertOldRowsPreserved();
            var frozen=freezeAndValidate(browserResult);
            note(Map.of("stage","checks-passed","rows",19,"rowProofs",proofs(frozen),"sqlAttempts",GUARD.snapshot()));
            cleanup(frozen);assertRestored();
            note(Map.of("stage","cleaned-and-verified","tables",26,"deleteAttempts",GUARD.deletes.get(),"sqlAttempts",GUARD.snapshot(),"digests",baseline));
        }catch(Exception|AssertionError failure){
            note(Map.of("stage","retained-after-failure","exceptionClass",failure.getClass().getName(),"orders",created.stream().map(OrderModels.Order::id).toList(),"sqlAttempts",GUARD.snapshot(),"deleteAttempts",GUARD.deletes.get()));
            throw failure;
        }finally{GUARD.armed=false;GUARD.cleanup=false;}
    }
    void prepare()throws Exception {
        assertThat(Path.of(System.getenv("M1_CANCEL_BROWSER_EVIDENCE_ROOT")).toRealPath()).isEqualTo(BATCH.toRealPath());
        runDirectory=BATCH.resolve("run-"+runId);Files.createDirectory(runDirectory);
        manifest=runDirectory.resolve("manifest.jsonl");Files.createFile(manifest);
        byte[] bytes=Files.readAllBytes(BATCH.resolve("baseline-v10.json"));assertThat(hash(bytes)).isEqualTo(BASELINE_SHA);
        var reference=json.readTree(bytes);var expected=new TreeSet<String>();
        for(var row:reference.path("Rows"))expected.add(row.path("table").asText());assertThat(expected).hasSize(26);
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("zhicai_dev");
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone",String.class)).isEqualTo("+00:00");
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'",String.class)).containsExactlyInAnyOrderElementsOf(expected);
        for(String table:expected){
            assertThat(table).matches("[a-z_]+");
            columns.put(table,jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position",String.class,table));
        }
        baseline.putAll(allDigests());
        for(var row:reference.path("Rows"))assertThat(baseline.get(row.path("table").asText())).isEqualTo(row.path("count").asInt()+":"+row.path("sha256").asText());
        for(String table:expected)original.put(table,rows(table));
        for(String table:RESOURCES)assertThat(original.get(table)).isEmpty();
        owner=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM account WHERE account_type='CUSTOMER' AND username='customer' AND enabled=1",String.class);
        String password=System.getenv("M1_CANCEL_BROWSER_CUSTOMER_PASSWORD");assertThat(password).isNotBlank();
        String passwordHash=jdbc.queryForObject("SELECT password_hash FROM account WHERE id=CAST(? AS UNSIGNED)",String.class,owner);
        assertThat(passwords.matches(password,passwordHash)).isTrue();
        warehouse=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM warehouse WHERE enabled=1 AND fulfillment_enabled=1 ORDER BY priority,id LIMIT 1",String.class);
        sku=jdbc.queryForObject("SELECT CAST(s.id AS CHAR) FROM sku s JOIN product p ON p.id=s.product_id JOIN category c ON c.id=p.category_id JOIN brand b ON b.id=p.brand_id WHERE s.enabled=1 AND p.state='ON_SALE' AND c.enabled=1 AND b.enabled=1 ORDER BY s.id LIMIT 1",String.class);
        verifyFrontend();note(Map.of("stage","preflight-verified","mode",System.getenv("M1_CANCEL_BROWSER_MODE"),"runId",runId,"digests",baseline));
    }
    void seed(){
        assertThat(jdbc.update("INSERT INTO customer_address(customer_id,recipient,phone,province,city,district,detail,is_default) VALUES(CAST(? AS UNSIGNED),?,'10000000007','测试省','测试市','测试区',?,FALSE)",owner,name,name)).isEqualTo(1);
        address=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM customer_address WHERE customer_id=CAST(? AS UNSIGNED) AND BINARY recipient=BINARY ? AND BINARY detail=BINARY ?",String.class,owner,name,name);
        assertThat(jdbc.update("INSERT INTO stock_balance(warehouse_id,sku_id,on_hand,reserved,quarantine) VALUES(CAST(? AS UNSIGNED),CAST(? AS UNSIGNED),2,0,0)",warehouse,sku)).isEqualTo(1);
        balance=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM stock_balance WHERE warehouse_id=CAST(? AS UNSIGNED) AND sku_id=CAST(? AS UNSIGNED)",String.class,warehouse,sku);
        for(int i=0;i<2;i++){
            final int index=i;
            created.add(Objects.requireNonNull(tx(false).execute(status->{
                var quote=quotes.create(owner,new QuoteModels.Request(address,List.of(new QuoteModels.Item(sku,1)),null));
                return orders.create(owner,new OrderModels.Create(quote.id(),name+"-"+index),"seed:"+runId);
            })));
        }
        tx(false).executeWithoutResult(status->{
            var order=created.get(1);
            jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM sales_order WHERE id=CAST(? AS UNSIGNED) FOR UPDATE",String.class,order.id());
            assertThat(jdbc.update("INSERT INTO payment(order_id,payment_no,channel,state,amount_minor,currency) VALUES(CAST(? AS UNSIGNED),?,'MOCK','PROCESSING',CAST(? AS UNSIGNED),'CNY')",order.id(),"UI"+runId.replace("-",""),order.payableMinor())).isEqualTo(1);
        });
        payment=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM payment WHERE payment_no=?",String.class,"UI"+runId.replace("-",""));
        assertThat(reserved()).isEqualTo(2);noteUnchecked(Map.of("stage","seeded","orders",created.stream().map(OrderModels.Order::id).toList()));
    }
    JsonNode runBrowser(boolean write)throws Exception {
        verifyFrontend();
        Path project=Path.of(System.getenv("M1_CANCEL_BROWSER_PROJECT")).toRealPath();
        var processBuilder=new ProcessBuilder(System.getenv("M1_CANCEL_BROWSER_NODE"),project.resolve("web-storefront/tests/order-cancel-real-browser.cjs").toString());
        processBuilder.directory(project.toFile()).redirectErrorStream(true).redirectOutput(runDirectory.resolve("browser.log").toFile());
        var environment=processBuilder.environment();
        environment.put("M1_CANCEL_BROWSER_CHILD_MODE",write?"approved-single-cancel-browser":"preflight");
        environment.put("M1_CANCEL_BROWSER_API","http://127.0.0.1:"+port);
        environment.put("M1_CANCEL_BROWSER_RUN",runDirectory.toString());environment.put("M1_CANCEL_BROWSER_RUN_ID",runId);
        environment.put("M1_CANCEL_BROWSER_PASSWORD",System.getenv("M1_CANCEL_BROWSER_CUSTOMER_PASSWORD"));
        environment.put("M1_CANCEL_BROWSER_ORDER_IDS",json.writeValueAsString(created.stream().map(OrderModels.Order::id).toList()));
        for(String secret:List.of("DB_PASSWORD","REDIS_PASSWORD","CATALOG_REPLAY_KEY","M1_CANCEL_BROWSER_CUSTOMER_PASSWORD"))environment.remove(secret);
        Process process=processBuilder.start();note(Map.of("stage","browser-started","pid",process.pid()));
        long deadline=System.nanoTime()+Duration.ofSeconds(110).toNanos();boolean completed=false;
        try{
            while(!process.waitFor(100,TimeUnit.MILLISECONDS)){
                if(write&&!completed&&Files.exists(runDirectory.resolve("closing-ready.json"))){
                    var ready=json.readTree(Files.readAllBytes(runDirectory.resolve("closing-ready.json")));
                    assertThat(ready.path("runId").asText()).isEqualTo(runId);assertThat(ready.path("orderId").asText()).isEqualTo(created.get(1).id());
                    assertThat(ready.path("state").asText()).isEqualTo("CLOSING");finishClosing();
                    Path pending=runDirectory.resolve("closing-completed.pending");
                    Files.writeString(pending,json.writeValueAsString(Map.of("runId",runId,"orderId",created.get(1).id(),"state","CANCELLED")),StandardOpenOption.CREATE_NEW);
                    Files.move(pending,runDirectory.resolve("closing-completed.json"),StandardCopyOption.ATOMIC_MOVE);completed=true;
                }
                assertThat(System.nanoTime()).as("Browser deadline").isLessThan(deadline);
            }
            assertThat(process.exitValue()).isZero();if(write)assertThat(completed).isTrue();
        }finally{
            if(process.isAlive()){
                Files.writeString(runDirectory.resolve("stop-request.json"),"{}",StandardOpenOption.CREATE_NEW);
                if(!process.waitFor(25,TimeUnit.SECONDS)){process.destroy();process.waitFor(5,TimeUnit.SECONDS);}
            }
        }
        verifyFrontend();
        var result=json.readTree(Files.readAllBytes(runDirectory.resolve("browser-result.json")));
        assertThat(result.path("runId").asText()).isEqualTo(runId);assertThat(result.path("pageErrors").asInt()).isZero();
        String session=result.path("sessionKey").asText();assertThat(session).matches("zhicai:auth:session:[a-f0-9]{64}");assertThat(redis.hasKey(session)).isFalse();
        if(write){
            var first=result.path("cancelAttempts").path(created.get(0).id());assertThat(first).hasSize(2);assertThat(first.get(0)).isEqualTo(first.get(1));
            assertThat(result.path("cancelAttempts").path(created.get(1).id())).hasSize(1);
        }else{assertThat(result.path("cancelAttempts").size()).isZero();}
        note(Map.of("stage","browser-verified","write",write,"sessionRevoked",true));return result;
    }
    void finishClosing(){
        String first=created.get(0).id(),second=created.get(1).id();
        assertThat(state(first)).isEqualTo("CANCELLED");assertThat(state(second)).isEqualTo("CLOSING");
        assertThat(reserved()).isEqualTo(1);assertThat(releases(first)).isEqualTo(1);assertThat(releases(second)).isZero();
        tx(false).executeWithoutResult(status->{
            jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM sales_order WHERE id=CAST(? AS UNSIGNED) FOR UPDATE",String.class,second);
            assertThat(jdbc.update("UPDATE payment SET state='CLOSED',version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=CAST(? AS UNSIGNED) AND order_id=CAST(? AS UNSIGNED) AND state='PROCESSING'",payment,second)).isEqualTo(1);
            assertThat(closing.completeClosing(second,"browser:"+runId)).isPresent();
        });
        assertThat(state(second)).isEqualTo("CANCELLED");assertThat(reserved()).isZero();assertThat(releases(second)).isEqualTo(1);
        noteUnchecked(Map.of("stage","closing-committed","orderId",second));
    }
    Map<String,List<Map<String,String>>> freezeAndValidate(JsonNode result)throws Exception {
        var frozen=new LinkedHashMap<String,List<Map<String,String>>>();
        for(String table:RESOURCES){
            var values=rows(table);
            assertThat(values).as(table).hasSize(ROW_COUNTS.get(table));
            frozen.put(table,values);
        }
        var temporaryAddress=frozen.get("customer_address").getFirst();
        assertThat(temporaryAddress.get("id")).isEqualTo(address);
        assertThat(temporaryAddress.get("customer_id")).isEqualTo(owner);
        assertThat(temporaryAddress.get("recipient")).isEqualTo(name);
        assertThat(temporaryAddress.get("detail")).isEqualTo(name);
        var temporaryStock=frozen.get("stock_balance").getFirst();
        assertThat(temporaryStock.get("id")).isEqualTo(balance);
        assertThat(temporaryStock.get("warehouse_id")).isEqualTo(warehouse);
        assertThat(temporaryStock.get("sku_id")).isEqualTo(sku);
        assertThat(temporaryStock.get("on_hand")).isEqualTo("2");
        assertThat(temporaryStock.get("reserved")).isEqualTo("0");
        var orderIds=new HashSet<>(created.stream().map(OrderModels.Order::id).toList());
        var ordersByLine=new HashMap<String,String>();
        for(int i=0;i<2;i++){
            var order=created.get(i);
            var row=frozen.get("sales_order").stream().filter(r->order.id().equals(r.get("id"))).findFirst().orElseThrow();
            assertThat(row.get("customer_id")).isEqualTo(owner);
            assertThat(row.get("warehouse_id")).isEqualTo(warehouse);
            assertThat(row.get("remark")).isEqualTo(name+"-"+i);
            assertThat(row.get("state")).isEqualTo("CANCELLED");
            assertThat(row.get("version")).isEqualTo(i==0?"1":"2");
            var quote=frozen.get("checkout_quote").stream().filter(r->r.get("id").equals(row.get("quote_id"))).findFirst().orElseThrow();
            assertThat(quote.get("customer_id")).isEqualTo(owner);
            assertThat(quote.get("used_order_id")).isEqualTo(order.id());
            var item=frozen.get("sales_order_item").stream().filter(r->order.id().equals(r.get("order_id"))).findFirst().orElseThrow();
            assertThat(item.get("sku_id")).isEqualTo(sku);
            assertThat(item.get("quantity")).isEqualTo("1");
            ordersByLine.put(item.get("id"),order.id());
            var reservation=frozen.get("stock_reservation").stream().filter(r->r.get("business_line_id").equals(item.get("id"))).findFirst().orElseThrow();
            assertThat(reservation.get("state")).isEqualTo("RELEASED");
            assertThat(reservation.get("business_type")).isEqualTo("ORDER");
            assertThat(reservation.get("warehouse_id")).isEqualTo(warehouse);
            assertThat(reservation.get("sku_id")).isEqualTo(sku);
            assertThat(reservation.get("quantity")).isEqualTo("1");
        }
        for(var row:frozen.get("stock_ledger")){
            assertThat(row.get("biz_type")).isEqualTo("ORDER");
            assertThat(ordersByLine.get(row.get("biz_line_id"))).isEqualTo(row.get("biz_id"));
            assertThat(row.get("warehouse_id")).isEqualTo(warehouse);
            assertThat(row.get("sku_id")).isEqualTo(sku);
            assertThat(row.get("action")).isIn("RESERVE","RELEASE");
        }
        var temporaryPayment=frozen.get("payment").getFirst();
        assertThat(temporaryPayment.get("id")).isEqualTo(payment);
        assertThat(temporaryPayment.get("order_id")).isEqualTo(created.get(1).id());
        assertThat(temporaryPayment.get("payment_no")).isEqualTo("UI"+runId.replace("-",""));
        assertThat(temporaryPayment.get("state")).isEqualTo("CLOSED");
        assertThat(temporaryPayment.get("channel")).isEqualTo("MOCK");
        for(var row:frozen.get("idempotency_request")){
            assertThat(row.get("actor_scope")).isEqualTo("CUSTOMER:"+owner);
            assertThat(row.get("method")).isEqualTo("POST");
            assertThat(row.get("state")).isEqualTo("COMPLETED");
            assertThat(row.get("http_status")).isEqualTo("200");
            var id=created.stream().map(OrderModels.Order::id).filter(candidate->row.get("route").equals("/api/v1/orders/"+candidate+"/cancel")).findFirst().orElseThrow();
            assertThat(row.get("key_hash")).isEqualTo(result.path("cancelAttempts").path(id).get(0).path("keyHash").asText());
        }
        var browserTraces=new HashSet<String>();
        for(var trace:result.path("cancelTraceIds"))browserTraces.add(trace.asText());
        assertThat(browserTraces).hasSize(2);
        for(var row:frozen.get("audit_log")){
            assertThat(row.get("actor_id")).isEqualTo(owner);
            assertThat(row.get("action")).isEqualTo("ORDER_CANCEL");
            assertThat(row.get("resource_type")).isEqualTo("ORDER");
            assertThat(orderIds).contains(row.get("resource_id"));
            assertThat(browserTraces).contains(row.get("trace_id"));
            assertThat(row.get("masked_before_after")).doesNotContain(name,"10000000007");
        }
        assertThat(GUARD.snapshot().entrySet().stream().filter(e->e.getKey().startsWith("INSERT ")).mapToInt(Map.Entry::getValue).sum()).isEqualTo(19);
        return frozen;
    }
    void cleanup(Map<String,List<Map<String,String>>> frozen){
        var transaction=tx(false);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);transaction.setTimeout(30);
        transaction.executeWithoutResult(status->{
            for(String table:RESOURCES)assertThat(MySqlAcceptanceSupport.digest(MySqlAcceptanceSupport.rows(jdbc,table,columns.get(table),true))).isEqualTo(MySqlAcceptanceSupport.digest(frozen.get(table)));
            GUARD.cleanup=true;
            for(String table:List.of("audit_log","idempotency_request","payment","stock_ledger","stock_reservation","sales_order_item","sales_order","checkout_quote","stock_balance","customer_address"))
                for(var row:frozen.get(table))assertThat(jdbc.update("DELETE FROM `"+table+"` WHERE id=CAST(? AS UNSIGNED) AND "+MySqlAcceptanceSupport.hashSql(columns.get(table))+"=?",row.get("id"),row.get("_guard_hash"))).isEqualTo(1);
        });GUARD.cleanup=false;assertThat(GUARD.deletes.get()).isEqualTo(19);
    }
    void verifyFrontend()throws Exception {
        Path project=Path.of(System.getenv("M1_CANCEL_BROWSER_PROJECT")).toRealPath();assertThat(project).isEqualTo(Path.of(".").toRealPath());
        var files=json.readTree(Files.readAllBytes(Path.of(System.getenv("M1_CANCEL_BROWSER_FRONTEND_MANIFEST")))).path("files");
        assertThat(files.isArray()).isTrue();assertThat(files.size()).isGreaterThan(0);
        for(var file:files){
            Path path=project.resolve(file.path("path").asText()).normalize();assertThat(path.startsWith(project)).isTrue();
            assertThat(hash(Files.readAllBytes(path))).isEqualToIgnoringCase(file.path("sha256").asText());
        }
    }
    Map<String,List<Map<String,String>>> proofs(Map<String,List<Map<String,String>>> frozen){
        var result=new LinkedHashMap<String,List<Map<String,String>>>();
        frozen.forEach((table,values)->result.put(table,values.stream().map(row->Map.of("id",row.get("id"),"fullRowHash",row.get("_guard_hash"))).toList()));return result;
    }
    List<Map<String,String>> rows(String table){return MySqlAcceptanceSupport.rows(jdbc,table,columns.get(table),false);}
    void assertOldRowsPreserved(){
        for(String table:columns.keySet())if(!RESOURCES.contains(table))assertThat(MySqlAcceptanceSupport.digest(rows(table))).as(table).isEqualTo(MySqlAcceptanceSupport.digest(original.get(table)));
    }
    Map<String,String> allDigests(){return Objects.requireNonNull(tx(true).execute(status->{
        var result=new TreeMap<String,String>();for(var entry:columns.entrySet()){
            int count=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM `"+entry.getKey()+"`",Integer.class));
            result.put(entry.getKey(),count+":"+MySqlAcceptanceSupport.referenceDigest(jdbc,entry.getKey(),entry.getValue(),count));
        }return result;
    }));}
    void assertRestored(){assertThat(allDigests()).isEqualTo(baseline);}
    TransactionTemplate tx(boolean readOnly){var transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(readOnly?TransactionDefinition.ISOLATION_REPEATABLE_READ:TransactionDefinition.ISOLATION_READ_COMMITTED);transaction.setReadOnly(readOnly);transaction.setTimeout(20);return transaction;}
    int reserved(){return Objects.requireNonNull(jdbc.queryForObject("SELECT reserved FROM stock_balance WHERE id=CAST(? AS UNSIGNED)",Integer.class,balance));}
    int releases(String id){return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE biz_type='ORDER' AND biz_id=CAST(? AS UNSIGNED) AND action='RELEASE'",Integer.class,id));}
    String state(String id){return jdbc.queryForObject("SELECT state FROM sales_order WHERE id=CAST(? AS UNSIGNED)",String.class,id);}
    String hash(byte[] bytes)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    void note(Map<String,?> value)throws Exception {Files.writeString(manifest,json.writeValueAsString(value)+"\n",StandardOpenOption.APPEND);}
    void noteUnchecked(Map<String,?> value){try{note(value);}catch(Exception error){throw new IllegalStateException("Cannot persist browser evidence",error);}}
}
