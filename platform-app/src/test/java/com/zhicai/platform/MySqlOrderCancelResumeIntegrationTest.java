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
@EnabledIfEnvironmentVariable(named="M1_CANCEL_RESUME_MODE",matches="read-only|approved-single-order-cancel-resume")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.flyway.enabled=false","spring.sql.init.mode=never","app.dev-bootstrap.enabled=false",
    "app.catalog.writes-enabled=false","app.customer.writes-enabled=true","app.order.writes-enabled=true","debug=false"})
@Import(MySqlOrderCancelResumeIntegrationTest.GuardConfiguration.class)
class MySqlOrderCancelResumeIntegrationTest {
    static final String URL="jdbc:mysql://127.0.0.1:3306/zhicai_dev?connectionTimeZone=UTC";
    static final Path BATCH=Path.of(".local/acceptance");
    static final String BASELINE_SHA="0000000000000000000000000000000000000000000000000000000000000000";
    static final List<String> RESOURCES=List.of("account","customer_address","stock_balance","checkout_quote","sales_order","sales_order_item","stock_reservation","stock_ledger","payment","idempotency_request","audit_log");
    static final Map<String,Integer> CAPS=Map.ofEntries(
        Map.entry("INSERT stock_ledger",3),Map.entry("INSERT idempotency_request",6),Map.entry("INSERT audit_log",3),
        Map.entry("UPDATE stock_balance",3),Map.entry("UPDATE stock_reservation",3),Map.entry("UPDATE sales_order",4),
        Map.entry("UPDATE payment",1),Map.entry("UPDATE idempotency_request",5));
    static final Map<String,Integer> ROW_COUNTS=Map.ofEntries(Map.entry("account",1),Map.entry("customer_address",1),Map.entry("stock_balance",1),
        Map.entry("checkout_quote",4),Map.entry("sales_order",4),Map.entry("sales_order_item",4),Map.entry("stock_reservation",4),
        Map.entry("stock_ledger",7),Map.entry("payment",4),Map.entry("idempotency_request",7),Map.entry("audit_log",3));
    static boolean approved(){return "approved-single-order-cancel-resume".equals(System.getenv("M1_CANCEL_RESUME_MODE"))
        && System.getenv("M1_CANCEL_RESUME_APPROVAL_ID")!=null&&!System.getenv("M1_CANCEL_RESUME_APPROVAL_ID").isBlank();}
    static volatile OrderCancelOverlap overlap;
    static final MySqlAcceptanceSupport.SqlGuard GUARD=new MySqlAcceptanceSupport.SqlGuard(CAPS,RESOURCES,40,MySqlOrderCancelResumeIntegrationTest::approved){
        @Override void afterExecution(String sql,String kind,Object value)throws java.sql.SQLException {
            var gate=overlap;
            if(gate!=null && sql.contains("FROM idempotency_request") && sql.trim().endsWith("FOR UPDATE NOWAIT"))gate.ownerLocked();
        }
    };
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
    @Autowired OrderReadService orderReads;
    @Autowired OrderClosingService closing;
    @Autowired PaymentClosingMapper payments;
    final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
    final String run="2d6e8c8e-3485-4662-aaa5-3be3414f8d84",name="取消验收-"+run;
    String otherName;
    final Map<String,String> sceneDigests=new TreeMap<>();
    final Map<String,String> untouchedClaimProofs=new TreeMap<>();
    String untouchedAccountProof;
    final Map<String,List<String>> columns=new TreeMap<>();
    final Map<String,List<Map<String,String>>> original=new TreeMap<>();
    final Map<String,String> baseline=new TreeMap<>(),claimActors=new LinkedHashMap<>(),claimRoutes=new LinkedHashMap<>();
    final List<OrderModels.Order> created=new ArrayList<>();
    final List<String> checks=new ArrayList<>(),tokens=new ArrayList<>();
    final Set<String> auditTraces=new HashSet<>();
    Path manifest;
    String owner,warehouse,sku,address,balance,outsider,customerToken;
    String activePayment;

