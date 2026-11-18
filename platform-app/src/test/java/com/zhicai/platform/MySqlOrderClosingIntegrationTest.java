package com.zhicai.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.catalog.*;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.inventory.*;
import com.zhicai.platform.trade.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

/** Candidate only. This context has no HTTP/payment adapter; a separate exact-source approval is mandatory. */
@EnabledIfEnvironmentVariable(named="M1_ORDER_CLOSING_INTEGRATION",matches="read-only-order-closing|approved-temporary-order-closing")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={
    "spring.flyway.enabled=false","spring.sql.init.mode=never","app.dev-bootstrap.enabled=false",
    "app.catalog.writes-enabled=false","app.customer.writes-enabled=false","debug=false"})
@Import(MySqlOrderClosingIntegrationTest.FixtureConfiguration.class)
class MySqlOrderClosingIntegrationTest {
    static final Path BATCH=Path.of(".local/acceptance");
    static final String URL="jdbc:mysql://127.0.0.1:3306/zhicai_dev?connectionTimeZone=UTC";
    static final String REFERENCE_SHA="0000000000000000000000000000000000000000000000000000000000000000";
    static final List<String> TABLES=List.of("account","role","permission","account_role","role_permission","warehouse","account_warehouse",
        "category","brand","asset","product","sku","product_asset","flyway_schema_history","customer_address","cart_item",
        "idempotency_request","audit_log","stock_balance","stock_reservation","stock_ledger","checkout_quote","sales_order","sales_order_item");
    static final List<String> RESOURCES=List.of("product","sku","product_asset","customer_address","stock_balance","checkout_quote","sales_order","sales_order_item","stock_reservation","stock_ledger");
    static final List<String> EMPTY=List.of("customer_address","cart_item","idempotency_request","audit_log","stock_balance","stock_reservation","stock_ledger","checkout_quote","sales_order","sales_order_item");
    static final List<String> DELETE_ORDER=List.of("stock_ledger","stock_reservation","sales_order_item","sales_order","checkout_quote","stock_balance","customer_address","product_asset","sku","product");
    static final ClosingGuard GUARD=new ClosingGuard();
    @DynamicPropertySource static void local(DynamicPropertyRegistry p){
        if(!URL.equals(System.getenv("DB_URL")))throw new IllegalStateException("Canonical local UTC JDBC URL required");
        p.add("spring.datasource.url",()->URL);p.add("spring.datasource.username",()->System.getenv("DB_USERNAME"));p.add("spring.datasource.password",()->System.getenv("DB_PASSWORD"));
        p.add("spring.datasource.hikari.connection-init-sql",()->"SET time_zone = '+00:00'");
        p.add("spring.flyway.enabled",()->false);p.add("spring.sql.init.mode",()->"never");p.add("app.dev-bootstrap.enabled",()->false);
        p.add("app.catalog.writes-enabled",()->false);p.add("app.customer.writes-enabled",()->false);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired OrderService orders;
    @Autowired QuoteService quotes;
    @Autowired OrderClosingService closing;
    @Autowired InventoryReservationService inventory;
    @Autowired ObjectMapper json;
    @Autowired SqlSessionFactory sessions;
    @Autowired FixtureClock clock;
    @Autowired FixturePaymentCheck payments;
    final String run=UUID.randomUUID().toString(),name="关闭验收-"+run,createTrace="create-"+run,cancelTrace="cancel-"+run,expireTrace="expire-"+run;
    final Map<String,List<String>> columns=new LinkedHashMap<>();
    final Map<String,List<Map<String,String>>> original=new LinkedHashMap<>();
    final Map<String,String> baseline=new LinkedHashMap<>(),referenceDigests=new LinkedHashMap<>();
    final Map<String,Set<String>> owned=new LinkedHashMap<>();
    final List<OrderModels.Order> created=new ArrayList<>();
    final List<QuoteModels.Quote> quoted=new ArrayList<>();
    final List<String> checks=new ArrayList<>();
    Map<String,List<Map<String,String>>> seeds,createdRows;
    Path manifest;
    String owner,other,warehouse,category,brand,asset,product,address,sku1,sku2;

    @Test @EnabledIfEnvironmentVariable(named="M1_ORDER_CLOSING_INTEGRATION",matches="read-only-order-closing")
    void readOnlyStartupAndBaseline() throws Exception {
        prepare();transactionGuards();
        assertThat(closing.expiredCandidates(clock.instant(),null,100)).isEmpty();
        assertThat(closing.expiredCandidates(clock.instant(),new OrderClosingService.Cursor(clock.instant(),"1"),100)).isEmpty();
        assertOriginalUnchanged();assertThat(GUARD.snapshot()).isEmpty();assertThat(GUARD.locks.get()).isZero();
        assertThat(payments.state).isEqualTo(OrderClosingService.PaymentState.UNKNOWN);
        note(Map.of("stage","read-only-startup-verified","referenceDigestsVerified",24,"originalDigests",baseline,"referenceDigests",referenceDigests,"sqlMutationAttempts",0,"lockingSelects",0,"unlockedCandidateQueries",2));
    }

    @Test @EnabledIfEnvironmentVariable(named="M1_ORDER_CLOSING_INTEGRATION",matches="approved-temporary-order-closing")
    void approvedTwoOrderClosingScenario() throws Exception {
        prepare();verifyApproval();transactionGuards();
        try {
            note(Map.of("stage","seed-intent","run",run,"owner",owner,"other",other,"warehouse",warehouse,"asset",asset,"sqlAttemptCaps",ClosingGuard.CAPS));
            GUARD.armed=true;createSeeds();
            for(int i=0;i<2;i++){
                var quote=Objects.requireNonNull(tx().execute(s->quotes.create(owner,new QuoteModels.Request(address,List.of(new QuoteModels.Item(sku1,2),new QuoteModels.Item(sku2,1)),null))));
                register("checkout_quote",quote.id());quoted.add(quote);
                var order=Objects.requireNonNull(tx().execute(s->orders.create(owner,new OrderModels.Create(quote.id(),name),createTrace)));
                register("sales_order",order.id());for(var line:order.items())register("sales_order_item",line.id());created.add(order);
                proveAndRegisterInventory(order,"RESERVE",createTrace,owner,i,i+1);
            }
            validateCreation();createdRows=ownRows();assertOriginalUnchanged();
            var first=created.get(0);var second=created.get(1);
            unchanged(()->assertApi(404,"NOT_FOUND",()->tx().execute(s->closing.cancel(other,first.id(),name,cancelTrace))));
            for(var state:List.of(OrderClosingService.PaymentState.UNKNOWN,OrderClosingService.PaymentState.ACTIVE_PAYMENT)){
                payments.state=state;
                unchanged(()->assertApi(503,"DEPENDENCY_UNAVAILABLE",()->tx().execute(s->closing.cancel(owner,first.id(),name,cancelTrace))));
            }
            payments.allowProvenNoPayment();
            unchanged(()->{var result=tx().execute(s->closing.expire(second.id(),expireTrace));assertThat(result).isEmpty();});
            race(first,false);
            proveAndRegisterInventory(first,"RELEASE",cancelTrace,owner,2,1);
            unchanged(()->assertApi(409,"STATE_CONFLICT",()->tx().execute(s->closing.cancel(owner,first.id(),name,cancelTrace))));
            unchanged(()->{var result=tx().execute(s->closing.expire(first.id(),expireTrace));assertThat(result).isEmpty();});
            clock.set(second.expiresAt());
            var candidates=closing.expiredCandidates(clock.instant(),null,100);
            assertThat(candidates).containsExactly(new OrderClosingService.Candidate(second.id(),second.expiresAt()));
            assertThat(closing.expiredCandidates(clock.instant(),new OrderClosingService.Cursor(second.expiresAt(),second.id()),100)).isEmpty();
            var before=allDigests();GUARD.failFinalOrderUpdate.set(true);
            assertThatThrownBy(()->tx().execute(s->closing.expire(second.id(),expireTrace))).hasRootCauseInstanceOf(ControlledSqlFailure.class);
            assertThat(GUARD.failFinalOrderUpdate.get()).isFalse();assertThat(GUARD.injected.get()).isEqualTo(1);
            assertThat(allDigests()).as("Actual two releases and final order UPDATE rolled back across all24 complete row digests").isEqualTo(before);
            checks.add("post-delegate final order UPDATE failure restores all24 digests; attempted release rows remain uncommitted");
            race(second,true);
            proveAndRegisterInventory(second,"RELEASE",expireTrace,null,1,0);
            unchanged(()->assertApi(409,"STATE_CONFLICT",()->tx().execute(s->closing.cancel(owner,second.id(),name,cancelTrace))));
            unchanged(()->{var result=tx().execute(s->closing.expire(second.id(),expireTrace));assertThat(result).isEmpty();});
            assertThat(closing.expiredCandidates(clock.instant(),null,100)).isEmpty();
            var frozen=validateFinal();assertOriginalUnchanged();
            assertThat(GUARD.snapshot()).containsExactlyInAnyOrderEntriesOf(ClosingGuard.CAPS);
            note(Map.of("stage","checks-passed","checks",checks,"sqlAttemptCounts",GUARD.snapshot(),"committedRows",27,"ownedRows",frozen));
            cleanup(frozen);assertOriginalUnchanged();
            note(Map.of("stage","cleaned-and-verified","originalDigests",baseline,"sqlAttemptCounts",GUARD.snapshot(),"deleteAttempts",GUARD.deletes.get(),"autoIncrementRestored",false));
        }catch(Exception|AssertionError failure){
            note(Map.of("stage","retained-after-failure","exceptionClass",failure.getClass().getName(),"knownIds",owned,"sqlAttemptCounts",GUARD.snapshot()));throw failure;
        }finally{GUARD.armed=false;GUARD.cleanup=false;payments.state=OrderClosingService.PaymentState.UNKNOWN;}
    }

    private void prepare() throws Exception {
        assertThat(Path.of(Objects.requireNonNull(System.getenv("M1_ORDER_CLOSING_EVIDENCE_ROOT"))).toRealPath()).isEqualTo(BATCH.toRealPath());
        Path directory=BATCH.resolve("closing-"+run);Files.createDirectory(directory);manifest=directory.resolve("manifest.jsonl");Files.createFile(manifest);
        note(Map.of("stage","preflight-intent","run",run,"mode",Objects.toString(System.getenv("M1_ORDER_CLOSING_INTEGRATION")),"fixtureSha256",Objects.toString(System.getenv("M1_ORDER_CLOSING_FIXTURE_SHA256")),"implementationSha256",Objects.toString(System.getenv("M1_ORDER_CLOSING_IMPLEMENTATION_SHA256"))));
        byte[] bytes=Files.readAllBytes(BATCH.resolve("baseline-v8-utc.json"));assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).isEqualTo(REFERENCE_SHA);
        var reference=json.readTree(bytes);
        var snapshot=tx();snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);snapshot.setReadOnly(true);
        snapshot.executeWithoutResult(status->{
        assertThat(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)c->c.getMetaData().getURL())).isEqualTo(URL);
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("zhicai_dev");assertThat(jdbc.queryForObject("SELECT @@session.time_zone",String.class)).isEqualTo("+00:00");
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'",String.class)).containsExactlyInAnyOrderElementsOf(TABLES);
        int[] checksums={-357524081,502337516,174647045,-493016158,237973485,947094408,-458546823,-652983552};
        var history=jdbc.queryForList("SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank");assertThat(history).hasSize(8);
        for(int i=0;i<8;i++){assertThat(history.get(i).get("version").toString()).isEqualTo(""+(i+1));assertThat(((Number)history.get(i).get("checksum")).intValue()).isEqualTo(checksums[i]);assertThat(history.get(i).get("success").toString()).isIn("true","1");}
        for(String table:TABLES){
            int count=reference.path("Counts").path(table).asInt(-1);assertThat(count(table)).as("Reviewed count: %s",table).isEqualTo(count);
            columns.put(table,jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position",String.class,table));
            String digest=MySqlAcceptanceSupport.referenceDigest(jdbc,table,columns.get(table),count);referenceDigests.put(table,digest);
            assertThat(digest).as("Exact reviewed UTC JSON row digest: %s",table).isEqualTo(reference.path("OriginalDigests").path(table).asText());
            original.put(table,rows(table,false));baseline.put(table,MySqlAcceptanceSupport.digest(original.get(table)));owned.put(table,new LinkedHashSet<>());
        }
        for(String table:EMPTY)assertThat(count(table)).isZero();
        owner=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM account WHERE account_type='CUSTOMER' AND enabled=1 ORDER BY id LIMIT 1",String.class);
        other=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM account WHERE id<>CAST(? AS UNSIGNED) ORDER BY id LIMIT 1",String.class,owner);assertThat(other).isNotEqualTo(owner);
        warehouse=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM warehouse WHERE enabled=1 AND fulfillment_enabled=1 ORDER BY priority,id LIMIT 1",String.class);
        category=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM category WHERE enabled=1 ORDER BY id LIMIT 1",String.class);
        brand=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM brand WHERE enabled=1 ORDER BY id LIMIT 1",String.class);
        asset=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM asset WHERE visibility='PUBLIC' AND size_bytes<=5242880 AND content_type IN ('image/jpeg','image/png','image/webp') ORDER BY id LIMIT 1",String.class);
        });
        for(Object service:List.of(orders,quotes,inventory,closing))assertThat(AopUtils.isAopProxy(service)).isTrue();
        for(String statement:List.of("lockOwned","lockAny","items","markCancelled","expired"))assertThat(sessions.getConfiguration().hasStatement("com.zhicai.platform.trade.OrderClosingMapper."+statement)).isTrue();
        assertThat(GUARD.snapshot()).isEmpty();assertThat(GUARD.locks.get()).isZero();assertThat(payments.state).isEqualTo(OrderClosingService.PaymentState.UNKNOWN);
        // Exact24 table baseline has no payment storage; NONE web context has no HTTP/channel entrance.
        // This proof permits only this isolated fixture's NO_ACTIVE_PAYMENT branch, never production wiring.
        payments.baselineProven=true;
        note(Map.of("stage","baseline-verified","tableCount",24,"referenceDigests",referenceDigests,"originalDigests",baseline,"sqlMutationAttempts",0,"lockingSelects",0,"baselineRead","readonly RR consistent snapshot, no locking SELECT","paymentProofScope","exact24 tables; NONE web test context; no production PaymentCheck"));
    }

    private void verifyApproval() throws Exception {
        var marker=json.readTree(Files.readString(BATCH.resolve("apply-attempt.json")));
        assertThat(marker.path("Mode").asText()).isEqualTo("approved-temporary-order-closing");
        for(String key:List.of("FixtureSha256","ImplementationSha256"))assertThat(marker.path(key).asText()).matches("[A-Fa-f0-9]{64}");
        assertThat(marker.path("FixtureSha256").asText()).isEqualTo(System.getenv("M1_ORDER_CLOSING_FIXTURE_SHA256"));
        assertThat(marker.path("ImplementationSha256").asText()).isEqualTo(System.getenv("M1_ORDER_CLOSING_IMPLEMENTATION_SHA256"));
        assertThat(marker.path("ApprovalId").asText()).isNotBlank().isEqualTo(System.getenv("M1_ORDER_CLOSING_APPROVAL_ID"));
    }
    private void transactionGuards(){
        int before=GUARD.executions.get();
        assertThatThrownBy(()->closing.cancel(owner,"1",name,cancelTrace)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(()->closing.expire("1",expireTrace)).isInstanceOf(IllegalTransactionStateException.class);
        for(int isolation:new int[]{TransactionDefinition.ISOLATION_DEFAULT,TransactionDefinition.ISOLATION_REPEATABLE_READ}){
            var invalid=tx();invalid.setIsolationLevel(isolation);
            assertThatThrownBy(()->invalid.execute(s->closing.cancel(owner,"1",name,cancelTrace))).isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(()->invalid.execute(s->closing.expire("1",expireTrace))).isInstanceOf(IllegalTransactionStateException.class);
        }
        var ro=tx();ro.setReadOnly(true);
        assertThatThrownBy(()->ro.execute(s->closing.cancel(owner,"1",name,cancelTrace))).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(()->ro.execute(s->closing.expire("1",expireTrace))).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(GUARD.executions.get()).isEqualTo(before);
    }
    private void createSeeds() throws Exception {
        tx().executeWithoutResult(s->{
            product=insert("product","INSERT INTO product(name,category_id,brand_id,description,state) VALUES(?,CAST(? AS UNSIGNED),CAST(? AS UNSIGNED),?,'ON_SALE')",name,category,brand,name);
            sku1=seedSku("a","100");sku2=seedSku("b","200");
            assertThat(jdbc.update("INSERT INTO product_asset(product_id,asset_id,sort_order) VALUES(CAST(? AS UNSIGNED),CAST(? AS UNSIGNED),0)",product,asset)).isEqualTo(1);register("product_asset",product+":"+asset);
            address=insert("customer_address","INSERT INTO customer_address(customer_id,recipient,phone,province,city,district,detail,is_default) VALUES(CAST(? AS UNSIGNED),?,'10000000007','测试省','测试市','测试区',?,FALSE)",owner,name,name);
            for(String sku:List.of(sku1,sku2))insert("stock_balance","INSERT INTO stock_balance(warehouse_id,sku_id,on_hand,reserved,quarantine) VALUES(CAST(? AS UNSIGNED),CAST(? AS UNSIGNED),10,0,0)",warehouse,sku);
        });
        seeds=ownRows();
        var p=find(seeds.get("product"),product);assertThat(p.get("name")).isEqualTo(name);assertThat(p.get("description")).isEqualTo(name);assertThat(p.get("category_id")).isEqualTo(category);assertThat(p.get("brand_id")).isEqualTo(brand);assertThat(p.get("state")).isEqualTo("ON_SALE");assertThat(p.get("version")).isEqualTo("0");
        for(var sku:seeds.get("sku")){
            boolean first=sku1.equals(sku.get("id"));String suffix=first?"a":"b",specs="[{\"name\":\"验收\",\"value\":\""+run+suffix+"\"}]";
            assertThat(sku.get("product_id")).isEqualTo(product);assertThat(sku.get("code")).isEqualTo("closing-"+run+suffix);assertThat(sku.get("price_minor")).isEqualTo(first?"100":"200");assertThat(sku.get("version")).isEqualTo("0");
            assertThat(sku.get("specs_hash")).isEqualTo(MySqlAcceptanceSupport.sha(specs));assertThat(json.readTree(sku.get("specs_json"))).isEqualTo(json.readTree(specs));
        }
        assertThat(seeds.get("product_asset")).hasSize(1);var pa=seeds.get("product_asset").getFirst();assertThat(pa.get("product_id")).isEqualTo(product);assertThat(pa.get("asset_id")).isEqualTo(asset);assertThat(pa.get("sort_order")).isEqualTo("0");
        var a=find(seeds.get("customer_address"),address);assertThat(a.get("customer_id")).isEqualTo(owner);assertThat(a.get("recipient")).isEqualTo(name);assertThat(a.get("detail")).isEqualTo(name);assertThat(a.get("phone")).isEqualTo("10000000007");assertThat(a.get("province")).isEqualTo("测试省");assertThat(a.get("city")).isEqualTo("测试市");assertThat(a.get("district")).isEqualTo("测试区");assertThat(a.get("is_default")).isIn("0","false");assertThat(a.get("version")).isEqualTo("0");
        for(var b:seeds.get("stock_balance")){assertThat(b.get("warehouse_id")).isEqualTo(warehouse);assertThat(b.get("sku_id")).isIn(sku1,sku2);assertThat(b.get("on_hand")).isEqualTo("10");for(String field:List.of("reserved","quarantine","version"))assertThat(b.get(field)).isEqualTo("0");}
        note(Map.of("stage","seed-created","ownedRows",seeds));
    }
    private String seedSku(String suffix,String price){String specs="[{\"name\":\"验收\",\"value\":\""+run+suffix+"\"}]";
        return insert("sku","INSERT INTO sku(product_id,code,specs_json,specs_hash,price_minor) VALUES(CAST(? AS UNSIGNED),?,?,?,CAST(? AS UNSIGNED))",product,"closing-"+run+suffix,specs,MySqlAcceptanceSupport.sha(specs),price);
    }
    private void race(OrderModels.Order order,boolean expiry) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var held=new CountDownLatch(1);var release=new CountDownLatch(1);var rivalAtSql=new CountDownLatch(1);
        var winnerConnection=new AtomicLong(-1);var rivalConnection=new AtomicLong(-1);GUARD.rivalAtSql=rivalAtSql;
        try {
            var winner=pool.submit(()->tx().execute(s->{
                winnerConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
                var result=expiry?closing.expire(order.id(),expireTrace).orElseThrow():closing.cancel(owner,order.id(),name,cancelTrace);
                held.countDown();await(release);return result;
            }));
            assertThat(held.await(8,TimeUnit.SECONDS)).isTrue();
            var rival=pool.submit(()->{GUARD.rival.set(true);try{
                assertApi(409,"STATE_CONFLICT",()->tx().execute(s->{rivalConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));return closing.cancel(owner,order.id(),name,cancelTrace);}));
            }finally{GUARD.rival.remove();}});
            assertThat(rivalAtSql.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->rival.get(350,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(winnerConnection.get()).isPositive().isNotEqualTo(rivalConnection.get());
            note(Map.of("stage","rival-blocked-before-commit","orderId",order.id(),"expiryWinner",expiry,"winnerConnection",winnerConnection.get(),"rivalConnection",rivalConnection.get()));
            release.countDown();var result=winner.get(10,TimeUnit.SECONDS);rival.get(10,TimeUnit.SECONDS);
            assertThat(result.id()).isEqualTo(order.id());assertThat(result.state()).isEqualTo("CANCELLED");assertThat(result.version()).isEqualTo(1);
            assertThat(result.cancelledAt()).isEqualTo(clock.instant().truncatedTo(ChronoUnit.MILLIS));assertThat(result.reason()).isEqualTo(expiry?"ORDER_EXPIRED":name);
            checks.add((expiry?"expiry":"manual cancel")+" wins independent RC order lock; rival waits then STATE_CONFLICT");
        }finally{
            release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(15,TimeUnit.SECONDS)).as("No cleanup until both workers settle").isTrue();GUARD.rivalAtSql=null;
        }
    }
    private void await(CountDownLatch latch){try{if(!latch.await(12,TimeUnit.SECONDS))throw new IllegalStateException("Closing commit barrier timeout");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}}
    private void unchanged(Runnable action){var before=allDigests();action.run();assertThat(allDigests()).as("Rejected/no-op request preserves all24 full row digests").isEqualTo(before);}

    private void proveAndRegisterInventory(OrderModels.Order order,String action,String trace,String actor,int beforeFactor,int afterFactor){
        for(var line:order.items()){
            var matches=rows("stock_ledger",false).stream().filter(r->line.id().equals(r.get("biz_line_id"))&&action.equals(r.get("action"))).toList();assertThat(matches).hasSize(1);var row=matches.getFirst();
            assertThat(row.get("biz_type")).isEqualTo("ORDER");assertThat(row.get("biz_id")).isEqualTo(order.id());assertThat(row.get("warehouse_id")).isEqualTo(warehouse);assertThat(row.get("sku_id")).isEqualTo(line.skuId());
            assertThat(row.get("actor_id")).isEqualTo(actor);assertThat(row.get("trace_id")).isEqualTo(trace);
            assertThat(row.get("reserved_before")).isEqualTo(""+(beforeFactor*line.quantity()));assertThat(row.get("reserved_after")).isEqualTo(""+(afterFactor*line.quantity()));
            assertThat(row.get("delta_reserved")).isEqualTo(""+((afterFactor-beforeFactor)*line.quantity()));
            for(String field:List.of("on_hand_before","on_hand_after"))assertThat(row.get(field)).isEqualTo("10");
            for(String field:List.of("quarantine_before","quarantine_after","in_transit_before","in_transit_after","delta_on_hand","delta_quarantine","delta_in_transit"))assertThat(row.get(field)).isEqualTo("0");
            register("stock_ledger",row.get("id"));
            var reservations=rows("stock_reservation",false).stream().filter(r->line.id().equals(r.get("business_line_id"))).toList();assertThat(reservations).hasSize(1);var reservation=reservations.getFirst();
            assertThat(reservation.get("business_type")).isEqualTo("ORDER");assertThat(reservation.get("warehouse_id")).isEqualTo(warehouse);assertThat(reservation.get("sku_id")).isEqualTo(line.skuId());assertThat(reservation.get("quantity")).isEqualTo(""+line.quantity());
            assertThat(reservation.get("state")).isEqualTo(action.equals("RESERVE")?"HELD":"RELEASED");assertThat(reservation.get("version")).isEqualTo(action.equals("RESERVE")?"0":"1");
            if(action.equals("RESERVE"))register("stock_reservation",reservation.get("id"));else assertThat(owned.get("stock_reservation")).contains(reservation.get("id"));
        }
    }
    private void validateCreation() throws Exception {
        assertThat(created).hasSize(2);assertThat(created.get(0).id()).isNotEqualTo(created.get(1).id());assertThat(created.get(0).orderNo()).isNotEqualTo(created.get(1).orderNo());
        for(int index=0;index<2;index++){
            var order=created.get(index);var quote=quoted.get(index);var persisted=ownRows();
            var o=find(persisted.get("sales_order"),order.id());var q=find(persisted.get("checkout_quote"),quote.id());var snap=json.readValue(q.get("snapshot_json"),QuoteModels.Snapshot.class);
            assertThat(order.orderNo()).matches("ZC[0-9a-f]{32}");assertThat(o.get("order_no")).isEqualTo(order.orderNo());assertThat(o.get("quote_id")).isEqualTo(quote.id());assertThat(o.get("customer_id")).isEqualTo(owner);assertThat(o.get("warehouse_id")).isEqualTo(warehouse);
            assertThat(o.get("state")).isEqualTo("UNPAID");assertThat(o.get("version")).isEqualTo("0");assertThat(o.get("remark")).isEqualTo(name);
            for(String field:List.of("coupon_id","paid_at","cancelled_at","completed_at"))assertThat(o.get(field)).isNull();
            for(String field:List.of("gross_minor","payable_minor"))assertThat(o.get(field)).isEqualTo("400");for(String field:List.of("discount_minor","shipping_minor","refunded_minor"))assertThat(o.get(field)).isEqualTo("0");
            assertThat(order.expiresAt()).isEqualTo(order.createdAt().plusSeconds(1800));assertThat(order.createdAt().getNano()%1_000_000).isZero();
            assertThat(MySqlAcceptanceSupport.utcTimestamp(o.get("created_at"))).isEqualTo(order.createdAt());assertThat(MySqlAcceptanceSupport.utcTimestamp(o.get("expires_at"))).isEqualTo(order.expiresAt());
            assertThat(q.get("used_order_id")).isEqualTo(order.id());assertThat(q.get("customer_id")).isEqualTo(owner);assertThat(q.get("warehouse_id")).isEqualTo(warehouse);
            assertThat(snap.owner()).isEqualTo(owner);assertThat(snap.addressId()).isEqualTo(address);assertThat(snap.items()).hasSize(2);
            assertThat(json.readValue(o.get("address_snapshot_json"),TradeModels.AddressInput.class)).isEqualTo(order.addressSnapshot());assertThat(order.addressSnapshot().recipient()).isEqualTo(name);assertThat(order.addressSnapshot().detail()).isEqualTo(name);
            assertThat(order.items()).hasSize(2);
            for(var line:order.items()){
                var item=find(persisted.get("sales_order_item"),line.id());var amount=snap.items().stream().filter(i->line.skuId().equals(i.amounts().skuId())).findFirst().orElseThrow().amounts();
                assertThat(item.get("order_id")).isEqualTo(order.id());assertThat(item.get("line_no")).isEqualTo(""+order.items().indexOf(line));assertThat(item.get("product_id")).isEqualTo(product);assertThat(item.get("sku_id")).isEqualTo(line.skuId());assertThat(item.get("product_name")).isEqualTo(name);
                assertThat(item.get("quantity")).isEqualTo(""+line.quantity());assertThat(item.get("unit_price_minor")).isEqualTo(amount.unitPriceMinor());assertThat(item.get("gross_minor")).isEqualTo(line.grossMinor());assertThat(item.get("payable_minor")).isEqualTo(line.payableMinor());
                assertThat(json.readTree(item.get("specs_snapshot_json"))).isEqualTo(json.readTree(json.writeValueAsString(amount.specs())));
                var image=json.readTree(item.get("image_snapshot_json"));var a=find(original.get("asset"),asset);
                assertThat(image.path("assetId").asText()).isEqualTo(asset);assertThat(image.path("storageKey").asText()).isEqualTo(a.get("storage_key"));assertThat(image.path("contentType").asText()).isEqualTo(a.get("content_type"));assertThat(image.path("sizeBytes").asLong()).isEqualTo(Long.parseLong(a.get("size_bytes")));
                for(String field:List.of("returned_quantity","refund_quantity_reserved","refunded_minor","version","discount_minor"))assertThat(item.get(field)).isEqualTo("0");
            }
        }
        for(var b:ownRows().get("stock_balance")){assertThat(b.get("warehouse_id")).isEqualTo(warehouse);assertThat(b.get("reserved")).isEqualTo(b.get("sku_id").equals(sku1)?"4":"2");assertThat(b.get("version")).isEqualTo("2");}
    }
    private Map<String,List<Map<String,String>>> validateFinal(){
        var result=ownRows();
        for(String table:List.of("product","sku","product_asset","customer_address"))assertThat(result.get(table)).isEqualTo(seeds.get(table));
        for(String table:List.of("checkout_quote","sales_order_item"))assertThat(result.get(table)).isEqualTo(createdRows.get(table));
        for(var o:result.get("sales_order")){
            var initial=find(createdRows.get("sales_order"),o.get("id"));assertImmutable("sales_order",initial,o,Set.of("state","cancelled_at","updated_at","version"));
            assertThat(o.get("state")).isEqualTo("CANCELLED");assertThat(o.get("version")).isEqualTo("1");
            Instant time=o.get("id").equals(created.get(0).id())?created.get(0).createdAt():created.get(1).expiresAt();
            assertThat(MySqlAcceptanceSupport.utcTimestamp(o.get("cancelled_at"))).isEqualTo(time);assertThat(o.get("updated_at")).isEqualTo(o.get("cancelled_at"));
        }
        for(var b:result.get("stock_balance")){assertImmutable("stock_balance",find(seeds.get("stock_balance"),b.get("id")),b,Set.of("reserved","updated_at","version"));assertThat(b.get("reserved")).isEqualTo("0");assertThat(b.get("version")).isEqualTo("4");}
        for(var r:result.get("stock_reservation")){assertImmutable("stock_reservation",find(createdRows.get("stock_reservation"),r.get("id")),r,Set.of("state","updated_at","version"));assertThat(r.get("state")).isEqualTo("RELEASED");assertThat(r.get("version")).isEqualTo("1");}
        for(var l:createdRows.get("stock_ledger"))assertThat(find(result.get("stock_ledger"),l.get("id"))).isEqualTo(l);
        var sizes=Map.of("product",1,"sku",2,"product_asset",1,"customer_address",1,"stock_balance",2,"checkout_quote",2,"sales_order",2,"sales_order_item",4,"stock_reservation",4,"stock_ledger",8);
        sizes.forEach((table,size)->assertThat(result.get(table)).hasSize(size));assertThat(result.values().stream().mapToInt(List::size).sum()).isEqualTo(27);
        checks.add("all27 generated identities, business links, owners, snapshots, stock deltas and complete postimages proved; no unknown membership adopted");return result;
    }
    private void assertImmutable(String table,Map<String,String> before,Map<String,String> after,Set<String> changed){for(String column:columns.get(table))if(!changed.contains(column))assertThat(after.get(column)).as("%s immutable %s",table,column).isEqualTo(before.get(column));}
    private static Map<String,String> find(List<Map<String,String>> rows,String id){return rows.stream().filter(r->id.equals(r.get("id"))).findFirst().orElseThrow();}
    private void cleanup(Map<String,List<Map<String,String>>> frozen) throws Exception {
        note(Map.of("stage","exact-cleanup-intent","knownIds",owned,"completeFrozenRows",frozen,"lockScope","RR full PRIMARY ranges of ten resource tables, including original catalog rows"));
        var expected=new LinkedHashMap<String,List<Map<String,String>>>();for(String table:RESOURCES){var rows=new ArrayList<>(original.get(table));rows.addAll(frozen.get(table));rows.sort(Comparator.comparing(MySqlAcceptanceSupport::rowDigest));expected.put(table,List.copyOf(rows));}
        GUARD.cleanup=true;var cleanup=tx();cleanup.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        cleanup.executeWithoutResult(s->{
            for(String table:RESOURCES)assertThat(rows(table,true)).as("Whole membership and complete postimages before first DELETE: %s",table).isEqualTo(expected.get(table));
            assertOriginalUnchanged();
            for(String table:DELETE_ORDER)for(var row:frozen.get(table)){
                String where=table.equals("product_asset")?"product_id=CAST(? AS UNSIGNED) AND asset_id=CAST(? AS UNSIGNED)":"id=CAST(? AS UNSIGNED)";
                var args=new ArrayList<Object>();if(table.equals("product_asset")){args.add(row.get("product_id"));args.add(row.get("asset_id"));}else args.add(row.get("id"));args.add(row.get("_guard_hash"));
                assertThat(jdbc.update("DELETE FROM `"+table+"` WHERE "+where+" AND "+MySqlAcceptanceSupport.hashSql(columns.get(table))+"=?",args.toArray())).isEqualTo(1);
            }
            for(String table:TABLES)assertThat(MySqlAcceptanceSupport.digest(rows(table,false))).isEqualTo(baseline.get(table));
        });GUARD.cleanup=false;assertThat(GUARD.deletes.get()).isEqualTo(27);
    }
    private TransactionTemplate tx(){var t=new TransactionTemplate(manager);t.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);t.setTimeout(20);return t;}
    private void assertApi(int status,String code,Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.status()).isEqualTo(status);assertThat(e.code()).isEqualTo(code);});}
    private String insert(String table,String sql,String... args){var holder=new GeneratedKeyHolder();assertThat(jdbc.update(c->{var ps=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)ps.setString(i+1,args[i]);return ps;},holder)).isEqualTo(1);String id=Objects.requireNonNull(holder.getKey()).toString();register(table,id);return id;}
    private void register(String table,String id){assertThat(id).matches(table.equals("product_asset")?"[1-9][0-9]{0,18}:[1-9][0-9]{0,18}":"[1-9][0-9]{0,18}");assertThat(owned.get(table).add(id)).as("Register proved identity exactly once: %s/%s",table,id).isTrue();}
    private int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Integer.class);}
    private String key(String table,Map<String,String> row){return table.equals("product_asset")?row.get("product_id")+":"+row.get("asset_id"):row.get("id");}
    private List<Map<String,String>> rows(String table,boolean lock){return MySqlAcceptanceSupport.rows(jdbc,table,columns.get(table),lock);}
    private Map<String,List<Map<String,String>>> ownRows(){var result=new LinkedHashMap<String,List<Map<String,String>>>();for(String table:RESOURCES)result.put(table,rows(table,false).stream().filter(r->owned.get(table).contains(key(table,r))).toList());return Collections.unmodifiableMap(result);}
    private Map<String,String> allDigests(){var result=new LinkedHashMap<String,String>();for(String table:TABLES)result.put(table,MySqlAcceptanceSupport.digest(rows(table,false)));return result;}
    private void assertOriginalUnchanged(){for(String table:TABLES)assertThat(MySqlAcceptanceSupport.digest(rows(table,false).stream().filter(r->!owned.get(table).contains(key(table,r))).toList())).as("All original rows and no unknown membership: %s",table).isEqualTo(baseline.get(table));}
    private synchronized void note(Map<String,?> event) throws Exception {byte[] bytes=(json.writeValueAsString(event)+"\n").getBytes(StandardCharsets.UTF_8);try(FileChannel f=FileChannel.open(manifest,StandardOpenOption.WRITE,StandardOpenOption.APPEND)){var b=ByteBuffer.wrap(bytes);while(b.hasRemaining())f.write(b);f.force(true);}}

    static class FixtureClock extends Clock {
        private volatile Instant now=Instant.now().truncatedTo(ChronoUnit.MILLIS);
        void set(Instant instant){now=Objects.requireNonNull(instant);}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){if(!ZoneOffset.UTC.equals(zone))throw new IllegalArgumentException("UTC only fixture");return this;}
        @Override public Instant instant(){return now;}
    }
    static class FixturePaymentCheck implements OrderClosingService.PaymentCheck {
        volatile OrderClosingService.PaymentState state=OrderClosingService.PaymentState.UNKNOWN;
        boolean baselineProven;
        void allowProvenNoPayment(){if(!baselineProven||!GUARD.armed||!GUARD.approved())throw new IllegalStateException("Exact baseline and separately approved isolated test context required");state=OrderClosingService.PaymentState.NO_ACTIVE_PAYMENT;}
        @Override public OrderClosingService.PaymentState lockedState(String id){return state;}
    }
    @TestConfiguration static class FixtureConfiguration {
        @Bean @Primary FixtureClock closingClock(){return new FixtureClock();}
        @Bean FixturePaymentCheck closingPaymentCheck(){return new FixturePaymentCheck();}
        @Bean CheckoutCatalogService closingCatalog(CheckoutCatalogMapper m,ObjectMapper j){return new CheckoutCatalogService(m,j);}
        @Bean InventoryAvailabilityService closingAvailability(InventoryAvailabilityMapper m){return new InventoryAvailabilityService(m);}
        @Bean InventoryReservationService closingInventory(StockMapper m){return new InventoryReservationService(m);}
        @Bean QuoteService closingQuotes(QuoteMapper m,AddressMapper a,CheckoutCatalogService c,InventoryAvailabilityService i,ObjectMapper j,Clock clock){return new QuoteService(m,a,c,i,j,clock);}
        @Bean OrderService closingOrders(OrderMapper m,CheckoutCatalogService c,InventoryAvailabilityService i,InventoryReservationService r,ObjectMapper j,Clock clock){return new OrderService(m,c,i,r,j,clock);}
        @Bean OrderClosingService closingService(OrderClosingMapper m,InventoryReservationService i,FixturePaymentCheck p,Clock clock){return new OrderClosingService(m,i,p,clock);}
        @Bean static BeanPostProcessor closingDataSourceGuard(){return new BeanPostProcessor(){@Override public Object postProcessAfterInitialization(Object bean,String name){return bean instanceof DataSource source?GUARD.dataSource(source):bean;}};}
    }
    static class ControlledSqlFailure extends SQLException {ControlledSqlFailure(){super("Deliberate failure after actual final order UPDATE","45000");}}
    static class ClosingGuard extends MySqlAcceptanceSupport.SqlGuard {
        static final Map<String,Integer> CAPS=Map.ofEntries(Map.entry("INSERT product",1),Map.entry("INSERT sku",2),Map.entry("INSERT product_asset",1),Map.entry("INSERT customer_address",1),Map.entry("INSERT stock_balance",2),Map.entry("INSERT checkout_quote",2),Map.entry("INSERT sales_order",2),Map.entry("INSERT sales_order_item",4),Map.entry("INSERT stock_reservation",4),Map.entry("INSERT stock_ledger",10),Map.entry("UPDATE checkout_quote",2),Map.entry("UPDATE stock_balance",10),Map.entry("UPDATE stock_reservation",6),Map.entry("UPDATE sales_order",3));
        final AtomicBoolean failFinalOrderUpdate=new AtomicBoolean();final AtomicInteger injected=new AtomicInteger();final ThreadLocal<Boolean> rival=new ThreadLocal<>();volatile CountDownLatch rivalAtSql;
        ClosingGuard(){super(CAPS,RESOURCES,27,()->"approved-temporary-order-closing".equals(System.getenv("M1_ORDER_CLOSING_INTEGRATION")));}
        @Override void beforeExecution(String sql,String kind){String upper=sql.toUpperCase(Locale.ROOT);if(Boolean.TRUE.equals(rival.get())&&rivalAtSql!=null&&upper.contains("FROM SALES_ORDER ")&&upper.contains("FOR UPDATE"))rivalAtSql.countDown();}
        @Override void afterExecution(String sql,String kind,Object value,Statement statement)throws SQLException {
            if(kind.equals("UPDATE sales_order")&&failFinalOrderUpdate.compareAndSet(true,false)){
                if(statement.getUpdateCount()!=1)throw new SQLException("Final order UPDATE did not affect exactly one row","45000");
                injected.incrementAndGet();throw new ControlledSqlFailure();
            }
        }
    }
}

