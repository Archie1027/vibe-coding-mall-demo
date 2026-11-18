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
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
import static org.assertj.core.api.Assertions.*;

/** CANDIDATE only. A separate exact-source approval and permanent one-attempt marker are mandatory. */
@EnabledIfEnvironmentVariable(named="M1_ORDER_CREATE_INTEGRATION",matches="read-only-order-startup|approved-temporary-order-create")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={
    "spring.flyway.enabled=false","spring.sql.init.mode=never","app.dev-bootstrap.enabled=false",
    "app.catalog.writes-enabled=false","app.customer.writes-enabled=false","debug=false"})
@Import(MySqlOrderCreateIntegrationTest.FixtureConfiguration.class)
class MySqlOrderCreateIntegrationTest {
    static final Path BATCH=Path.of(".local/acceptance");
    static final Path REFERENCE=BATCH.resolve("baseline-v8-utc.json");
    static final String URL="jdbc:mysql://127.0.0.1:3306/zhicai_dev?connectionTimeZone=UTC";
    static final List<String> TABLES=List.of("account","role","permission","account_role","role_permission","warehouse","account_warehouse",
        "category","brand","asset","product","sku","product_asset","flyway_schema_history","customer_address","cart_item",
        "idempotency_request","audit_log","stock_balance","stock_reservation","stock_ledger","checkout_quote","sales_order","sales_order_item");
    static final List<String> RESOURCES=List.of("product","sku","product_asset","customer_address","stock_balance","checkout_quote","sales_order","sales_order_item","stock_reservation","stock_ledger");
    static final List<String> DELETE_ORDER=List.of("stock_ledger","stock_reservation","sales_order_item","sales_order","checkout_quote","stock_balance","customer_address","product_asset","sku","product");
    static final List<String> EMPTY=List.of("customer_address","cart_item","idempotency_request","audit_log","stock_balance","stock_reservation","stock_ledger","checkout_quote","sales_order","sales_order_item");
    static final SqlGuard GUARD=new SqlGuard();
    @DynamicPropertySource static void local(DynamicPropertyRegistry p){
        if(!Set.of(URL,"jdbc:mysql://127.0.0.1:3306/zhicai_dev").contains(Objects.toString(System.getenv("DB_URL"),"")))throw new IllegalStateException("Canonical local zhicai_dev URL required");
        p.add("spring.datasource.url",()->URL);p.add("spring.datasource.username",()->System.getenv("DB_USERNAME"));p.add("spring.datasource.password",()->System.getenv("DB_PASSWORD"));
        p.add("spring.datasource.hikari.connection-init-sql",()->"SET time_zone = '+00:00'");
        p.add("spring.flyway.enabled",()->false);p.add("spring.sql.init.mode",()->"never");p.add("app.dev-bootstrap.enabled",()->false);
        p.add("app.catalog.writes-enabled",()->false);p.add("app.customer.writes-enabled",()->false);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired OrderService orders;
    @Autowired QuoteService quotes;
    @Autowired InventoryReservationService inventory;
    @Autowired CheckoutCatalogService catalog;
    @Autowired ObjectMapper json;
    @Autowired SqlSessionFactory sessions;
    final String run=UUID.randomUUID().toString(),trace="order-"+run,name="订单验收-"+run;
    final Map<String,String> baseline=new LinkedHashMap<>();
    final Map<String,String> referenceDigests=new LinkedHashMap<>();
    final Map<String,List<Map<String,String>>> original=new LinkedHashMap<>();
    final Map<String,Set<String>> owned=new LinkedHashMap<>();
    final Map<String,List<String>> columns=new LinkedHashMap<>();
    final List<String> checks=new ArrayList<>();
    Path manifest;
    String owner,warehouse,category,brand,asset,product,address,sku1,sku2;
    QuoteModels.Quote quote1,quote2,quote3;
    OrderModels.Order order;
    Map<String,List<Map<String,String>>> seeds;

    @Test @EnabledIfEnvironmentVariable(named="M1_ORDER_CREATE_INTEGRATION",matches="read-only-order-startup")
    void readOnlyStartupAndBaseline() throws Exception {
        prepare();guardNegativeChecks();transactionGuards();
        assertThat(json.readValue(json.writeValueAsString(new OrderModels.Create("1","验收")),OrderModels.Create.class)).isEqualTo(new OrderModels.Create("1","验收"));
        assertOriginalUnchanged();assertThat(GUARD.attempts).isEmpty();assertThat(GUARD.locks.get()).isZero();
        note(Map.of("stage","read-only-startup-verified","tableCount",24,"originalDigests",baseline,"referenceDigests",referenceDigests,"sqlMutationAttempts",0,"lockingSelects",0,"guardNegativeCases",13,"referenceCountsVerified",true,"referenceDigestsVerified",24));
    }

    @Test @EnabledIfEnvironmentVariable(named="M1_ORDER_CREATE_INTEGRATION",matches="approved-temporary-order-create")
    void approvedSingleOrderCreateScenario() throws Exception {
        prepare();verifyApproval();transactionGuards();
        try {
            note(Map.of("stage","seed-intent","run",run,"owner",owner,"warehouse",warehouse,"category",category,"brand",brand,"asset",asset,"sqlAttemptCaps",SqlGuard.CAPS));
            GUARD.armed=true;createSeeds();
            quote1=quote();quote2=quote();register("checkout_quote",quote1.id());register("checkout_quote",quote2.id());
            concurrentOrderAndPriceWriter();
            assertApi("QUOTE_CHANGED",()->tx().execute(s->orders.create(owner,new OrderModels.Create(quote2.id(),name),trace)));
            assertThat(count("sales_order")).isEqualTo(1);checks.add("pre-update second quote rejects QUOTE_CHANGED with no second order");
            quote3=quote();register("checkout_quote",quote3.id());
            var before=allDigests();GUARD.failFinalQuoteUpdate.set(true);
            assertThatThrownBy(()->tx().execute(s->orders.create(owner,new OrderModels.Create(quote3.id(),name),trace))).hasRootCauseInstanceOf(ControlledSqlFailure.class);
            assertThat(GUARD.failFinalQuoteUpdate.get()).isFalse();assertThat(GUARD.injected.get()).isEqualTo(1);
            assertThat(allDigests()).as("Actual final quote UPDATE executed, then SQLException rolled the entire order transaction back").isEqualTo(before);
            checks.add("actual final quote UPDATE delegates before controlled SQLException; all24 full-row digests restore after rollback");
            var frozen=validateOwned();assertOriginalUnchanged();
            assertThat(GUARD.attempts).containsExactlyInAnyOrderEntriesOf(SqlGuard.CAPS);
            note(Map.of("stage","checks-passed","checks",checks,"sqlAttemptCounts",GUARD.snapshot(),"committedRows",17,"ownedRows",frozen));
            cleanup(frozen);assertOriginalUnchanged();
            note(Map.of("stage","cleaned-and-verified","originalDigests",baseline,"sqlAttemptCounts",GUARD.snapshot(),"deleteAttempts",GUARD.deletes.get(),"autoIncrementRestored",false));
        }catch(Exception|AssertionError failure){note(Map.of("stage","retained-after-failure","exceptionClass",failure.getClass().getName(),"knownIds",owned,"sqlAttemptCounts",GUARD.snapshot()));throw failure;}
        finally{GUARD.armed=false;GUARD.cleanup=false;}
    }

    private void prepare() throws Exception {
        String root=System.getenv("M1_ORDER_EVIDENCE_ROOT");assertThat(root).isNotBlank();Path evidence=Path.of(root).toRealPath();
        assertThat(evidence).isEqualTo(BATCH.toRealPath());assertThat(evidence.startsWith(Path.of(".").toRealPath())).isFalse();
        Path directory=evidence.resolve("order-"+run);Files.createDirectory(directory);manifest=directory.resolve("manifest.jsonl");Files.createFile(manifest);
        note(Map.of("stage","preflight-intent","run",run,"mode",Objects.toString(System.getenv("M1_ORDER_CREATE_INTEGRATION")),"fixtureSha256",Objects.toString(System.getenv("M1_ORDER_FIXTURE_SHA256")),"implementationSha256",Objects.toString(System.getenv("M1_ORDER_IMPLEMENTATION_SHA256"))));
        assertThat(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)c->c.getMetaData().getURL())).isEqualTo(URL);
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("zhicai_dev");assertThat(jdbc.queryForObject("SELECT @@session.time_zone",String.class)).isEqualTo("+00:00");
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'",String.class)).containsExactlyInAnyOrderElementsOf(TABLES);
        int[] checksums={-357524081,502337516,174647045,-493016158,237973485,947094408,-458546823,-652983552};
        var history=jdbc.queryForList("SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank");assertThat(history).hasSize(8);
        for(int i=0;i<8;i++){assertThat(history.get(i).get("version").toString()).isEqualTo(""+(i+1));assertThat(((Number)history.get(i).get("checksum")).intValue()).isEqualTo(checksums[i]);assertThat(history.get(i).get("success").toString()).isIn("true","1");}
        byte[] referenceBytes=Files.readAllBytes(REFERENCE);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(referenceBytes))).isEqualTo("0000000000000000000000000000000000000000000000000000000000000000");
        var reference=json.readTree(referenceBytes);
        for(String table:TABLES){assertThat(count(table)).as("Reviewed V8 baseline: %s",table).isEqualTo(reference.path("Counts").path(table).asInt(-1));
            columns.put(table,jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position",String.class,table));
            referenceDigests.put(table,referenceDigest(table,reference.path("Counts").path(table).asInt(-1)));
            assertThat(referenceDigests.get(table)).as("Exact original reviewed full-row baseline: %s",table).isEqualTo(reference.path("OriginalDigests").path(table).asText());
            original.put(table,rows(table,false));baseline.put(table,digest(original.get(table)));owned.put(table,new LinkedHashSet<>());
        }
        for(String table:EMPTY)assertThat(count(table)).isZero();
        owner=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM account WHERE account_type='CUSTOMER' AND enabled=1 ORDER BY id LIMIT 1",String.class);
        warehouse=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM warehouse WHERE enabled=1 AND fulfillment_enabled=1 ORDER BY priority,id LIMIT 1",String.class);
        category=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM category WHERE enabled=1 ORDER BY id LIMIT 1",String.class);
        brand=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM brand WHERE enabled=1 ORDER BY id LIMIT 1",String.class);
        asset=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM asset WHERE visibility='PUBLIC' AND size_bytes<=5242880 AND content_type IN ('image/jpeg','image/png','image/webp') ORDER BY id LIMIT 1",String.class);
        for(Object service:List.of(orders,quotes,inventory))assertThat(AopUtils.isAopProxy(service)).as("Transactional service proxy: %s",service.getClass().getName()).isTrue();
        for(String statement:List.of("trade.OrderMapper.lockQuote","trade.OrderMapper.insertOrder","trade.OrderMapper.insertItem","trade.OrderMapper.markQuoteUsed","trade.QuoteMapper.insert","catalog.CheckoutCatalogMapper.lockProduct","catalog.CheckoutCatalogMapper.lockCategoryAnchor","catalog.CheckoutCatalogMapper.lockEnabledSkus","catalog.CheckoutCatalogMapper.lockPublicImages","inventory.StockMapper.insertReservation","inventory.StockMapper.insertLedger"))assertThat(sessions.getConfiguration().hasStatement("com.zhicai.platform."+statement)).isTrue();
        assertThat(GUARD.attempts).isEmpty();assertThat(GUARD.locks.get()).isZero();
        note(Map.of("stage","baseline-verified","originalDigests",baseline,"referenceDigests",referenceDigests,"referenceCounts",reference.path("Counts"),"tableCount",24,"sqlMutationAttempts",0,"lockingSelects",0));
    }
    private String referenceDigest(String table,int expectedCount){return MySqlAcceptanceSupport.referenceDigest(jdbc,table,columns.get(table),expectedCount);}
    private void verifyApproval() throws Exception {
        var marker=json.readTree(Files.readString(BATCH.resolve("apply-attempt.json")));
        assertThat(marker.path("Mode").asText()).isEqualTo("approved-temporary-order-create");
        for(String key:List.of("FixtureSha256","ImplementationSha256"))assertThat(marker.path(key).asText()).matches("[A-Fa-f0-9]{64}");
        assertThat(marker.path("FixtureSha256").asText()).isEqualTo(System.getenv("M1_ORDER_FIXTURE_SHA256"));
        assertThat(marker.path("ImplementationSha256").asText()).isEqualTo(System.getenv("M1_ORDER_IMPLEMENTATION_SHA256"));
        assertThat(marker.path("ApprovalId").asText()).isNotBlank().isEqualTo(System.getenv("M1_ORDER_APPROVAL_ID"));
    }
    private void transactionGuards(){
        var request=new OrderModels.Create("1",name);var qr=new QuoteModels.Request("1",List.of(new QuoteModels.Item("1",1)),null);
        int before=GUARD.executions.get();
        assertThatThrownBy(()->orders.create(owner,request,trace)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(()->quotes.create(owner,qr)).isInstanceOf(IllegalTransactionStateException.class);
        for(int isolation:new int[]{TransactionDefinition.ISOLATION_DEFAULT,TransactionDefinition.ISOLATION_REPEATABLE_READ}){
            var invalid=tx();invalid.setIsolationLevel(isolation);
            assertThatThrownBy(()->invalid.execute(s->orders.create(owner,request,trace))).isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(()->invalid.execute(s->quotes.create(owner,qr))).isInstanceOf(IllegalTransactionStateException.class);
        }
        var ro=tx();ro.setReadOnly(true);
        assertThatThrownBy(()->ro.execute(s->orders.create(owner,request,trace))).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(()->ro.execute(s->quotes.create(owner,qr))).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(GUARD.executions.get()).as("Transaction gates reject before first SQL execution").isEqualTo(before);
    }
    private void createSeeds() throws Exception {
        tx().executeWithoutResult(s->{
            product=insert("product","INSERT INTO product(name,category_id,brand_id,description,state) VALUES(?,CAST(? AS UNSIGNED),CAST(? AS UNSIGNED),?,'ON_SALE')",name,category,brand,name);
            sku1=seedSku("a","100");sku2=seedSku("b","200");
            assertThat(jdbc.update("INSERT INTO product_asset(product_id,asset_id,sort_order) VALUES(CAST(? AS UNSIGNED),CAST(? AS UNSIGNED),0)",product,asset)).isEqualTo(1);register("product_asset",product+":"+asset);
            address=insert("customer_address","INSERT INTO customer_address(customer_id,recipient,phone,province,city,district,detail,is_default) VALUES(CAST(? AS UNSIGNED),?,'10000000007','测试省','测试市','测试区',?,FALSE)",owner,name,name);
            for(String sku:List.of(sku1,sku2))insert("stock_balance","INSERT INTO stock_balance(warehouse_id,sku_id,on_hand,reserved,quarantine) VALUES(CAST(? AS UNSIGNED),CAST(? AS UNSIGNED),10,0,0)",warehouse,sku);
        });
        seeds=ownRows();note(Map.of("stage","seed-created","ownedRows",seeds));
    }
    private String seedSku(String suffix,String price){String specs="[{\"name\":\"验收\",\"value\":\""+run+suffix+"\"}]";
        return insert("sku","INSERT INTO sku(product_id,code,specs_json,specs_hash,price_minor) VALUES(CAST(? AS UNSIGNED),?,?,?,CAST(? AS UNSIGNED))",product,"order-"+run+suffix,specs,sha(specs),price);
    }
    private QuoteModels.Quote quote(){return Objects.requireNonNull(tx().execute(s->quotes.create(owner,new QuoteModels.Request(address,List.of(new QuoteModels.Item(sku1,2),new QuoteModels.Item(sku2,1)),null))));}
    private void concurrentOrderAndPriceWriter() throws Exception {
        var pool=Executors.newFixedThreadPool(3);var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        var writerAtSql=new CountDownLatch(1);var rivalAtSql=new CountDownLatch(1);
        GUARD.writerAtSql=writerAtSql;GUARD.rivalAtSql=rivalAtSql;
        try {
            var first=pool.submit(()->tx().execute(s->{var result=orders.create(owner,new OrderModels.Create(quote1.id(),name),trace);held.countDown();await(release);return result;}));
            assertThat(held.await(8,TimeUnit.SECONDS)).isTrue();
            var rival=pool.submit(()->{GUARD.role.set("rival");try{assertApi("STATE_CONFLICT",()->tx().execute(s->orders.create(owner,new OrderModels.Create(quote1.id(),name),trace)));}finally{GUARD.role.remove();}});
            var writer=pool.submit(()->{GUARD.role.set("writer");try{tx().executeWithoutResult(s->{
                assertThat(jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM product WHERE id=CAST(? AS UNSIGNED) FOR UPDATE",String.class,product)).isEqualTo(product);
                assertThat(jdbc.update("UPDATE sku SET price_minor=101,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=CAST(? AS UNSIGNED) AND product_id=CAST(? AS UNSIGNED) AND price_minor=100 AND version=0",sku1,product)).isEqualTo(1);
            });}finally{GUARD.role.remove();}});
            assertThat(writerAtSql.await(5,TimeUnit.SECONDS)).isTrue();assertThat(rivalAtSql.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->writer.get(350,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThatThrownBy(()->rival.get(350,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            note(Map.of("stage","concurrency-blocked-before-first-commit","writerAtSql",true,"sameQuoteAtSql",true));
            release.countDown();order=first.get(10,TimeUnit.SECONDS);rival.get(10,TimeUnit.SECONDS);writer.get(10,TimeUnit.SECONDS);
            register("sales_order",order.id());for(var line:order.items())register("sales_order_item",line.id());
            assertThat(count("sales_order")).isEqualTo(1);assertThat(count("stock_reservation")).isEqualTo(2);assertThat(count("stock_ledger")).isEqualTo(2);
            checks.add("same-quote independent RC transaction and product-root price writer both block until T1 commits; rival STATE_CONFLICT; only one order");
        }finally{
            release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(15,TimeUnit.SECONDS)).as("All worker outcomes must settle before any cleanup").isTrue();
            GUARD.writerAtSql=null;GUARD.rivalAtSql=null;
        }
    }
    private void await(CountDownLatch latch){try{if(!latch.await(12,TimeUnit.SECONDS))throw new IllegalStateException("Order commit barrier timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    private Map<String,List<Map<String,String>>> validateOwned() throws Exception {
        var result=ownRows();var orderRows=result.get("sales_order");assertThat(orderRows).hasSize(1);var o=orderRows.getFirst();
        assertThat(o.get("id")).isEqualTo(order.id());assertThat(order.id()).matches("[1-9][0-9]{0,18}");assertThat(order.orderNo()).matches("ZC[0-9a-f]{32}");
        assertThat(o.get("order_no")).isEqualTo(order.orderNo());assertThat(o.get("quote_id")).isEqualTo(quote1.id());assertThat(o.get("customer_id")).isEqualTo(owner);
        assertThat(o.get("warehouse_id")).isEqualTo(warehouse);assertThat(o.get("state")).isEqualTo("UNPAID");assertThat(o.get("remark")).isEqualTo(name);
        assertThat(order.expiresAt()).isEqualTo(order.createdAt().plusSeconds(1800));assertThat(order.createdAt().getNano()%1_000_000).isZero();
        assertThat(utcOrderTimestamp(o.get("expires_at"))).isEqualTo(order.expiresAt());assertThat(utcOrderTimestamp(o.get("created_at"))).isEqualTo(order.createdAt());
        for(String field:List.of("gross_minor","payable_minor"))assertThat(o.get(field)).isEqualTo("400");
        for(String field:List.of("discount_minor","shipping_minor","refunded_minor","version"))assertThat(o.get(field)).isEqualTo("0");
        var q1=result.get("checkout_quote").stream().filter(r->quote1.id().equals(r.get("id"))).findFirst().orElseThrow();
        var snap=json.readValue(q1.get("snapshot_json"),QuoteModels.Snapshot.class);
        assertThat(json.readValue(o.get("address_snapshot_json"),TradeModels.AddressInput.class)).isEqualTo(order.addressSnapshot());
        assertThat(order.addressSnapshot().recipient()).isEqualTo(name);assertThat(order.addressSnapshot().detail()).isEqualTo(name);
        assertThat(q1.get("used_order_id")).isEqualTo(order.id());
        for(var q:result.get("checkout_quote")){assertThat(q.get("customer_id")).isEqualTo(owner);assertThat(q.get("warehouse_id")).isEqualTo(warehouse);
            var snapshot=json.readValue(q.get("snapshot_json"),QuoteModels.Snapshot.class);assertThat(snapshot.owner()).isEqualTo(owner);assertThat(snapshot.addressId()).isEqualTo(address);assertThat(snapshot.items()).hasSize(2);
            if(!q.get("id").equals(quote1.id()))assertThat(q.get("used_order_id")).isNull();
        }
        var items=result.get("sales_order_item");assertThat(items).hasSize(2);
        for(var line:order.items()){
            var i=items.stream().filter(r->line.id().equals(r.get("id"))).findFirst().orElseThrow();var quoted=snap.items().stream().filter(x->line.skuId().equals(x.amounts().skuId())).findFirst().orElseThrow();
            assertThat(i.get("order_id")).isEqualTo(order.id());assertThat(i.get("product_id")).isEqualTo(product);assertThat(i.get("sku_id")).isEqualTo(line.skuId());assertThat(i.get("product_name")).isEqualTo(name);
            assertThat(Integer.parseInt(i.get("line_no"))).isEqualTo(order.items().indexOf(line));assertThat(i.get("quantity")).isEqualTo(""+line.quantity());assertThat(i.get("unit_price_minor")).isEqualTo(quoted.amounts().unitPriceMinor());
            assertThat(i.get("gross_minor")).isEqualTo(line.grossMinor());assertThat(i.get("payable_minor")).isEqualTo(line.payableMinor());
            assertThat(json.readTree(i.get("specs_snapshot_json"))).isEqualTo(json.readTree(json.writeValueAsString(quoted.amounts().specs())));
            var image=json.readTree(i.get("image_snapshot_json"));assertThat(image.path("assetId").asText()).isEqualTo(asset);
            var assetRow=original.get("asset").stream().filter(r->asset.equals(r.get("id"))).findFirst().orElseThrow();assertThat(image.path("storageKey").asText()).isEqualTo(assetRow.get("storage_key"));
            assertThat(image.path("contentType").asText()).isEqualTo(assetRow.get("content_type"));assertThat(image.path("sizeBytes").asLong()).isEqualTo(Long.parseLong(assetRow.get("size_bytes")));
            for(String field:List.of("returned_quantity","refund_quantity_reserved","refunded_minor","version","discount_minor"))assertThat(i.get(field)).isEqualTo("0");
            var rs=rows("stock_reservation",false).stream().filter(r->line.id().equals(r.get("business_line_id"))).toList();assertThat(rs).hasSize(1);var r=rs.getFirst();
            assertThat(r.get("business_type")).isEqualTo("ORDER");assertThat(r.get("warehouse_id")).isEqualTo(warehouse);assertThat(r.get("sku_id")).isEqualTo(line.skuId());assertThat(r.get("quantity")).isEqualTo(""+line.quantity());assertThat(r.get("state")).isEqualTo("HELD");assertThat(r.get("version")).isEqualTo("0");register("stock_reservation",r.get("id"));
            var ls=rows("stock_ledger",false).stream().filter(e->line.id().equals(e.get("biz_line_id"))).toList();assertThat(ls).hasSize(1);var l=ls.getFirst();
            for(String key:List.of("biz_type","action"))assertThat(l.get(key)).isEqualTo(key.equals("action")?"RESERVE":"ORDER");assertThat(l.get("biz_id")).isEqualTo(order.id());assertThat(l.get("warehouse_id")).isEqualTo(warehouse);assertThat(l.get("sku_id")).isEqualTo(line.skuId());assertThat(l.get("trace_id")).isEqualTo(trace);assertThat(l.get("actor_id")).isEqualTo(owner);
            assertThat(l.get("delta_reserved")).isEqualTo(""+line.quantity());assertThat(l.get("reserved_before")).isEqualTo("0");assertThat(l.get("reserved_after")).isEqualTo(""+line.quantity());
            for(String key:List.of("on_hand_before","on_hand_after"))assertThat(l.get(key)).isEqualTo("10");for(String key:List.of("quarantine_before","quarantine_after","in_transit_before","in_transit_after","delta_on_hand","delta_quarantine","delta_in_transit"))assertThat(l.get(key)).isEqualTo("0");register("stock_ledger",l.get("id"));
        }
        result=ownRows();assertThat(result.get("product")).hasSize(1);var p=result.get("product").getFirst();assertThat(p.get("name")).isEqualTo(name);assertThat(p.get("description")).isEqualTo(name);assertThat(p.get("category_id")).isEqualTo(category);assertThat(p.get("brand_id")).isEqualTo(brand);assertThat(p.get("state")).isEqualTo("ON_SALE");
        assertThat(result.get("customer_address")).isEqualTo(seeds.get("customer_address"));assertThat(result.get("product_asset")).isEqualTo(seeds.get("product_asset"));assertThat(result.get("product")).isEqualTo(seeds.get("product"));
        for(var s:result.get("sku")){
            var seed=seeds.get("sku").stream().filter(x->x.get("id").equals(s.get("id"))).findFirst().orElseThrow();
            for(String column:columns.get("sku"))if(!Set.of("price_minor","version","updated_at").contains(column))assertThat(s.get(column)).as("SKU immutable seeded column %s",column).isEqualTo(seed.get(column));
            assertThat(s.get("price_minor")).isEqualTo(s.get("id").equals(sku1)?"101":"200");assertThat(s.get("version")).isEqualTo(s.get("id").equals(sku1)?"1":"0");
        }
        for(var b:result.get("stock_balance")){
            var seed=seeds.get("stock_balance").stream().filter(x->x.get("id").equals(b.get("id"))).findFirst().orElseThrow();
            for(String column:columns.get("stock_balance"))if(!Set.of("reserved","version","updated_at").contains(column))assertThat(b.get(column)).as("Balance immutable seeded column %s",column).isEqualTo(seed.get(column));
            assertThat(b.get("reserved")).isEqualTo(b.get("sku_id").equals(sku1)?"2":"1");assertThat(b.get("version")).isEqualTo("1");
        }
        var sizes=Map.of("product",1,"sku",2,"product_asset",1,"customer_address",1,"stock_balance",2,"checkout_quote",3,"sales_order",1,"sales_order_item",2,"stock_reservation",2,"stock_ledger",2);
        for(var entry:sizes.entrySet())assertThat(result.get(entry.getKey())).hasSize(entry.getValue());
        checks.add("all17 committed rows have generated/derived identities and verified random fields, owner, business references, snapshots, amounts, order timestamps and stock effects; every complete row is frozen before cleanup");return result;
    }
    private void cleanup(Map<String,List<Map<String,String>>> frozen) throws Exception {
        note(Map.of("stage","exact-cleanup-intent","knownIds",owned,"rowHashes",rowHashes(frozen),"lockScope","RR full PRIMARY ranges of ten resource tables; includes original product/sku/product_asset rows"));
        var expected=new LinkedHashMap<String,List<Map<String,String>>>();for(String table:RESOURCES){var list=new ArrayList<>(original.get(table));list.addAll(frozen.get(table));list.sort(Comparator.comparing(this::rowDigest));expected.put(table,List.copyOf(list));}
        GUARD.cleanup=true;var cleanup=tx();cleanup.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        cleanup.executeWithoutResult(s->{
            for(String table:RESOURCES)assertThat(rows(table,true)).as("Exact whole membership and full postimage before first DELETE: %s",table).isEqualTo(expected.get(table));
            assertOriginalUnchanged();
            for(String table:DELETE_ORDER)for(var row:frozen.get(table)){
                String where=table.equals("product_asset")?"product_id=CAST(? AS UNSIGNED) AND asset_id=CAST(? AS UNSIGNED)":"id=CAST(? AS UNSIGNED)";
                var args=new ArrayList<Object>();if(table.equals("product_asset")){args.add(row.get("product_id"));args.add(row.get("asset_id"));}else args.add(row.get("id"));args.add(row.get("_guard_hash"));
                assertThat(jdbc.update("DELETE FROM `"+table+"` WHERE "+where+" AND "+hashSql(table)+"=?",args.toArray())).isEqualTo(1);
            }
            for(String table:TABLES)assertThat(digest(rows(table,false))).as("Original24 digest restored inside cleanup transaction: %s",table).isEqualTo(baseline.get(table));
        });GUARD.cleanup=false;assertThat(GUARD.deletes.get()).isEqualTo(17);
    }
    private Map<String,List<String>> rowHashes(Map<String,List<Map<String,String>>> data){var result=new LinkedHashMap<String,List<String>>();data.forEach((t,r)->result.put(t,r.stream().map(x->x.get("_guard_hash")).toList()));return result;}
    private TransactionTemplate tx(){var t=new TransactionTemplate(manager);t.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);t.setTimeout(20);return t;}
    private void assertApi(String code,Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.status()).isEqualTo(409);assertThat(e.code()).isEqualTo(code);});}
    private String insert(String table,String sql,String... args){var holder=new GeneratedKeyHolder();assertThat(jdbc.update(c->{var ps=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)ps.setString(i+1,args[i]);return ps;},holder)).isEqualTo(1);String id=Objects.requireNonNull(holder.getKey()).toString();register(table,id);return id;}
    private void register(String table,String id){assertThat(id).matches(table.equals("product_asset")?"[1-9][0-9]{0,18}:[1-9][0-9]{0,18}":"[1-9][0-9]{0,18}");assertThat(owned.get(table).add(id)).as("Known identity registered only once: %s/%s",table,id).isTrue();}
    private int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Integer.class);}
    private String key(String table,Map<String,String> row){return table.equals("product_asset")?row.get("product_id")+":"+row.get("asset_id"):row.get("id");}
    private String hashSql(String table){return MySqlAcceptanceSupport.hashSql(columns.get(table));}
    private List<Map<String,String>> rows(String table,boolean lock){return MySqlAcceptanceSupport.rows(jdbc,table,columns.get(table),lock);}
    private Map<String,List<Map<String,String>>> ownRows(){var result=new LinkedHashMap<String,List<Map<String,String>>>();for(String table:RESOURCES)result.put(table,rows(table,false).stream().filter(r->owned.get(table).contains(key(table,r))).toList());return Collections.unmodifiableMap(result);}
    private Map<String,String> allDigests(){var result=new LinkedHashMap<String,String>();for(String table:TABLES)result.put(table,digest(rows(table,false)));return result;}
    private void assertOriginalUnchanged(){for(String table:TABLES)assertThat(digest(rows(table,false).stream().filter(r->!owned.get(table).contains(key(table,r))).toList())).as("All original rows and no unknown membership: %s",table).isEqualTo(baseline.get(table));}
    private String rowDigest(Map<String,String> row){return MySqlAcceptanceSupport.rowDigest(row);}
    private String digest(List<Map<String,String>> rows){return MySqlAcceptanceSupport.digest(rows);}
    static String sha(String value){return MySqlAcceptanceSupport.sha(value);}
    static Instant utcOrderTimestamp(String value){return MySqlAcceptanceSupport.utcTimestamp(value);}
    private synchronized void note(Map<String,?> event) throws Exception {byte[] bytes=(json.writeValueAsString(event)+"\n").getBytes(StandardCharsets.UTF_8);try(FileChannel f=FileChannel.open(manifest,StandardOpenOption.WRITE,StandardOpenOption.APPEND)){var b=ByteBuffer.wrap(bytes);while(b.hasRemaining())f.write(b);f.force(true);}}

    /** Guard self-checks have no connection and can never write a database. */
    private void guardNegativeChecks(){
        var g=new SqlGuard();
        for(String sql:List.of("INSERT INTO product(name) VALUES(?)","UPDATE sku SET price_minor=1","DELETE FROM product WHERE id=1","CREATE TABLE x(id int)","CALL x()","SELECT 1; DELETE FROM product","SELECT * FROM product FOR UPDATE","SELECT * FROM category FOR SHARE","SELECT * FROM product LOCK IN SHARE MODE","/*skip*/ DELETE FROM product","WITH x AS (SELECT 1) DELETE FROM product","SELECT 1 INTO OUTFILE '/tmp/forbidden'"))assertThatThrownBy(()->g.before(sql)).isInstanceOf(SQLException.class);
        g.armed=true;g.testApproval=true;g.beforeUnchecked("INSERT INTO product(name) VALUES(?)");assertThatThrownBy(()->g.before("INSERT INTO product(name) VALUES(?)")).isInstanceOf(SQLException.class);assertThat(g.attempts.get("INSERT product")).isEqualTo(1);
        try {
            var contract=new OrderFixtureGuardContractTest();
            contract.dataSourceHasNoPublicTargetOrUnwrapEscape();contract.utcDateTimeIsIndependentOfDefaultTimeZone();
            for(var escape:OrderFixtureGuardContractTest.Escape.values()){
                OrderFixtureGuardContractTest.verifyOwnerGraph(escape,false);OrderFixtureGuardContractTest.verifyOwnerGraph(escape,true);
            }
        } catch(Exception failure){throw new IllegalStateException("Database-free JDBC owner-graph containment proof failed",failure);}
    }
    @TestConfiguration static class FixtureConfiguration {
        @Bean CheckoutCatalogService fixtureOrderCatalog(CheckoutCatalogMapper m,ObjectMapper j){return new CheckoutCatalogService(m,j);}
        @Bean InventoryAvailabilityService fixtureOrderAvailability(InventoryAvailabilityMapper m){return new InventoryAvailabilityService(m);}
        @Bean InventoryReservationService fixtureOrderInventory(StockMapper m){return new InventoryReservationService(m);}
        @Bean QuoteService fixtureOrderQuotes(QuoteMapper m,AddressMapper a,CheckoutCatalogService c,InventoryAvailabilityService i,ObjectMapper j,Clock clock){return new QuoteService(m,a,c,i,j,clock);}
        @Bean OrderService fixtureOrderService(OrderMapper m,CheckoutCatalogService c,InventoryAvailabilityService i,InventoryReservationService r,ObjectMapper j,Clock clock){return new OrderService(m,c,i,r,j,clock);}
        @Bean static BeanPostProcessor guardedDataSource(){return new BeanPostProcessor(){@Override public Object postProcessAfterInitialization(Object bean,String name){return bean instanceof DataSource source?GUARD.dataSource(source):bean;}};}
    }
    static class ControlledSqlFailure extends SQLException {ControlledSqlFailure(){super("Deliberate failure after actual final quote update","45000");}}
    static class SqlGuard extends MySqlAcceptanceSupport.SqlGuard {
        static final Map<String,Integer> CAPS=Map.ofEntries(Map.entry("INSERT product",1),Map.entry("INSERT sku",2),Map.entry("UPDATE sku",1),Map.entry("INSERT product_asset",1),Map.entry("INSERT customer_address",1),Map.entry("INSERT stock_balance",2),Map.entry("UPDATE stock_balance",4),Map.entry("INSERT checkout_quote",3),Map.entry("UPDATE checkout_quote",2),Map.entry("INSERT sales_order",2),Map.entry("INSERT sales_order_item",4),Map.entry("INSERT stock_reservation",4),Map.entry("INSERT stock_ledger",4));
        final AtomicInteger injected=new AtomicInteger();
        final AtomicBoolean failFinalQuoteUpdate=new AtomicBoolean();final ThreadLocal<String> role=new ThreadLocal<>();
        volatile CountDownLatch writerAtSql,rivalAtSql;
        boolean testApproval;
        SqlGuard(){super(CAPS,RESOURCES,17,()->"approved-temporary-order-create".equals(System.getenv("M1_ORDER_CREATE_INTEGRATION")));}
        @Override boolean approved(){return testApproval||super.approved();}
        @Override void beforeExecution(String sql,String kind){
            if("writer".equals(role.get())&&writerAtSql!=null&&sql.toUpperCase(Locale.ROOT).contains("FOR UPDATE"))writerAtSql.countDown();
            if("rival".equals(role.get())&&rivalAtSql!=null&&sql.toUpperCase(Locale.ROOT).contains("FOR UPDATE"))rivalAtSql.countDown();
        }
        @Override void afterExecution(String sql,String kind,Object value)throws SQLException {
            if(kind.equals("UPDATE checkout_quote")&&failFinalQuoteUpdate.compareAndSet(true,false)){injected.incrementAndGet();throw new ControlledSqlFailure();}
        }
    }
}