    @Test void singleBoundedScenario()throws Exception {
        prepare();securityPreflight();
        if(!approved()){
            assertThat(System.getenv("M1_CANCEL_RESUME_MODE")).isEqualTo("read-only");assertThat(GUARD.snapshot()).isEmpty();assertThat(GUARD.locks.get()).isZero();
            assertThat(allDigests()).isEqualTo(sceneDigests);note(Map.of("stage","read-only-verified","tables",26,"sqlMutationAttempts",0,"lockingSelects",0,"digests",sceneDigests));return;
        }
        assertThat(System.getenv("M1_CANCEL_RESUME_FIXTURE_SHA256")).matches("[A-Fa-f0-9]{64}");
        assertThat(System.getenv("M1_CANCEL_RESUME_IMPLEMENTATION_SHA256")).matches("[A-Fa-f0-9]{64}");
        try{
            note(Map.of("stage","write-intent","run",run,"caps",CAPS,"maxCommittedRows",40,"deleteCap",40));GUARD.armed=true;
            customerToken=login("customer",System.getenv("M1_CANCEL_RESUME_CUSTOMER_PASSWORD"),"customer");
            assertThat(reserved()).isEqualTo(4);
            noPaymentConcurrentCancel();activeClosing();unknownDoesNotRelease();allTerminalCancel();
            logoutAll();assertOldRowsPreserved();
            var frozen=freezeAndValidate();note(Map.of("stage","checks-passed","checks",checks,"sqlAttempts",GUARD.snapshot(),"committedRows",40,"rowProofs",proofs(frozen)));
            cleanup(frozen);assertAllRestored();
            note(Map.of("stage","cleaned-and-verified","tables",26,"digests",baseline,"sqlAttempts",GUARD.snapshot(),"deleteAttempts",GUARD.deletes.get(),"autoIncrementRestored",false));
        }catch(Exception|AssertionError failure){note(Map.of("stage","retained-after-failure","exceptionClass",failure.getClass().getName(),"run",run,"knownOrders",created.stream().map(OrderModels.Order::id).toList(),"sqlAttempts",GUARD.snapshot(),"deleteAttempts",GUARD.deletes.get()));throw failure;}
        finally{GUARD.armed=false;GUARD.cleanup=false;}
    }
    void prepare()throws Exception {
        assertThat(Path.of(System.getenv("M1_CANCEL_RESUME_EVIDENCE_ROOT")).toRealPath()).isEqualTo(BATCH.toRealPath());
        Path directory=BATCH.resolve("run-"+UUID.randomUUID());Files.createDirectory(directory);manifest=directory.resolve("manifest.jsonl");Files.createFile(manifest);
        var bytes=Files.readAllBytes(BATCH.resolve("baseline-v10.json"));assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).isEqualTo(BASELINE_SHA);
        var sceneBytes=Files.readAllBytes(BATCH.resolve("retained-scene-v1.json"));
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sceneBytes))).isEqualTo("0000000000000000000000000000000000000000000000000000000000000000");
        var scene=json.readTree(sceneBytes);assertThat(scene.path("RowCount").asInt()).isEqualTo(29);assertThat(scene.path("SourceRun").asText()).isEqualTo(run);
        for(var binding:Map.of("SnapshotSha256","failure-snapshot-2d6e8c8e.json","ManifestSha256","prior-manifest.jsonl").entrySet())
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(BATCH.resolve(binding.getValue()))))).isEqualToIgnoringCase(scene.path(binding.getKey()).asText());
        var reference=json.readTree(bytes);var expected=new TreeSet<String>();for(var row:reference.path("Rows")){expected.add(row.path("table").asText());baseline.put(row.path("table").asText(),row.path("count").asInt()+":"+row.path("sha256").asText());}
        assertThat(expected).hasSize(26);assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("zhicai_dev");assertThat(jdbc.queryForObject("SELECT @@session.time_zone",String.class)).isEqualTo("+00:00");
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'",String.class)).containsExactlyInAnyOrderElementsOf(expected);
        for(String table:expected){assertThat(table).matches("[a-z_]+");columns.put(table,jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position",String.class,table));}
        sceneDigests.putAll(allDigests());for(var row:json.readTree(Files.readAllBytes(BATCH.resolve("failure-snapshot-2d6e8c8e.json"))).path("Rows"))assertThat(sceneDigests.get(row.path("table").asText())).isEqualTo(row.path("count").asInt()+":"+row.path("sha256").asText());
        for(String table:expected){
            var current=rows(table);var ids=new HashSet<String>();
            if(RESOURCES.contains(table))for(var proof:scene.path("RowProofs").path(table)){
                String id=proof.path("id").asText();assertThat(ids.add(id)).isTrue();
                var row=current.stream().filter(r->id.equals(r.get("id"))).findFirst().orElseThrow();assertThat(row.get("_guard_hash")).isEqualTo(proof.path("fullRowHash").asText());
            }
            original.put(table,current.stream().filter(r->!ids.contains(r.get("id"))).toList());
            assertThat(original.get(table)).hasSize(Integer.parseInt(baseline.get(table).split(":")[0]));
        }
        var detail=scene.path("Details");owner=detail.path("owner").asText();warehouse=detail.path("warehouse").asText();sku=detail.path("sku").asText();address=detail.path("address").asText();balance=detail.path("balance").asText();outsider=detail.path("outsider").asText();
        assertThat(owner).isEqualTo("4");assertThat(outsider).isEqualTo("9");
        String accountColumns=String.join(",",columns.get("account").stream().map(c->"`"+c+"`").toList());
        var accountHashes=jdbc.queryForList("SELECT SHA2(CAST(JSON_ARRAY("+accountColumns+") AS CHAR CHARACTER SET utf8mb4),256) FROM account WHERE id<>CAST(? AS UNSIGNED) ORDER BY id",String.class,outsider);
        assertThat(accountHashes).hasSize(5);assertThat("5:"+MySqlAcceptanceSupport.sha(String.join("",accountHashes))).isEqualTo(baseline.get("account"));
        var retainedAccount=rows("account").stream().filter(r->outsider.equals(r.get("id"))).findFirst().orElseThrow();
        otherName=retainedAccount.get("username");untouchedAccountProof=retainedAccount.get("_guard_hash");
        int index=0;for(var id:scene.path("Orders")){
            var order=orderReads.find(owner,id.asText());assertThat(order.state()).isEqualTo("UNPAID");assertThat(order.version()).isZero();created.add(order);
            assertThat(rows("sales_order").stream().filter(r->order.id().equals(r.get("id"))).findFirst().orElseThrow().get("remark")).isEqualTo(name+"-"+index++);
        }
        assertThat(created).hasSize(4);assertThat(reserved()).isEqualTo(4);
        activePayment=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM payment WHERE order_id=CAST(? AS UNSIGNED) AND state='PROCESSING'",String.class,created.get(1).id());
        for(var row:rows("idempotency_request")){claimActors.put(row.get("key_hash"),row.get("actor_scope"));claimRoutes.put(row.get("key_hash"),row.get("route"));untouchedClaimProofs.put(row.get("id"),row.get("_guard_hash"));}
        assertThat(claimActors).hasSize(2);assertThat(payments.facts(created.get(0).id())).isEqualTo(new PaymentClosingMapper.Facts(0,0,0,0));
        note(Map.of("stage","preflight-verified","mode",System.getenv("M1_CANCEL_RESUME_MODE"),"run",run,"retainedRows",29,"digests",sceneDigests,"originalAccountsVerified",true));
    }
    void securityPreflight()throws Exception {
        expect(cancel("1","r",null,UUID.randomUUID().toString()),401,null);
        String staff=login("admin",System.getenv("M1_CANCEL_RESUME_STAFF_PASSWORD"),"staff");
        expect(cancel("1","r",staff,UUID.randomUUID().toString()),403,null);logout(staff);
        String customer=login("customer",System.getenv("M1_CANCEL_RESUME_CUSTOMER_PASSWORD"),"customer");
        expect(cancel("0","r",customer,UUID.randomUUID().toString()),400,null);
        expect(send("POST","/api/v1/orders/1/cancel","{}",customer,UUID.randomUUID().toString()),400,null);
        expect(cancel("1","r",customer,null),400,null);logout(customer);
        assertThat(tokens).isEmpty();assertThat(GUARD.snapshot()).isEmpty();assertThat(GUARD.locks.get()).isZero();
    }
    void noPaymentConcurrentCancel()throws Exception {
        String id=created.get(0).id(),key=claim(owner,id);var gate=new OrderCancelOverlap(Duration.ofSeconds(10));overlap=gate;
        HttpResponse<String> success;
        try(var executor=Executors.newSingleThreadExecutor()){
            Future<HttpResponse<String>> first=executor.submit(()->cancel(id,name,customerToken,key));
            try{gate.awaitOwner();expect(cancel(id,name,customerToken,key),409,"REQUEST_IN_PROGRESS");}
            finally{gate.releaseOwner();overlap=null;}
            success=first.get(25,TimeUnit.SECONDS);
        }finally{gate.releaseOwner();overlap=null;}
        assertOrder(success,id,"CANCELLED",1);
        var replay=cancel(id,name,customerToken,key);expect(replay,200,null);assertThat(replay.body()).isEqualTo(success.body());
        expect(cancel(id,name+"变更",customerToken,key),409,"IDEMPOTENCY_CONFLICT");
        assertThat(reserved()).isEqualTo(3);assertThat(countRelease(id)).isEqualTo(1);
        checks.add("contender overlaps the proven owner work lock: 409 while owner finishes 200; one release and exact durable replay");
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
        var account=frozen.get("account").getFirst();assertThat(account.get("id")).isEqualTo(outsider);assertThat(account.get("username")).isEqualTo(otherName);assertThat(account.get("account_type")).isEqualTo("CUSTOMER");assertThat(account.get("password_hash")).startsWith("$2");
        assertThat(account.get("_guard_hash")).isEqualTo(untouchedAccountProof);
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
        for(var row:frozen.get("idempotency_request")){assertThat(claimActors.get(row.get("key_hash"))).isEqualTo(row.get("actor_scope"));assertThat(claimRoutes.get(row.get("key_hash"))).isEqualTo(row.get("route"));assertThat(row.get("method")).isEqualTo("POST");if(untouchedClaimProofs.containsKey(row.get("id")))assertThat(row.get("_guard_hash")).isEqualTo(untouchedClaimProofs.get(row.get("id")));}
        for(var row:frozen.get("audit_log")){assertThat(row.get("actor_id")).isEqualTo(owner);assertThat(row.get("action")).isEqualTo("ORDER_CANCEL");assertThat(row.get("resource_type")).isEqualTo("ORDER");assertThat(orderIds).contains(row.get("resource_id"));assertThat(auditTraces).contains(row.get("trace_id"));assertThat(row.get("masked_before_after")).doesNotContain(name,"10000000007");}
        assertThat(GUARD.snapshot().entrySet().stream().filter(e->e.getKey().startsWith("INSERT ")).mapToInt(Map.Entry::getValue).sum()).isBetween(11,12);
        return frozen;
    }
    void cleanup(Map<String,List<Map<String,String>>> frozen){
        var transaction=tx(false);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);transaction.setTimeout(30);
        transaction.executeWithoutResult(s->{
            for(String table:RESOURCES){var locked=MySqlAcceptanceSupport.rows(jdbc,table,columns.get(table),true);var expected=new ArrayList<>(original.get(table));expected.addAll(frozen.get(table));assertThat(MySqlAcceptanceSupport.digest(locked)).as(table).isEqualTo(MySqlAcceptanceSupport.digest(expected));}
            GUARD.cleanup=true;
            for(String table:List.of("audit_log","idempotency_request","payment","stock_ledger","stock_reservation","sales_order_item","sales_order","checkout_quote","stock_balance","customer_address","account"))
                for(var row:frozen.get(table))assertThat(jdbc.update("DELETE FROM `"+table+"` WHERE id=CAST(? AS UNSIGNED) AND "+MySqlAcceptanceSupport.hashSql(columns.get(table))+"=?",row.get("id"),row.get("_guard_hash"))).isEqualTo(1);
        });GUARD.cleanup=false;assertThat(GUARD.deletes.get()).isEqualTo(40);
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
    HttpResponse<String> cancel(String id,String reason,String token,String key)throws Exception{
        var result=send("POST","/api/v1/orders/"+id+"/cancel",json.writeValueAsString(Map.of("reason",reason)),token,key);
        var body=json.readTree(result.body());note(Map.of("stage","http-cancel-result","orderId",id,"status",result.statusCode(),"code",body.path("code").asText(),"traceId",body.path("traceId").asText()));return result;
    }
    HttpResponse<String> send(String method,String route,String body,String token,String key)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+route)).version(HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(20)).header("Accept","application/json");
        if(token!=null)request.header("Authorization","Bearer "+token);if(key!=null)request.header("Idempotency-Key",key);if(body!=null)request.header("Content-Type","application/json");
        return http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    void expect(HttpResponse<String> response,int status,String code)throws Exception {assertThat(response.statusCode()).isEqualTo(status);if(code!=null)assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);}
    synchronized void note(Map<String,?> value)throws Exception {Files.writeString(manifest,json.writeValueAsString(value)+"\n",StandardOpenOption.APPEND);}
}
