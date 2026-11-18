package com.zhicai.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.catalog.CheckoutCatalogMapper;
import com.zhicai.platform.catalog.CheckoutCatalogService;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.inventory.InventoryAvailabilityMapper;
import com.zhicai.platform.inventory.InventoryAvailabilityService;
import com.zhicai.platform.trade.*;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.zhicai.platform.trade.QuoteModels.*;
import static org.assertj.core.api.Assertions.*;

/** A new explicit SQL batch approval is required. Failure retains evidence and rows; never rerun automatically. */
@EnabledIfEnvironmentVariable(named="M1_QUOTE_PERSISTENCE_INTEGRATION",matches="read-only-quote-startup|approved-temporary-quote-persistence|read-only-retained-quote|approved-retained-quote-continuation")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={
    "spring.flyway.enabled=false","spring.sql.init.mode=never","app.dev-bootstrap.enabled=false",
    "app.catalog.writes-enabled=false","app.customer.writes-enabled=false","debug=false"})
@Import(MySqlQuotePersistenceIntegrationTest.FixtureConfiguration.class)
class MySqlQuotePersistenceIntegrationTest {
    static final String CANONICAL="jdbc:mysql://127.0.0.1:3306/zhicai_dev?connectionTimeZone=UTC";
    static final Instant CREATED=Instant.parse("2026-10-25T01:02:03.123Z");
    static final String RETAINED_RUN="59cad966-f357-4201-afac-1029c59c7531";
    static final String RETAINED_MANIFEST=".local/acceptance"+RETAINED_RUN+"/manifest.jsonl";
    static final String RETAINED_MANIFEST_SHA="0000000000000000000000000000000000000000000000000000000000000000";
    static final String RETAINED_SNAPSHOT_SHA="0000000000000000000000000000000000000000000000000000000000000000";
    static final Path RETAINED_SCENE=Path.of(".local/acceptance");
    static final List<String> TABLES=List.of("account","role","permission","account_role","role_permission","warehouse","account_warehouse",
        "category","brand","asset","product","sku","product_asset","flyway_schema_history","customer_address","cart_item",
        "idempotency_request","audit_log","stock_balance","stock_reservation","stock_ledger","checkout_quote");
    static final List<String> OWNED=List.of("checkout_quote","customer_address","stock_balance");
    static final List<String> EMPTY=List.of("customer_address","cart_item","idempotency_request","audit_log","stock_balance","stock_reservation","stock_ledger","checkout_quote");
    @DynamicPropertySource static void localDatabase(DynamicPropertyRegistry properties){
        if(!CANONICAL.equals(System.getenv("DB_URL")))throw new IllegalStateException("Canonical local UTC database URL required");
        properties.add("spring.datasource.url",()->CANONICAL);
        properties.add("spring.datasource.username",()->System.getenv("DB_USERNAME"));
        properties.add("spring.datasource.password",()->System.getenv("DB_PASSWORD"));
        properties.add("spring.datasource.hikari.connection-init-sql",()->"SET time_zone = '+00:00'");
        properties.add("spring.flyway.enabled",()->false);
        properties.add("spring.sql.init.mode",()->"never");
        properties.add("app.dev-bootstrap.enabled",()->false);
        properties.add("app.catalog.writes-enabled",()->false);
        properties.add("app.customer.writes-enabled",()->false);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired QuoteService quotes;
    @Autowired CheckoutCatalogService catalog;
    @Autowired AddressMapper addresses;
    @Autowired ObjectMapper json;
    @Autowired SqlSessionFactory sessions;
    @Autowired WriteBudget budget;
    String run=UUID.randomUUID().toString();
    String recipient="验收"+run,detail="临时报价验收-"+run;
    final Map<String,String> baseline=new LinkedHashMap<>();
    final List<String> checks=new ArrayList<>();
    Path manifest;
    String owner,otherOwner,warehouse,sku,addressId,balanceId,quoteId;
    Map<String,List<Map<String,String>>> seeded;

    @Test
    @EnabledIfEnvironmentVariable(named="M1_QUOTE_PERSISTENCE_INTEGRATION",matches="read-only-quote-startup")
    void readOnlyStartupAndBaseline() throws Exception {
        prepare();
        transactionGuards(new Request("1",List.of(new Item(sku,1)),null));
        originalUnchanged(TABLES);
        assertThat(budget.inserts.get()).isZero();
        note(Map.of("stage","read-only-startup-verified","originalDigests",baseline,"mapperSqlStatements",budget.statements.get(),"quoteInsertAttempts",0));
    }

    @Test
    @EnabledIfEnvironmentVariable(named="M1_QUOTE_PERSISTENCE_INTEGRATION",matches="read-only-retained-quote")
    void readOnlyRetainedScene() throws Exception {
        prepareRetained();
        var before=snapshot();var expected=retainedExpected();
        transactionGuards(request(1));verifyDurable(quoteFrom(expected),expected);
        var persisted=json.readTree(before.get("checkout_quote").getFirst().get("snapshot_json"));
        com.fasterxml.jackson.databind.JsonNode inMemory=json.valueToTree(expected);
        note(Map.of("stage","json-assertion-root-cause","persistedAddressVersionNode",persisted.path("address").path("version").getClass().getSimpleName(),
            "inMemoryAddressVersionNode",inMemory.path("address").path("version").getClass().getSimpleName(),"rawTreeEquality",persisted.equals(inMemory),
            "normalizedTreeEquality",persisted.equals(json.readTree(json.writeValueAsString(expected)))));
        validateOwnedRows(expected,before);
        assertThat(snapshot()).isEqualTo(before);assertThat(budget.inserts.get()).isZero();
        originalUnchanged(TABLES.stream().filter(t->!OWNED.contains(t)).toList());
        String sceneHash=captureRetainedScene(before);
        note(Map.of("stage","read-only-retained-verified","oldManifestSha256",RETAINED_MANIFEST_SHA,"rowDigests",digests(before),"quoteInsertAttempts",0,"retainedScene",RETAINED_SCENE.toString(),"retainedSceneSha256",sceneHash));
    }

    @Test
    @EnabledIfEnvironmentVariable(named="M1_QUOTE_PERSISTENCE_INTEGRATION",matches="approved-retained-quote-continuation")
    void approvedRetainedQuoteContinuation() throws Exception {
        prepareRetained();
        try {
            var committed=snapshot();var expected=retainedExpected();
            verifyDurable(quoteFrom(expected),expected);
            validateOwnedRows(expected,committed);
            verifyRetainedScene(committed);
            budget.armed=true;
            verifyOuterRollback(committed,1);
            var frozen=validateOwnedRows(expected,committed);
            note(Map.of("stage","continuation-checks-passed","oldManifestSha256",RETAINED_MANIFEST_SHA,"quoteInsertAttempts",budget.inserts.get(),"maxQuoteInsertAttempts",1));
            cleanup(frozen);originalUnchanged(TABLES);
            note(Map.of("stage","cleaned-and-verified","continuationOf",RETAINED_RUN,"originalDigests",baseline,"quoteInsertAttempts",budget.inserts.get(),"autoIncrementRestored",false));
        }catch(Exception|AssertionError failure){note(Map.of("stage","retained-after-failure","exceptionClass",failure.getClass().getName()));throw failure;}
        finally{budget.armed=false;}
    }

    @Test
    @EnabledIfEnvironmentVariable(named="M1_QUOTE_PERSISTENCE_INTEGRATION",matches="approved-temporary-quote-persistence")
    void approvedSingleQuotePersistenceScenario() throws Exception {
        prepare();
        try {
            transactionGuards(new Request("1",List.of(new Item(sku,1)),null));
            createSeeds();
            budget.armed=true;
            expectApi(400,"VALIDATION_ERROR",()->transaction().execute(status->quotes.create(owner,request(0))));
            assertThat(snapshot()).isEqualTo(seeded);
            expectApi(404,"NOT_FOUND",()->transaction().execute(status->quotes.create(otherOwner,request(1))));
            assertThat(snapshot()).isEqualTo(seeded);
            expectApi(409,"NO_SINGLE_WAREHOUSE",()->transaction().execute(status->quotes.create(owner,request(99))));
            assertThat(snapshot()).isEqualTo(seeded);assertThat(budget.inserts.get()).isZero();
            checks.add("invalid quantity, foreign owner address and no single warehouse leave all three resource tables unchanged");

            var expectedCatalog=catalog.skus(List.of(sku)).get(sku);
            var expectedAddress=addresses.find(owner,addressId);
            assertThat(expectedAddress).isEqualTo(new TradeModels.Address(addressId,recipient,"10000000007","测试省","测试市","测试区",detail,false,0));
            var quote=transaction().execute(status->quotes.create(owner,new Request(addressId,List.of(new Item(sku,1),new Item(sku,1)),null)));
            assertThat(quote).isNotNull();quoteId=quote.id();assertThat(quoteId).matches("[1-9][0-9]{0,18}");
            assertThat(quote.warehouseId()).isEqualTo(warehouse);assertThat(quote.items()).hasSize(1);
            var line=quote.items().getFirst();assertThat(line.quantity()).isEqualTo(2);
            assertThat(line.skuId()).isEqualTo(sku);assertThat(line.productName()).isEqualTo(expectedCatalog.productName());
            assertThat(line.specs()).isEqualTo(expectedCatalog.specs());assertThat(line.unitPriceMinor()).isEqualTo(expectedCatalog.priceMinor());
            String amount=Long.toString(Math.multiplyExact(Long.parseLong(expectedCatalog.priceMinor()),2));
            assertThat(line.grossMinor()).isEqualTo(amount);assertThat(line.discountMinor()).isEqualTo("0");assertThat(line.payableMinor()).isEqualTo(amount);
            assertThat(quote.grossMinor()).isEqualTo(amount);assertThat(quote.payableMinor()).isEqualTo(amount);
            assertThat(quote.discountMinor()).isEqualTo("0");assertThat(quote.shippingMinor()).isEqualTo("0");assertThat(quote.couponId()).isNull();
            assertThat(quote.expiresAt()).isEqualTo(CREATED.plusSeconds(300));
            var expected=new Snapshot(1,owner,addressId,expectedAddress,warehouse,List.of(new SnapshotLine(expectedCatalog,line)),amount,"0","0",amount,null,CREATED,CREATED.plusSeconds(300));
            verifyDurable(quote,expected);
            assertThat(rows("customer_address",false)).isEqualTo(seeded.get("customer_address"));
            assertThat(rows("stock_balance",false)).isEqualTo(seeded.get("stock_balance"));
            originalUnchanged(TABLES.stream().filter(t->!OWNED.contains(t)).toList());
            checks.add("real proxy inserts one generated String ID; independent transaction/JDBC verifies complete JSON, catalog versions, UTC millisecond DATETIME and 300 second expiry");
            checks.add("duplicate SKU merges to quantity two; quoting leaves balance, reservations, ledger, idempotency and audit unchanged");

            var committed=snapshot();
            verifyOuterRollback(committed,2);

            var frozen=validateOwnedRows(expected,committed);
            note(Map.of("stage","checks-passed","checks",checks,"quoteInsertAttempts",budget.inserts.get(),"maxQuoteInsertAttempts",4));
            cleanup(frozen);
            originalUnchanged(TABLES);
            note(Map.of("stage","cleaned-and-verified","checks",checks,"originalDigests",baseline,"quoteInsertAttempts",budget.inserts.get(),"autoIncrementRestored",false));
        } catch(Exception|AssertionError failure){note(Map.of("stage","retained-after-failure","exceptionClass",failure.getClass().getName()));throw failure;}
        finally {budget.armed=false;}
    }

    private void prepare() throws Exception {
        openEvidence("quote-");
        note(Map.of("stage","baseline-intent","run",run,"maxSeedAddresses",1,"maxSeedBalances",1,"maxQuoteInsertAttempts",4,"cleanupOnlyAfterAllChecks",true));
        verifyDatabaseStructure();
        for(String table:EMPTY)assertThat(count(table)).as("Reviewed empty table: %s",table).isZero();
        owner=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM account WHERE account_type='CUSTOMER' AND username='customer' AND enabled=1",String.class);
        otherOwner=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM account WHERE id<>CAST(? AS UNSIGNED) ORDER BY id LIMIT 1",String.class,owner);
        warehouse=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM warehouse WHERE enabled=1 AND fulfillment_enabled=1 ORDER BY priority,id LIMIT 1",String.class);
        sku=jdbc.queryForObject("SELECT CAST(s.id AS CHAR) FROM sku s JOIN product p ON p.id=s.product_id JOIN category c ON c.id=p.category_id JOIN brand b ON b.id=p.brand_id WHERE s.enabled=1 AND p.state='ON_SALE' AND c.enabled=1 AND b.enabled=1 ORDER BY s.id LIMIT 1",String.class);
        verifyProxyMappers();
        for(String table:TABLES)baseline.put(table,digest(rows(table,false)));
        budget.reset();note(Map.of("stage","baseline-verified","originalDigests",baseline,"originalAccounts",4,"tableCount",22,"fixedUtcClock",CREATED.toString()));
    }
    private void openEvidence(String prefix) throws Exception {
        String root=System.getenv("M1_QUOTE_EVIDENCE_ROOT");assertThat(root).isNotBlank();Path evidence=Path.of(root);
        assertThat(evidence.isAbsolute()).isTrue();Files.createDirectories(evidence);evidence=evidence.toRealPath();
        assertThat(evidence.startsWith(Path.of(".").toRealPath())).isFalse();
        Path directory=evidence.resolve(prefix+run);Files.createDirectory(directory);manifest=directory.resolve("manifest.jsonl");Files.createFile(manifest);
    }
    private void verifyDatabaseStructure(){
        assertThat(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)c->c.getMetaData().getURL())).isEqualTo(CANONICAL);
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("zhicai_dev");
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone",String.class)).isEqualTo("+00:00");
        var tables=jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'",String.class);
        assertThat(tables).containsExactlyInAnyOrderElementsOf(TABLES);
        int[] checksums={-357524081,502337516,174647045,-493016158,237973485,947094408,-458546823};
        var history=jdbc.queryForList("SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank");assertThat(history).hasSize(7);
        for(int i=0;i<7;i++){assertThat(history.get(i).get("version").toString()).isEqualTo(Integer.toString(i+1));assertThat(((Number)history.get(i).get("checksum")).intValue()).isEqualTo(checksums[i]);assertThat(history.get(i).get("success").toString()).isIn("true","1");}
        for(var expected:Map.of("account",4,"category",15,"brand",26,"product",1000,"sku",1000,"asset",120,"product_asset",1000).entrySet())assertThat(count(expected.getKey())).as("Reviewed baseline count: %s",expected.getKey()).isEqualTo(expected.getValue());
    }
    private void verifyProxyMappers(){
        assertThat(AopUtils.isAopProxy(quotes)).isTrue();
        for(String id:List.of("com.zhicai.platform.trade.QuoteMapper.insert","com.zhicai.platform.trade.AddressMapper.find","com.zhicai.platform.catalog.CheckoutCatalogMapper.findEnabledSkus","com.zhicai.platform.inventory.InventoryAvailabilityMapper.warehouses","com.zhicai.platform.inventory.InventoryAvailabilityMapper.balances"))assertThat(sessions.getConfiguration().hasStatement(id)).isTrue();
    }
    private void prepareRetained() throws Exception {
        assertThat(System.getenv("M1_QUOTE_RETAINED_MANIFEST_SHA256")).isEqualToIgnoringCase(RETAINED_MANIFEST_SHA);
        Path old=Path.of(RETAINED_MANIFEST).toRealPath();
        assertThat(Path.of(System.getenv("M1_QUOTE_RETAINED_MANIFEST")).toRealPath()).isEqualTo(old);
        byte[] bytes=Files.readAllBytes(old);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).isEqualTo(RETAINED_MANIFEST_SHA);
        var events=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        for(String line:new String(bytes,StandardCharsets.UTF_8).lines().toList())events.add(json.readTree(line));
        assertThat(events.stream().map(e->e.path("stage").asText()).toList()).containsExactly("baseline-intent","baseline-verified","seed-intent","seed-created","retained-after-failure");
        assertThat(events.getFirst().path("run").asText()).isEqualTo(RETAINED_RUN);
        run=RETAINED_RUN;recipient="验收"+run;detail="临时报价验收-"+run;
        owner="4";warehouse="1";sku="3001";addressId="23";balanceId="4";quoteId="1";
        openEvidence("retained-"+UUID.randomUUID()+"-");
        note(Map.of("stage","continuation-baseline-intent","oldManifest",old.toString(),"oldManifestSha256",RETAINED_MANIFEST_SHA,"existingAddressId",addressId,"existingBalanceId",balanceId,"existingQuoteId",quoteId,"maxAdditionalQuoteInsertAttempts",1));
        verifyDatabaseStructure();verifyProxyMappers();
        events.get(1).path("originalDigests").fields().forEachRemaining(e->baseline.put(e.getKey(),e.getValue().asText()));
        assertThat(baseline.keySet()).containsExactlyInAnyOrderElementsOf(TABLES);
        for(String value:baseline.values())assertThat(value).matches("[a-f0-9]{64}");
        for(String table:EMPTY)assertThat(baseline.get(table)).isEqualTo(sha(""));
        assertThat(jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM account WHERE account_type='CUSTOMER' AND username='customer' AND enabled=1",String.class)).isEqualTo(owner);
        otherOwner=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM account WHERE id<>CAST(? AS UNSIGNED) ORDER BY id LIMIT 1",String.class,owner);
        var intent=events.get(2);assertThat(intent.path("owner").asText()).isEqualTo(owner);assertThat(intent.path("warehouse").asText()).isEqualTo(warehouse);assertThat(intent.path("sku").asText()).isEqualTo(sku);
        assertThat(intent.path("recipient").asText()).isEqualTo(recipient);assertThat(intent.path("detail").asText()).isEqualTo(detail);
        var oldSeeds=new LinkedHashMap<String,List<Map<String,String>>>();
        for(String table:OWNED){
            var values=new ArrayList<Map<String,String>>();
            for(var value:events.get(3).path("rows").path(table)){
                var row=new LinkedHashMap<String,String>();value.fields().forEachRemaining(e->row.put(e.getKey(),e.getValue().isNull()?null:e.getValue().asText()));values.add(Collections.unmodifiableMap(row));
            }
            oldSeeds.put(table,List.copyOf(values));
            assertThat(digest(values)).isEqualTo(events.get(3).path("rowDigests").path(table).asText());
        }
        seeded=Collections.unmodifiableMap(oldSeeds);assertThat(seeded.get("checkout_quote")).isEmpty();
        assertThat(seeded.get("customer_address")).hasSize(1);assertThat(seeded.get("stock_balance")).hasSize(1);
        assertThat(rows("customer_address",false)).isEqualTo(seeded.get("customer_address"));assertThat(rows("stock_balance",false)).isEqualTo(seeded.get("stock_balance"));
        var quotes=rows("checkout_quote",false);assertThat(quotes).hasSize(1);var quote=quotes.getFirst();
        assertThat(quote.keySet()).containsExactlyInAnyOrder("id","customer_id","warehouse_id","snapshot_json","expires_at","used_order_id","created_at");
        assertThat(quote.get("id")).isEqualTo(quoteId);assertThat(quote.get("customer_id")).isEqualTo(owner);assertThat(quote.get("warehouse_id")).isEqualTo(warehouse);assertThat(quote.get("used_order_id")).isNull();
        assertThat(quote.get("created_at")).isEqualTo("2026-10-25 01:02:03.123");assertThat(quote.get("expires_at")).isEqualTo("2026-10-25 01:07:03.123");
        assertThat(sha(quote.get("snapshot_json"))).isEqualTo(RETAINED_SNAPSHOT_SHA);
        originalUnchanged(TABLES.stream().filter(t->!OWNED.contains(t)).toList());
        budget.reset();note(Map.of("stage","retained-membership-verified","oldManifestSha256",RETAINED_MANIFEST_SHA,"rowDigests",digests(snapshot()),"snapshotJsonSha256",RETAINED_SNAPSHOT_SHA));
        if("approved-retained-quote-continuation".equals(System.getenv("M1_QUOTE_PERSISTENCE_INTEGRATION")))verifyRetainedScene(snapshot());
    }
    private byte[] retainedSceneBytes(Map<String,List<Map<String,String>>> current) throws Exception {
        var scene=new LinkedHashMap<String,Object>();scene.put("formatVersion",1);scene.put("retainedRun",RETAINED_RUN);
        scene.put("oldManifestSha256",RETAINED_MANIFEST_SHA);scene.put("originalDigests",baseline);
        scene.put("rows",current);scene.put("rowDigests",digests(current));scene.put("snapshotJsonSha256",RETAINED_SNAPSHOT_SHA);
        // Canonicalize only artifact serialization. Preserve JDBC column order in the old digest algorithm.
        return json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(scene);
    }
    private String captureRetainedScene(Map<String,List<Map<String,String>>> current) throws Exception {
        byte[] expected=retainedSceneBytes(current);
        if(Files.exists(RETAINED_SCENE))assertThat(Files.readAllBytes(RETAINED_SCENE)).as("Never overwrite or adopt a different retained scene artifact").isEqualTo(expected);
        else try(FileChannel file=FileChannel.open(RETAINED_SCENE,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            var buffer=ByteBuffer.wrap(expected);while(buffer.hasRemaining())file.write(buffer);file.force(true);
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(RETAINED_SCENE)));
    }
    private void verifyRetainedScene(Map<String,List<Map<String,String>>> current){
        try {
            String approved=System.getenv("M1_QUOTE_RETAINED_SCENE_SHA256");assertThat(approved).matches("[a-fA-F0-9]{64}");
            byte[] bytes=Files.readAllBytes(RETAINED_SCENE);
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).isEqualToIgnoringCase(approved);
            assertThat(bytes).as("Approved immutable postimage: exact membership, all columns, digests, original baseline and failed-run identity").isEqualTo(retainedSceneBytes(current));
        }catch(Exception failure){throw new IllegalStateException("Approved retained scene could not be proved",failure);}
    }
    private Snapshot retainedExpected(){
        var current=catalog.skus(List.of(sku)).get(sku);assertThat(current).isNotNull();
        var address=addresses.find(owner,addressId);
        assertThat(address).isEqualTo(new TradeModels.Address(addressId,recipient,"10000000007","测试省","测试市","测试区",detail,false,0));
        String amount=Long.toString(Math.multiplyExact(Long.parseLong(current.priceMinor()),2));
        var line=new Line(sku,current.productName(),current.specs(),2,current.priceMinor(),amount,"0",amount);
        return new Snapshot(1,owner,addressId,address,warehouse,List.of(new SnapshotLine(current,line)),amount,"0","0",amount,null,CREATED,CREATED.plusSeconds(300));
    }
    private Quote quoteFrom(Snapshot expected){return new Quote(quoteId,warehouse,expected.items().stream().map(SnapshotLine::amounts).toList(),expected.grossMinor(),expected.discountMinor(),expected.shippingMinor(),expected.payableMinor(),null,expected.expiresAt());}
    private void verifyOuterRollback(Map<String,List<Map<String,String>>> committed,int expectedAttempts){
        String[] rolledBackId={null};
        assertThatThrownBy(()->transaction().execute(status->{var rolledBack=quotes.create(owner,request(1));rolledBackId[0]=rolledBack.id();assertThat(count("checkout_quote")).isEqualTo(2);throw new DeliberateRollback();})).isExactlyInstanceOf(DeliberateRollback.class);
        assertThat(rolledBackId[0]).matches("[1-9][0-9]{0,18}").isNotEqualTo(quoteId);
        assertThat(snapshot()).isEqualTo(committed);assertThat(budget.inserts.get()).isEqualTo(expectedAttempts);
        originalUnchanged(TABLES.stream().filter(t->!OWNED.contains(t)).toList());
        checks.add("mapper insert completes before deliberate outer READ_COMMITTED rollback; all three resource memberships and row digests unchanged");
    }
    private TransactionTemplate transaction(){var tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setTimeout(10);return tx;}
    private void transactionGuards(Request request){
        int before=budget.statements.get();
        assertThatThrownBy(()->quotes.create(owner,request)).isInstanceOf(IllegalTransactionStateException.class);
        for(int isolation:new int[]{TransactionDefinition.ISOLATION_DEFAULT,TransactionDefinition.ISOLATION_REPEATABLE_READ}){
            var tx=transaction();tx.setIsolationLevel(isolation);
            assertThatThrownBy(()->tx.execute(status->quotes.create(owner,request))).isInstanceOf(IllegalTransactionStateException.class);
        }
        var readOnly=transaction();readOnly.setReadOnly(true);
        assertThatThrownBy(()->readOnly.execute(status->quotes.create(owner,request))).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(budget.statements.get()).as("Every transaction guard refuses before mapped SQL").isEqualTo(before);
        checks.add("real proxy refuses missing/default/RR/read-only outer transaction before mapped SQL");
    }
    private Request request(int quantity){return new Request(addressId,List.of(new Item(sku,quantity)),null);}
    private void expectApi(int status,String code,Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.status()).isEqualTo(status);assertThat(e.code()).isEqualTo(code);});}
    private int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Integer.class);}
    private void createSeeds() throws Exception {
        note(Map.of("stage","seed-intent","owner",owner,"warehouse",warehouse,"sku",sku,"recipient",recipient,"detail",detail));
        transaction().executeWithoutResult(status->{
            addressId=insertId("INSERT INTO customer_address(customer_id,recipient,phone,province,city,district,detail,is_default) VALUES(CAST(? AS UNSIGNED),?,'10000000007','测试省','测试市','测试区',?,FALSE)",owner,recipient,detail);
            balanceId=insertId("INSERT INTO stock_balance(warehouse_id,sku_id,on_hand,reserved,quarantine) VALUES(CAST(? AS UNSIGNED),CAST(? AS UNSIGNED),5,0,2)",warehouse,sku);
        });
        seeded=snapshot();assertThat(seeded.get("customer_address")).hasSize(1);assertThat(seeded.get("stock_balance")).hasSize(1);assertThat(seeded.get("checkout_quote")).isEmpty();
        note(Map.of("stage","seed-created","rows",seeded,"rowDigests",digests(seeded)));
    }
    private String insertId(String sql,String... parameters){
        var key=new GeneratedKeyHolder();
        assertThat(jdbc.update(connection->{var statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<parameters.length;i++)statement.setString(i+1,parameters[i]);return statement;},key)).isEqualTo(1);
        String id=Objects.requireNonNull(key.getKey()).toString();assertThat(id).matches("[1-9][0-9]{0,18}");return id;
    }
    private void verifyDurable(Quote quote,Snapshot expected) throws Exception {
        // This starts only after the creating transaction has committed and uses a fresh outer transaction.
        transaction().executeWithoutResult(status->{
            try {
                jdbc.query("SELECT CAST(id AS CHAR),CAST(customer_id AS CHAR),CAST(warehouse_id AS CHAR),snapshot_json,created_at,expires_at,used_order_id,DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%fZ') FROM checkout_quote WHERE id=CAST(? AS UNSIGNED)",rs->{
                    try {
                    assertThat(rs.next()).isTrue();assertThat(rs.getString(1)).isEqualTo(quote.id());assertThat(rs.getString(2)).isEqualTo(owner);assertThat(rs.getString(3)).isEqualTo(warehouse);
                    String persisted=rs.getString(4);
                    assertThat(json.readValue(persisted,Snapshot.class)).isEqualTo(expected);
                    var tree=json.readTree(persisted);assertThat(tree).isEqualTo(json.readTree(json.writeValueAsString(expected)));
                    assertThat(tree.get("owner").isTextual()).isTrue();assertThat(tree.get("grossMinor").isTextual()).isTrue();assertThat(tree.has("couponId")).isFalse();
                    assertThat(tree.get("createdAt").asText()).isEqualTo("2026-10-25T01:02:03.123Z");assertThat(tree.get("expiresAt").asText()).isEqualTo("2026-10-25T01:07:03.123Z");
                    var utc=Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                    assertThat(rs.getTimestamp(5,utc).toInstant()).isEqualTo(expected.createdAt());assertThat(rs.getTimestamp(6,utc).toInstant()).isEqualTo(expected.expiresAt());
                    assertThat(rs.getObject(7)).isNull();assertThat(rs.getString(8)).isEqualTo("2026-10-25T01:02:03.123000Z");assertThat(rs.next()).isFalse();return null;
                    }catch(java.io.IOException failure){throw new IllegalStateException("Persisted application JSON round trip failed",failure);}
                },quote.id());
                var wire=json.readTree(json.writeValueAsString(quote));assertThat(wire.get("id").isTextual()).isTrue();assertThat(wire.get("id").asText()).isEqualTo(quoteId);
            }catch(java.io.IOException failure){throw new IllegalStateException("Application JSON round trip failed",failure);}
        });
        var versions=jdbc.queryForMap("SELECT s.version sv,p.version pv,c.version cv,b.version bv,CAST(s.price_minor AS CHAR) price,s.specs_json specs FROM sku s JOIN product p ON p.id=s.product_id JOIN category c ON c.id=p.category_id JOIN brand b ON b.id=p.brand_id WHERE s.id=CAST(? AS UNSIGNED)",sku);
        var projection=expected.items().getFirst().catalog();
        assertThat(projection.skuVersion()).isEqualTo(((Number)versions.get("sv")).longValue());assertThat(projection.productVersion()).isEqualTo(((Number)versions.get("pv")).longValue());
        assertThat(projection.categoryVersion()).isEqualTo(((Number)versions.get("cv")).longValue());assertThat(projection.brandVersion()).isEqualTo(((Number)versions.get("bv")).longValue());
        assertThat(projection.priceMinor()).isEqualTo(versions.get("price"));
        com.fasterxml.jackson.databind.JsonNode specs=json.valueToTree(projection.specs());assertThat(specs).isEqualTo(json.readTree(versions.get("specs").toString()));
    }
    private Map<String,List<Map<String,String>>> validateOwnedRows(Snapshot expected,Map<String,List<Map<String,String>>> committed) throws Exception {
        assertThat(snapshot()).isEqualTo(committed);
        var quote=committed.get("checkout_quote");assertThat(quote).hasSize(1);var q=quote.getFirst();
        assertThat(q.get("id")).isEqualTo(quoteId);assertThat(q.get("customer_id")).isEqualTo(owner);assertThat(q.get("warehouse_id")).isEqualTo(warehouse);assertThat(q.get("used_order_id")).isNull();
        assertThat(json.readValue(q.get("snapshot_json"),Snapshot.class)).isEqualTo(expected);
        var address=committed.get("customer_address");assertThat(address).hasSize(1);var a=address.getFirst();
        assertThat(a.get("id")).isEqualTo(addressId);assertThat(a.get("customer_id")).isEqualTo(owner);assertThat(a.get("recipient")).isEqualTo(recipient);assertThat(a.get("detail")).isEqualTo(detail);
        assertThat(address).isEqualTo(seeded.get("customer_address"));
        var balances=committed.get("stock_balance");assertThat(balances).hasSize(1);var b=balances.getFirst();
        assertThat(b.get("id")).isEqualTo(balanceId);assertThat(b.get("warehouse_id")).isEqualTo(warehouse);assertThat(b.get("sku_id")).isEqualTo(sku);
        assertThat(b.get("on_hand")).isEqualTo("5");assertThat(b.get("reserved")).isEqualTo("0");assertThat(b.get("quarantine")).isEqualTo("2");assertThat(balances).isEqualTo(seeded.get("stock_balance"));
        note(Map.of("stage","owned-rows-frozen","rows",committed,"rowDigests",digests(committed),"snapshotJsonSha256",sha(q.get("snapshot_json"))));
        return committed;
    }
    private void cleanup(Map<String,List<Map<String,String>>> frozen) throws Exception {
        note(Map.of("stage","exact-cleanup-intent","rowDigests",digests(frozen)));
        var cleanupTransaction=transaction();cleanupTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        cleanupTransaction.executeWithoutResult(status->{
            // A separate RR transaction locks each full PRIMARY range, including its supremum gap.
            // This blocks new membership in these three tables until cleanup commits. Business calls remain RC.
            // Lock and compare FULL memberships and values before the first delete. Never adopt a new row.
            for(String table:OWNED)assertThat(rows(table,true)).as("No whole-membership/value drift: %s",table).isEqualTo(frozen.get(table));
            if("approved-retained-quote-continuation".equals(System.getenv("M1_QUOTE_PERSISTENCE_INTEGRATION")))verifyRetainedScene(frozen);
            var q=frozen.get("checkout_quote").getFirst();var a=frozen.get("customer_address").getFirst();var b=frozen.get("stock_balance").getFirst();
            assertThat(jdbc.update("DELETE FROM checkout_quote WHERE id=CAST(? AS UNSIGNED) AND customer_id=CAST(? AS UNSIGNED) AND warehouse_id=CAST(? AS UNSIGNED) AND used_order_id IS NULL AND SHA2(CAST(snapshot_json AS CHAR CHARACTER SET utf8mb4),256)=? AND created_at=? AND expires_at=?",quoteId,owner,warehouse,sha(q.get("snapshot_json")),q.get("created_at"),q.get("expires_at"))).isEqualTo(1);
            assertThat(jdbc.update("DELETE FROM customer_address WHERE id=CAST(? AS UNSIGNED) AND customer_id=CAST(? AS UNSIGNED) AND BINARY recipient=BINARY ? AND BINARY detail=BINARY ? AND phone='10000000007' AND province='测试省' AND city='测试市' AND district='测试区' AND is_default=FALSE AND version=0 AND created_at=? AND updated_at=?",addressId,owner,recipient,detail,a.get("created_at"),a.get("updated_at"))).isEqualTo(1);
            assertThat(jdbc.update("DELETE FROM stock_balance WHERE id=CAST(? AS UNSIGNED) AND warehouse_id=CAST(? AS UNSIGNED) AND sku_id=CAST(? AS UNSIGNED) AND on_hand=5 AND reserved=0 AND quarantine=2 AND warning_threshold=? AND version=? AND created_at=? AND updated_at=?",balanceId,warehouse,sku,b.get("warning_threshold"),b.get("version"),b.get("created_at"),b.get("updated_at"))).isEqualTo(1);
            originalUnchanged(TABLES);
        });
    }
    private Map<String,List<Map<String,String>>> snapshot(){var result=new LinkedHashMap<String,List<Map<String,String>>>();for(String table:OWNED)result.put(table,rows(table,false));return Collections.unmodifiableMap(result);}
    private List<Map<String,String>> rows(String table,boolean lock){
        return jdbc.query("SELECT * FROM `"+table+"`"+(lock?" FORCE INDEX (PRIMARY) ORDER BY id FOR UPDATE":""),rs->{
            var result=new ArrayList<Map<String,String>>();int columns=rs.getMetaData().getColumnCount();
            while(rs.next()){
                var row=new LinkedHashMap<String,String>();
                for(int c=1;c<=columns;c++){
                    Object value=rs.getObject(c);String text;
                    if(value==null)text=null;else if(value instanceof byte[] bytes)text=HexFormat.of().formatHex(bytes);
                    else if(value instanceof Blob blob)text=HexFormat.of().formatHex(blob.getBytes(1,Math.toIntExact(blob.length())));
                    else text=rs.getString(c);
                    row.put(rs.getMetaData().getColumnLabel(c),text);
                }
                result.add(Collections.unmodifiableMap(row));
            }
            result.sort(Comparator.comparing(this::rowDigest));return List.copyOf(result);
        });
    }
    private String rowDigest(Map<String,String> row){var value=new StringBuilder();row.forEach((key,text)->value.append(key.length()).append(':').append(key).append(':').append(text==null?-1:text.length()).append(':').append(text==null?"":text).append(';'));return sha(value.toString());}
    private String digest(List<Map<String,String>> rows){return sha(String.join("\n",rows.stream().map(this::rowDigest).sorted().toList()));}
    private Map<String,String> digests(Map<String,List<Map<String,String>>> rows){var result=new LinkedHashMap<String,String>();rows.forEach((table,value)->result.put(table,digest(value)));return result;}
    private void originalUnchanged(List<String> tables){for(String table:tables)assertThat(digest(rows(table,false))).as("Original full-row digest: %s",table).isEqualTo(baseline.get(table));}
    private String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private synchronized void note(Map<String,?> event) throws Exception {byte[] bytes=(json.writeValueAsString(event)+"\n").getBytes(StandardCharsets.UTF_8);try(FileChannel file=FileChannel.open(manifest,StandardOpenOption.WRITE,StandardOpenOption.APPEND)){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())file.write(buffer);file.force(true);}}
    static class DeliberateRollback extends RuntimeException {}

    @TestConfiguration
    static class FixtureConfiguration {
        @Bean CheckoutCatalogService quoteFixtureCatalog(CheckoutCatalogMapper mapper,ObjectMapper json){return new CheckoutCatalogService(mapper,json);}
        @Bean InventoryAvailabilityService quoteFixtureAvailability(InventoryAvailabilityMapper mapper){return new InventoryAvailabilityService(mapper);}
        @Bean QuoteService quoteFixtureService(QuoteMapper mapper,AddressMapper addresses,CheckoutCatalogService catalog,InventoryAvailabilityService inventory,ObjectMapper json){return new QuoteService(mapper,addresses,catalog,inventory,json,Clock.fixed(CREATED,ZoneOffset.UTC));}
        @Bean WriteBudget quoteFixtureWriteBudget(){return new WriteBudget();}
    }
    @Intercepts({
        @Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class}),
        @Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}),
        @Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class,CacheKey.class,BoundSql.class})})
    static class WriteBudget implements Interceptor {
        final AtomicInteger inserts=new AtomicInteger(),statements=new AtomicInteger();boolean armed;
        void reset(){inserts.set(0);statements.set(0);armed=false;}
        @Override public Object intercept(Invocation invocation) throws Throwable {
            statements.incrementAndGet();var statement=(MappedStatement)invocation.getArgs()[0];
            if(invocation.getMethod().getName().equals("update")){
                String gate=System.getenv("M1_QUOTE_PERSISTENCE_INTEGRATION");
                if(!statement.getId().equals("com.zhicai.platform.trade.QuoteMapper.insert") || !armed
                    || !("approved-temporary-quote-persistence".equals(gate) || "approved-retained-quote-continuation".equals(gate)))
                    throw new IllegalStateException("Unapproved mapped write blocked before SQL");
                int limit="approved-retained-quote-continuation".equals(gate)?1:4;
                if(inserts.incrementAndGet()>limit)throw new IllegalStateException("Quote INSERT attempt budget exceeded before SQL");
            }
            return invocation.proceed();
        }
    }
}