/** No Spring context or database: raw JDBC delegates are Mockito objects, including every escape handle. */
class OrderFixtureGuardContractTest {
    enum Escape { METADATA_CONNECTION, QUERY_RESULT, CURRENT_RESULT, GENERATED_KEYS, METADATA_RESULT }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(Escape.class)
    void readOnlyOwnerReferencesRejectBeforeDelegate(Escape escape) throws Exception {verifyOwnerGraph(escape,false);}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(Escape.class)
    void cappedOwnerReferencesRejectBeforeDelegate(Escape escape) throws Exception {verifyOwnerGraph(escape,true);}
    @Test void dataSourceHasNoPublicTargetOrUnwrapEscape() throws Exception {
        var raw=org.mockito.Mockito.mock(DataSource.class);
        org.mockito.Mockito.when(raw.unwrap(DataSource.class)).thenReturn(raw);
        var guarded=(DataSource)MySqlOrderCreateIntegrationTest.FixtureConfiguration.guardedDataSource().postProcessAfterInitialization(raw,"fake");
        assertThat(Arrays.stream(guarded.getClass().getMethods()).map(Method::getName)).doesNotContain("getTargetDataSource");
        assertThatThrownBy(()->guarded.unwrap(DataSource.class)).isInstanceOf(SQLException.class);
        org.mockito.Mockito.verify(raw,org.mockito.Mockito.never()).unwrap(DataSource.class);
    }
    @Test void utcDateTimeIsIndependentOfDefaultTimeZone(){
        var previous=TimeZone.getDefault();
        try {
            for(String zone:List.of("Asia/Taipei","UTC")){
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                assertThat(MySqlOrderCreateIntegrationTest.utcOrderTimestamp("2026-10-26 11:04:09.123")).isEqualTo(Instant.parse("2026-10-26T11:04:09.123Z"));
                assertThat(MySqlOrderCreateIntegrationTest.utcOrderTimestamp("2026-10-26 11:04:09.0")).isEqualTo(Instant.parse("2026-10-26T11:04:09Z"));
            }
        } finally {TimeZone.setDefault(previous);}
    }
    static void verifyOwnerGraph(Escape escape,boolean capped)throws Exception {
        var raw=org.mockito.Mockito.mock(Connection.class);var statement=org.mockito.Mockito.mock(Statement.class);
        var metadata=org.mockito.Mockito.mock(DatabaseMetaData.class);var result=org.mockito.Mockito.mock(ResultSet.class);
        org.mockito.Mockito.when(raw.createStatement()).thenReturn(statement);org.mockito.Mockito.when(raw.getMetaData()).thenReturn(metadata);
        org.mockito.Mockito.when(metadata.getConnection()).thenReturn(raw);org.mockito.Mockito.when(metadata.getTables(null,null,null,null)).thenReturn(result);
        org.mockito.Mockito.when(statement.executeQuery("SELECT 1")).thenReturn(result);org.mockito.Mockito.when(statement.getResultSet()).thenReturn(result);org.mockito.Mockito.when(statement.getGeneratedKeys()).thenReturn(result);
        org.mockito.Mockito.when(result.getStatement()).thenReturn(statement);
        var guard=new MySqlOrderCreateIntegrationTest.SqlGuard();
        if(capped){guard.armed=true;guard.testApproval=true;guard.before("INSERT INTO product(name) VALUES(?)");}
        var connection=guard.connection(raw);var guardedStatement=connection.createStatement();
        Statement reached=switch(escape){
            case METADATA_CONNECTION->connection.getMetaData().getConnection().createStatement();
            case QUERY_RESULT->guardedStatement.executeQuery("SELECT 1").getStatement();
            case CURRENT_RESULT->guardedStatement.getResultSet().getStatement();
            case GENERATED_KEYS->guardedStatement.getGeneratedKeys().getStatement();
            case METADATA_RESULT->connection.getMetaData().getTables(null,null,null,null).getStatement();
        };
        assertThatThrownBy(()->reached.executeUpdate("INSERT INTO product(name) VALUES(?)")).isInstanceOf(SQLException.class);
        assertThatThrownBy(()->reached.executeBatch()).isInstanceOf(SQLException.class);
        assertThatThrownBy(()->connection.unwrap(Connection.class)).isInstanceOf(SQLException.class);
        org.mockito.Mockito.verify(statement,org.mockito.Mockito.never()).executeUpdate(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(statement,org.mockito.Mockito.never()).executeBatch();
        assertThat(guard.snapshot()).hasSize(capped?1:0);
    }
}