/** No Spring context or JDBC connection; verifies this fixture's callback and test-only payment boundary. */
class OrderClosingFixtureContractTest {
    @Test void paymentProofFailsClosed(){var p=new MySqlOrderClosingIntegrationTest.FixturePaymentCheck();assertThat(p.lockedState("1")).isEqualTo(OrderClosingService.PaymentState.UNKNOWN);assertThatThrownBy(p::allowProvenNoPayment).isInstanceOf(IllegalStateException.class);p.baselineProven=true;assertThatThrownBy(p::allowProvenNoPayment).isInstanceOf(IllegalStateException.class);}
    @Test void clockMovesAtExactUtcBoundary(){var c=new MySqlOrderClosingIntegrationTest.FixtureClock();var expiry=Instant.parse("2026-10-27T01:02:03.456Z");c.set(expiry.minusMillis(1));assertThat(c.instant()).isBefore(expiry);c.set(expiry);assertThat(c.instant()).isEqualTo(expiry);assertThat(c.getZone()).isEqualTo(ZoneOffset.UTC);}
    @Test void closingBudgetIs29Insert21Update27Delete(){var caps=MySqlOrderClosingIntegrationTest.ClosingGuard.CAPS;assertThat(caps).isEqualTo(MySqlAcceptanceSupportTest.CLOSING);assertThat(caps.entrySet().stream().filter(e->e.getKey().startsWith("INSERT ")).mapToInt(Map.Entry::getValue).sum()).isEqualTo(29);assertThat(caps.entrySet().stream().filter(e->e.getKey().startsWith("UPDATE ")).mapToInt(Map.Entry::getValue).sum()).isEqualTo(21);assertThat(new MySqlOrderClosingIntegrationTest.ClosingGuard().maxDeletes).isEqualTo(27);}
    @Test void injectedFailureOccursAfterDelegateOnlyOnce() throws Exception {
        var guard=new MySqlOrderClosingIntegrationTest.ClosingGuard();guard.failFinalOrderUpdate.set(true);
        var approved=new MySqlAcceptanceSupport.SqlGuard(MySqlOrderClosingIntegrationTest.ClosingGuard.CAPS,MySqlOrderClosingIntegrationTest.RESOURCES,27,()->true){
            @Override void afterExecution(String sql,String kind,Object value,Statement statement)throws SQLException {guard.afterExecution(sql,kind,value,statement);}
        };approved.armed=true;
        var raw=org.mockito.Mockito.mock(Connection.class);var target=org.mockito.Mockito.mock(Statement.class);org.mockito.Mockito.when(raw.createStatement()).thenReturn(target);
        String sql="UPDATE sales_order SET state='CANCELLED' WHERE id=1";org.mockito.Mockito.when(target.executeUpdate(sql)).thenReturn(1);org.mockito.Mockito.when(target.getUpdateCount()).thenReturn(1);
        var statement=approved.connection(raw).createStatement();assertThatThrownBy(()->statement.executeUpdate(sql)).isInstanceOf(MySqlOrderClosingIntegrationTest.ControlledSqlFailure.class);
        org.mockito.Mockito.verify(target).executeUpdate(sql);assertThat(guard.injected.get()).isEqualTo(1);assertThat(statement.executeUpdate(sql)).isEqualTo(1);assertThat(guard.injected.get()).isEqualTo(1);
    }
    @Test void myBatisPreparedExecuteFalseStillInjectsControlledFailureAfterAffectedOne() throws Exception {
        var closing=new MySqlOrderClosingIntegrationTest.ClosingGuard();closing.failFinalOrderUpdate.set(true);
        var guard=new MySqlAcceptanceSupport.SqlGuard(MySqlOrderClosingIntegrationTest.ClosingGuard.CAPS,MySqlOrderClosingIntegrationTest.RESOURCES,27,()->true){
            @Override void afterExecution(String sql,String kind,Object value,Statement statement)throws SQLException {closing.afterExecution(sql,kind,value,statement);}
        };guard.armed=true;
        var raw=org.mockito.Mockito.mock(Connection.class);var target=org.mockito.Mockito.mock(PreparedStatement.class);
        String sql="UPDATE sales_order SET state='CANCELLED' WHERE id=?";org.mockito.Mockito.when(raw.prepareStatement(sql)).thenReturn(target);
        org.mockito.Mockito.when(target.execute()).thenReturn(false);org.mockito.Mockito.when(target.getUpdateCount()).thenReturn(1);
        var statement=guard.connection(raw).prepareStatement(sql);
        assertThatThrownBy(statement::execute).isInstanceOf(MySqlOrderClosingIntegrationTest.ControlledSqlFailure.class);
        var order=org.mockito.Mockito.inOrder(target);order.verify(target).execute();order.verify(target).getUpdateCount();
        assertThat(closing.injected.get()).isEqualTo(1);assertThat(closing.failFinalOrderUpdate.get()).isFalse();
    }
}
